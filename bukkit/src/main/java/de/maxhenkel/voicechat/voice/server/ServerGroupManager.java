package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.compatibility.Compatibility;
import de.maxhenkel.voicechat.config.ServerConfig;
import de.maxhenkel.voicechat.net.*;
import de.maxhenkel.voicechat.permission.PermissionManager;
import de.maxhenkel.voicechat.plugins.PluginManager;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ServerGroupManager {

    private static final long GROUP_CREATION_COOLDOWN_NANOS = 1_000_000_000L;

    private final Map<UUID, Group> groups;
    private final Map<UUID, Long> groupCreationCooldowns;
    private final Object membershipLock;
    private final PlayerStateManager states;
    private final HotloadGroupCleanup hotloadCleanup;

    public ServerGroupManager(PlayerStateManager states) {
        groups = new ConcurrentHashMap<>();
        groupCreationCooldowns = new HashMap<>();
        membershipLock = new Object();
        this.states = states;
        hotloadCleanup = new HotloadGroupCleanup();
    }

    public void onJoinGroupPacket(Player player, JoinGroupPacket packet) {
        ServerConfig config = Voicechat.SERVER_CONFIG;
        if (config == null || !config.groupsEnabled.get()) {
            return;
        }
        if (!player.hasPermission(PermissionManager.GROUPS_PERMISSION)) {
            NetManager.sendStatusMessage(player, "message.voicechat.no_group_permission");
            return;
        }
        joinGroup(groups.get(packet.getGroup()), player, packet.getPassword());
    }

    public void onCreateGroupPacket(Player player, CreateGroupPacket packet) {
        ServerConfig config = Voicechat.SERVER_CONFIG;
        if (config == null || !config.groupsEnabled.get()) {
            return;
        }
        if (!player.hasPermission(PermissionManager.GROUPS_PERMISSION)) {
            NetManager.sendStatusMessage(player, "message.voicechat.no_group_permission");
            return;
        }
        if (!Voicechat.GROUP_REGEX.matcher(packet.getName()).matches()) {
            Voicechat.LOGGER.warn("Player {} tried to create a group with an invalid name", player.getDisplayName());
            return;
        }
        if (packet.getPassword() != null && !Voicechat.GROUP_REGEX.matcher(packet.getPassword()).matches()) {
            Voicechat.LOGGER.warn("Player {} tried to create a group with an invalid password", player.getDisplayName());
            return;
        }
        if (!allowGroupCreation(player.getUniqueId())) {
            return;
        }
        Group group = new Group(UUID.randomUUID(), packet.getName(), packet.getPassword(), false, false, packet.getType());
        addGroup(group, player);
    }

    private boolean allowGroupCreation(UUID playerId) {
        long now = System.nanoTime();
        synchronized (membershipLock) {
            Long nextAllowed = groupCreationCooldowns.get(playerId);
            if (nextAllowed != null && now < nextAllowed) {
                return false;
            }
            groupCreationCooldowns.put(playerId, now + GROUP_CREATION_COOLDOWN_NANOS);
            return true;
        }
    }

    public void onLeaveGroupPacket(Player player, LeaveGroupPacket packet) {
        leaveGroup(player);
    }

    public void onPlayerCompatibilityCheckSucceeded(Player player) {
        clearPreviousGroups(player);
        Voicechat.LOGGER.debug("Synchronizing {} groups with {}", groups.size(), player.getDisplayName());
        for (Group group : groups.values()) {
            NetManager.sendToClient(player, new AddGroupPacket(group.toClientGroup()));
        }
    }

    public void onPlayerQuit(PlayerQuitEvent event) {
        hotloadCleanup.forget(event.getPlayer().getUniqueId());
        synchronized (membershipLock) {
            groupCreationCooldowns.remove(event.getPlayer().getUniqueId());
        }
        cleanupGroups();
    }

    private PlayerStateManager getStates() {
        return states;
    }

    public void addGroup(Group group, @Nullable Player player) {
        if (PluginManager.instance().onCreateGroup(player, group)) {
            return;
        }
        synchronized (membershipLock) {
            if (groups.putIfAbsent(group.getId(), group) != null) {
                return;
            }
        }
        broadcastAddGroup(group);

        if (player == null) {
            return;
        }

        PlayerStateManager manager = getStates();
        PlayerState state;
        synchronized (membershipLock) {
            if (groups.get(group.getId()) != group) {
                return;
            }
            state = manager.setGroupState(player, group.getId());
        }
        manager.broadcastState(player, state);

        NetManager.sendToClient(player, new JoinedGroupPacket(group.getId(), false));
        cleanupGroups();
    }

    public void joinGroup(@Nullable Group group, Player player, String password) {
        tryJoinGroup(group, player, password);
    }

    public boolean tryJoinGroup(@Nullable Group group, Player player, String password) {
        if (PluginManager.instance().onJoinGroup(player, group)) {
            return false;
        }
        if (group == null) {
            NetManager.sendToClient(player, new JoinedGroupPacket(null, false));
            return false;
        }
        if (group.getPassword() != null) {
            if (!group.getPassword().equals(password)) {
                NetManager.sendToClient(player, new JoinedGroupPacket(null, true));
                return false;
            }
        }
        PlayerStateManager manager = getStates();
        PlayerState state;
        synchronized (membershipLock) {
            if (groups.get(group.getId()) != group) {
                state = null;
            } else {
                state = manager.setGroupState(player, group.getId());
            }
        }
        if (state == null) {
            NetManager.sendToClient(player, new JoinedGroupPacket(null, false));
            return false;
        }
        manager.broadcastState(player, state);

        NetManager.sendToClient(player, new JoinedGroupPacket(group.getId(), false));
        cleanupGroups();
        return true;
    }

    public void leaveGroup(Player player) {
        tryLeaveGroup(player);
    }

    public boolean tryLeaveGroup(Player player) {
        if (PluginManager.instance().onLeaveGroup(player)) {
            return false;
        }

        PlayerStateManager manager = getStates();
        PlayerState state;
        synchronized (membershipLock) {
            state = manager.setGroupState(player, null);
        }
        manager.broadcastState(player, state);
        NetManager.sendToClient(player, new JoinedGroupPacket(null, false));

        cleanupGroups();
        return true;
    }

    public void cleanupGroups() {
        PlayerStateManager manager = getStates();
        Set<UUID> usedGroups;
        List<UUID> groupsToRemove;
        synchronized (membershipLock) {
            usedGroups = manager.getStates().stream().filter(PlayerState::hasGroup).map(PlayerState::getGroup).collect(Collectors.toCollection(HashSet::new));
            groupsToRemove = groups.entrySet().stream().filter(entry -> !entry.getValue().isPersistent()).map(Map.Entry::getKey).filter(uuid -> !usedGroups.contains(uuid)).collect(Collectors.toList());
        }
        for (UUID uuid : groupsToRemove) {
            removeGroup(uuid);
        }
    }

    public boolean removeGroup(UUID groupId) {
        Group group = groups.get(groupId);
        if (group == null) {
            return false;
        }

        if (PluginManager.instance().onRemoveGroup(group)) {
            return false;
        }

        synchronized (membershipLock) {
            if (groups.get(groupId) != group) {
                return false;
            }
            PlayerStateManager manager = getStates();
            if (manager.getStates().stream().anyMatch(state -> state.hasGroup() && state.getGroup().equals(groupId))) {
                return false;
            }
            // Record before removing the registry entry: disable can cancel the queued broadcast.
            hotloadCleanup.rememberRemoval(ServerPlayerManager.getPlayerIds(), groupId);
            if (!groups.remove(groupId, group)) {
                return false;
            }
        }
        broadcastRemoveGroup(groupId);
        // TODO Handle kicking players from group instead of preventing it
        return true;
    }

    @Nullable
    public Group getGroup(UUID groupID) {
        return groups.get(groupID);
    }

    private void broadcastAddGroup(Group group) {
        AddGroupPacket packet = new AddGroupPacket(group.toClientGroup());
        Compatibility compatibility = Voicechat.compatibility;
        if (!Voicechat.ACTIVE || compatibility == null) return;
        compatibility.runTask(() -> {
            Voicechat plugin = Voicechat.INSTANCE;
            if (!Voicechat.ACTIVE || plugin == null || !plugin.isEnabled()) return;
            plugin.getServer().getOnlinePlayers().forEach(p -> NetManager.sendToClient(p, packet));
        });
    }

    private void broadcastRemoveGroup(UUID group) {
        RemoveGroupPacket packet = new RemoveGroupPacket(group);
        Compatibility compatibility = Voicechat.compatibility;
        if (!Voicechat.ACTIVE || compatibility == null) return;
        compatibility.runTask(() -> {
            Voicechat plugin = Voicechat.INSTANCE;
            if (!Voicechat.ACTIVE || plugin == null || !plugin.isEnabled()) return;
            plugin.getServer().getOnlinePlayers().forEach(p -> compatibility.runForPlayer(p, () -> {
                if (NetManager.sendToClientOnOwner(p, packet)) {
                    hotloadCleanup.removalSent(p.getUniqueId(), group);
                }
            }));
        });
    }

    @Nullable
    public Group getPlayerGroup(Player player) {
        PlayerState state = getStates().getState(player.getUniqueId());
        if (state == null) {
            return null;
        }
        UUID groupId = state.getGroup();
        if (groupId == null) {
            return null;
        }
        return getGroup(groupId);
    }

    public Map<UUID, Group> getGroups() {
        return groups;
    }

    /** Called on the player's entity owner before sending a new handshake or group list. */
    public void clearPreviousGroups(Player player) {
        Set<UUID> previous = hotloadCleanup.get(player.getUniqueId());
        boolean resetMembership = hotloadCleanup.needsMembershipReset(player.getUniqueId());
        if (previous.isEmpty() && !resetMembership) return;
        for (UUID group : previous) {
            if (!NetManager.sendToClientOnOwner(player, new RemoveGroupPacket(group))) return;
        }
        if (resetMembership && !NetManager.sendToClientOnOwner(player, new JoinedGroupPacket(null, false))) return;
        hotloadCleanup.complete(player.getUniqueId(), previous);
    }

    public void rememberClientGroups() {
        synchronized (membershipLock) {
            hotloadCleanup.save(ServerPlayerManager.getPlayerIds(), List.copyOf(groups.keySet()));
        }
    }

    public void retainOnlineCleanup(List<UUID> players) { hotloadCleanup.retainPlayers(players); }

    public void clear() {
        synchronized (membershipLock) {
            groups.clear();
            groupCreationCooldowns.clear();
        }
    }

}
