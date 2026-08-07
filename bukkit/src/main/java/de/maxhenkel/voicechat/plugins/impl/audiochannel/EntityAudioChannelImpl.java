package de.maxhenkel.voicechat.plugins.impl.audiochannel;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.Entity;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.SoundPacketEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import de.maxhenkel.voicechat.compatibility.Compatibility;
import de.maxhenkel.voicechat.plugins.impl.PositionImpl;
import de.maxhenkel.voicechat.voice.common.PlayerSoundPacket;
import de.maxhenkel.voicechat.voice.common.Utils;
import de.maxhenkel.voicechat.voice.server.Server;
import de.maxhenkel.voicechat.voice.server.ServerPlayerManager;
import org.bukkit.Location;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

public class EntityAudioChannelImpl extends AudioChannelImpl implements EntityAudioChannel {

    private static final long POSITION_REFRESH_NANOS = 200_000_000L;

    protected volatile EntityTarget entityTarget;
    protected volatile boolean whispering;
    protected volatile float distance;
    protected final AtomicLong nextPositionRefresh;
    protected volatile EntityPositionSnapshot entityPosition;

    public EntityAudioChannelImpl(UUID channelId, Server server, Entity entity) {
        super(channelId, server);
        this.entityTarget = new EntityTarget(entity);
        this.whispering = false;
        this.distance = Utils.getDefaultDistance();
        this.nextPositionRefresh = new AtomicLong();
    }

    @Override
    public void setWhispering(boolean whispering) {
        this.whispering = whispering;
    }

    @Override
    public boolean isWhispering() {
        return whispering;
    }

    @Override
    public void updateEntity(Entity entity) {
        this.entityPosition = null;
        this.nextPositionRefresh.set(0L);
        this.entityTarget = new EntityTarget(entity);
    }

    @Override
    public Entity getEntity() {
        return entityTarget.entity;
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
        EntityTarget currentTarget = entityTarget;
        float currentDistance = distance;
        @Nullable String currentCategory = category;
        @Nullable Predicate<ServerPlayer> currentFilter = filter;
        broadcast(new PlayerSoundPacket(channelId, currentTarget.uuid, opusData, sequenceNumber.getAndIncrement(),
                        whispering, currentDistance, currentCategory),
                currentTarget, currentDistance, currentFilter);
    }

    @Override
    public void send(MicrophonePacket microphonePacket) {
        send(microphonePacket.getOpusEncodedData());
    }

    @Override
    public void flush() {
        if (server.isClosed()) return;
        EntityTarget currentTarget = entityTarget;
        float currentDistance = distance;
        @Nullable String currentCategory = category;
        @Nullable Predicate<ServerPlayer> currentFilter = filter;
        broadcast(new PlayerSoundPacket(channelId, currentTarget.uuid, new byte[0], sequenceNumber.getAndIncrement(),
                        whispering, currentDistance, currentCategory),
                currentTarget, currentDistance, currentFilter);
    }

    private void broadcast(PlayerSoundPacket packet, EntityTarget currentTarget, float currentDistance,
                           @Nullable Predicate<ServerPlayer> currentFilter) {
        ServerPlayerManager.PlayerSnapshot snapshot = ServerPlayerManager.getSnapshot(currentTarget.uuid);
        if (snapshot != null) {
            server.broadcastSnapshots(ServerPlayerManager.getSnapshotsInRange(snapshot.getWorldId(), snapshot.getPosition(),
                            server.getBroadcastRange(currentDistance), currentFilter), packet, null, null, null,
                    SoundPacketEvent.SOURCE_PLUGIN);
            return;
        }
        refreshEntityPosition(currentTarget);
        EntityPositionSnapshot position = entityPosition;
        if (position == null) {
            return;
        }
        server.broadcastSnapshots(ServerPlayerManager.getSnapshotsInRange(position.worldId, position.position,
                        server.getBroadcastRange(currentDistance), currentFilter), packet, null, null, null,
                SoundPacketEvent.SOURCE_PLUGIN);
    }

    private void refreshEntityPosition(EntityTarget currentTarget) {
        Entity currentEntity = currentTarget.entity;
        Object rawEntity = currentEntity.getEntity();
        if (!(rawEntity instanceof org.bukkit.entity.Entity)) {
            return;
        }
        long now = System.nanoTime();
        long nextRefresh = nextPositionRefresh.get();
        if (now < nextRefresh || !nextPositionRefresh.compareAndSet(nextRefresh, now + POSITION_REFRESH_NANOS)) {
            return;
        }
        Compatibility compatibility = Voicechat.compatibility;
        if (compatibility == null) {
            return;
        }
        org.bukkit.entity.Entity bukkitEntity = (org.bukkit.entity.Entity) rawEntity;
        compatibility.runForEntity(bukkitEntity, () -> {
            if (server.isClosed() || entityTarget != currentTarget || !bukkitEntity.isValid()) {
                return;
            }
            Location location = bukkitEntity.getLocation();
            if (location.getWorld() == null) {
                return;
            }
            entityPosition = new EntityPositionSnapshot(location.getWorld().getUID(), new PositionImpl(location));
        });
    }

    private static final class EntityTarget {
        private final Entity entity;
        private final UUID uuid;

        private EntityTarget(Entity entity) {
            this.entity = entity;
            this.uuid = entity.getUuid();
        }
    }

    private static final class EntityPositionSnapshot {
        private final UUID worldId;
        private final PositionImpl position;

        private EntityPositionSnapshot(UUID worldId, PositionImpl position) {
            this.worldId = worldId;
            this.position = position;
        }
    }

}
