package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.compatibility.Compatibility;
import de.maxhenkel.voicechat.permission.PermissionManager;
import de.maxhenkel.voicechat.plugins.impl.PositionImpl;
import de.maxhenkel.voicechat.plugins.impl.ServerLevelImpl;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;

public class ServerPlayerManager implements Listener {

    public static final ServerPlayerManager INSTANCE = new ServerPlayerManager();
    private static final long FULL_REFRESH_INTERVAL_TICKS = 100L;

    public static void init(Plugin plugin) {
        INSTANCE.active = true;
        plugin.getServer().getPluginManager().registerEvents(INSTANCE, plugin);
        Voicechat.compatibility.scheduleSyncRepeatingTask(INSTANCE::refresh, 1L, FULL_REFRESH_INTERVAL_TICKS);
    }

    public static void shutdown() {
        INSTANCE.active = false;
        INSTANCE.players.clear();
    }

    public static java.util.List<UUID> getPlayerIds() {
        return java.util.List.copyOf(INSTANCE.players.keySet());
    }

    private final ConcurrentMap<UUID, PlayerSnapshot> players;
    private volatile boolean active;

    private ServerPlayerManager() {
        players = new ConcurrentHashMap<>();
    }

    private void refresh() {
        Voicechat plugin = Voicechat.INSTANCE;
        Compatibility compatibility = Voicechat.compatibility;
        if (!active || !Voicechat.ACTIVE || plugin == null || !plugin.isEnabled()
                || compatibility == null) {
            return;
        }
        Player[] onlinePlayers = plugin.getServer().getOnlinePlayers().toArray(new Player[0]);
        for (Player player : onlinePlayers) {
            compatibility.runForPlayer(player, () -> {
                if (active && Voicechat.ACTIVE && Voicechat.compatibility == compatibility && player.isOnline()) {
                    update(player, player.getLocation());
                }
            });
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        update(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        UUID playerId = event.getPlayer().getUniqueId();
        players.computeIfPresent(playerId, (ignored, current) -> current.withLocation(to));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        players.remove(event.getPlayer().getUniqueId());
    }

    private void update(Player player, Location location) {
        if (!active) {
            return;
        }
        Entity spectatorTarget = player.getSpectatorTarget();
        UUID spectatorTargetId = spectatorTarget instanceof Player ? spectatorTarget.getUniqueId() : null;
        PlayerSnapshot snapshot = new PlayerSnapshot(
                player,
                player.getUniqueId(),
                player.getName(),
                location.getWorld(),
                location.getX(),
                location.getY(),
                location.getZ(),
                player.getGameMode(),
                spectatorTargetId,
                player.hasPermission(PermissionManager.SPEAK_PERMISSION),
                player.hasPermission(PermissionManager.LISTEN_PERMISSION)
        );
        players.put(snapshot.getUuid(), snapshot);
    }

    public static void refreshPlayer(Player player) {
        INSTANCE.update(player, player.getLocation());
    }

    @Nullable
    public static PlayerSnapshot getSnapshot(UUID playerId) {
        return INSTANCE.players.get(playerId);
    }

    public static ArrayList<PlayerSnapshot> getSnapshotsInRange(UUID worldId, Position pos, double range,
                                                                 @Nullable Predicate<ServerPlayer> filter) {
        return INSTANCE.getSnapshotsInRangeInternal(worldId, pos.getX(), pos.getY(), pos.getZ(), range, filter);
    }

    public static ArrayList<PlayerSnapshot> getSnapshotsInRange(UUID worldId, Location pos, double range,
                                                                 @Nullable Predicate<ServerPlayer> filter) {
        return INSTANCE.getSnapshotsInRangeInternal(worldId, pos.getX(), pos.getY(), pos.getZ(), range, filter);
    }

    private ArrayList<PlayerSnapshot> getSnapshotsInRangeInternal(UUID worldId, double x, double y, double z,
                                                                   double range, @Nullable Predicate<ServerPlayer> filter) {
        ArrayList<PlayerSnapshot> nearbyPlayers = new ArrayList<>();
        double squaredRange = square(range);
        for (PlayerSnapshot player : players.values()) {
            if (!worldId.equals(player.worldId)) {
                continue;
            }
            if (player.squaredDistanceTo(x, y, z) <= squaredRange && (filter == null || filter.test(player))) {
                nearbyPlayers.add(player);
            }
        }
        return nearbyPlayers;
    }

    /**
     * Kept for binary compatibility with existing Bukkit integrations. Internal
     * audio paths use immutable snapshots instead.
     */
    public static ArrayList<Player> getPlayersInRange(World level, Location pos, double range,
                                                       @Nullable Predicate<Player> filter) {
        ArrayList<Player> result = new ArrayList<>();
        for (PlayerSnapshot snapshot : getSnapshotsInRange(level.getUID(), pos, range, null)) {
            Player player = snapshot.getRealPlayer();
            if (filter == null || filter.test(player)) {
                result.add(player);
            }
        }
        return result;
    }

    public static boolean isInRange(Location pos1, Location pos2, double range) {
        return (square(pos1.getX() - pos2.getX()) + square(pos1.getY() - pos2.getY())
                + square(pos1.getZ() - pos2.getZ())) <= square(range);
    }

    private static double square(double value) {
        return value * value;
    }

    public static final class PlayerSnapshot implements ServerPlayer {
        private final Player player;
        private final UUID uuid;
        private final String name;
        private final ServerLevelImpl serverLevel;
        private final UUID worldId;
        private final double x;
        private final double y;
        private final double z;
        private final GameMode gameMode;
        @Nullable
        private final UUID spectatorTarget;
        private final boolean canSpeak;
        private final boolean canListen;

        private PlayerSnapshot(Player player, UUID uuid, String name, World world, double x, double y, double z,
                               GameMode gameMode, @Nullable UUID spectatorTarget, boolean canSpeak, boolean canListen) {
            this(player, uuid, name, new ServerLevelImpl(world), world.getUID(), x, y, z, gameMode,
                    spectatorTarget, canSpeak, canListen);
        }

        private PlayerSnapshot(Player player, UUID uuid, String name, ServerLevelImpl serverLevel, UUID worldId,
                               double x, double y, double z, GameMode gameMode,
                               @Nullable UUID spectatorTarget, boolean canSpeak, boolean canListen) {
            this.player = player;
            this.uuid = uuid;
            this.name = name;
            this.serverLevel = serverLevel;
            this.worldId = worldId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.gameMode = gameMode;
            this.spectatorTarget = spectatorTarget;
            this.canSpeak = canSpeak;
            this.canListen = canListen;
        }

        private PlayerSnapshot withLocation(Location location) {
            World world = location.getWorld();
            UUID newWorldId = world.getUID();
            double newX = location.getX();
            double newY = location.getY();
            double newZ = location.getZ();
            if (worldId.equals(newWorldId) && x == newX && y == newY && z == newZ) {
                return this;
            }
            ServerLevelImpl newServerLevel = worldId.equals(newWorldId) ? serverLevel : new ServerLevelImpl(world);
            return new PlayerSnapshot(player, uuid, name, newServerLevel, newWorldId, newX, newY, newZ,
                    gameMode, spectatorTarget, canSpeak, canListen);
        }

        private double squaredDistanceTo(double otherX, double otherY, double otherZ) {
            return square(x - otherX) + square(y - otherY) + square(z - otherZ);
        }

        public Player getRealPlayer() {
            return player;
        }

        public String getName() {
            return name;
        }

        public GameMode getGameMode() {
            return gameMode;
        }

        public UUID getWorldId() {
            return worldId;
        }

        public Location getPacketLocation() {
            return new Location(null, x, y, z);
        }

        @Nullable
        public UUID getSpectatorTarget() {
            return spectatorTarget;
        }

        public boolean canSpeak() {
            return canSpeak;
        }

        public boolean canListen() {
            return canListen;
        }

        @Override
        public UUID getUuid() {
            return uuid;
        }

        @Override
        public Object getEntity() {
            return player;
        }

        @Override
        public Position getPosition() {
            return new PositionImpl(x, y, z);
        }

        @Override
        public Object getPlayer() {
            return player;
        }

        @Override
        public ServerLevel getServerLevel() {
            return serverLevel;
        }
    }
}
