package de.maxhenkel.voicechat.voice.server;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Short-lived server-side state used by PlaceholderAPI nametags.
 * A voice packet is evidence that the client is transmitting, not a
 * persistent player setting, so the state expires without a scheduled task.
 */
public final class TalkingState {

    private static final long TALKING_TIMEOUT_MILLIS = 250L;
    private static final ConcurrentMap<UUID, Long> LAST_PACKET = new ConcurrentHashMap<>();

    private TalkingState() {
    }

    public static void mark(UUID playerId) {
        LAST_PACKET.put(playerId, System.currentTimeMillis());
    }

    public static boolean isTalking(UUID playerId) {
        Long lastPacket = LAST_PACKET.get(playerId);
        if (lastPacket == null) {
            return false;
        }
        if (System.currentTimeMillis() - lastPacket < TALKING_TIMEOUT_MILLIS) {
            return true;
        }
        LAST_PACKET.remove(playerId, lastPacket);
        return false;
    }

    public static void clear(UUID playerId) {
        LAST_PACKET.remove(playerId);
    }

    public static void clearAll() {
        LAST_PACKET.clear();
    }
}
