package de.maxhenkel.voicechat.integration.placeholderapi;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.config.Translations;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import de.maxhenkel.voicechat.voice.server.Server;
import de.maxhenkel.voicechat.voice.server.ServerVoiceEvents;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

public class VoicechatExpansion extends PlaceholderExpansion {

    @Override
    public @NotNull String getIdentifier() {
        return Voicechat.MODID;
    }

    @Override
    public @NotNull String getAuthor() {
        Voicechat instance = Voicechat.INSTANCE;
        return instance == null ? "" : String.join(", ", instance.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        Voicechat instance = Voicechat.INSTANCE;
        return instance == null ? "" : instance.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) {
            return super.onPlaceholderRequest(player, params);
        }
        ServerVoiceEvents events = Voicechat.SERVER;
        Translations translations = Voicechat.TRANSLATIONS;
        if (events == null || translations == null) {
            return "";
        }

        if (params.equalsIgnoreCase("installed")) {
            return events.isCompatible(player) ? translations.placeholderVoicechatInstalled.get() : "";
        } else if (params.equalsIgnoreCase("not_installed")) {
            return !events.isCompatible(player) ? translations.placeholderVoicechatNotInstalled.get() : "";
        } else if (params.equalsIgnoreCase("disabled")) {
            Server server = events.getServer();
            if (server == null) {
                return "";
            }
            PlayerState state = server.getPlayerStateManager().getState(player.getUniqueId());
            if (state == null) {
                return "";
            }
            if (!state.isDisabled()) {
                return "";
            }
            return translations.placeholderVoicechatDisabled.get();
        }

        return super.onPlaceholderRequest(player, params);
    }
}
