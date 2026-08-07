package de.maxhenkel.voicechat.plugins.impl.audiochannel;

import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.events.SoundPacketEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import de.maxhenkel.voicechat.plugins.impl.PositionImpl;
import de.maxhenkel.voicechat.plugins.impl.ServerLevelImpl;
import de.maxhenkel.voicechat.voice.common.LocationSoundPacket;
import de.maxhenkel.voicechat.voice.common.Utils;
import de.maxhenkel.voicechat.voice.server.Server;
import de.maxhenkel.voicechat.voice.server.ServerPlayerManager;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Predicate;

public class LocationalAudioChannelImpl extends AudioChannelImpl implements LocationalAudioChannel {

    protected final UUID worldId;
    protected volatile PositionImpl position;
    protected volatile float distance;

    public LocationalAudioChannelImpl(UUID channelId, Server server, ServerLevel level, PositionImpl position) {
        super(channelId, server);
        if (!(level instanceof ServerLevelImpl)) {
            throw new IllegalArgumentException("level is not an instance of ServerLevelImpl");
        }
        this.worldId = ((ServerLevelImpl) level).getWorldId();
        this.position = position;
        this.distance = Utils.getDefaultDistance();
    }

    @Override
    public void updateLocation(Position position) {
        if (position instanceof PositionImpl) {
            this.position = (PositionImpl) position;
        } else {
            throw new IllegalArgumentException("position is not an instance of PositionImpl");
        }
    }

    @Override
    public Position getLocation() {
        return position;
    }

    @Override
    public float getDistance() {
        return distance;
    }

    @Override
    public void setDistance(float distance) {
        this.distance = distance;
    }

    @Override
    public void send(byte[] opusData) {
        if (server.isClosed()) return;
        PositionImpl currentPosition = position;
        float currentDistance = distance;
        @Nullable String currentCategory = category;
        @Nullable Predicate<ServerPlayer> currentFilter = filter;
        broadcast(new LocationSoundPacket(channelId, channelId, currentPosition.getPosition(), opusData,
                        sequenceNumber.getAndIncrement(), currentDistance, currentCategory),
                currentPosition, currentDistance, currentFilter);
    }

    @Override
    public void send(MicrophonePacket packet) {
        send(packet.getOpusEncodedData());
    }

    @Override
    public void flush() {
        if (server.isClosed()) return;
        PositionImpl currentPosition = position;
        float currentDistance = distance;
        @Nullable String currentCategory = category;
        @Nullable Predicate<ServerPlayer> currentFilter = filter;
        broadcast(new LocationSoundPacket(channelId, channelId, currentPosition.getPosition(), new byte[0],
                        sequenceNumber.getAndIncrement(), currentDistance, currentCategory),
                currentPosition, currentDistance, currentFilter);
    }

    private void broadcast(LocationSoundPacket packet, PositionImpl currentPosition, float currentDistance,
                           @Nullable Predicate<ServerPlayer> currentFilter) {
        server.broadcastSnapshots(ServerPlayerManager.getSnapshotsInRange(worldId, currentPosition.getPosition(),
                        server.getBroadcastRange(currentDistance), currentFilter),
                packet, null, null, null, SoundPacketEvent.SOURCE_PLUGIN);
    }

}
