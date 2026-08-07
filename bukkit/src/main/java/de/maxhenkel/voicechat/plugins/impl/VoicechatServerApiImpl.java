package de.maxhenkel.voicechat.plugins.impl;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.api.audiochannel.*;
import de.maxhenkel.voicechat.api.audiolistener.AudioListener;
import de.maxhenkel.voicechat.api.audiolistener.PlayerAudioListener;
import de.maxhenkel.voicechat.api.audiosender.AudioSender;
import de.maxhenkel.voicechat.api.config.ConfigAccessor;
import de.maxhenkel.voicechat.api.events.SoundPacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.packets.EntitySoundPacket;
import de.maxhenkel.voicechat.api.packets.LocationalSoundPacket;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;
import de.maxhenkel.voicechat.config.ServerConfig;
import de.maxhenkel.voicechat.plugins.PluginManager;
import de.maxhenkel.voicechat.plugins.impl.audiochannel.*;
import de.maxhenkel.voicechat.plugins.impl.audiolistener.PlayerAudioListenerImpl;
import de.maxhenkel.voicechat.plugins.impl.audiosender.AudioSenderImpl;
import de.maxhenkel.voicechat.plugins.impl.config.ConfigAccessorImpl;
import de.maxhenkel.voicechat.plugins.impl.packets.EntitySoundPacketImpl;
import de.maxhenkel.voicechat.plugins.impl.packets.LocationalSoundPacketImpl;
import de.maxhenkel.voicechat.plugins.impl.packets.StaticSoundPacketImpl;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import de.maxhenkel.voicechat.voice.common.SoundPacket;
import de.maxhenkel.voicechat.voice.server.ClientConnection;
import de.maxhenkel.voicechat.voice.server.Server;
import de.maxhenkel.voicechat.voice.server.ServerVoiceEvents;
import de.maxhenkel.voicechat.voice.server.ServerPlayerManager;
import org.bukkit.Bukkit;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class VoicechatServerApiImpl extends VoicechatApiImpl implements VoicechatServerApi {

    private static final VoicechatServerApiImpl INSTANCE = new VoicechatServerApiImpl();

    private VoicechatServerApiImpl() {

    }

    public static VoicechatServerApiImpl instance() {
        return INSTANCE;
    }

    @Nullable
    private static Server getActiveServer() {
        ServerVoiceEvents events = Voicechat.SERVER;
        return events == null ? null : events.getServer();
    }


    @Override
    public void sendEntitySoundPacketTo(VoicechatConnection connection, EntitySoundPacket p) {
        if (p instanceof EntitySoundPacketImpl) {
            sendPacket(connection, ((EntitySoundPacketImpl) p).getPacket());
        }
    }

    @Override
    public void sendLocationalSoundPacketTo(VoicechatConnection connection, LocationalSoundPacket p) {
        if (p instanceof LocationalSoundPacketImpl) {
            sendPacket(connection, ((LocationalSoundPacketImpl) p).getPacket());
        }
    }

    @Override
    public void sendStaticSoundPacketTo(VoicechatConnection connection, StaticSoundPacket p) {
        if (p instanceof StaticSoundPacketImpl) {
            sendPacket(connection, ((StaticSoundPacketImpl) p).getPacket());
        }
    }

    @Nullable
    @Override
    public EntityAudioChannel createEntityAudioChannel(UUID channelId, Entity entity) {
        Server server = getActiveServer();
        if (server == null) {
            return null;
        }
        return new EntityAudioChannelImpl(channelId, server, entity);
    }

    @Nullable
    @Override
    public LocationalAudioChannel createLocationalAudioChannel(UUID channelId, ServerLevel level, Position initialPosition) {
        Server server = getActiveServer();
        if (server == null) {
            return null;
        }
        if (!(level instanceof ServerLevelImpl)) {
            throw new IllegalArgumentException("level is not an instance of ServerLevelImpl");
        }
        if (initialPosition instanceof PositionImpl) {
            return new LocationalAudioChannelImpl(channelId, server, level, (PositionImpl) initialPosition);
        } else {
            throw new IllegalArgumentException("initialPosition is not an instance of PositionImpl");
        }
    }

    @Nullable
    @Override
    public StaticAudioChannel createStaticAudioChannel(UUID channelId, ServerLevel level, VoicechatConnection connection) {
        Server server = getActiveServer();
        if (server == null) {
            return null;
        }
        StaticAudioChannelImpl staticAudioChannel = new StaticAudioChannelImpl(channelId, server);
        staticAudioChannel.addTarget(connection);
        return staticAudioChannel;
    }

    @Nullable
    @Override
    public StaticAudioChannel createStaticAudioChannel(UUID channelId) {
        Server server = getActiveServer();
        if (server == null) {
            return null;
        }
        return new StaticAudioChannelImpl(channelId, server);
    }

    @Override
    public AudioPlayer createAudioPlayer(AudioChannel audioChannel, OpusEncoder encoder, Supplier<short[]> audioSupplier) {
        return new AudioPlayerImpl(audioChannel, encoder, audioSupplier);
    }

    @Override
    public AudioPlayer createAudioPlayer(AudioChannel audioChannel, OpusEncoder encoder, short[] audio) {
        return new AudioPlayerImpl(audioChannel, encoder, new AudioSupplier(audio));
    }

    @Override
    public AudioSender createAudioSender(VoicechatConnection connection) {
        return new AudioSenderImpl(connection.getPlayer().getUuid());
    }

    @Override
    public boolean registerAudioSender(AudioSender sender) {
        if (!(sender instanceof AudioSenderImpl)) {
            return false;
        }
        return AudioSenderImpl.registerAudioSender((AudioSenderImpl) sender);
    }

    @Override
    public boolean unregisterAudioSender(AudioSender sender) {
        if (!(sender instanceof AudioSenderImpl)) {
            return false;
        }
        return AudioSenderImpl.unregisterAudioSender((AudioSenderImpl) sender);
    }

    @Override
    public PlayerAudioListener.Builder playerAudioListenerBuilder() {
        return new PlayerAudioListenerImpl.BuilderImpl();
    }

    @Override
    public boolean registerAudioListener(AudioListener listener) {
        PluginManager manager = PluginManager.getIfInitialized();
        return getActiveServer() != null && manager != null && manager.registerAudioListener(listener);
    }

    @Override
    public boolean unregisterAudioListener(AudioListener listener) {
        return unregisterAudioListener(listener.getListenerId());
    }

    @Override
    public boolean unregisterAudioListener(UUID listenerId) {
        PluginManager manager = PluginManager.getIfInitialized();
        return manager != null && manager.unregisterAudioListener(listenerId);
    }

    public static void sendPacket(VoicechatConnection receiver, SoundPacket<?> soundPacket) {
        if (!(receiver instanceof VoicechatConnectionImpl)) {
            throw new IllegalArgumentException("VoicechatConnection is not an instance of VoicechatConnectionImpl");
        }
        Server server = getActiveServer();
        if (server == null) return;
        ServerPlayerManager.PlayerSnapshot snapshot = ServerPlayerManager.getSnapshot(
                ((VoicechatConnectionImpl) receiver).getPlayerUuid());
        if (snapshot != null) server.sendPluginSoundPacket(snapshot, soundPacket);
    }

    public static void sendPacket(org.bukkit.entity.Player receiver, SoundPacket<?> soundPacket) {
        Server server = getActiveServer();
        if (server == null) {
            return;
        }
        ServerPlayerManager.PlayerSnapshot snapshot = ServerPlayerManager.getSnapshot(receiver.getUniqueId());
        if (snapshot != null) server.sendPluginSoundPacket(snapshot, soundPacket);
    }

    @Nullable
    @Override
    public VoicechatConnection getConnectionOf(UUID playerUuid) {
        Server server = getActiveServer();
        if (server == null) {
            return null;
        }
        ServerPlayerManager.PlayerSnapshot snapshot = ServerPlayerManager.getSnapshot(playerUuid);
        if (snapshot == null) {
            return null;
        }
        PlayerState state = server.getPlayerStateManager().getState(playerUuid);
        return state == null ? null : new VoicechatConnectionImpl(snapshot, state);
    }

    @Override
    public Group createGroup(String name, @Nullable String password) {
        return createGroup(name, password, false);
    }

    @Override
    public Group createGroup(String name, @Nullable String password, boolean persistent) {
        return groupBuilder().setName(name).setPassword(password).setPersistent(persistent).build();
    }

    @Override
    public Group.Builder groupBuilder() {
        return new GroupImpl.BuilderImpl();
    }

    @Override
    public boolean removeGroup(UUID groupId) {
        Server server = getActiveServer();
        if (server == null) {
            return false;
        }
        return server.getGroupManager().removeGroup(groupId);
    }

    @Nullable
    @Override
    public Group getGroup(UUID groupId) {
        Server server = getActiveServer();
        if (server == null) {
            return null;
        }
        de.maxhenkel.voicechat.voice.server.Group group = server.getGroupManager().getGroup(groupId);
        return group == null ? null : new GroupImpl(group);
    }

    @Override
    public Collection<Group> getGroups() {
        Server server = getActiveServer();
        if (server == null) {
            return Collections.emptyList();
        }
        return server.getGroupManager().getGroups().values().stream().map(group -> (Group) new GroupImpl(group)).collect(Collectors.toList());
    }

    @Nullable
    @Override
    public UUID getSecret(UUID userId) {
        return null;
    }

    @Override
    public Collection<ServerPlayer> getPlayersInRange(ServerLevel level, Position pos, double range, @Nullable Predicate<ServerPlayer> filter) {
        if (pos instanceof PositionImpl) {
            if (!(level instanceof ServerLevelImpl)) {
                throw new IllegalArgumentException("level is not an instance of ServerLevelImpl");
            }
            return ServerPlayerManager.getSnapshotsInRange(((ServerLevelImpl) level).getWorldId(), pos, range, filter)
                    .stream().map(player -> (ServerPlayer) player).collect(Collectors.toList());
        } else {
            throw new IllegalArgumentException("Position is not an instance of PositionImpl");
        }
    }

    @Override
    public double getBroadcastRange() {
        ServerConfig config = Voicechat.SERVER_CONFIG;
        if (config == null) {
            return 0D;
        }
        return Math.max(config.voiceChatDistance.get(), config.broadcastRange.get());
    }

    @Override
    public void registerVolumeCategory(VolumeCategory category) {
        if (!(category instanceof VolumeCategoryImpl)) {
            throw new IllegalArgumentException("VolumeCategory is not an instance of VolumeCategoryImpl");
        }
        Server server = getActiveServer();
        if (server == null) {
            return;
        }
        server.getCategoryManager().addCategory((VolumeCategoryImpl) category);
        PluginManager.instance().onRegisterVolumeCategory(category);
    }

    @Override
    public void unregisterVolumeCategory(String categoryId) {
        Server server = getActiveServer();
        if (server == null) {
            return;
        }
        VolumeCategoryImpl category = server.getCategoryManager().removeCategory(categoryId);
        if (category != null) {
            PluginManager.instance().onUnregisterVolumeCategory(category);
        }
    }

    @Override
    public Collection<VolumeCategory> getVolumeCategories() {
        Server server = getActiveServer();
        if (server == null) {
            return Collections.emptyList();
        }
        return server.getCategoryManager().getCategories().stream().map(VolumeCategory.class::cast).collect(Collectors.toList());
    }

    @Override
    public ConfigAccessor getServerConfig() {
        ServerConfig config = Voicechat.SERVER_CONFIG;
        if (config == null) {
            throw new IllegalStateException("Voice chat server is not running");
        }
        return new ConfigAccessorImpl(config.voiceChatDistance.getConfig());
    }

}
