package de.maxhenkel.voicechat.plugins.impl.audiosender;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.audiosender.AudioSender;
import de.maxhenkel.voicechat.voice.common.MicPacket;
import de.maxhenkel.voicechat.voice.server.Server;
import de.maxhenkel.voicechat.voice.server.ServerVoiceEvents;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public class AudioSenderImpl implements AudioSender {

    private static final ConcurrentMap<UUID, AudioSenderImpl> AUDIO_SENDERS = new ConcurrentHashMap<>();

    private final UUID uuid;
    private volatile boolean whispering;
    private final AtomicLong nextSequenceNumber;

    public AudioSenderImpl(UUID uuid) {
        this.uuid = uuid;
        this.nextSequenceNumber = new AtomicLong();
    }

    public static boolean registerAudioSender(AudioSenderImpl audioSender) {
        ServerVoiceEvents events = Voicechat.SERVER;
        if (events == null || events.isCompatible(audioSender.uuid)) {
            return false;
        }
        return AUDIO_SENDERS.putIfAbsent(audioSender.uuid, audioSender) == null;
    }

    public static boolean unregisterAudioSender(AudioSenderImpl audioSender) {
        return AUDIO_SENDERS.remove(audioSender.uuid, audioSender);
    }

    public static void clear() {
        AUDIO_SENDERS.clear();
    }

    @Override
    public AudioSender whispering(boolean whispering) {
        this.whispering = whispering;
        return this;
    }

    @Override
    public boolean isWhispering() {
        return whispering;
    }

    @Override
    public AudioSender sequenceNumber(long sequenceNumber) {
        if (sequenceNumber < 0L) {
            throw new IllegalArgumentException("Sequence number must be positive");
        }
        this.nextSequenceNumber.set(sequenceNumber);
        return this;
    }

    @Override
    public boolean canSend() {
        ServerVoiceEvents events = Voicechat.SERVER;
        return events != null && !events.isCompatible(uuid) && AUDIO_SENDERS.get(uuid) == this;
    }

    @Override
    public boolean send(byte[] opusEncodedAudioData) {
        return sendMicrophonePacket(opusEncodedAudioData);
    }

    @Override
    public boolean reset() {
        return sendMicrophonePacket(new byte[0]);
    }

    public boolean sendMicrophonePacket(byte[] data) {
        if (data == null) {
            throw new IllegalStateException("opusEncodedData is not set");
        }
        ServerVoiceEvents events = Voicechat.SERVER;
        if (events == null || events.isCompatible(uuid) || AUDIO_SENDERS.get(uuid) != this) {
            return false;
        }
        Server server = events.getServer();
        if (server == null) {
            return false;
        }
        try {
            MicPacket packet = new MicPacket(data, data.length > 0 && whispering, nextSequenceNumber.getAndIncrement());
            if (data.length <= 0) {
                nextSequenceNumber.set(0L);
            }
            server.onMicPacket(uuid, packet);
        } catch (Exception e) {
            Voicechat.LOGGER.error("Failed to send audio", e);
        }
        return true;
    }

}
