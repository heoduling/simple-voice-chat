package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.BuildConstants;
import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.compatibility.Compatibility;
import de.maxhenkel.voicechat.config.ServerConfig;
import de.maxhenkel.voicechat.config.Translations;
import de.maxhenkel.voicechat.compatibility.PlayerHideEvent;
import de.maxhenkel.voicechat.compatibility.PlayerShowEvent;
import de.maxhenkel.voicechat.net.NetManager;
import de.maxhenkel.voicechat.net.PacketRateLimiter;
import de.maxhenkel.voicechat.net.RequestSecretPacket;
import de.maxhenkel.voicechat.net.SecretPacket;
import de.maxhenkel.voicechat.plugins.PluginManager;
import de.maxhenkel.voicechat.voice.common.Secret;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ServerVoiceEvents implements Listener {

    private static final int HOTLOAD_BATCH_SIZE = 16;

    private final Map<UUID, Integer> clientCompatibilities;
    private final PacketRateLimiter rateLimiter;
    private final AtomicInteger resyncGeneration;
    private volatile Server server;

    public ServerVoiceEvents() {
        clientCompatibilities = new ConcurrentHashMap<>();
        rateLimiter = new PacketRateLimiter(Voicechat.SERVER_CONFIG.tcpRateLimit.get());
        resyncGeneration = new AtomicInteger();
    }

    public void init() {
        if (server != null) {
            return;
        }
        if (!Bukkit.getOnlineMode()) {
            Voicechat.LOGGER.warn("Running in offline mode - Voice chat encryption is not secure!");
        }

        Voicechat.compatibility.registerPlayerHideEvent(this::onPlayerHide);
        Voicechat.compatibility.registerPlayerShowEvent(this::onPlayerShow);

        server = new Server();
        server.start();

        PluginManager.instance().onServerStarted();
    }

    public void onRequestSecretPacket(Player player, RequestSecretPacket packet) {
        Voicechat.LOGGER.info("Received secret request of {} ({})", player.getName(), packet.getCompatibilityVersion());

        UUID playerUUID;

        try {
            playerUUID = player.getUniqueId();
        } catch (UnsupportedOperationException e) {
            player.kickPlayer("Tried to authenticate voice chat while still connecting");
            Voicechat.LOGGER.warn("{} tried to authenticate voice chat while still connecting", player.getName());
            return;
        }

        clientCompatibilities.put(playerUUID, packet.getCompatibilityVersion());
        if (packet.getCompatibilityVersion() != Voicechat.COMPATIBILITY_VERSION) {
            Voicechat.LOGGER.warn("Connected client {} has incompatible voice chat version (server={}, client={})", player.getName(), Voicechat.COMPATIBILITY_VERSION, packet.getCompatibilityVersion());
            sendIncompatibleMessage(player, packet.getCompatibilityVersion());
        } else {
            initializePlayerConnection(player);
        }
    }

    public boolean isCompatible(Player player) {
        return isCompatible(player.getUniqueId());
    }

    public boolean isCompatible(UUID playerUuid) {
        return clientCompatibilities.getOrDefault(playerUuid, -1) == Voicechat.COMPATIBILITY_VERSION;
    }

    public static void sendIncompatibleMessage(Player player, int clientCompatibilityVersion) {
        Translations translations = Voicechat.TRANSLATIONS;
        Compatibility compatibility = Voicechat.compatibility;
        if (translations == null || compatibility == null) {
            return;
        }
        if (clientCompatibilityVersion <= 6) {
            // Send a literal string, as we don't know if the translations exist on these versions
            player.sendMessage(String.format(translations.voicechatNotCompatibleMessage.get(), BuildConstants.MOD_COMPATIBLE_VERSION, BuildConstants.PLUGIN_NAME));
        } else {
            // This translation key is only available for compatibility version 7+
            compatibility.sendIncompatibleMessage(player, BuildConstants.MOD_COMPATIBLE_VERSION, BuildConstants.PLUGIN_NAME);
        }
    }

    public void initializePlayerConnection(Player player) {
        Server activeServer = server;
        ServerConfig config = Voicechat.SERVER_CONFIG;
        if (!Voicechat.ACTIVE || activeServer == null || config == null) {
            return;
        }
        activeServer.getPlayerStateManager().onPlayerCompatibilityCheckSucceeded(player);
        activeServer.getCategoryManager().onPlayerCompatibilityCheckSucceeded(player);
        activeServer.getGroupManager().onPlayerCompatibilityCheckSucceeded(player);

        Secret secret = activeServer.generateNewSecret(player.getUniqueId());
        if (secret == null) {
            Voicechat.LOGGER.warn("Player already requested secret - ignoring");
            return;
        }
        NetManager.sendToClient(player, new SecretPacket(player, secret, activeServer.getPort(), config));
        Voicechat.LOGGER.info("Sent secret to {}", player.getName());
    }

    public void resyncOnlinePlayers() {
        Voicechat plugin = Voicechat.INSTANCE;
        if (!Voicechat.ACTIVE || server == null || plugin == null || !plugin.isEnabled()) return;
        int generation = resyncGeneration.incrementAndGet();
        List<UUID> playerIds = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();
        server.getGroupManager().retainOnlineCleanup(playerIds);
        scheduleResyncBatch(playerIds, generation, 0);
    }

    private void scheduleResyncBatch(List<UUID> playerIds, int generation, int offset) {
        if (offset >= playerIds.size() || generation != resyncGeneration.get()) return;
        Compatibility compatibility = Voicechat.compatibility;
        if (!Voicechat.ACTIVE || compatibility == null) return;
        compatibility.runTaskLater(() -> {
            Voicechat plugin = Voicechat.INSTANCE;
            if (!Voicechat.ACTIVE || generation != resyncGeneration.get() || server == null
                    || plugin == null || !plugin.isEnabled() || Voicechat.compatibility != compatibility) return;
            int end = Math.min(offset + HOTLOAD_BATCH_SIZE, playerIds.size());
            for (int index = offset; index < end; index++) {
                Player player = Bukkit.getPlayer(playerIds.get(index));
                if (player == null) continue;
                compatibility.runForPlayer(player, () -> resyncPlayer(player, generation));
            }
            scheduleResyncBatch(playerIds, generation, end);
        }, 1L);
    }

    private void resyncPlayer(Player player, int generation) {
        Server activeServer = server;
        NetManager activeNetManager = Voicechat.netManager;
        Voicechat plugin = Voicechat.INSTANCE;
        ServerConfig config = Voicechat.SERVER_CONFIG;
        if (!Voicechat.ACTIVE || generation != resyncGeneration.get() || activeServer == null
                || activeNetManager == null || config == null || !player.isOnline()
                || plugin == null || !plugin.isEnabled()) return;
        ServerPlayerManager.refreshPlayer(player);
        activeNetManager.synchronizePlayer(player);
        activeServer.getGroupManager().clearPreviousGroups(player);
        if (activeServer.hasSecret(player.getUniqueId())) {
            // A config reload keeps groups/states but replaces the UDP session and encryption key.
            activeServer.disconnectClient(player.getUniqueId());
        }
        Secret secret = activeServer.generateHotloadSecret(player.getUniqueId());
        if (secret != null) {
            // Do not assume that an existing player has the mod. Only a real
            // UDP connection earns a compatibility entry and full state sync.
            NetManager.sendToClientUnchecked(player, new SecretPacket(player, secret, activeServer.getPort(), config));
        }
    }

    /**
     * The normal login path has already sent its initial state. A player who
     * was online during PlugMan load reaches this point after proving that it
     * can authenticate to the UDP server.
     */
    public void onHotloadConnectionValidated(Player player) {
        Server activeServer = server;
        if (!Voicechat.ACTIVE || activeServer == null || !player.isOnline()) {
            return;
        }
        if (clientCompatibilities.putIfAbsent(player.getUniqueId(), Voicechat.COMPATIBILITY_VERSION) != null) {
            return;
        }
        activeServer.getPlayerStateManager().onPlayerCompatibilityCheckSucceeded(player);
        activeServer.getCategoryManager().onPlayerCompatibilityCheckSucceeded(player);
        activeServer.getGroupManager().onPlayerCompatibilityCheckSucceeded(player);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Server activeServer = server;
        ServerConfig config = Voicechat.SERVER_CONFIG;
        if (!Voicechat.ACTIVE || activeServer == null || config == null) return;
        Player player = event.getPlayer();
        activeServer.getPlayerStateManager().onPlayerJoin(event);

        // The client sends RequestSecretPacket while it is processing the join
        // packet. PlayerJoinEvent can run after that request, so register the
        // channels and send a bounded retry from the same player-owned context.
        NetManager activeNetManager = Voicechat.netManager;
        Compatibility compatibility = Voicechat.compatibility;
        if (activeNetManager != null && compatibility != null) {
            ServerPlayerManager.refreshPlayer(player);
            activeNetManager.synchronizePlayer(player);
            clientCompatibilities.remove(player.getUniqueId());
            Secret secret = activeServer.generateHotloadSecret(player.getUniqueId());
            NetManager.sendToClientUnchecked(player, new SecretPacket(player, secret, activeServer.getPort(), config));
        }

        if (!config.forceVoiceChat.get()) {
            return;
        }

        Translations translations = Voicechat.TRANSLATIONS;
        if (compatibility == null || translations == null) return;
        compatibility.runTaskLater(() -> {
            if (!Voicechat.ACTIVE || Voicechat.compatibility != compatibility) return;
            compatibility.runForPlayer(player, () -> {
                Voicechat plugin = Voicechat.INSTANCE;
                if (!Voicechat.ACTIVE || !player.isOnline() || plugin == null || !plugin.isEnabled()) return;
                ServerConfig currentConfig = Voicechat.SERVER_CONFIG;
                Translations currentTranslations = Voicechat.TRANSLATIONS;
                if (currentConfig == null || !currentConfig.forceVoiceChat.get() || currentTranslations == null) return;
                if (!isCompatible(player)) {
                    player.kickPlayer(String.format(
                            currentTranslations.forceVoicechatKickMessage.get(),
                            BuildConstants.PLUGIN_NAME,
                            plugin.getDescription().getVersion()
                    ));
                }
            });
        }, config.loginTimeout.get() / 50L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        clientCompatibilities.remove(event.getPlayer().getUniqueId());
        rateLimiter.onPlayerLoggedOut(event.getPlayer());
        Server activeServer = server;
        if (activeServer == null) {
            return;
        }

        activeServer.getPlayerStateManager().onPlayerQuit(event);
        activeServer.getGroupManager().onPlayerQuit(event);

        activeServer.disconnectClient(event.getPlayer().getUniqueId());
        Voicechat.LOGGER.info("Disconnecting client {}", event.getPlayer().getName());
    }

    public void onPlayerHide(PlayerHideEvent event) {
        Server activeServer = server;
        if (activeServer == null) return;
        activeServer.getPlayerStateManager().onPlayerHide(event);
    }

    public void onPlayerShow(PlayerShowEvent event) {
        Server activeServer = server;
        if (activeServer == null) return;
        activeServer.getPlayerStateManager().onPlayerShow(event);
    }

    public PacketRateLimiter getRateLimiter() {
        return rateLimiter;
    }

    public Server getServer() {
        return server;
    }

    public void shutdown() {
        resyncGeneration.incrementAndGet();
        Server activeServer = server;
        server = null;
        try {
            if (activeServer != null) {
                activeServer.close();
            }
        } finally {
            clientCompatibilities.clear();
            rateLimiter.clear();
        }
    }
}
