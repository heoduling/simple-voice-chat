package de.maxhenkel.voicechat.plugins;

import de.maxhenkel.voicechat.BukkitVersion;
import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.events.Event;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.logging.JavaLoggingLogger;
import de.maxhenkel.voicechat.plugins.impl.BukkitVoicechatServiceImpl;
import de.maxhenkel.voicechat.plugins.impl.audiochannel.AudioPlayerImpl;
import de.maxhenkel.voicechat.voice.server.ClientConnection;
import de.maxhenkel.voicechat.voice.common.Secret;
import de.maxhenkel.voicechat.voice.server.Server;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PluginEventSnapshotSelfTest {

    private PluginEventSnapshotSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        assert26_2VersionParsing();
        VoicechatPlugin owner = () -> "self_test";
        List<String> calls = new ArrayList<>();
        List<Consumer<? extends Event>> consumers = new ArrayList<>();
        consumers.add(owned(owner, event -> calls.add("low"), -10));
        consumers.add(owned(owner, event -> calls.add("high"), 10));
        consumers.add(owned(owner, event -> calls.add("normal"), 0));

        Map<Class<? extends Event>, List<Consumer<? extends Event>>> source = new HashMap<>();
        source.put(TestEvent.class, consumers);
        Map<Class<? extends Event>, List<Consumer<? extends Event>>> snapshot = snapshot(source);
        for (Consumer<? extends Event> consumer : snapshot.get(TestEvent.class)) {
            @SuppressWarnings("unchecked")
            Consumer<TestEvent> typed = (Consumer<TestEvent>) consumer;
            typed.accept(new TestEvent());
        }
        if (!calls.equals(List.of("high", "normal", "low"))) {
            throw new AssertionError("Wrong event priority order: " + calls);
        }
        assertUnmodifiable(snapshot, snapshot.get(TestEvent.class));
        assertRawPacketLimit();
        assertDynamicRegistrationLifecycle();
        assertRegistrationBeforeManagerInitIsQueued();
        assertFailedDynamicRegistrationIsReleased();
        assertLaggingAudioPlayerStops();
        assertHotloadSecretReuse();
    }

    private static void assert26_2VersionParsing() {
        BukkitVersion standard = BukkitVersion.parseBukkitVersion("26.2-R0.1-SNAPSHOT");
        BukkitVersion dotted = BukkitVersion.parseBukkitVersion("26.2.local-SNAPSHOT");
        BukkitVersion dashed = BukkitVersion.parseBukkitVersion("26.2-local-SNAPSHOT");
        if (standard == null || dotted == null || dashed == null) {
            throw new AssertionError("Could not parse a supported 26.2 Bukkit/Paper version format");
        }
        if (!standard.equals(dotted) || !standard.equals(dashed)) {
            throw new AssertionError("Equivalent 26.2 builds did not select the same compatibility");
        }
    }

    private static Consumer<? extends Event> owned(VoicechatPlugin owner, Consumer<TestEvent> delegate, int priority)
            throws Exception {
        Class<?> type = Class.forName("de.maxhenkel.voicechat.plugins.PluginManager$OwnedConsumer");
        Constructor<?> constructor = type.getDeclaredConstructor(VoicechatPlugin.class, Consumer.class, int.class);
        constructor.setAccessible(true);
        return (Consumer<? extends Event>) constructor.newInstance(owner, delegate, priority);
    }

    @SuppressWarnings("unchecked")
    private static Map<Class<? extends Event>, List<Consumer<? extends Event>>> snapshot(
            Map<Class<? extends Event>, List<Consumer<? extends Event>>> source) throws Exception {
        Method method = PluginManager.class.getDeclaredMethod("immutableEventSnapshot", Map.class);
        method.setAccessible(true);
        return (Map<Class<? extends Event>, List<Consumer<? extends Event>>>) method.invoke(null, source);
    }

    private static void assertUnmodifiable(Map<?, ?> map, List<?> list) {
        try {
            map.clear();
            throw new AssertionError("Event snapshot map is mutable");
        } catch (UnsupportedOperationException expected) {
        }
        try {
            list.clear();
            throw new AssertionError("Event snapshot list is mutable");
        } catch (UnsupportedOperationException expected) {
        }
    }

    private static void assertRawPacketLimit() {
        ClientConnection connection = new ClientConnection(
                java.util.UUID.randomUUID(), new InetSocketAddress("127.0.0.1", 24454));
        for (int packet = 0; packet < 220; packet++) {
            if (!connection.allowRawPacket()) {
                throw new AssertionError("Raw packet limiter rejected normal headroom at packet " + packet);
            }
        }
        if (connection.allowRawPacket()) {
            throw new AssertionError("Raw packet limiter accepted packet 221");
        }
        try {
            java.lang.reflect.Field windowStart = ClientConnection.class.getDeclaredField("rawRateWindowStart");
            windowStart.setAccessible(true);
            windowStart.setLong(connection, System.nanoTime() - 1_000_000_000L);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not advance raw packet limiter window", e);
        }
        if (!connection.allowRawPacket()) {
            throw new AssertionError("Raw packet limiter did not reset after one monotonic second");
        }
    }

    private static void assertDynamicRegistrationLifecycle() throws Exception {
        Logger logger = Logger.getLogger("voicechat-self-test");
        logger.setLevel(Level.OFF);
        Voicechat.LOGGER = new JavaLoggingLogger(logger);
        Voicechat.ACTIVE = true;

        PluginManager manager = new PluginManager();
        setField(null, "instance", manager);
        setField(manager, "plugins", new CopyOnWriteArrayList<VoicechatPlugin>());
        setField(manager, "events", Map.of());
        setField(manager, "playerAudioListeners", new ConcurrentHashMap<>());

        AtomicBoolean initialized = new AtomicBoolean();
        AtomicInteger dispatched = new AtomicInteger();
        VoicechatPlugin plugin = new VoicechatPlugin() {
            @Override
            public String getPluginId() {
                return "dynamic_self_test";
            }

            @Override
            public void initialize(de.maxhenkel.voicechat.api.VoicechatApi api) {
                initialized.set(true);
            }

            @Override
            public void registerEvents(de.maxhenkel.voicechat.api.events.EventRegistration registration) {
                registration.registerEvent(TestEvent.class, event -> dispatched.incrementAndGet(), 5);
            }
        };

        if (!manager.registerPlugin(plugin) || !initialized.get()) {
            throw new AssertionError("Dynamic plugin was not initialized");
        }
        manager.dispatchEvent(TestEvent.class, new TestEvent());
        if (dispatched.get() != 1) {
            throw new AssertionError("Dynamic event was not dispatched");
        }
        manager.unregisterPlugin(plugin);
        manager.dispatchEvent(TestEvent.class, new TestEvent());
        if (dispatched.get() != 1) {
            throw new AssertionError("Unregistered dynamic event was still dispatched");
        }

        manager.shutdown();
        Voicechat.ACTIVE = false;
    }

    private static void assertFailedDynamicRegistrationIsReleased() throws Exception {
        Voicechat.ACTIVE = true;
        PluginManager manager = new PluginManager();
        setField(null, "instance", manager);
        setField(manager, "plugins", new CopyOnWriteArrayList<VoicechatPlugin>());
        setField(manager, "events", Map.of());
        setField(manager, "playerAudioListeners", new ConcurrentHashMap<>());
        BukkitVoicechatServiceImpl service = new BukkitVoicechatServiceImpl();
        Voicechat.apiService = service;
        VoicechatPlugin broken = new VoicechatPlugin() {
            @Override
            public String getPluginId() {
                return "broken_dynamic_self_test";
            }

            @Override
            public void initialize(de.maxhenkel.voicechat.api.VoicechatApi api) {
                throw new IllegalStateException("expected self-test failure");
            }
        };
        service.registerPlugin(broken);
        if (!service.getPlugins().isEmpty()) {
            throw new AssertionError("Failed dynamic plugin remained registered in the Bukkit service");
        }
        manager.shutdown();
        Voicechat.apiService = null;
        Voicechat.ACTIVE = false;
    }

    private static void assertRegistrationBeforeManagerInitIsQueued() throws Exception {
        Voicechat.ACTIVE = true;
        PluginManager manager = new PluginManager();
        setField(null, "instance", manager);
        BukkitVoicechatServiceImpl service = new BukkitVoicechatServiceImpl();
        Voicechat.apiService = service;
        AtomicBoolean initialized = new AtomicBoolean();
        VoicechatPlugin plugin = new VoicechatPlugin() {
            @Override
            public String getPluginId() {
                return "queued_dynamic_self_test";
            }

            @Override
            public void initialize(de.maxhenkel.voicechat.api.VoicechatApi api) {
                initialized.set(true);
            }
        };
        service.registerPlugin(plugin);
        if (!service.getPlugins().contains(plugin)) {
            throw new AssertionError("Plugin registered before manager init was discarded");
        }
        manager.init();
        if (!initialized.get()) {
            throw new AssertionError("Queued plugin was not initialized with the manager");
        }
        manager.shutdown();
        Voicechat.apiService = null;
        Voicechat.ACTIVE = false;
    }

    private static void assertLaggingAudioPlayerStops() throws Exception {
        UUID channelId = UUID.randomUUID();
        AudioChannel channel = proxy(AudioChannel.class, (method, args) -> {
            if (method.getName().equals("getId")) return channelId;
            if (method.getName().equals("isClosed")) return false;
            return defaultValue(method.getReturnType());
        });
        OpusEncoder encoder = proxy(OpusEncoder.class, (method, args) -> {
            if (method.getName().equals("encode")) return new byte[0];
            return defaultValue(method.getReturnType());
        });
        AtomicInteger suppliedFrames = new AtomicInteger();
        AudioPlayerImpl player = new AudioPlayerImpl(channel, encoder, () -> {
            suppliedFrames.incrementAndGet();
            long until = System.nanoTime() + 25_000_000L;
            while (System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            return new short[AudioPlayerImpl.FRAME_SIZE];
        });
        player.startPlaying();
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (suppliedFrames.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        player.stopPlaying();
        player.join(300L);
        if (player.isAlive()) {
            throw new AssertionError("Lagging audio player ignored stopPlaying interrupt");
        }
    }

    private static void assertHotloadSecretReuse() throws Exception {
        Method method = Server.class.getDeclaredMethod("getOrCreateSecret", Map.class, UUID.class);
        method.setAccessible(true);
        Map<UUID, Secret> secrets = new ConcurrentHashMap<>();
        UUID playerId = UUID.randomUUID();
        Secret first = (Secret) method.invoke(null, secrets, playerId);
        Secret second = (Secret) method.invoke(null, secrets, playerId);
        if (first == null || first != second || secrets.size() != 1) {
            throw new AssertionError("Hotload handshake did not reuse the existing player secret");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, ProxyCall call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type},
                (proxy, method, args) -> call.invoke(method, args));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }

    @FunctionalInterface
    private interface ProxyCall {
        Object invoke(Method method, Object[] args) throws Throwable;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = PluginManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class TestEvent implements Event {

        private boolean cancelled;

        @Override
        public boolean isCancellable() {
            return true;
        }

        @Override
        public boolean cancel() {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}
