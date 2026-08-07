package de.maxhenkel.voicechat.plugins.impl;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.compatibility.Compatibility;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import de.maxhenkel.voicechat.voice.server.PlayerStateManager;
import de.maxhenkel.voicechat.voice.server.Server;
import de.maxhenkel.voicechat.voice.server.ServerPlayerManager;
import de.maxhenkel.voicechat.voice.server.ServerVoiceEvents;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import java.util.UUID;

public class VoicechatConnectionImpl implements VoicechatConnection {

    private final ServerPlayer player;
    private final Player serverPlayer;
    private final PlayerState state;
    @Nullable
    private final Group group;

    public VoicechatConnectionImpl(Player player, PlayerState state) {
        this.serverPlayer = player;
        this.player = new ServerPlayerImpl(player);
        this.state = state;
        this.group = GroupImpl.create(state);
    }

    public VoicechatConnectionImpl(ServerPlayerManager.PlayerSnapshot player, PlayerState state) {
        this.serverPlayer = player.getRealPlayer();
        this.player = player;
        this.state = state;
        this.group = GroupImpl.create(state);
    }

    public UUID getPlayerUuid() {
        return player.getUuid();
    }

    @Nullable
    public static VoicechatConnectionImpl fromPlayer(@Nullable Player player) {
        if (player == null) {
            return null;
        }
        ServerVoiceEvents events = Voicechat.SERVER;
        Server server = events == null ? null : events.getServer();
        if (server == null) {
            return null;
        }
        PlayerState state = server.getPlayerStateManager().getState(player.getUniqueId());
        if (state == null) {
            return null;
        }
        return new VoicechatConnectionImpl(player, state);
    }

    @Nullable
    @Override
    public Group getGroup() {
        return group;
    }

    @Override
    public boolean isInGroup() {
        return group != null;
    }

    @Override
    public void setGroup(@Nullable Group group) {
        Compatibility compatibility = Voicechat.compatibility;
        if (!Voicechat.ACTIVE || compatibility == null) return;
        compatibility.runForPlayer(serverPlayer, () -> setGroupOnPlayerThread(group));
    }

    private void setGroupOnPlayerThread(@Nullable Group group) {
        ServerVoiceEvents events = Voicechat.SERVER;
        Server server = events == null ? null : events.getServer();
        if (server == null || !serverPlayer.isOnline()) {
            return;
        }
        if (group == null) {
            server.getGroupManager().leaveGroup((Player) player.getPlayer());
            return;
        }
        if (group instanceof GroupImpl) {
            GroupImpl g = (GroupImpl) group;
            de.maxhenkel.voicechat.voice.server.Group actualGroup = server.getGroupManager().getGroup(g.getGroup().getId());
            if (actualGroup == null) {
                server.getGroupManager().addGroup(g.getGroup(), serverPlayer);
                return;
            }
            server.getGroupManager().joinGroup(actualGroup, serverPlayer, g.getGroup().getPassword());
        }
    }

    @Override
    public boolean isConnected() {
        return !state.isDisconnected();
    }

    @Override
    public void setConnected(boolean connected) {
        if (isInstalled()) {
            return;
        }
        ServerVoiceEvents events = Voicechat.SERVER;
        Server server = events == null ? null : events.getServer();
        if (server == null) {
            return;
        }
        PlayerStateManager manager = server.getPlayerStateManager();
        PlayerState actualState = manager.getState(state.getUuid());
        if (actualState == null) {
            return;
        }
        if (actualState.isDisconnected() != connected) {
            return;
        }
        actualState.setDisconnected(!connected);
        manager.broadcastState(null, actualState);
    }

    @Override
    public boolean isDisabled() {
        return state.isDisabled();
    }

    @Override
    public void setDisabled(boolean disabled) {
        if (isInstalled()) {
            return;
        }
        ServerVoiceEvents events = Voicechat.SERVER;
        Server server = events == null ? null : events.getServer();
        if (server == null) {
            return;
        }
        PlayerStateManager manager = server.getPlayerStateManager();
        PlayerState actualState = manager.getState(state.getUuid());
        if (actualState == null) {
            return;
        }
        if (actualState.isDisabled() == disabled) {
            return;
        }
        actualState.setDisabled(disabled);
        manager.broadcastState(null, actualState);
    }

    @Override
    public boolean isInstalled() {
        ServerVoiceEvents events = Voicechat.SERVER;
        return events != null && events.isCompatible(getPlayerUuid());
    }

    @Override
    public ServerPlayer getPlayer() {
        return player;
    }

}
