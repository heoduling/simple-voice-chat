package de.maxhenkel.voicechat.plugins.impl.audiochannel;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import de.maxhenkel.voicechat.voice.common.GroupSoundPacket;
import de.maxhenkel.voicechat.voice.server.ClientConnection;
import de.maxhenkel.voicechat.voice.server.Group;
import de.maxhenkel.voicechat.voice.server.Server;
import de.maxhenkel.voicechat.voice.server.ServerGroupManager;
import de.maxhenkel.voicechat.voice.server.ServerPlayerManager;
import de.maxhenkel.voicechat.voice.common.PlayerState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class StaticAudioChannelImpl extends AudioChannelImpl implements StaticAudioChannel {

    protected volatile boolean bypassGroupIsolation;
    protected final Set<UUID> targets;

    public StaticAudioChannelImpl(UUID channelId, Server server) {
        super(channelId, server);
        this.targets = ConcurrentHashMap.newKeySet();
    }

    @Override
    public void send(byte[] opusData) {
        if (server.isClosed()) return;
        broadcast(new GroupSoundPacket(channelId, channelId, opusData, sequenceNumber.getAndIncrement(), category));
    }

    @Override
    public void send(MicrophonePacket packet) {
        send(packet.getOpusEncodedData());
    }

    @Override
    public void flush() {
        if (server.isClosed()) return;
        GroupSoundPacket packet = new GroupSoundPacket(channelId, channelId, new byte[0], sequenceNumber.getAndIncrement(), category);
        broadcast(packet);
    }

    private void broadcast(GroupSoundPacket packet) {
        ServerGroupManager groupManager = server.getGroupManager();
        Predicate<ServerPlayer> currentFilter = filter;
        boolean currentBypassGroupIsolation = bypassGroupIsolation;
        for (UUID target : targets) {
                ClientConnection connection = server.getConnection(target);
                if (connection == null) {
                    continue;
                }
                ServerPlayerManager.PlayerSnapshot player = ServerPlayerManager.getSnapshot(target);
                if (player == null) {
                    continue;
                }
                if (!currentBypassGroupIsolation) {
                    PlayerState state = server.getPlayerStateManager().getState(target);
                    UUID groupId = state == null ? null : state.getGroup();
                    Group playerGroup = groupId == null ? null : groupManager.getGroup(groupId);
                    if (playerGroup != null && playerGroup.isIsolated()) {
                        continue;
                    }
                }
                if (currentFilter != null) {
                    if (!currentFilter.test(player)) {
                        continue;
                    }
                }
                server.sendPluginSoundPacket(player, packet);
        }
    }

    @Override
    public void setBypassGroupIsolation(boolean bypassGroupIsolation) {
        this.bypassGroupIsolation = bypassGroupIsolation;
    }

    @Override
    public boolean bypassesGroupIsolation() {
        return bypassGroupIsolation;
    }

    @Override
    public void addTarget(VoicechatConnection target) {
        targets.add(target.getPlayer().getUuid());
    }

    @Override
    public void removeTarget(VoicechatConnection target) {
        targets.remove(target.getPlayer().getUuid());
    }

    @Override
    public void clearTargets() {
        targets.clear();
    }
}
