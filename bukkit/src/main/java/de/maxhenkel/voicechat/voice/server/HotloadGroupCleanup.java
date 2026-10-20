package de.maxhenkel.voicechat.voice.server;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Only UUID strings cross the plugin class-loader boundary; no plugin objects are retained. */
public final class HotloadGroupCleanup {
    private static final String KEY = "voicechat.hotload.group_cleanup";
    private final Map<UUID, Set<UUID>> pending = new ConcurrentHashMap<>();
    private final Set<UUID> resetMembership = ConcurrentHashMap.newKeySet();

    public HotloadGroupCleanup() {
        String data = System.clearProperty(KEY);
        if (data == null || data.isEmpty()) return;
        for (String line : data.split("\\n")) {
            String[] parts = line.split("=", 2);
            if (parts.length != 2) continue;
            Set<UUID> groups = new HashSet<>();
            for (String id : parts[1].split(",")) groups.add(UUID.fromString(id));
            pending.put(UUID.fromString(parts[0]), Set.copyOf(groups));
            resetMembership.add(UUID.fromString(parts[0]));
        }
    }

    public Set<UUID> get(UUID player) { return pending.getOrDefault(player, Set.of()); }
    public boolean needsMembershipReset(UUID player) { return resetMembership.contains(player); }
    public void complete(UUID player, Set<UUID> groups) {
        pending.remove(player, groups);
        resetMembership.remove(player);
    }
    public void rememberRemoval(Collection<UUID> players, UUID group) {
        for (UUID player : players) pending.compute(player, (id, previous) -> {
            Set<UUID> next = new HashSet<>(previous == null ? Set.of() : previous);
            next.add(group);
            return Set.copyOf(next);
        });
    }
    public void removalSent(UUID player, UUID group) {
        pending.computeIfPresent(player, (id, previous) -> {
            Set<UUID> next = new HashSet<>(previous);
            next.remove(group);
            return next.isEmpty() ? null : Set.copyOf(next);
        });
    }
    public void forget(UUID player) { pending.remove(player); resetMembership.remove(player); }
    public void retainPlayers(Collection<UUID> players) {
        pending.keySet().retainAll(players);
        resetMembership.retainAll(players);
    }

    public void save(Collection<UUID> onlinePlayers, Collection<UUID> currentGroups) {
        StringJoiner data = new StringJoiner("\n");
        Set<UUID> players = new HashSet<>(pending.keySet());
        players.addAll(onlinePlayers);
        for (UUID player : players) {
            Set<UUID> groups = new HashSet<>(get(player));
            groups.addAll(currentGroups);
            if (groups.isEmpty()) continue;
            StringJoiner ids = new StringJoiner(",");
            groups.forEach(id -> ids.add(id.toString()));
            data.add(player + "=" + ids);
        }
        if (data.length() == 0) System.clearProperty(KEY);
        else System.setProperty(KEY, data.toString());
    }
}
