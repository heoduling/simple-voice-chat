package de.maxhenkel.voicechat;

import de.maxhenkel.voicechat.configbuilder.ConfigBuilder;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.command.VoiceChatCommands;
import de.maxhenkel.voicechat.compatibility.BukkitCompatibilityManager;
import de.maxhenkel.voicechat.compatibility.Compatibility;
import de.maxhenkel.voicechat.config.ServerConfig;
import de.maxhenkel.voicechat.config.Translations;
import de.maxhenkel.voicechat.integration.commodore.CommodoreCommands;
import de.maxhenkel.voicechat.integration.commodore.MissingArgumentTypeException;
import de.maxhenkel.voicechat.integration.placeholderapi.VoicechatExpansion;
import de.maxhenkel.voicechat.integration.viaversion.ViaVersionCompatibility;
import de.maxhenkel.voicechat.logging.JavaLoggingLogger;
import de.maxhenkel.voicechat.logging.VoicechatLogger;
import de.maxhenkel.voicechat.net.NetManager;
import de.maxhenkel.voicechat.plugins.PluginManager;
import de.maxhenkel.voicechat.plugins.impl.BukkitVoicechatServiceImpl;
import de.maxhenkel.voicechat.plugins.impl.audiosender.AudioSenderImpl;
import de.maxhenkel.voicechat.plugins.impl.audiochannel.AudioPlayerImpl;
import de.maxhenkel.voicechat.voice.server.ServerVoiceEvents;
import de.maxhenkel.voicechat.voice.server.Server;
import de.maxhenkel.voicechat.voice.server.ServerPlayerManager;
import de.maxhenkel.voicechat.commodore.Commodore;
import de.maxhenkel.voicechat.commodore.CommodoreProvider;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.regex.Pattern;

public final class Voicechat extends JavaPlugin implements Listener {

    public static volatile Voicechat INSTANCE;
    public static volatile boolean ACTIVE;

    public static final String MODID = "voicechat";
    public static VoicechatLogger LOGGER;

    public static int COMPATIBILITY_VERSION = BuildConstants.COMPATIBILITY_VERSION;

    public static volatile ServerConfig SERVER_CONFIG;
    public static volatile Translations TRANSLATIONS;
    public static volatile ServerVoiceEvents SERVER;

    public static volatile BukkitVoicechatServiceImpl apiService;
    public static volatile NetManager netManager;
    public static volatile Compatibility compatibility;
    private VoicechatExpansion placeholderExpansion;
    private final Object lifecycleLock = new Object();

    public static final int MAX_GROUP_NAME_LENGTH = 24;
    public static final Pattern GROUP_REGEX = Pattern.compile("^[^\\p{C}\\s][^\\p{C}]{0,23}$");

    @Override
    public void onEnable() {
        INSTANCE = this;
        ACTIVE = true;
        LOGGER = new JavaLoggingLogger(getLogger());

        if (debugMode()) {
            LOGGER.warn("Running in debug mode - Don't leave this enabled in production!");
        }

        try {
            compatibility = BukkitCompatibilityManager.loadCompatibility();
            if (compatibility == null) {
                disablePlugin();
                return;
            }
        } catch (Throwable t) {
            LOGGER.fatal("An unexpected error occurred while loading compatibility", t);
            disablePlugin();
            return;
        }

        LOGGER.info("Compatibility version {}", COMPATIBILITY_VERSION);

        SERVER_CONFIG = ConfigBuilder.builder(ServerConfig::new).path(getDataFolder().toPath().resolve("voicechat-server.properties")).build();
        TRANSLATIONS = ConfigBuilder.builder(Translations::new).path(getDataFolder().toPath().resolve("translations.properties")).build();

        netManager = new NetManager();
        netManager.onEnable();

        apiService = new BukkitVoicechatServiceImpl();
        getServer().getServicesManager().register(BukkitVoicechatService.class, apiService, this, ServicePriority.Normal);
        Bukkit.getPluginManager().registerEvents(this, this);

        PluginCommand voicechatCommand = getCommand(VoiceChatCommands.VOICECHAT_COMMAND);
        if (voicechatCommand != null) {
            VoiceChatCommands voiceChatCommands = new VoiceChatCommands();
            voicechatCommand.setExecutor(voiceChatCommands);
            voicechatCommand.setTabCompleter(voiceChatCommands);
            try {
                if (CommodoreProvider.isSupported()) {
                    Commodore commodore = CommodoreProvider.getCommodore(this);
                    CommodoreCommands.registerCompletions(commodore);
                    LOGGER.info("Successfully initialized commodore command completion");
                } else {
                    LOGGER.warn("Commodore command completion is not supported");
                }
            } catch (MissingArgumentTypeException t) {
                LOGGER.warn("Failed to initialize commodore command completion: {}", t.getMessage());
            } catch (Throwable t) {
                LOGGER.warn("Failed to initialize commodore command completion", t);
            }
        } else {
            LOGGER.error("Failed to register commands");
        }

        try {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                placeholderExpansion = new VoicechatExpansion();
                placeholderExpansion.register();
                LOGGER.info("Successfully registered PlaceholderAPI expansion");
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to register PlaceholderAPI expansion", t);
        }

        try {
            if (Bukkit.getPluginManager().getPlugin("ViaVersion") != null) {
                ViaVersionCompatibility.register();
                LOGGER.info("Successfully added ViaVersion mappings");
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to add ViaVersion mappings", t);
        }

        compatibility.runTask(() -> {
            synchronized (lifecycleLock) {
                if (!ACTIVE || INSTANCE != this || !isEnabled()) return;
                try {
                    ServerVoiceEvents serverEvents = new ServerVoiceEvents();
                    SERVER = serverEvents;
                    PluginManager.instance().init();
                    if (!ACTIVE || INSTANCE != this || SERVER != serverEvents || !isEnabled()) return;
                    serverEvents.init();
                    if (!ACTIVE || INSTANCE != this || SERVER != serverEvents || serverEvents.getServer() == null
                            || !isEnabled()) return;

                    Bukkit.getPluginManager().registerEvents(serverEvents, this);
                    ServerPlayerManager.init(this);
                    serverEvents.resyncOnlinePlayers();
                } catch (Throwable t) {
                    LOGGER.fatal("Failed to initialize voice chat", t);
                    Bukkit.getPluginManager().disablePlugin(this);
                }
            }
        });
    }

    private void disablePlugin() {
        LOGGER.fatal("Disabling {}", BuildConstants.PLUGIN_NAME);
        Bukkit.getPluginManager().disablePlugin(this);
    }

    @Override
    public void onDisable() {
        synchronized (lifecycleLock) {
            ACTIVE = false;
            if (placeholderExpansion != null) {
                cleanup("PlaceholderAPI expansion", placeholderExpansion::unregister);
                placeholderExpansion = null;
            }
            ServerVoiceEvents activeServer = SERVER;
            SERVER = null;
            if (activeServer != null) {
                cleanup("voice chat server", activeServer::shutdown);
            }
            cleanup("audio players", AudioPlayerImpl::shutdownAll);
            NetManager activeNetManager = netManager;
            netManager = null;
            if (activeNetManager != null) {
                cleanup("plugin message channels", activeNetManager::onDisable);
            }
            BukkitVoicechatServiceImpl activeApiService = apiService;
            apiService = null;
            if (activeApiService != null) {
                cleanup("voice chat API service", () -> {
                    activeApiService.clear();
                    getServer().getServicesManager().unregister(activeApiService);
                });
            }
            cleanup("Bukkit listeners", () -> HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this));
            cleanup("player snapshots", ServerPlayerManager::shutdown);
            PluginManager pluginManager = PluginManager.getIfInitialized();
            if (pluginManager != null) cleanup("voice chat plugin API", pluginManager::shutdown);
            cleanup("audio senders", AudioSenderImpl::clear);
            compatibility = null;
            SERVER_CONFIG = null;
            TRANSLATIONS = null;
            INSTANCE = null;
            System.clearProperty("VOICECHAT_RELOADED");
        }
    }

    private void cleanup(String component, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            LOGGER.warn("Failed to clean up {}", component, t);
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (!ACTIVE || event.getPlugin() == this) {
            return;
        }
        ClassLoader owner = event.getPlugin().getClass().getClassLoader();
        BukkitVoicechatServiceImpl service = apiService;
        int removed;
        if (service != null) {
            removed = service.unregisterPluginsOwnedBy(owner);
        } else {
            PluginManager manager = PluginManager.getIfInitialized();
            removed = manager == null ? 0 : manager.unregisterPluginsOwnedBy(owner);
        }
        if (removed > 0) {
            LOGGER.info("Unregistered {} voice chat integration(s) owned by disabled plugin '{}'", removed, event.getPlugin().getName());
        }
        ServerVoiceEvents events = SERVER;
        Server voiceServer = events == null ? null : events.getServer();
        if (voiceServer != null && voiceServer.isSocketOwnedBy(owner)) {
            LOGGER.error("The disabled plugin '{}' owned the active voice chat UDP socket; disabling voice chat to release it safely",
                    event.getPlugin().getName());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    public static boolean debugMode() {
        return System.getProperty("voicechat.debug") != null;
    }

}
