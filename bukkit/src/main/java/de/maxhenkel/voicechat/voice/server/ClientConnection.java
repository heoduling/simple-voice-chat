package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.voice.common.NetworkMessage;

import java.net.SocketAddress;
import java.util.UUID;

public class ClientConnection {

    private UUID playerUUID;
    private SocketAddress address;
    private long lastKeepAliveResponse;
    private long rawRateWindowStart;
    private int rawPacketsInWindow;
    private long audioRateWindowStart;
    private int audioPacketsInWindow;
    private long controlRateWindowStart;
    private int controlPacketsInWindow;

    public ClientConnection(UUID playerUUID, SocketAddress address) {
        this.playerUUID = playerUUID;
        this.address = address;
        this.lastKeepAliveResponse = System.currentTimeMillis();
        long rateWindowStart = System.nanoTime();
        this.rawRateWindowStart = rateWindowStart;
        this.audioRateWindowStart = rateWindowStart;
        this.controlRateWindowStart = rateWindowStart;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public SocketAddress getAddress() {
        return address;
    }

    public long getLastKeepAliveResponse() {
        return lastKeepAliveResponse;
    }

    public void setLastKeepAliveResponse(long lastKeepAliveResponse) {
        this.lastKeepAliveResponse = lastKeepAliveResponse;
    }

    public void send(Server server, NetworkMessage message) throws Exception {
        server.getSocket().send(message.writeServer(server, this), address);
    }

    public synchronized boolean allowRawPacket() {
        long now = System.nanoTime();
        if (now - rawRateWindowStart >= 1_000_000_000L) {
            rawRateWindowStart = now;
            rawPacketsInWindow = 0;
        }
        return ++rawPacketsInWindow <= 220;
    }

    public synchronized boolean allowAudioPacket() {
        long now = System.nanoTime();
        if (now - audioRateWindowStart >= 1_000_000_000L) {
            audioRateWindowStart = now;
            audioPacketsInWindow = 0;
        }
        return ++audioPacketsInWindow <= 100;
    }

    public synchronized boolean allowControlPacket() {
        long now = System.nanoTime();
        if (now - controlRateWindowStart >= 1_000_000_000L) {
            controlRateWindowStart = now;
            controlPacketsInWindow = 0;
        }
        return ++controlPacketsInWindow <= 100;
    }

}
