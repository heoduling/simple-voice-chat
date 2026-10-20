package de.maxhenkel.voicechat.plugins;

import de.maxhenkel.voicechat.BukkitVersion;
import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.events.Event;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.config.Translations;
import de.maxhenkel.voicechat.configbuilder.ConfigBuilder;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        assertPlaceholderConfigUpgrade();
        assertReloadAndHotloadGroups();
        assertPendingGroupRemovalHandoff();
        assertChineseUpgradeAndMessages();
        assertReadableProperties();
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

    private static void assertPendingGroupRemovalHandoff() {
        var cleanup = new de.maxhenkel.voicechat.voice.server.HotloadGroupCleanup();
        UUID player = UUID.randomUUID(), removed = UUID.randomUUID(), second = UUID.randomUUID();
        cleanup.rememberRemoval(List.of(player), removed);
        cleanup.rememberRemoval(List.of(player), second);
        if (cleanup.needsMembershipReset(player)) throw new AssertionError("Normal removal reset membership");
        cleanup.removalSent(player, second);
        cleanup.save(List.of(player), List.of());
        var restored = new de.maxhenkel.voicechat.voice.server.HotloadGroupCleanup();
        if (!restored.get(player).equals(java.util.Set.of(removed)) || !restored.needsMembershipReset(player)) {
            throw new AssertionError("Unsent deletion lost across hot-load");
        }
        restored.complete(player, restored.get(player));
        restored.save(List.of(player), List.of());
        if (restored.needsMembershipReset(player) || System.getProperty("voicechat.hotload.group_cleanup") != null) {
            throw new AssertionError("Completed removal retained");
        }
        System.out.println("Pending removal handoff and membership isolation passed");
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

    private static void assertReadableProperties() throws Exception {
        String original = "chinese=\\u4F60\\u597D\nemoji=\\uD83C\\uDF99\nliteral=\\\\u4F60\n"
                + "escaped=\\u003D\\u000A\nunpaired=\\uD83C\ninvalid=\\uZZZZ\n";
        String decoded = de.maxhenkel.voicechat.config.ReadableProperties.decode(original);
        if (!decoded.contains("chinese=你好") || !decoded.contains("emoji=🎙")
                || !decoded.contains("literal=\\\\u4F60") || !decoded.contains("escaped=\\u003D\\u000A")
                || !decoded.contains("unpaired=\\uD83C") || !decoded.contains("invalid=\\uZZZZ")) {
            throw new AssertionError("Readable properties changed escaping semantics: " + decoded);
        }
        java.util.Properties before = new java.util.Properties(), after = new java.util.Properties();
        // The invalid sequence is intentionally not a valid Java properties value.
        before.load(new java.io.StringReader(original.replace("invalid=\\uZZZZ\n", "")));
        after.load(new java.io.StringReader(decoded.replace("invalid=\\uZZZZ\n", "")));
        if (!before.equals(after)) throw new AssertionError("UTF-8 conversion changed logical config values");
        if (!decoded.equals(de.maxhenkel.voicechat.config.ReadableProperties.decode(decoded))) throw new AssertionError("Conversion is not idempotent");
        System.out.println("Readable Chinese/emoji properties, literal escapes and logical round-trip passed");
    }

    private static void assertChineseUpgradeAndMessages() throws Exception {
        Path directory = Files.createTempDirectory("voicechat-chinese-upgrade-");
        Path path = directory.resolve("translations.properties");
        Files.writeString(path, "# Simple Voice Chat translations\n# Original English header\n\nforce_voicechat_kick_message=You need %s %s to play on this server\n"
                + "voicechat_needed_for_command_message=You need to have %s installed on your client to use this command\n"
                + "player_command_message=服主自定义提示\n"
                + "placeholder_api_voicechat_talking_icon=<green>🎙</green>\n"
                + "custom_keep=不要删除这个自定义键\n", StandardCharsets.UTF_8);
        try {
            Translations translations = ConfigBuilder.builder(Translations::new).path(path)
                    .removeUnused(false).migration(Translations::migrateLegacyDefaults).saveSyncAfterBuild(true).build();
            if (!translations.forceVoicechatKickMessage.get().equals("进入本服需要安装 %s %s。")
                    || !translations.voicechatNeededForCommandMessage.get().contains("客户端安装兼容")) {
                throw new AssertionError("Old English defaults did not upgrade to Chinese");
            }
            if (!translations.playerCommandMessage.get().equals("服主自定义提示")
                    || !translations.placeholderVoicechatTalkingIcon.get().equals("<green>🎙</green>")) {
                throw new AssertionError("Chinese migration overwrote custom messages or MiniMessage");
            }
            String saved = Files.readString(path);
            if (!saved.contains("custom_keep=") || !saved.contains("中文提示与变量显示配置") || !saved.contains("玩家已通过兼容性检查")) {
                throw new AssertionError("Chinese comments or unrelated key lost");
            }
            String ping = de.maxhenkel.voicechat.compatibility.FallbackCompatibility.fallbackTranslation("message.voicechat.ping_received_attempt", "42", "3");
            if (!ping.equals("第 3 次尝试收到回应，耗时 42 毫秒。")) throw new AssertionError("Chinese ping parameters reversed: " + ping);
            String json = de.maxhenkel.voicechat.compatibility.Compatibility1_21_5.INSTANCE.createTranslationMessage("message.voicechat.group_does_not_exist");
            if (!json.contains("此群组不存在") || json.contains("\"translate\"")) throw new AssertionError("Server message still relies on client language");
            String invite = de.maxhenkel.voicechat.compatibility.Compatibility1_21_5.constructInviteMessage("服主", "测试群组", "/voicechat join test");
            if (!invite.contains("接受邀请") || !invite.contains("点击加入") || !invite.contains("click_event") || !invite.contains("/voicechat join test")) {
                throw new AssertionError("Localized invite lost its click action");
            }
            System.out.println("Chinese migration, custom values, parameter order, messages and clickable invite passed");
        } finally {
            Files.deleteIfExists(path);
            Files.deleteIfExists(directory);
        }
    }

    private static void assertReloadAndHotloadGroups() throws Exception {
        for (String host : List.of("", "pl.sjcmc.cn", "localhost:24454", "[::1]:24454", "24454")) {
            de.maxhenkel.voicechat.config.ReloadValidation.validateHost(host);
        }
        for (String host : List.of("https://example.com", "host:0", "host:70000", "host/path", "user@host", "65536", "host?query")) {
            try {
                de.maxhenkel.voicechat.config.ReloadValidation.validateHost(host);
                throw new AssertionError("Accepted an invalid host: " + host);
            } catch (IllegalArgumentException expected) {}
        }
        UUID player = UUID.randomUUID(), group = UUID.randomUUID(), nextGroup = UUID.randomUUID();
        de.maxhenkel.voicechat.voice.server.HotloadGroupCleanup first = new de.maxhenkel.voicechat.voice.server.HotloadGroupCleanup();
        first.save(List.of(player), List.of(group));
        de.maxhenkel.voicechat.voice.server.HotloadGroupCleanup second = new de.maxhenkel.voicechat.voice.server.HotloadGroupCleanup();
        if (!second.get(player).equals(java.util.Set.of(group))) throw new AssertionError("Hot-load lost stale groups");
        // Disable again before the pending player callback runs: retain BOTH generations.
        second.save(List.of(player), List.of(nextGroup));
        de.maxhenkel.voicechat.voice.server.HotloadGroupCleanup third = new de.maxhenkel.voicechat.voice.server.HotloadGroupCleanup();
        if (!third.get(player).equals(java.util.Set.of(group, nextGroup))) throw new AssertionError("Rapid unload lost pending cleanup");
        third.complete(player, third.get(player));
        third.save(List.of(player), List.of());
        if (!new de.maxhenkel.voicechat.voice.server.HotloadGroupCleanup().get(player).isEmpty()) throw new AssertionError("Completed cleanup was retained");
        third.save(List.of(player), List.of(group));
        de.maxhenkel.voicechat.voice.server.HotloadGroupCleanup quit = new de.maxhenkel.voicechat.voice.server.HotloadGroupCleanup();
        quit.retainPlayers(List.of());
        quit.save(List.of(), List.of());
        if (System.getProperty("voicechat.hotload.group_cleanup") != null) throw new AssertionError("Offline cleanup leaked");
        System.out.println("Reload host validation and four hot-load group lifecycle cases passed");
    }

    private static void assertPlaceholderConfigUpgrade() throws Exception {
        Path directory = Files.createTempDirectory("voicechat-placeholder-config-");
        Path config = directory.resolve("translations.properties");
        String installed = "<aqua>INSTALLED</aqua>";
        String talking = "<green>YES</green>";
        Files.write(config, String.join("\n",
                "placeholder_api_voicechat_installed=" + installed,
                "placeholder_api_voicechat_talking=" + talking
        ).getBytes(StandardCharsets.UTF_8));
        try {
            Translations translations = ConfigBuilder.builder(Translations::new)
                    .path(config)
                    .saveSyncAfterBuild(true)
                    .build();
            if (!installed.equals(translations.placeholderVoicechatInstalled.get())
                    || !talking.equals(translations.placeholderVoicechatTalking.get())) {
                throw new AssertionError("Existing MiniMessage placeholder values were changed during config upgrade");
            }
            if (!"0".equals(translations.placeholderVoicechatNotTalking.get())
                    || !"\uD83C\uDF99".equals(translations.placeholderVoicechatTalkingIcon.get())
                    || !"".equals(translations.placeholderVoicechatNotTalkingIcon.get())) {
                throw new AssertionError("Missing talking placeholder defaults were not loaded");
            }
            String upgraded = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
            if (!upgraded.contains("placeholder_api_voicechat_not_talking=")
                    || !upgraded.contains("placeholder_api_voicechat_talking_icon=")
                    || !upgraded.contains("placeholder_api_voicechat_not_talking_icon=")
                    || !upgraded.contains("玩家说话时显示的内容")) {
                throw new AssertionError("Missing talking placeholder keys or Chinese comments were not written to the old config");
            }
        } finally {
            Files.deleteIfExists(config);
            Files.deleteIfExists(directory);
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
