package de.maxhenkel.voicechat.voice.common;

import io.netty.buffer.ByteBuf;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

public class Secret {

    public static final int SECRET_SIZE_BYTES = 16;
    public static final int IV_SIZE_BYTES = 12;
    public static final int TAG_LEN_BITS = 128;
    public static final String CIPHER = "AES/GCM/NoPadding";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ThreadLocal<Cipher> CIPHERS = ThreadLocal.withInitial(() -> {
        try {
            return Cipher.getInstance(CIPHER);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
            throw new IllegalStateException("AES/GCM cipher is unavailable", e);
        }
    });

    private final byte[] secret;
    private final SecretKeySpec keySpec;
    private final int ivPrefix;
    private final AtomicLong ivCounter;

    protected Secret(byte[] secret) {
        this.secret = secret;
        this.keySpec = new SecretKeySpec(secret, "AES");
        this.ivPrefix = RANDOM.nextInt();
        this.ivCounter = new AtomicLong();
    }

    public static Secret generateNewRandomSecret() {
        byte[] secret = new byte[SECRET_SIZE_BYTES];
        RANDOM.nextBytes(secret);
        return new Secret(secret);
    }

    public static Secret fromBytes(byte[] secret) {
        return new Secret(secret);
    }

    public static Secret fromBytes(ByteBuf buf) {
        byte[] secretBytes = new byte[SECRET_SIZE_BYTES];
        buf.readBytes(secretBytes);
        return Secret.fromBytes(secretBytes);
    }

    public void toBytes(ByteBuf buf) {
        buf.writeBytes(secret);
    }

    public byte[] getSecret() {
        return secret;
    }

    public SecretKeySpec getKeySpec() {
        return keySpec;
    }

    private byte[] nextIV() {
        byte[] iv = new byte[IV_SIZE_BYTES];
        ByteBuffer.wrap(iv).putInt(ivPrefix).putLong(ivCounter.getAndIncrement());
        return iv;
    }

    public byte[] encrypt(byte[] data) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        byte[] iv = nextIV();
        Cipher cipher = CIPHERS.get();
        cipher.init(Cipher.ENCRYPT_MODE, getKeySpec(), new GCMParameterSpec(TAG_LEN_BITS, iv));
        byte[] enc = cipher.doFinal(data);
        byte[] payload = new byte[iv.length + enc.length];
        System.arraycopy(iv, 0, payload, 0, iv.length);
        System.arraycopy(enc, 0, payload, iv.length, enc.length);
        return payload;
    }

    public byte[] decrypt(byte[] payload) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        byte[] iv = Arrays.copyOfRange(payload, 0, IV_SIZE_BYTES);
        Cipher cipher = CIPHERS.get();
        cipher.init(Cipher.DECRYPT_MODE, getKeySpec(), new GCMParameterSpec(TAG_LEN_BITS, iv));
        return cipher.doFinal(payload, IV_SIZE_BYTES, payload.length - IV_SIZE_BYTES);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Secret)) {
            return false;
        }
        return Arrays.equals(secret, ((Secret) o).secret);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(secret);
    }
}
