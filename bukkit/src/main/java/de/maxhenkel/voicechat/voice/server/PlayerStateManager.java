package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.compatibility.Compatibility;
import de.maxhenkel.voicechat.compatibility.PlayerHideEvent;
import de.maxhenkel.voicechat.compatibility.PlayerShowEvent;
import de.maxhenkel.voicechat.net.*;
import de.maxhenkel.voicechat.plugins.PluginManager;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerStateManager implements Listener {

    private final ConcurrentHashMap<UUID, PlayerState> states;

    public PlayerStateManager() {
        this.states = new ConcurrentHashMap<>();
    }

    public void onUpdateStatePacket(Player player, UpdateStatePacket packet) {
        PlayerState state = states.get(player.getUniqueId());

        if (state == null) {
            state = defaultDisconnectedState(player);
        }

        state.setDisabled(packet.isDisabled());

        states.put(player.getUniqueId(), state);

        broadcastState(player, state);
        Voicechat.LOGGER.debug("Got state of {}: {}", player.getName(), state);
    }

    public void onPlayerQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
        broadcastRemoveState(event.getPlayer());
        Voicechat.LOGGER.debug("Removing state of {}", event.getPlayer().getName());
    }

    public void onPlayerJoin(PlayerJoinEvent event) {
        PlayerState state = defaultDisconnectedState(event.getPlayer());
        states.put(event.getPlayer().getUniqueId(), state);
        broadcastState(event.getPlayer(), state);
        Voicechat.LOGGER.debug("Setting default state of {}: {}", event.getPlayer().getName(), state);
    }

    public void ensurePlayer(Player player) {
        states.computeIfAbsent(player.getUniqueId(), ignored -> defaultDisconnectedState(player));
    }

    public void onPlayerHide(PlayerHideEvent event) {
        RemovePlayerStatePacket packet = new RemovePlayerStatePacket(event.getHiddenPlayer().getUniqueId());
        NetManager.sendToClient(event.getObserver(), packet);
        Voicechat.LOGGER.debug("Removing state of {} for {}", event.getHiddenPlayer().getName(), event.getObserver().getName());
    }

    public void onPlayerShow(PlayerShowEvent event) {
        PlayerState state = states.get(event.getShownPlayer().getUniqueId());
        if (state == null) {
            state = defaultDisconnectedState(event.getShownPlayer());
        }
        PlayerStatePacket packet = new PlayerStatePacket(state);
        NetManager.sendToClient(event.getObserver(), packet);
        Voicechat.LOGGER.debug("Sending state of {} to {}", event.getShownPlayer().getName(), event.getObserver().getName());
    }

    public void broadcastState(@Nullable Player stateOwner, PlayerState state) {
        PlayerStatePacket packet = new PlayerStatePacket(state);
        UUID stateOwnerId = stateOwner == null ? null : stateOwner.getUniqueId();
        broadcastPacket(packet, stateOwnerId, true);
        PluginManager.instance().onPlayerStateChanged(state);
    }

    public void broadcastRemoveState(Player stateOwner) {
        RemovePlayerStatePacket packet = new RemovePlayerStatePacket(stateOwner.getUniqueId());
        broadcastPacket(packet, null, false);
        // Send the default disconnected state to the API when disconnecting
        PluginManager.instance().onPlayerStateChanged(defaultDisconnectedState(stateOwner));
    }

    private void broadcastPacket(Packet<?> packet, @Nullable UUID stateOwnerId, boolean respectVisibility) {
        Compatibility compatibility = Voicechat.compatibility;
        if (!Voicechat.ACTIVE || compatibility == null) return;
        compatibility.runTask(() -> {
            Voicechat plugin = Voicechat.INSTANCE;
            if (!Voicechat.ACTIVE || plugin == null || !plugin.isEnabled()
                    || Voicechat.compatibility != compatibility) return;
            Player[] receivers = plugin.getServer().getOnlinePlayers().toArray(new Player[0]);
            for (Player receiver : receivers) {
                compatibility.runForPlayer(receiver, () -> {
                    if (!Voicechat.ACTIVE || !receiver.isOnline()
                            || Voicechat.compatibility != compatibility) return;
                    if (respectVisibility && stateOwnerId != null) {
                        ServerPlayerManager.PlayerSnapshot owner = ServerPlayerManager.getSnapshot(stateOwnerId);
                        if (owner != null && !compatibility.canSee(receiver, owner.getRealPlayer())) return;
                    }
                    NetManager.sendToClient(receiver, packet);
                });
            }
        });
    }

    public void onPlayerCompatibilityCheckSucceeded(Player player) {
        Voicechat plugin = Voicechat.INSTANCE;
        Compatibility compatibility = Voicechat.compatibility;
        if (!Voicechat.ACTIVE || plugin == null || compatibility == null) {
            return;
        }
        List<PlayerState> stateList = new ArrayList<>(states.size());
        for (PlayerState state : states.values()) {
            ServerPlayerManager.PlayerSnapshot otherPlayer = ServerPlayerManager.getSnapshot(state.getUuid());
            if (otherPlayer == null) {
                continue;
            }
            if (!compatibility.canSee(player, otherPlayer.getRealPlayer())) {
                continue;
            }
            stateList.add(state);
        }
        PlayerStatesPacket packet = new PlayerStatesPacket(stateList);
        NetManager.sendToClient(player, packet);
        Voicechat.LOGGER.debug("Sending initial states to {}", player.getName());
    }

    public void onPlayerVoicechatDisconnect(UUID uuid) {
        PlayerState state = states.get(uuid);
        if (state == null) {
            return;
        }

        state.setDisconnected(true);

        ServerPlayerManager.PlayerSnapshot snapshot = ServerPlayerManager.getSnapshot(uuid);
        @Nullable Player player = snapshot == null ? null : snapshot.getRealPlayer();

        broadcastState(player, state);
        Voicechat.LOGGER.debug("Set state of {} to disconnected: {}", uuid, state);
    }

    public void onPlayerVoicechatConnect(Player player) {
        PlayerState state = states.get(player.getUniqueId());

        if (state == null) {
            state = defaultDisconnectedState(player);
        }

        state.setDisconnected(false);

        states.put(player.getUniqueId(), state);

        broadcastState(player, state);
        Voicechat.LOGGER.debug("Set state of {} to connected: {}", player.getName(), state);
    }

    @Nullable
    public PlayerState getState(UUID playerUUID) {
        return states.get(playerUUID);
    }

    public static PlayerState defaultDisconnectedState(Player player) {
        return new PlayerState(player.getUniqueId(), player.getName(), false, true);
    }

    PlayerState setGroupState(Player player, @Nullable UUID group) {
        PlayerState state = states.get(player.getUniqueId());
        if (state == null) {
            state = PlayerStateManager.defaultDisconnectedState(player);
            Voicechat.LOGGER.debug("Defaulting to default state for {}: {}", player.getName(), state);
        }
        state.setGroup(group);
        states.put(player.getUniqueId(), state);
        return state;
    }

    public void setGroup(Player player, @Nullable UUID group) {
        PlayerState state = setGroupState(player, group);
        broadcastState(player, state);
        Voicechat.LOGGER.debug("Setting group of {}: {}", player.getName(), state);
    }

    public Collection<PlayerState> getStates() {
        return states.values();
    }

    public void clear() {
        states.clear();
    }

}
