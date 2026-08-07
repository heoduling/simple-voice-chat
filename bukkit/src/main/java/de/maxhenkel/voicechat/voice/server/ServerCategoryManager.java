package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.compatibility.Compatibility;
import de.maxhenkel.voicechat.net.AddCategoryPacket;
import de.maxhenkel.voicechat.net.NetManager;
import de.maxhenkel.voicechat.net.RemoveCategoryPacket;
import de.maxhenkel.voicechat.plugins.CategoryManager;
import de.maxhenkel.voicechat.plugins.impl.VolumeCategoryImpl;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;

public class ServerCategoryManager extends CategoryManager {

    public void onPlayerCompatibilityCheckSucceeded(Player player) {
        Voicechat.LOGGER.debug("Synchronizing {} volume categories with {}", categories.size(), player.getName());
        for (VolumeCategoryImpl category : getCategories()) {
            NetManager.sendToClient(player, new AddCategoryPacket(category));
        }
    }

    @Override
    public void addCategory(VolumeCategoryImpl category) {
        super.addCategory(category);
        Voicechat.LOGGER.debug("Synchronizing volume category {} with all players", category.getId());
        broadcastAddCategory(category);
    }

    @Override
    @Nullable
    public VolumeCategoryImpl removeCategory(String categoryId) {
        VolumeCategoryImpl volumeCategory = super.removeCategory(categoryId);
        Voicechat.LOGGER.debug("Removing volume category {} for all players", categoryId);
        broadcastRemoveCategory(categoryId);
        return volumeCategory;
    }

    private void broadcastAddCategory(VolumeCategoryImpl category) {
        AddCategoryPacket packet = new AddCategoryPacket(category);
        Compatibility compatibility = Voicechat.compatibility;
        if (!Voicechat.ACTIVE || compatibility == null) return;
        compatibility.runTask(() -> {
            Voicechat plugin = Voicechat.INSTANCE;
            if (!Voicechat.ACTIVE || plugin == null || !plugin.isEnabled()) return;
            plugin.getServer().getOnlinePlayers().forEach(p -> NetManager.sendToClient(p, packet));
        });
    }

    private void broadcastRemoveCategory(String categoryId) {
        RemoveCategoryPacket packet = new RemoveCategoryPacket(categoryId);
        Compatibility compatibility = Voicechat.compatibility;
        if (!Voicechat.ACTIVE || compatibility == null) return;
        compatibility.runTask(() -> {
            Voicechat plugin = Voicechat.INSTANCE;
            if (!Voicechat.ACTIVE || plugin == null || !plugin.isEnabled()) return;
            plugin.getServer().getOnlinePlayers().forEach(p -> NetManager.sendToClient(p, packet));
        });
    }

    public void clear() {
        categories.clear();
    }

}
