package de.maxhenkel.voicechat.plugins;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatSocket;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.audiolistener.AudioListener;
import de.maxhenkel.voicechat.api.audiolistener.PlayerAudioListener;
import de.maxhenkel.voicechat.api.events.*;
import de.maxhenkel.voicechat.plugins.impl.GroupImpl;
import de.maxhenkel.voicechat.plugins.impl.BukkitVoicechatServiceImpl;
import de.maxhenkel.voicechat.plugins.impl.VoicechatConnectionImpl;
import de.maxhenkel.voicechat.plugins.impl.VoicechatServerApiImpl;
import de.maxhenkel.voicechat.plugins.impl.VoicechatSocketImpl;
import de.maxhenkel.voicechat.plugins.impl.audiolistener.PlayerAudioListenerImpl;
import de.maxhenkel.voicechat.plugins.impl.audiochannel.AudioPlayerImpl;
import de.maxhenkel.voicechat.plugins.impl.events.*;
import de.maxhenkel.voicechat.plugins.impl.packets.*;
import de.maxhenkel.voicechat.voice.common.*;
import de.maxhenkel.voicechat.voice.server.Group;
import de.maxhenkel.voicechat.voice.server.Server;
import de.maxhenkel.voicechat.voice.server.ServerPlayerManager;
import de.maxhenkel.voicechat.voice.server.ServerVoiceEvents;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class PluginManager {

    private static final PluginManager EMPTY = new PluginManager(true);

    private volatile List<VoicechatPlugin> plugins;
    private volatile Map<Class<? extends Event>, List<Consumer<? extends Event>>> events;
    private volatile Map<UUID, CopyOnWriteArrayList<PlayerAudioListener>> playerAudioListeners;
    private final boolean empty;

    public PluginManager() {
        this(false);
    }

    private PluginManager(boolean empty) {
        this.empty = empty;
    }

    public synchronized void init() {
        BukkitVoicechatServiceImpl service = Voicechat.apiService;
        if (empty || !Voicechat.ACTIVE || instance != this || service == null || plugins != null) {
            return;
        }
        Voicechat.LOGGER.info("Loading plugins");
        CopyOnWriteArrayList<VoicechatPlugin> candidates = new CopyOnWriteArrayList<>(service.getPlugins());
        plugins = candidates;
        Voicechat.LOGGER.info("Loaded {} plugin(s)", candidates.size());
        Voicechat.LOGGER.info("Initializing plugins");
        for (VoicechatPlugin plugin : candidates) {
            try {
                plugin.initialize(VoicechatServerApiImpl.instance());
            } catch (Exception e) {
                Voicechat.LOGGER.error("Failed to initialize plugin '{}'", plugin.getPluginId(), e);
            }
            if (!ownsPluginList(candidates, service)) {
                return;
            }
        }
        Voicechat.LOGGER.info("Initialized {} plugin(s)", candidates.size());
        Map<Class<? extends Event>, List<Consumer<? extends Event>>> gatheredEvents = gatherEvents(candidates);
        if (!ownsPluginList(candidates, service)) {
            return;
        }
        events = gatheredEvents;
        playerAudioListeners = new ConcurrentHashMap<>();
    }

    private boolean ownsPluginList(List<VoicechatPlugin> candidates, BukkitVoicechatServiceImpl service) {
        return Voicechat.ACTIVE && instance == this && Voicechat.apiService == service && plugins == candidates;
    }

    private Map<Class<? extends Event>, List<Consumer<? extends Event>>> gatherEvents(List<VoicechatPlugin> candidates) {
        EventBuilder eventBuilder = EventBuilder.create();
        for (VoicechatPlugin plugin : candidates) {
            Voicechat.LOGGER.info("Registering events for '{}'", plugin.getPluginId());
            try {
                EventRegistration registration = new EventRegistration() {
                    @Override
                    public <T extends Event> void registerEvent(Class<T> eventClass, Consumer<T> event, int priority) {
                        eventBuilder.addEvent(eventClass, new OwnedConsumer<>(plugin, event, priority), priority);
                    }
                };
                plugin.registerEvents(registration);
            } catch (Throwable t) {
                Voicechat.LOGGER.error("Failed to register events for '{}'", plugin.getPluginId(), t);
            }
            if (!Voicechat.ACTIVE || instance != this || plugins != candidates) {
                break;
            }
        }
        return immutableEventSnapshot(eventBuilder.build());
    }

    public synchronized boolean registerPlugin(VoicechatPlugin plugin) {
        if (empty || plugins == null || events == null) {
            return false;
        }
        if (plugins.contains(plugin)) return true;

        try {
            plugin.initialize(VoicechatServerApiImpl.instance());
        } catch (Throwable t) {
            Voicechat.LOGGER.error("Failed to initialize dynamically registered plugin '{}'", plugin.getPluginId(), t);
            return false;
        }
        if (!isActiveManager()) {
            return false;
        }

        EventBuilder eventBuilder = EventBuilder.create();
        try {
            EventRegistration registration = new EventRegistration() {
                @Override
                public <T extends Event> void registerEvent(Class<T> eventClass, Consumer<T> event, int priority) {
                    eventBuilder.addEvent(eventClass, new OwnedConsumer<>(plugin, event, priority), priority);
                }
            };
            plugin.registerEvents(registration);
        } catch (Throwable t) {
            Voicechat.LOGGER.error("Failed to register events for dynamically registered plugin '{}'", plugin.getPluginId(), t);
            return false;
        }
        if (!isActiveManager()) {
            return false;
        }

        Map<Class<? extends Event>, List<Consumer<? extends Event>>> mergedEvents = new HashMap<>();
        events.forEach((eventClass, consumers) -> mergedEvents.put(eventClass, new ArrayList<>(consumers)));
        eventBuilder.build().forEach((eventClass, consumers) ->
                mergedEvents.computeIfAbsent(eventClass, ignored -> new ArrayList<>()).addAll(consumers));

        plugins.add(plugin);
        events = immutableEventSnapshot(mergedEvents);
        Voicechat.LOGGER.info("Dynamically registered voice chat plugin '{}'", plugin.getPluginId());
        return true;
    }

    private boolean isActiveManager() {
        return Voicechat.ACTIVE && instance == this && plugins != null && events != null;
    }

    public synchronized boolean isInitialized() {
        return plugins != null && events != null;
    }

    private static Map<Class<? extends Event>, List<Consumer<? extends Event>>> immutableEventSnapshot(
            Map<Class<? extends Event>, List<Consumer<? extends Event>>> source) {
        Map<Class<? extends Event>, List<Consumer<? extends Event>>> snapshot = new HashMap<>();
        source.forEach((eventClass, consumers) -> {
            List<Consumer<? extends Event>> ordered = new ArrayList<>(consumers);
            ordered.sort((left, right) -> Integer.compare(priorityOf(right), priorityOf(left)));
            snapshot.put(eventClass, Collections.unmodifiableList(ordered));
        });
        return Collections.unmodifiableMap(snapshot);
    }

    private static int priorityOf(Consumer<? extends Event> consumer) {
        return consumer instanceof OwnedConsumer ? ((OwnedConsumer<?>) consumer).priority : 0;
    }

    public void unregisterPlugin(VoicechatPlugin plugin) {
        unregisterPlugins(candidate -> candidate == plugin, candidate -> candidate == plugin, plugin.getClass().getClassLoader());
    }

    public int unregisterPluginsOwnedBy(ClassLoader owner) {
        return unregisterPlugins(candidate -> candidate.getClass().getClassLoader() == owner,
                candidate -> candidate.getClass().getClassLoader() == owner, owner);
    }

    private synchronized int unregisterPlugins(Predicate<VoicechatPlugin> pluginFilter,
                                                Predicate<VoicechatPlugin> eventFilter,
                                                ClassLoader owner) {
        List<VoicechatPlugin> currentPlugins = plugins;
        int removed = 0;
        if (currentPlugins != null) {
            for (VoicechatPlugin plugin : currentPlugins) {
                if (pluginFilter.test(plugin) && currentPlugins.remove(plugin)) {
                    removed++;
                }
            }
        }

        Map<Class<? extends Event>, List<Consumer<? extends Event>>> currentEvents = events;
        if (currentEvents != null) {
            Map<Class<? extends Event>, List<Consumer<? extends Event>>> filteredEvents = new HashMap<>();
            for (Map.Entry<Class<? extends Event>, List<Consumer<? extends Event>>> entry : currentEvents.entrySet()) {
                List<Consumer<? extends Event>> remaining = new ArrayList<>();
                for (Consumer<? extends Event> consumer : entry.getValue()) {
                    if (consumer instanceof OwnedConsumer
                            && eventFilter.test(((OwnedConsumer<?>) consumer).owner)) {
                        ((OwnedConsumer<?>) consumer).deactivate();
                    } else {
                        remaining.add(consumer);
                    }
                }
                if (!remaining.isEmpty()) {
                    filteredEvents.put(entry.getKey(), Collections.unmodifiableList(remaining));
                }
            }
            events = Collections.unmodifiableMap(filteredEvents);
        }

        Map<UUID, CopyOnWriteArrayList<PlayerAudioListener>> listenersByPlayer = playerAudioListeners;
        if (listenersByPlayer != null) {
            listenersByPlayer.values().forEach(listeners -> listeners.removeIf(listener -> {
                if (!isOwnedBy(listener, owner)) {
                    return false;
                }
                deactivate(listener);
                return true;
            }));
            listenersByPlayer.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        }
        AudioPlayerImpl.shutdownOwnedBy(owner);
        return removed;
    }

    private static boolean isOwnedBy(PlayerAudioListener listener, ClassLoader owner) {
        return listener.getClass().getClassLoader() == owner
                || listener instanceof PlayerAudioListenerImpl && ((PlayerAudioListenerImpl) listener).isOwnedBy(owner);
    }

    private static void deactivate(PlayerAudioListener listener) {
        if (listener instanceof PlayerAudioListenerImpl) {
            ((PlayerAudioListenerImpl) listener).deactivate();
        }
    }

    private static final class OwnedConsumer<T extends Event> implements Consumer<T> {
        private final VoicechatPlugin owner;
        private final Consumer<T> delegate;
        private final int priority;
        private volatile boolean active;

        private OwnedConsumer(VoicechatPlugin owner, Consumer<T> delegate, int priority) {
            this.owner = owner;
            this.delegate = delegate;
            this.priority = priority;
            this.active = true;
        }

        @Override
        public void accept(T event) {
            if (active) {
                delegate.accept(event);
            }
        }

        private void deactivate() {
            active = false;
        }
    }

    public synchronized boolean registerAudioListener(AudioListener l) {
        if (!(l instanceof PlayerAudioListener)) {
            return false;
        }
        PlayerAudioListener listener = (PlayerAudioListener) l;
        Map<UUID, CopyOnWriteArrayList<PlayerAudioListener>> listenersByPlayer = playerAudioListeners;
        if (listenersByPlayer == null) {
            return false;
        }
        boolean exists = listenersByPlayer
                .values()
                .stream()
                .anyMatch(listeners ->
                        listeners
                                .stream()
                                .anyMatch(playerAudioListener -> playerAudioListener.getListenerId().equals(listener.getListenerId()))
                );

        if (exists) {
            return false;
        }

        listenersByPlayer.computeIfAbsent(listener.getPlayerUuid(), k -> new CopyOnWriteArrayList<>()).add(listener);
        return true;
    }

    public synchronized boolean unregisterAudioListener(UUID listenerId) {
        Map<UUID, CopyOnWriteArrayList<PlayerAudioListener>> listenersByPlayer = playerAudioListeners;
        if (listenersByPlayer == null) {
            return false;
        }
        boolean removed = listenersByPlayer
                .values()
                .stream()
                .anyMatch(listeners ->
                        listeners.removeIf(listener -> {
                            if (!listener.getListenerId().equals(listenerId)) {
                                return false;
                            }
                            deactivate(listener);
                            return true;
                        })
                );
        if (!removed) {
            return false;
        }
        listenersByPlayer.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return true;
    }

    public List<PlayerAudioListener> getPlayerAudioListeners(UUID playerUuid) {
        Map<UUID, CopyOnWriteArrayList<PlayerAudioListener>> listenersByPlayer = playerAudioListeners;
        if (listenersByPlayer == null) {
            return Collections.emptyList();
        }
        List<PlayerAudioListener> listeners = listenersByPlayer.get(playerUuid);
        return listeners == null ? Collections.emptyList() : listeners;
    }

    public void onListenerAudio(UUID playerUuid, SoundPacket<?> packet) {
        if (playerUuid.equals(packet.getSender())) {
            return;
        }
        List<PlayerAudioListener> listeners = getPlayerAudioListeners(playerUuid);
        if (listeners.isEmpty()) {
            return;
        }

        SoundPacketImpl soundPacket;
        if (packet instanceof GroupSoundPacket) {
            soundPacket = new StaticSoundPacketImpl((GroupSoundPacket) packet);
        } else if (packet instanceof PlayerSoundPacket) {
            soundPacket = new EntitySoundPacketImpl((PlayerSoundPacket) packet);
        } else if (packet instanceof LocationSoundPacket) {
            soundPacket = new LocationalSoundPacketImpl((LocationSoundPacket) packet);
        } else {
            soundPacket = new SoundPacketImpl(packet);
        }

        for (PlayerAudioListener l : listeners) {
            if (l instanceof PlayerAudioListenerImpl) {
                try {
                    ((PlayerAudioListenerImpl) l).accept(soundPacket);
                } catch (Throwable t) {
                    Voicechat.LOGGER.error("Failed to process audio listener", t);
                }
            }
        }
    }

    public <T extends Event> boolean dispatchEvent(Class<T> eventClass, T event) {
        Map<Class<? extends Event>, List<Consumer<? extends Event>>> registeredEvents = this.events;
        if (registeredEvents == null) {
            return false;
        }
        List<Consumer<? extends Event>> events = registeredEvents.get(eventClass);
        if (events == null) {
            return false;
        }
        for (Consumer<? extends Event> evt : events) {
            try {
                Consumer<T> e = (Consumer<T>) evt;
                e.accept(event);
                if (event.isCancelled()) {
                    break;
                }
            } catch (Throwable t) {
                Voicechat.LOGGER.error("Failed to dispatch event '{}'", eventClass.getSimpleName(), t);
            }
        }
        return event.isCancelled();
    }

    public boolean hasEventListeners(Class<? extends Event> eventClass) {
        Map<Class<? extends Event>, List<Consumer<? extends Event>>> registeredEvents = events;
        if (registeredEvents == null) {
            return false;
        }
        List<Consumer<? extends Event>> listeners = registeredEvents.get(eventClass);
        return listeners != null && !listeners.isEmpty();
    }

    public boolean hasSoundPacketListeners(SoundPacket<?> packet) {
        if (packet instanceof LocationSoundPacket) {
            return hasEventListeners(LocationalSoundPacketEvent.class);
        }
        if (packet instanceof PlayerSoundPacket) {
            return hasEventListeners(EntitySoundPacketEvent.class);
        }
        if (packet instanceof GroupSoundPacket) {
            return hasEventListeners(StaticSoundPacketEvent.class);
        }
        return false;
    }

    public VoicechatSocket getSocketImplementation() {
        VoicechatServerStartingEventImpl event = new VoicechatServerStartingEventImpl();
        dispatchEvent(VoicechatServerStartingEvent.class, event);
        VoicechatSocket socket = event.getSocketImplementation();
        if (socket == null) {
            socket = new VoicechatSocketImpl();
            Voicechat.LOGGER.debug("Using default voicechat socket implementation");
        } else {
            Voicechat.LOGGER.info("Using custom voicechat socket implementation: {}", socket.getClass().getName());
        }
        return socket;
    }

    public String getVoiceHost(Player player, String voiceHost) {
        VoiceHostEventImpl event = new VoiceHostEventImpl(player, voiceHost);
        dispatchEvent(VoiceHostEvent.class, event);
        return event.getVoiceHost();
    }

    public void onRegisterVolumeCategory(VolumeCategory category) {
        dispatchEvent(RegisterVolumeCategoryEvent.class, new RegisterVolumeCategoryEventImpl(category));
    }

    public void onUnregisterVolumeCategory(VolumeCategory category) {
        dispatchEvent(UnregisterVolumeCategoryEvent.class, new UnregisterVolumeCategoryEventImpl(category));
    }

    public void onServerStarted() {
        dispatchEvent(VoicechatServerStartedEvent.class, new VoicechatServerStartedEventImpl());
    }

    public void onServerStopped() {
        if (events != null) {
            dispatchEvent(VoicechatServerStoppedEvent.class, new VoicechatServerStoppedEventImpl());
        }
    }

    public void onPlayerConnected(@Nullable Player player) {
        if (player == null) {
            return;
        }
        dispatchEvent(PlayerConnectedEvent.class, new PlayerConnectedEventImpl(VoicechatConnectionImpl.fromPlayer(player)));
    }

    public void onPlayerDisconnected(UUID player) {
        dispatchEvent(PlayerDisconnectedEvent.class, new PlayerDisconnectedEventImpl(player));
    }

    public void onPlayerStateChanged(PlayerState state) {
        dispatchEvent(PlayerStateChangedEvent.class, new PlayerStateChangedEventImpl(state));
    }

    public boolean onJoinGroup(Player player, @Nullable Group group) {
        if (group == null) {
            return onLeaveGroup(player);
        }
        return dispatchEvent(JoinGroupEvent.class, new JoinGroupEventImpl(new GroupImpl(group), VoicechatConnectionImpl.fromPlayer(player)));
    }

    public boolean onCreateGroup(@Nullable Player player, @Nullable Group group) {
        if (group == null) {
            if (player == null) {
                return false;
            }
            return onLeaveGroup(player);
        }
        return dispatchEvent(CreateGroupEvent.class, new CreateGroupEventImpl(new GroupImpl(group), VoicechatConnectionImpl.fromPlayer(player)));
    }

    public boolean onLeaveGroup(Player player) {
        ServerVoiceEvents events = Voicechat.SERVER;
        Server server = events == null ? null : events.getServer();
        if (server == null) {
            return false;
        }
        @Nullable GroupImpl group = null;
        PlayerState state = server.getPlayerStateManager().getState(player.getUniqueId());
        if (state != null) {
            UUID groupUUID = state.getGroup();
            if (groupUUID != null) {
                Group g = server.getGroupManager().getGroup(groupUUID);
                if (g != null) {
                    group = new GroupImpl(g);
                }
            }
        }

        return dispatchEvent(LeaveGroupEvent.class, new LeaveGroupEventImpl(group, VoicechatConnectionImpl.fromPlayer(player)));
    }

    public boolean onRemoveGroup(Group group) {
        return dispatchEvent(RemoveGroupEvent.class, new RemoveGroupEventImpl(new GroupImpl(group)));
    }

    public boolean onMicPacket(ServerPlayerManager.PlayerSnapshot sender, PlayerState senderState, MicPacket packet) {
        return dispatchEvent(MicrophonePacketEvent.class, new MicrophonePacketEventImpl(
                new MicrophonePacketImpl(packet, sender.getUuid()),
                new VoicechatConnectionImpl(sender, senderState)
        ));
    }

    public float getDistance(ServerPlayerManager.PlayerSnapshot sender, PlayerState senderState, MicPacket packet, float originalDistance) {
        VoiceDistanceEventImpl event = new VoiceDistanceEventImpl(new MicrophonePacketImpl(packet, sender.getUuid()), new VoicechatConnectionImpl(sender, senderState), originalDistance);
        dispatchEvent(VoiceDistanceEvent.class, event);
        return event.getDistance();
    }

    public boolean onSoundPacket(@Nullable ServerPlayerManager.PlayerSnapshot sender, @Nullable PlayerState senderState,
                                 ServerPlayerManager.PlayerSnapshot receiver, PlayerState receiverState,
                                 SoundPacket<?> p, String source) {
        VoicechatConnection senderConnection = null;
        if (sender != null && senderState != null) {
            senderConnection = new VoicechatConnectionImpl(sender, senderState);
        }

        VoicechatConnection receiverConnection = new VoicechatConnectionImpl(receiver, receiverState);
        if (p instanceof LocationSoundPacket) {
            return dispatchEvent(LocationalSoundPacketEvent.class, new LocationalSoundPacketEventImpl(
                    new LocationalSoundPacketImpl((LocationSoundPacket) p),
                    senderConnection,
                    receiverConnection,
                    source
            ));
        } else if (p instanceof PlayerSoundPacket) {
            return dispatchEvent(EntitySoundPacketEvent.class, new EntitySoundPacketEventImpl(
                    new EntitySoundPacketImpl((PlayerSoundPacket) p),
                    senderConnection,
                    receiverConnection,
                    source
            ));
        } else if (p instanceof GroupSoundPacket) {
            return dispatchEvent(StaticSoundPacketEvent.class, new StaticSoundPacketEventImpl(
                    new StaticSoundPacketImpl((GroupSoundPacket) p),
                    senderConnection,
                    receiverConnection,
                    source
            ));
        }
        return false;
    }

    private static volatile PluginManager instance;

    public static PluginManager instance() {
        PluginManager current = instance;
        if (current != null) {
            return current;
        }
        Voicechat voicechat = Voicechat.INSTANCE;
        if (!Voicechat.ACTIVE || voicechat == null || !voicechat.isEnabled()) {
            return EMPTY;
        }
        synchronized (PluginManager.class) {
            current = instance;
            if (current == null) {
                current = new PluginManager();
                instance = current;
            }
        }
        return current;
    }

    public static PluginManager getIfInitialized() {
        return instance;
    }

    public synchronized void shutdown() {
        if (empty) {
            return;
        }
        if (events != null) {
            events.values().forEach(consumers -> consumers.forEach(consumer -> {
                if (consumer instanceof OwnedConsumer) {
                    ((OwnedConsumer<?>) consumer).deactivate();
                }
            }));
        }
        if (playerAudioListeners != null) {
            playerAudioListeners.values().forEach(listeners -> listeners.forEach(PluginManager::deactivate));
            playerAudioListeners.clear();
        }
        if (Voicechat.apiService != null) Voicechat.apiService.clear();
        playerAudioListeners = null;
        events = null;
        plugins = null;
        if (instance == this) instance = null;
    }

}
