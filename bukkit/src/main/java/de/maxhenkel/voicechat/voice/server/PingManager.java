package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.voice.common.PingPacket;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class PingManager {

    private final Map<UUID, Ping> listeners;
    private final Map<UUID, UUID> pingByPlayer;
    private final Server server;

    public PingManager(Server server) {
        this.server = server;
        listeners = new ConcurrentHashMap<>();
        pingByPlayer = new ConcurrentHashMap<>();
    }

    public void onPongPacket(PingPacket packet) {
        Voicechat.LOGGER.debug("Received pong {}", packet.getId());
        Ping ping = listeners.remove(packet.getId());
        if (ping == null) {
            return;
        }
        pingByPlayer.remove(ping.connection.getPlayerUUID(), ping.id);
        ping.listener.onPong(ping.getAttempt(), System.currentTimeMillis() - packet.getTimestamp());
    }

    public void checkTimeouts() {
        if (listeners.isEmpty()) {
            return;
        }
        List<Map.Entry<UUID, Ping>> timedOut = listeners.entrySet().stream().filter(uuidPingEntry -> uuidPingEntry.getValue().isTimedOut()).collect(Collectors.toList());
        for (Map.Entry<UUID, Ping> pingEntry : timedOut) {
            Ping ping = pingEntry.getValue();
            if (listeners.get(pingEntry.getKey()) != ping) {
                continue;
            }
            if (!ping.id.equals(pingByPlayer.get(ping.connection.getPlayerUUID()))) {
                listeners.remove(pingEntry.getKey(), ping);
                continue;
            }
            int attempt = ping.getAttempt();
            if (attempt >= ping.maxAttempts) {
                listeners.remove(pingEntry.getKey(), ping);
                pingByPlayer.remove(ping.connection.getPlayerUUID(), ping.id);
                ping.listener.onTimeout(attempt);
            } else {
                ping.listener.onFailedAttempt(attempt);
                try {
                    ping.sendNext();
                } catch (Exception e) {
                    listeners.remove(ping.id, ping);
                    pingByPlayer.remove(ping.connection.getPlayerUUID(), ping.id);
                    int failedAttempt = ping.getAttempt();
                    ping.listener.onTimeout(failedAttempt);
                    Voicechat.LOGGER.warn("Failed to send ping {} after attempt {}", ping.id, failedAttempt);
                }
            }
        }
    }

    public void sendPing(ClientConnection connection, long timeout, int attempts, PingListener listener) throws Exception {
        Ping ping = new Ping(connection, listener, timeout, attempts);
        PingPacket packet = ping.prepareNextPacket();
        synchronized (this) {
            UUID oldPingId = pingByPlayer.put(connection.getPlayerUUID(), ping.id);
            if (oldPingId != null) {
                listeners.remove(oldPingId);
            }
            listeners.put(ping.id, ping);
        }
        try {
            server.sendPacketRaw(packet, connection);
            Voicechat.LOGGER.debug("Sent ping {} attempt {}", ping.id, ping.getAttempt());
        } catch (Exception e) {
            listeners.remove(ping.id, ping);
            pingByPlayer.remove(connection.getPlayerUUID(), ping.id);
            throw e;
        }
    }

    /**
     * Drops callbacks that belong to the stopped voice server. Keeping them
     * after a PlugMan unload would retain plugin classes and connections.
     */
    public void clear() {
        listeners.clear();
        pingByPlayer.clear();
    }

    private class Ping {
        private final UUID id;
        private final ClientConnection connection;
        private final PingListener listener;
        private long timestamp;
        private final long timeout;
        private final int maxAttempts;
        private int attempt;

        public Ping(ClientConnection connection, PingListener listener, long timeout, int maxAttempts) {
            this.id = UUID.randomUUID();
            this.connection = connection;
            this.listener = listener;
            this.timeout = timeout;
            this.maxAttempts = maxAttempts;
            this.attempt = 0;
        }

        public synchronized boolean isTimedOut() {
            return (System.currentTimeMillis() - timestamp) >= timeout;
        }

        public synchronized int getAttempt() {
            return attempt;
        }

        public synchronized PingPacket prepareNextPacket() {
            timestamp = System.currentTimeMillis();
            attempt++;
            return new PingPacket(id, timestamp);
        }

        public void sendNext() throws Exception {
            PingPacket packet = prepareNextPacket();
            server.sendPacketRaw(packet, connection);
            Voicechat.LOGGER.debug("Sent ping {} attempt {}", id, getAttempt());
        }
    }

    public interface PingListener {
        void onPong(int attempts, long pingMilliseconds);

        void onFailedAttempt(int attempts);

        void onTimeout(int attempts);
    }

}
