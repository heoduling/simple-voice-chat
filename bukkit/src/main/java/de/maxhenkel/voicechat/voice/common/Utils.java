package de.maxhenkel.voicechat.voice.common;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.config.ServerConfig;

public class Utils {

    /**
     * Gets the default voice chat distance
     *
     * @return the default voice chat distance
     */
    public static float getDefaultDistance() {
        ServerConfig config = Voicechat.SERVER_CONFIG;
        return config == null ? 0F : config.voiceChatDistance.get().floatValue();
    }

}
