package de.maxhenkel.voicechat.plugins.impl;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.plugins.PluginManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class BukkitVoicechatServiceImpl implements BukkitVoicechatService {

    private final CopyOnWriteArrayList<VoicechatPlugin> plugins;

    public BukkitVoicechatServiceImpl() {
        plugins = new CopyOnWriteArrayList<>();
    }

    @Override
    public void registerPlugin(VoicechatPlugin plugin) {
        if (!Voicechat.ACTIVE || Voicechat.apiService != this || !plugins.addIfAbsent(plugin)) {
            return;
        }
        PluginManager manager = PluginManager.getIfInitialized();
        if (manager != null) {
            if (!manager.registerPlugin(plugin)) {
                if (manager.isInitialized() || !Voicechat.ACTIVE || Voicechat.apiService != this) {
                    plugins.remove(plugin);
                }
                return;
            }
            if (!Voicechat.ACTIVE || Voicechat.apiService != this || !plugins.contains(plugin)) {
                manager.unregisterPlugin(plugin);
            }
        }
    }

    @Override
    public void unregisterPlugin(VoicechatPlugin plugin) {
        plugins.remove(plugin);
        PluginManager manager = PluginManager.getIfInitialized();
        if (manager != null) {
            manager.unregisterPlugin(plugin);
        }
    }

    public int unregisterPluginsOwnedBy(ClassLoader owner) {
        int removed = 0;
        for (VoicechatPlugin plugin : plugins) {
            if (plugin.getClass().getClassLoader() == owner && plugins.remove(plugin)) {
                removed++;
            }
        }
        PluginManager manager = PluginManager.getIfInitialized();
        if (manager != null) {
            removed = Math.max(removed, manager.unregisterPluginsOwnedBy(owner));
        }
        return removed;
    }

    public List<VoicechatPlugin> getPlugins() {
        return Collections.unmodifiableList(new ArrayList<>(plugins));
    }

    public void clear() {
        plugins.clear();
    }
}
