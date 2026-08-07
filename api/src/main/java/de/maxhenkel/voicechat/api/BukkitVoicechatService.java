package de.maxhenkel.voicechat.api;

public interface BukkitVoicechatService {

    /**
     * Registers the voice chat plugin on bukkit based servers.
     *
     * @param plugin the voicechat plugin
     */
    void registerPlugin(VoicechatPlugin plugin);

    /**
     * Unregisters a voice chat plugin on Bukkit based servers.
     *
     * @param plugin the voice chat plugin
     */
    default void unregisterPlugin(VoicechatPlugin plugin) {

    }

}
