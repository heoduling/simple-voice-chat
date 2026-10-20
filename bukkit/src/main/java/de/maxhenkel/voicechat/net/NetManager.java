package de.maxhenkel.voicechat.net;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.compatibility.Compatibility;
import de.maxhenkel.voicechat.util.FriendlyByteBuf;
import de.maxhenkel.voicechat.voice.server.ServerVoiceEvents;
import io.netty.buffer.Unpooled;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NetManager implements Listener {

    private final Set<String> packets = ConcurrentHashMap.newKeySet();
    private final Set<String> outgoingPackets = ConcurrentHashMap.newKeySet();

    public void onEnable() {
        packets.clear();
        outgoingPackets.clear();
        Bukkit.getPluginManager().registerEvents(this, Voicechat.INSTANCE);
        try {
            registerIncomingPacket(UpdateStatePacket.class);
            registerIncomingPacket(RequestSecretPacket.class);
            registerIncomingPacket(CreateGroupPacket.class);
            registerIncomingPacket(JoinGroupPacket.class);
            registerIncomingPacket(LeaveGroupPacket.class);

            registerOutgoingPacket(SecretPacket.class);
            registerOutgoingPacket(PlayerStatesPacket.class);
            registerOutgoingPacket(PlayerStatePacket.class);
            registerOutgoingPacket(RemovePlayerStatePacket.class);
            registerOutgoingPacket(AddGroupPacket.class);
            registerOutgoingPacket(RemoveGroupPacket.class);
            registerOutgoingPacket(JoinedGroupPacket.class);
            registerOutgoingPacket(AddCategoryPacket.class);
            registerOutgoingPacket(RemoveCategoryPacket.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to register voice chat packets", e);
        }
    }

    public void onDisable() {
        Set<String> incomingChannels = Bukkit.getMessenger().getIncomingChannels(Voicechat.INSTANCE);
        Set<String> outgoingChannels = Bukkit.getMessenger().getOutgoingChannels(Voicechat.INSTANCE);
        for (String channel : incomingChannels) {
            Bukkit.getMessenger().unregisterIncomingPluginChannel(Voicechat.INSTANCE, channel);
        }
        for (String channel : outgoingChannels) {
            Bukkit.getMessenger().unregisterOutgoingPluginChannel(Voicechat.INSTANCE, channel);
        }
        packets.clear();
        outgoingPackets.clear();
    }

    public Set<String> getPackets() {
        return Collections.unmodifiableSet(packets);
    }

    public void synchronizePlayer(Player player) {
        Compatibility compatibility = Voicechat.compatibility;
        if (!Voicechat.ACTIVE || compatibility == null) {
            return;
        }
        for (String channel : outgoingPackets) {
            compatibility.addChannel(player, channel);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Compatibility compatibility = Voicechat.compatibility;
        if (!Voicechat.ACTIVE || compatibility == null) {
            return;
        }
        Set<String> outgoingChannels = Bukkit.getMessenger().getOutgoingChannels(Voicechat.INSTANCE);
        for (String channel : outgoingChannels) {
            compatibility.addChannel(event.getPlayer(), channel);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Compatibility compatibility = Voicechat.compatibility;
        if (compatibility == null) {
            return;
        }
        Set<String> outgoingChannels = Bukkit.getMessenger().getOutgoingChannels(Voicechat.INSTANCE);
        for (String channel : outgoingChannels) {
            compatibility.removeChannel(event.getPlayer(), channel);
        }
    }

    private <T extends Packet<?>> void registerIncomingPacket(Class<T> packetClass) throws NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
        Constructor<T> c = packetClass.getDeclaredConstructor();
        String id = c.newInstance().getID().toString();
        packets.add(id);
        Bukkit.getMessenger().registerIncomingPluginChannel(Voicechat.INSTANCE, id, (s, player, bytes) -> {
            Voicechat plugin = Voicechat.INSTANCE;
            ServerVoiceEvents events = Voicechat.SERVER;
            Compatibility compatibility = Voicechat.compatibility;
            if (!Voicechat.ACTIVE || plugin == null || !plugin.isEnabled() || events == null
                    || compatibility == null) {
                return;
            }
            UUID playerId = player.getUniqueId();
            if (!events.getRateLimiter().allow(playerId)) {
                return;
            }
            T packet;
            try {
                packet = c.newInstance();
                FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
                packet.fromBytes(buffer);
            } catch (Exception e) {
                Voicechat.LOGGER.warn("Rejected malformed {} packet from {}", packetClass.getSimpleName(), playerId);
                return;
            }
            compatibility.runForPlayer(player, () -> {
                if (!Voicechat.ACTIVE || !player.isOnline() || Voicechat.INSTANCE != plugin
                        || !plugin.isEnabled() || Voicechat.SERVER != events
                        || Voicechat.compatibility != compatibility) {
                    return;
                }
                packet.onPacket(player);
            });
        });
    }

    private <T extends Packet<?>> void registerOutgoingPacket(Class<T> packetClass) throws NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
        String id = packetClass.getDeclaredConstructor().newInstance().getID().toString();
        packets.add(id);
        outgoingPackets.add(id);
        Bukkit.getMessenger().registerOutgoingPluginChannel(Voicechat.INSTANCE, id);
    }

    public static void sendToClient(Player player, Packet<?> p) {
        sendToClient(player, p, true);
    }

    /**
     * Used only for the bounded post-load handshake. It deliberately skips the
     * compatibility cache because that cache is not populated until the client
     * completes the UDP handshake.
     */
    public static void sendToClientUnchecked(Player player, Packet<?> p) {
        sendToClient(player, p, false);
    }

    /** Direct send for ordered hot-load cleanup. Caller MUST already own this player. */
    public static boolean sendToClientOnOwner(Player player, Packet<?> packet) {
        Voicechat plugin = Voicechat.INSTANCE;
        if (!Voicechat.ACTIVE || plugin == null || !plugin.isEnabled() || !player.isOnline()) return false;
        if (!Bukkit.isOwnedByCurrentRegion(player)) throw new IllegalStateException("Player packet sent outside its entity owner");
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf();
            packet.toBytes(buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            player.sendPluginMessage(plugin, packet.getID().toString(), bytes);
            return true;
        } catch (Exception exception) {
            Voicechat.LOGGER.error("Failed to send hot-load cleanup to {}", player.getName(), exception);
            return false;
        }
    }

    private static void sendToClient(Player player, Packet<?> p, boolean requireCompatible) {
        Compatibility compatibility = Voicechat.compatibility;
        if (compatibility == null) {
            return;
        }
        ServerVoiceEvents currentEvents = Voicechat.SERVER;
        if (requireCompatible && (currentEvents == null || !currentEvents.isCompatible(player))) {
            return;
        }
        compatibility.runForPlayer(player, () -> {
            Voicechat plugin = Voicechat.INSTANCE;
            ServerVoiceEvents events = Voicechat.SERVER;
            if (!Voicechat.ACTIVE || !player.isOnline() || plugin == null || !plugin.isEnabled()) {
                return;
            }
            if (events == null || (requireCompatible && !events.isCompatible(player))) {
                return;
            }
            try {
                FriendlyByteBuf buf = new FriendlyByteBuf();
                p.toBytes(buf);
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                player.sendPluginMessage(plugin, p.getID().toString(), bytes);
            } catch (Exception e) {
                Voicechat.LOGGER.error("Failed to send packet to '{}'", player.getName(), e);
            }
        });
    }

    public static void sendMessage(Player player, String key, String... args) {
        Compatibility compatibility = Voicechat.compatibility;
        if (compatibility == null) return;
        compatibility.runForPlayer(player, () -> {
            Voicechat plugin = Voicechat.INSTANCE;
            if (Voicechat.ACTIVE && player.isOnline() && plugin != null && plugin.isEnabled()) {
                compatibility.sendTranslationMessage(player, key, args);
            }
        });
    }

    public static void sendStatusMessage(Player player, String key, String... args) {
        Compatibility compatibility = Voicechat.compatibility;
        if (compatibility == null) return;
        compatibility.runForPlayer(player, () -> {
            Voicechat plugin = Voicechat.INSTANCE;
            if (Voicechat.ACTIVE && player.isOnline() && plugin != null && plugin.isEnabled()) {
                compatibility.sendStatusMessage(player, key, args);
            }
        });
    }

}
