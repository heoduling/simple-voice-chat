package de.maxhenkel.voicechat.voice.common;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import de.maxhenkel.voicechat.debug.PingHandler;
import de.maxhenkel.voicechat.util.FriendlyByteBuf;
import de.maxhenkel.voicechat.voice.server.ClientConnection;
import de.maxhenkel.voicechat.voice.server.Server;
import io.netty.buffer.Unpooled;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.lang.reflect.InvocationTargetException;
import java.net.SocketAddress;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class NetworkMessage {

    public static final byte MAGIC_BYTE = (byte) 0b11111111;

    private final long timestamp;
    private Packet<? extends Packet> packet;
    private SocketAddress address;

    public NetworkMessage(long timestamp, Packet<?> packet) {
        this(timestamp);
        this.packet = packet;
    }

    public NetworkMessage(Packet<?> packet) {
        this(System.currentTimeMillis());
        this.packet = packet;
    }

    private NetworkMessage(long timestamp) {
        this.timestamp = timestamp;
    }

    @Nonnull
    public Packet<? extends Packet> getPacket() {
        return packet;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public long getTTL() {
        return packet.getTTL();
    }

    public SocketAddress getAddress() {
        return address;
    }

    private static final Map<Byte, Class<? extends Packet>> packetRegistry;
    private static final Map<Class<? extends Packet>, Byte> packetTypes;

    static {
        packetRegistry = new HashMap<>();
        packetRegistry.put((byte) 0x1, MicPacket.class);
        packetRegistry.put((byte) 0x2, PlayerSoundPacket.class);
        packetRegistry.put((byte) 0x3, GroupSoundPacket.class);
        packetRegistry.put((byte) 0x4, LocationSoundPacket.class);
        packetRegistry.put((byte) 0x5, AuthenticatePacket.class);
        packetRegistry.put((byte) 0x6, AuthenticateAckPacket.class);
        packetRegistry.put((byte) 0x7, PingPacket.class);
        packetRegistry.put((byte) 0x8, KeepAlivePacket.class);
        packetRegistry.put((byte) 0x9, ConnectionCheckPacket.class);
        packetRegistry.put((byte) 0xA, ConnectionCheckAckPacket.class);
        packetTypes = new HashMap<>();
        packetRegistry.forEach((id, type) -> packetTypes.put(type, id));
    }

    @Nullable
    public static NetworkMessage readPacketServer(RawUdpPacket packet, Server server) throws IllegalAccessException, InstantiationException, InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, NoSuchAlgorithmException, BadPaddingException, InvalidKeyException, InvocationTargetException, NoSuchMethodException {
        byte[] data = packet.getData();
        FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        if (b.readByte() != MAGIC_BYTE) {
            Voicechat.LOGGER.debug("Received invalid packet from {}", packet.getSocketAddress());
            return null;
        }
        UUID playerID = b.readUUID();
        Secret secret = server.getSecret(playerID);
        if (secret == null) {
            if (PingHandler.onPacket(server, packet.getSocketAddress(), playerID, b)) {
                return null;
            }
            // Ignore packets if they are not from a player that has a secret
            Voicechat.LOGGER.debug("Player {} does not have a secret", playerID);
            return null;
        }
        return readFromBytes(packet.getSocketAddress(), secret, b.readByteArray(), packet.getTimestamp());
    }

    @Nullable
    private static NetworkMessage readFromBytes(SocketAddress socketAddress, Secret secret, byte[] encryptedPayload, long timestamp) throws InstantiationException, IllegalAccessException, InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, NoSuchAlgorithmException, BadPaddingException, InvalidKeyException, NoSuchMethodException, InvocationTargetException {
        byte[] decrypt;
        try {
            decrypt = secret.decrypt(encryptedPayload);
        } catch (Exception e) {
            // Return null if the encryption fails due to a wrong secret
            Voicechat.LOGGER.debug("Failed to decrypt packet from {}", socketAddress);
            return null;
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(decrypt));
        byte packetType = buffer.readByte();
        Class<? extends Packet> packetClass = packetRegistry.get(packetType);
        if (packetClass == null) {
            Voicechat.LOGGER.debug("Got invalid packet ID {}", packetType);
            return null;
        }
        Packet<? extends Packet<?>> p = createPacket(packetType);

        NetworkMessage message = new NetworkMessage(timestamp);
        message.address = socketAddress;
        message.packet = p.fromBytes(buffer);

        return message;
    }

    private static byte getPacketType(Packet<? extends Packet> packet) {
        return packetTypes.getOrDefault(packet.getClass(), (byte) -1);
    }

    private static Packet<? extends Packet<?>> createPacket(byte packetType) {
        switch (packetType) {
            case 0x1:
                return new MicPacket();
            case 0x2:
                return new PlayerSoundPacket();
            case 0x3:
                return new GroupSoundPacket();
            case 0x4:
                return new LocationSoundPacket();
            case 0x5:
                return new AuthenticatePacket();
            case 0x6:
                return new AuthenticateAckPacket();
            case 0x7:
                return new PingPacket();
            case 0x8:
                return new KeepAlivePacket();
            case 0x9:
                return new ConnectionCheckPacket();
            case 0xA:
                return new ConnectionCheckAckPacket();
            default:
                throw new IllegalArgumentException("Unknown packet type " + packetType);
        }
    }

    public byte[] writeServer(Server server, ClientConnection connection) throws InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, NoSuchAlgorithmException, BadPaddingException, InvalidKeyException {
        Secret secret = server.getSecret(connection.getPlayerUUID());
        if (secret == null) {
            throw new IllegalStateException("Connection has no encryption secret");
        }
        byte[] payload = write(secret);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(1 + payload.length));
        buffer.writeByte(MAGIC_BYTE);
        buffer.writeByteArray(payload);

        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return bytes;
    }

    public byte[] write(Secret secret) throws InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, NoSuchAlgorithmException, BadPaddingException, InvalidKeyException {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        byte type = getPacketType(packet);
        if (type < 0) {
            throw new IllegalArgumentException("Packet type not found");
        }

        buffer.writeByte(type);
        packet.toBytes(buffer);

        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return secret.encrypt(bytes);
    }

}
