package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import de.maxhenkel.voicechat.api.VoicechatSocket;
import de.maxhenkel.voicechat.api.events.SoundPacketEvent;
import de.maxhenkel.voicechat.compatibility.Compatibility;
import de.maxhenkel.voicechat.debug.CooldownTimer;
import de.maxhenkel.voicechat.debug.VoicechatUncaughtExceptionHandler;
import de.maxhenkel.voicechat.net.NetManager;
import de.maxhenkel.voicechat.plugins.PluginManager;
import de.maxhenkel.voicechat.voice.common.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.AsynchronousCloseException;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class Server extends Thread {

    private final Map<UUID, ClientConnection> connections;
    private final Map<UUID, ClientConnection> unCheckedConnections;
    private final Map<SocketAddress, ClientConnection> connectionsByAddress;
    private final Map<SocketAddress, ClientConnection> unCheckedConnectionsByAddress;
    private final Map<UUID, Secret> secrets;
    private final int port;
    private final org.bukkit.Server server;
    private final VoicechatSocket socket;
    private final String bindAddress;
    private final ProcessThread processThread;
    private static final int CONTROL_QUEUE_CAPACITY = 1_024;
    private static final int AUDIO_QUEUE_CAPACITY = 4_096;
    private static final int UNKNOWN_PACKET_RATE_LIMIT = 1_000;
    private static final long HOTLOAD_SECRET_TTL_MILLIS = 30_000L;
    private static final long HOTLOAD_SECRET_PRUNE_INTERVAL_MILLIS = 1_000L;
    private final BlockingQueue<NetworkMessage> controlQueue;
    private final BlockingQueue<NetworkMessage> audioQueue;
    private final Map<UUID, Long> hotloadSecretExpiries;
    private final PingManager pingManager;
    private final PlayerStateManager playerStateManager;
    private final ServerGroupManager groupManager;
    private final ServerCategoryManager categoryManager;
    private volatile boolean cleanupComplete;
    private volatile boolean closed;
    private long unknownRateWindowStart;
    private int unknownPacketsInWindow;

    public Server() {
        int configPort = Voicechat.SERVER_CONFIG.voiceChatPort.get();
        if (configPort < 0) {
            Voicechat.LOGGER.info("Using the Minecraft servers port as voice chat port");
            port = Bukkit.getPort();
        } else {
            port = configPort;
        }
        this.server = Bukkit.getServer();
        // Resolve Bukkit server properties on the global-region thread before
        // the detached UDP thread starts.
        this.bindAddress = getBindAddress();
        socket = PluginManager.instance().getSocketImplementation();
        connections = new ConcurrentHashMap<>();
        unCheckedConnections = new ConcurrentHashMap<>();
        connectionsByAddress = new ConcurrentHashMap<>();
        unCheckedConnectionsByAddress = new ConcurrentHashMap<>();
        secrets = new ConcurrentHashMap<>();
        controlQueue = new ArrayBlockingQueue<>(CONTROL_QUEUE_CAPACITY);
        audioQueue = new ArrayBlockingQueue<>(AUDIO_QUEUE_CAPACITY);
        hotloadSecretExpiries = new ConcurrentHashMap<>();
        pingManager = new PingManager(this);
        playerStateManager = new PlayerStateManager();
        groupManager = new ServerGroupManager(playerStateManager);
        categoryManager = new ServerCategoryManager();
        cleanupComplete = false;
        closed = false;
        unknownRateWindowStart = System.nanoTime();
        setDaemon(true);
        setName("VoiceChatServerThread");
        setUncaughtExceptionHandler(new VoicechatUncaughtExceptionHandler());
        processThread = new ProcessThread();
    }

    @Override
    public void run() {
        boolean unexpectedShutdown = false;
        try {
            String bindAddress = this.bindAddress;
            try {
                InetAddress.getByName(bindAddress);
            } catch (UnknownHostException e) {
                Voicechat.LOGGER.error("Failed to parse bind IP address '{}'", bindAddress, e);
                Voicechat.LOGGER.info("Binding to wildcard IP address");
                bindAddress = "";
            }
            socket.open(port, bindAddress);
            if (closed) {
                socket.close();
                return;
            }
            processThread.start();

            if (bindAddress.isEmpty()) {
                Voicechat.LOGGER.info("Voice chat server started at port {}", socket.getLocalPort());
            } else {
                Voicechat.LOGGER.info("Voice chat server started at {}:{}", bindAddress, socket.getLocalPort());
            }

            while (!socket.isClosed() && !isClosed()) {
                try {
                    RawUdpPacket rawPacket = socket.read();
                    ClientConnection rawConnection = connectionsByAddress.get(rawPacket.getSocketAddress());
                    if (rawConnection == null) {
                        rawConnection = unCheckedConnectionsByAddress.get(rawPacket.getSocketAddress());
                    }
                    if (rawConnection == null ? !allowUnknownPacket() : !rawConnection.allowRawPacket()) {
                        continue;
                    }
                    NetworkMessage message;
                    try {
                        // Parse and authenticate before retaining anything. Invalid UDP data
                        // never enters either bounded queue.
                        message = NetworkMessage.readPacketServer(rawPacket, Server.this);
                    } catch (IndexOutOfBoundsException | ReflectiveOperationException e) {
                        CooldownTimer.run("failed_reading_packet", () ->
                                Voicechat.LOGGER.warn("Failed to read packet from {}", rawPacket.getSocketAddress()));
                        continue;
                    }
                    if (message == null || isExpired(message)) {
                        continue;
                    }
                    if (message.getPacket() instanceof MicPacket) {
                        ClientConnection connection = getSender(message);
                        if (connection == null || !connection.allowAudioPacket()) {
                            continue;
                        }
                        // Audio may be dropped only when it is already too old to be useful.
                        if (!audioQueue.offer(message)) {
                            audioQueue.poll();
                            audioQueue.offer(message);
                        }
                    } else {
                        ClientConnection connection = getSender(message);
                        if (connection == null) {
                            connection = getUnconnectedSender(message);
                        }
                        if (connection != null && !connection.allowControlPacket()) {
                            UUID connectionId = connection.getPlayerUUID();
                            CooldownTimer.run("control-rate-" + connectionId, () ->
                                    Voicechat.LOGGER.warn("Dropping excessive control packets from {}", connectionId));
                            continue;
                        }
                        // Never block the UDP reader on a client-controlled queue.
                        // Clients retry handshake/control messages, while blocking here
                        // would pause audio for every connected player.
                        if (!controlQueue.offer(message)) {
                            CooldownTimer.run("control-queue-full", () ->
                                    Voicechat.LOGGER.warn("Dropping control packet because the queue is full ({} entries)", controlQueue.size()));
                        }
                    }
                } catch (InterruptedException e) {
                    if (!isClosed()) Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // Only log an error if the error isn't caused by the socket being closed
                    if (!(e instanceof SocketException && e.getCause() instanceof AsynchronousCloseException)) {
                        if (Voicechat.debugMode()) {
                            Voicechat.LOGGER.error("Failed to read from socket", e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            unexpectedShutdown = !closed;
            if (unexpectedShutdown) {
                Voicechat.LOGGER.error("Voice chat server error", e);
            }
        } finally {
            unexpectedShutdown |= !closed;
            closed = true;
            processThread.close();
            processThread.interrupt();
            try {
                socket.close();
            } catch (Throwable t) {
                Voicechat.LOGGER.warn("Failed to close voice chat socket", t);
            }
        }
        if (unexpectedShutdown) {
            Compatibility compatibility = Voicechat.compatibility;
            if (compatibility != null) {
                compatibility.runTask(() -> {
                    Voicechat plugin = Voicechat.INSTANCE;
                    if (Voicechat.ACTIVE && plugin != null && plugin.isEnabled()) {
                        Voicechat.LOGGER.fatal("Disabling voice chat because the UDP server stopped unexpectedly");
                        plugin.getServer().getPluginManager().disablePlugin(plugin);
                    }
                });
            }
        }
    }

    private boolean isExpired(NetworkMessage message) {
        if (System.currentTimeMillis() - message.getTimestamp() <= message.getTTL()) {
            return false;
        }
        CooldownTimer.run("ttl", () -> {
            Voicechat.LOGGER.warn("Dropping expired voice chat packets; queues: control={}, audio={}",
                    controlQueue.size(), audioQueue.size());
        });
        return true;
    }

    private boolean allowUnknownPacket() {
        long now = System.nanoTime();
        if (now - unknownRateWindowStart >= 1_000_000_000L) {
            unknownRateWindowStart = now;
            unknownPacketsInWindow = 0;
        }
        return ++unknownPacketsInWindow <= UNKNOWN_PACKET_RATE_LIMIT;
    }

    private String getBindAddress() {
        String bindAddress = Voicechat.SERVER_CONFIG.voiceChatBindAddress.get();

        if (bindAddress.trim().equals("*")) {
            bindAddress = "";
        } else if (bindAddress.trim().isEmpty()) {
            try {
                bindAddress = Bukkit.getIp();
                if (!bindAddress.trim().isEmpty()) {
                    try {
                        InetAddress address = InetAddress.getByName(bindAddress);
                        if (address.isLoopbackAddress()) {
                            bindAddress = "";
                        } else {
                            Voicechat.LOGGER.info("Using server-ip as bind address: {}", bindAddress);
                        }
                    } catch (Exception e) {
                        Voicechat.LOGGER.warn("Invalid server-ip", e);
                        bindAddress = "";
                    }
                }
            } catch (Throwable t) {
                Voicechat.LOGGER.warn("Failed to get server-ip from server.properties - binding to wildcard address", t);
            }
        }

        return bindAddress;
    }

    @Nullable
    public Secret getSecret(UUID playerUUID) {
        return secrets.get(playerUUID);
    }

    /**
     * @param playerUUID the player uuid
     * @return the new secret or null if the player already has a secret
     */
    @Nullable
    public Secret generateNewSecret(UUID playerUUID) {
        Secret secret = Secret.generateNewRandomSecret();
        return secrets.putIfAbsent(playerUUID, secret) == null ? secret : null;
    }

    /**
     * Returns a short-lived secret for a bounded post-join handshake. Reusing
     * an already-created secret closes the race where the client's one-time
     * login request arrives just before the join handler registers its channel.
     */
    public Secret generateHotloadSecret(UUID playerUUID) {
        Secret secret = getOrCreateSecret(secrets, playerUUID);
        hotloadSecretExpiries.put(playerUUID, System.currentTimeMillis() + HOTLOAD_SECRET_TTL_MILLIS);
        return secret;
    }

    static Secret getOrCreateSecret(Map<UUID, Secret> secrets, UUID playerUUID) {
        return secrets.computeIfAbsent(playerUUID, ignored -> Secret.generateNewRandomSecret());
    }

    public boolean hasSecret(UUID playerUUID) {
        return secrets.containsKey(playerUUID);
    }

    public void disconnectClient(UUID playerUUID) {
        ClientConnection connection = connections.remove(playerUUID);
        if (connection != null) {
            connectionsByAddress.remove(connection.getAddress(), connection);
        }
        ClientConnection uncheckedConnection = unCheckedConnections.remove(playerUUID);
        if (uncheckedConnection != null) {
            unCheckedConnectionsByAddress.remove(uncheckedConnection.getAddress(), uncheckedConnection);
        }
        secrets.remove(playerUUID);
        hotloadSecretExpiries.remove(playerUUID);
        TalkingState.clear(playerUUID);
        PluginManager.instance().onPlayerDisconnected(playerUUID);
    }

    public synchronized void close() {
        // Cleanup must still run if the packet processor stopped unexpectedly.
        if (cleanupComplete) return;
        closed = true;
        try {
            socket.close();
        } catch (Throwable t) {
            Voicechat.LOGGER.warn("Failed to close voice chat socket", t);
        }
        processThread.close();
        interrupt();
        processThread.interrupt();
        joinQuietly(this, 2_000L);
        joinQuietly(processThread, 2_000L);
        controlQueue.clear();
        audioQueue.clear();
        connections.clear();
        unCheckedConnections.clear();
        connectionsByAddress.clear();
        unCheckedConnectionsByAddress.clear();
        secrets.clear();
        hotloadSecretExpiries.clear();
        pingManager.clear();
        playerStateManager.clear();
        groupManager.rememberClientGroups();
        groupManager.clear();
        categoryManager.clear();
        TalkingState.clearAll();
        CooldownTimer.clear();
        PluginManager pluginManager = PluginManager.getIfInitialized();
        if (pluginManager != null) pluginManager.onServerStopped();
        cleanupComplete = true;
    }

    private static void joinQuietly(Thread thread, long timeoutMillis) {
        if (thread == Thread.currentThread()) return;
        try {
            thread.join(timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isClosed() {
        return closed || !processThread.running;
    }

    private class ProcessThread extends Thread {
        private volatile boolean running;
        private long lastKeepAlive;
        private long lastHotloadSecretPrune;
        private int controlBurst;

        public ProcessThread() {
            running = true;
            lastKeepAlive = 0L;
            lastHotloadSecretPrune = 0L;
            setDaemon(true);
            setName("VoiceChatPacketProcessingThread");
            setUncaughtExceptionHandler(new VoicechatUncaughtExceptionHandler());
        }

        @Override
        public void run() {
            while (running) {
                try {
                    pingManager.checkTimeouts();
                    long keepAliveTime = System.currentTimeMillis();
                    expireHotloadSecrets(keepAliveTime);
                    if (keepAliveTime - lastKeepAlive > Voicechat.SERVER_CONFIG.keepAlive.get()) {
                        sendKeepAlives();
                        lastKeepAlive = keepAliveTime;
                    }

                    NetworkMessage message = controlBurst < 8 ? controlQueue.poll() : null;
                    if (message != null) {
                        controlBurst++;
                    } else {
                        message = audioQueue.poll(10, TimeUnit.MILLISECONDS);
                        controlBurst = 0;
                        if (message == null) {
                            message = controlQueue.poll();
                            if (message != null) controlBurst = 1;
                        }
                    }
                    if (message == null) {
                        continue;
                    }
                    if (isExpired(message)) {
                        continue;
                    }

                    if (message.getPacket() instanceof AuthenticatePacket) {
                        AuthenticatePacket packet = (AuthenticatePacket) message.getPacket();
                        Secret secret = secrets.get(packet.getPlayerUUID());
                        if (secret != null && secret.equals(packet.getSecret())) {
                            ClientConnection connection = unCheckedConnections.get(packet.getPlayerUUID());
                            if (connection == null) {
                                connection = connections.get(packet.getPlayerUUID());
                            }
                            if (connection == null) {
                                connection = new ClientConnection(packet.getPlayerUUID(), message.getAddress());
                                unCheckedConnections.put(packet.getPlayerUUID(), connection);
                                unCheckedConnectionsByAddress.put(message.getAddress(), connection);
                                hotloadSecretExpiries.remove(packet.getPlayerUUID());
                                Voicechat.LOGGER.info("Successfully authenticated player {}", packet.getPlayerUUID());
                            }
                            sendPacket(new AuthenticateAckPacket(), connection);
                        }
                    }

                    if (message.getPacket() instanceof ConnectionCheckPacket) {
                        ClientConnection connection = getUnconnectedSender(message);
                        if (connection == null) {
                            connection = getSender(message);
                            if (connection != null) {
                                sendPacket(new ConnectionCheckAckPacket(), connection);
                            }
                            continue;
                        }
                        // Refresh keepalive, so players who took longer than the timeout can still connect
                        connection.setLastKeepAliveResponse(System.currentTimeMillis());
                        ServerPlayerManager.PlayerSnapshot playerSnapshot = ServerPlayerManager.getSnapshot(connection.getPlayerUUID());
                        if (playerSnapshot == null) {
                            continue;
                        }
                        ClientConnection previous = connections.put(connection.getPlayerUUID(), connection);
                        if (previous != null && previous != connection) {
                            connectionsByAddress.remove(previous.getAddress(), previous);
                        }
                        connectionsByAddress.put(connection.getAddress(), connection);
                        unCheckedConnections.remove(connection.getPlayerUUID(), connection);
                        unCheckedConnectionsByAddress.remove(connection.getAddress(), connection);
                        Voicechat.LOGGER.info("Successfully validated connection of player {}", connection.getPlayerUUID());
                        Player player = playerSnapshot.getRealPlayer();
                        UUID playerId = connection.getPlayerUUID();
                        Compatibility compatibility = Voicechat.compatibility;
                        if (compatibility != null) {
                            compatibility.runForPlayer(player, () -> {
                                Voicechat plugin = Voicechat.INSTANCE;
                                if (!Voicechat.ACTIVE || closed || !player.isOnline() || plugin == null
                                        || !plugin.isEnabled()) {
                                    return;
                                }
                                ServerVoiceEvents voiceEvents = Voicechat.SERVER;
                                if (voiceEvents == null) {
                                    return;
                                }
                                voiceEvents.onHotloadConnectionValidated(player);
                                playerStateManager.onPlayerVoicechatConnect(player);
                                PluginManager.instance().onPlayerConnected(player);
                                Voicechat.LOGGER.info("Player {} ({}) successfully connected to voice chat", player.getName(), playerId);
                            });
                        }
                        sendPacket(new ConnectionCheckAckPacket(), connection);
                        continue;
                    }

                    ClientConnection conn = getSender(message);
                    if (conn == null) {
                        continue;
                    }

                    if (message.getPacket() instanceof MicPacket) {
                        onMicPacket(conn.getPlayerUUID(), (MicPacket) message.getPacket());
                    } else if (message.getPacket() instanceof PingPacket) {
                        PingPacket packet = (PingPacket) message.getPacket();
                        pingManager.onPongPacket(packet);
                    } else if (message.getPacket() instanceof KeepAlivePacket) {
                        conn.setLastKeepAliveResponse(System.currentTimeMillis());
                    }
                } catch (InterruptedException e) {
                    if (running) Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (running) Voicechat.LOGGER.error("Voice chat server error", e);
                }
            }
        }

        public void close() {
            running = false;
        }
    }

    private void expireHotloadSecrets(long now) {
        if (now - processThread.lastHotloadSecretPrune < HOTLOAD_SECRET_PRUNE_INTERVAL_MILLIS) {
            return;
        }
        processThread.lastHotloadSecretPrune = now;
        hotloadSecretExpiries.forEach((playerId, expiresAt) -> {
            if (expiresAt > now || !hotloadSecretExpiries.remove(playerId, expiresAt)) {
                return;
            }
            if (!connections.containsKey(playerId) && !unCheckedConnections.containsKey(playerId)) {
                secrets.remove(playerId);
            }
        });
    }

    public void onMicPacket(UUID playerUuid, MicPacket packet) {
        ServerPlayerManager.PlayerSnapshot player = ServerPlayerManager.getSnapshot(playerUuid);
        if (player == null) {
            return;
        }
        if (!player.canSpeak()) {
            CooldownTimer.run("no-speak-" + playerUuid, () -> {
                NetManager.sendStatusMessage(player.getRealPlayer(), "message.voicechat.no_speak_permission");
            });
            return;
        }
        PlayerState state = playerStateManager.getState(playerUuid);
        if (state == null) {
            return;
        }
        PluginManager pluginManager = PluginManager.instance();
        boolean cancelled = pluginManager.hasEventListeners(de.maxhenkel.voicechat.api.events.MicrophonePacketEvent.class)
                && pluginManager.onMicPacket(player, state, packet);
        if (!cancelled) {
            TalkingState.mark(playerUuid);
            processMicPacket(player, state, packet);
        }
    }

    private void processMicPacket(ServerPlayerManager.PlayerSnapshot player, PlayerState state, MicPacket packet) {
        UUID groupId = state.getGroup();
        if (groupId != null) {
            @Nullable Group group = groupManager.getGroup(groupId);
            processGroupPacket(state, player, packet, groupId);
            if (group == null || group.isOpen()) {
                processProximityPacket(state, player, packet);
            }
            return;
        }
        processProximityPacket(state, player, packet);
    }

    private void processGroupPacket(PlayerState senderState, ServerPlayerManager.PlayerSnapshot sender,
                                    MicPacket packet, UUID groupId) {
        GroupSoundPacket groupSoundPacket = new GroupSoundPacket(senderState.getUuid(), senderState.getUuid(), packet.getData(), packet.getSequenceNumber(), null);
        for (PlayerState state : playerStateManager.getStates()) {
            if (!groupId.equals(state.getGroup())) {
                continue;
            }
            if (senderState.getUuid().equals(state.getUuid())) {
                continue;
            }
            ServerPlayerManager.PlayerSnapshot p = ServerPlayerManager.getSnapshot(state.getUuid());
            if (p == null) {
                continue;
            }
            @Nullable ClientConnection connection = getConnection(state.getUuid());
            sendSnapshotSoundPacket(sender, senderState, p, state, connection, groupSoundPacket,
                    SoundPacketEvent.SOURCE_GROUP);
        }
    }

    private void processProximityPacket(PlayerState senderState, ServerPlayerManager.PlayerSnapshot sender, MicPacket packet) {
        @Nullable UUID groupId = senderState.getGroup();
        float distance;
        if (packet.isWhispering()) {
            distance = Voicechat.SERVER_CONFIG.whisperDistance.get().floatValue();
        } else {
            distance = Utils.getDefaultDistance();
        }

        PluginManager pluginManager = PluginManager.instance();
        if (pluginManager.hasEventListeners(de.maxhenkel.voicechat.api.events.VoiceDistanceEvent.class)) {
            distance = pluginManager.getDistance(sender, senderState, packet, distance);
        }

        SoundPacket<?> soundPacket = null;
        String source = null;
        if (sender.getGameMode().equals(GameMode.SPECTATOR)) {
            if (Voicechat.SERVER_CONFIG.spectatorPlayerPossession.get()) {
                UUID spectatingPlayerId = sender.getSpectatorTarget();
                if (spectatingPlayerId != null && !spectatingPlayerId.equals(sender.getUuid())) {
                    ServerPlayerManager.PlayerSnapshot spectatingPlayer = ServerPlayerManager.getSnapshot(spectatingPlayerId);
                    if (spectatingPlayer != null) {
                        PlayerState receiverState = playerStateManager.getState(spectatingPlayerId);
                        if (receiverState == null) {
                            return;
                        }
                        GroupSoundPacket groupSoundPacket = new GroupSoundPacket(senderState.getUuid(), senderState.getUuid(), packet.getData(), packet.getSequenceNumber(), null);
                        @Nullable ClientConnection connection = getConnection(receiverState.getUuid());
                        sendSnapshotSoundPacket(sender, senderState, spectatingPlayer, receiverState,
                                connection, groupSoundPacket, SoundPacketEvent.SOURCE_SPECTATOR);
                        return;
                    }
                }
            }
            if (Voicechat.SERVER_CONFIG.spectatorInteraction.get()) {
                soundPacket = new LocationSoundPacket(sender.getUuid(), sender.getUuid(), sender.getPacketLocation(),
                        packet.getData(), packet.getSequenceNumber(), distance, null);
                source = SoundPacketEvent.SOURCE_SPECTATOR;
            }
        }

        if (soundPacket == null) {
            soundPacket = new PlayerSoundPacket(sender.getUuid(), sender.getUuid(), packet.getData(),
                    packet.getSequenceNumber(), packet.isWhispering(), distance, null);
            source = SoundPacketEvent.SOURCE_PROXIMITY;
        }

        broadcastSnapshots(ServerPlayerManager.getSnapshotsInRange(sender.getWorldId(), sender.getPosition(),
                        getBroadcastRange(distance), p -> !p.getUuid().equals(sender.getUuid())),
                soundPacket, sender, senderState, groupId, source);
    }

    public void sendPluginSoundPacket(ServerPlayerManager.PlayerSnapshot receiver, SoundPacket<?> soundPacket) {
        PlayerState state = playerStateManager.getState(receiver.getUuid());
        if (state == null) {
            return;
        }
        sendSnapshotSoundPacket(null, null, receiver, state, getConnection(receiver.getUuid()), soundPacket,
                SoundPacketEvent.SOURCE_PLUGIN);
    }

    private void sendSnapshotSoundPacket(@Nullable ServerPlayerManager.PlayerSnapshot sender,
                                         @Nullable PlayerState senderState,
                                         ServerPlayerManager.PlayerSnapshot receiver, PlayerState receiverState,
                                         @Nullable ClientConnection connection, SoundPacket<?> soundPacket, String source) {
        UUID receiverId = receiver.getUuid();
        PluginManager pluginManager = PluginManager.instance();
        pluginManager.onListenerAudio(receiverId, soundPacket);

        if (connection == null || receiverState.isDisabled() || receiverState.isDisconnected()) {
            return;
        }

        // Event connections are backed by the same immutable snapshots as the
        // normal high-frequency path.
        if (pluginManager.hasSoundPacketListeners(soundPacket)
                && pluginManager.onSoundPacket(sender, senderState, receiver, receiverState, soundPacket, source)) {
            return;
        }

        if (!receiver.canListen()) {
            CooldownTimer.run("no-listen-" + receiverId, 30_000L, () ->
                    NetManager.sendStatusMessage(receiver.getRealPlayer(), "message.voicechat.no_listen_permission"));
            return;
        }
        sendPacket(soundPacket, connection);
    }

    public double getBroadcastRange(float minRange) {
        double broadcastRange = Voicechat.SERVER_CONFIG.broadcastRange.get();
        if (broadcastRange < 0D) {
            broadcastRange = Voicechat.SERVER_CONFIG.voiceChatDistance.get() + 1D;
        }
        return Math.max(broadcastRange, minRange);
    }

    public void broadcastSnapshots(Collection<ServerPlayerManager.PlayerSnapshot> players, SoundPacket<?> packet,
                                   @Nullable ServerPlayerManager.PlayerSnapshot sender,
                                   @Nullable PlayerState senderState,
                                   @Nullable UUID groupId, String source) {
        for (ServerPlayerManager.PlayerSnapshot player : players) {
            PlayerState state = playerStateManager.getState(player.getUuid());
            if (state == null) {
                continue;
            }
            UUID receiverGroupId = state.getGroup();
            if (receiverGroupId != null && receiverGroupId.equals(groupId)) {
                continue;
            }
            Group receiverGroup = receiverGroupId == null ? null : groupManager.getGroup(receiverGroupId);
            if (receiverGroup != null && receiverGroup.isIsolated()) {
                continue;
            }
            sendSnapshotSoundPacket(sender, senderState, player, state, getConnection(player.getUuid()), packet, source);
        }
    }

    private void sendKeepAlives() {
        long timestamp = System.currentTimeMillis();
        long connectionTimeout = Voicechat.SERVER_CONFIG.keepAlive.get() * 10L;

        for (ClientConnection connection : unCheckedConnections.values()) {
            if (timestamp - connection.getLastKeepAliveResponse() < connectionTimeout) continue;
            if (unCheckedConnections.remove(connection.getPlayerUUID(), connection)) {
                unCheckedConnectionsByAddress.remove(connection.getAddress(), connection);
                if (!connections.containsKey(connection.getPlayerUUID())) {
                    secrets.remove(connection.getPlayerUUID());
                    hotloadSecretExpiries.remove(connection.getPlayerUUID());
                }
            }
        }

        for (ClientConnection connection : connections.values()) {
            if (timestamp - connection.getLastKeepAliveResponse() >= connectionTimeout) {
                // Don't call disconnectClient here!
                secrets.remove(connection.getPlayerUUID());
                Voicechat.LOGGER.info("Player {} timed out", connection.getPlayerUUID());
                ServerPlayerManager.PlayerSnapshot snapshot = ServerPlayerManager.getSnapshot(connection.getPlayerUUID());
                Compatibility compatibility = Voicechat.compatibility;
                if (snapshot != null && compatibility != null) {
                    Player player = snapshot.getRealPlayer();
                    compatibility.runForPlayer(player, () -> {
                        ServerVoiceEvents voiceEvents = Voicechat.SERVER;
                        if (!Voicechat.ACTIVE || closed || !player.isOnline() || voiceEvents == null) return;
                        Voicechat.LOGGER.info("Reconnecting player {}", snapshot.getName());
                        playerStateManager.onPlayerVoicechatDisconnect(connection.getPlayerUUID());
                        PluginManager.instance().onPlayerDisconnected(connection.getPlayerUUID());
                        voiceEvents.initializePlayerConnection(player);
                    });
                } else {
                    Voicechat.LOGGER.error("Reconnecting player {} failed (Could not find player)", connection.getPlayerUUID());
                    playerStateManager.onPlayerVoicechatDisconnect(connection.getPlayerUUID());
                    PluginManager.instance().onPlayerDisconnected(connection.getPlayerUUID());
                }
                if (connections.remove(connection.getPlayerUUID(), connection)) {
                    connectionsByAddress.remove(connection.getAddress(), connection);
                }
            }
        }

        for (ClientConnection connection : connections.values()) {
            sendPacket(new KeepAlivePacket(), connection);
        }

    }

    @Nullable
    public ClientConnection getSender(NetworkMessage message) {
        return connectionsByAddress.get(message.getAddress());
    }

    @Nullable
    public ClientConnection getUnconnectedSender(NetworkMessage message) {
        return unCheckedConnectionsByAddress.get(message.getAddress());
    }

    public Map<UUID, ClientConnection> getConnections() {
        return connections;
    }

    @Nullable
    public ClientConnection getConnection(UUID playerID) {
        return connections.get(playerID);
    }

    public VoicechatSocket getSocket() {
        return socket;
    }

    public boolean isSocketOwnedBy(ClassLoader owner) {
        return socket.getClass().getClassLoader() == owner;
    }

    public int getPort() {
        return socket.getLocalPort();
    }

    /**
     * Sends the packet and handles potential errors.
     *
     * @param packet     the packet to send
     * @param connection the connection to send the packet to
     * @return if the packet was sent successfully
     */
    public boolean sendPacket(Packet<?> packet, ClientConnection connection) {
        try {
            sendPacketRaw(packet, connection);
            return true;
        } catch (Exception e) {
            // Reload/quit can retire a connection after a keepalive iteration selected it.
            // Its failed send is a normal terminal outcome; retain errors for live sessions.
            if (closed || (connections.get(connection.getPlayerUUID()) != connection
                    && unCheckedConnections.get(connection.getPlayerUUID()) != connection)) return false;
            CooldownTimer.run("send-error-" + connection.getPlayerUUID(), 30_000L, () ->
                    Voicechat.LOGGER.error("Failed to send voice chat packet to {}", connection.getPlayerUUID(), e));
            return false;
        }
    }

    /**
     * Sends the packet. You must handle potential errors
     *
     * @param packet     the packet to send
     * @param connection the connection to send the packet to
     * @throws Exception if an I/O error occurs
     */
    public void sendPacketRaw(Packet<?> packet, ClientConnection connection) throws Exception {
        connection.send(this, new NetworkMessage(packet));
    }

    public PingManager getPingManager() {
        return pingManager;
    }

    public PlayerStateManager getPlayerStateManager() {
        return playerStateManager;
    }

    public ServerGroupManager getGroupManager() {
        return groupManager;
    }

    public ServerCategoryManager getCategoryManager() {
        return categoryManager;
    }

}
