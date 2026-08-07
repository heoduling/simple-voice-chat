package de.maxhenkel.voicechat.plugins.impl.audiochannel;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.debug.VoicechatUncaughtExceptionHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class AudioPlayerImpl extends Thread implements AudioPlayer {

    private static final long FRAME_SIZE_NS = 20_000_000;
    public static final int FRAME_SIZE = 960;
    private static final Set<AudioPlayerImpl> ACTIVE_PLAYERS = ConcurrentHashMap.newKeySet();

    private final AudioChannel audioChannel;
    private final OpusEncoder encoder;
    private final Supplier<short[]> audioSupplier;
    private volatile boolean started;
    private volatile boolean stoppedCallbackCalled;
    private volatile boolean stoppedCallbackEnabled = true;
    @Nullable
    private volatile Runnable onStopped;

    public AudioPlayerImpl(AudioChannel audioChannel, @Nonnull OpusEncoder encoder, Supplier<short[]> audioSupplier) {
        this.audioChannel = audioChannel;
        this.encoder = encoder;
        this.audioSupplier = audioSupplier;
        setDaemon(true);
        setName(String.format("AudioPlayer-%s", audioChannel.getId()));
        setUncaughtExceptionHandler(new VoicechatUncaughtExceptionHandler());
    }

    @Override
    public void startPlaying() {
        synchronized (this) {
            if (started) {
                return;
            }
            ACTIVE_PLAYERS.add(this);
            try {
                start();
                started = true;
            } catch (RuntimeException | Error t) {
                ACTIVE_PLAYERS.remove(this);
                throw t;
            }
        }
    }

    @Override
    public void stopPlaying() {
        interrupt();
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    @Override
    public boolean isPlaying() {
        return isAlive();
    }

    @Override
    public boolean isStopped() {
        return started && !isAlive();
    }

    @Override
    public void setOnStopped(Runnable onStopped) {
        this.onStopped = onStopped;
    }

    @Override
    public void run() {
        long framePosition = 0L;

        long startTime = System.nanoTime();

        short[] frame;

        try {
            while (!isInterrupted() && !audioChannel.isClosed() && (frame = audioSupplier.get()) != null) {
                if (frame.length != FRAME_SIZE) {
                    Voicechat.LOGGER.error("Got invalid audio frame size {}!={}", frame.length, FRAME_SIZE);
                    break;
                }
                audioChannel.send(encoder.encode(frame));
                framePosition++;
                long waitTimestamp = startTime + framePosition * FRAME_SIZE_NS;

                long waitNanos = waitTimestamp - System.nanoTime();

                try {
                    if (waitNanos > 0L) {
                        Thread.sleep(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000));
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        } catch (Throwable t) {
            Voicechat.LOGGER.error("Audio player {} stopped because of an unexpected error", audioChannel.getId(), t);
        } finally {
            try {
                encoder.close();
            } catch (Throwable t) {
                Voicechat.LOGGER.warn("Failed to close audio encoder {}", audioChannel.getId(), t);
            }
            try {
                audioChannel.flush();
            } catch (Throwable t) {
                Voicechat.LOGGER.warn("Failed to flush audio channel {}", audioChannel.getId(), t);
            }
            Runnable callback = stoppedCallbackEnabled ? onStopped : null;
            if (callback != null && !stoppedCallbackCalled) {
                stoppedCallbackCalled = true;
                try {
                    callback.run();
                } catch (Throwable t) {
                    Voicechat.LOGGER.error("Audio player stop callback failed", t);
                }
            }
            ACTIVE_PLAYERS.remove(this);
        }
    }

    public static void shutdownAll() {
        stopAndJoin(ACTIVE_PLAYERS.toArray(new AudioPlayerImpl[0]));
    }

    public static void shutdownOwnedBy(ClassLoader owner) {
        List<AudioPlayerImpl> ownedPlayers = new ArrayList<>();
        for (AudioPlayerImpl player : ACTIVE_PLAYERS) {
            Runnable callback = player.onStopped;
            if (isOwnedBy(player.audioSupplier, owner) || isOwnedBy(player.audioChannel, owner)
                    || player.audioChannel instanceof AudioChannelImpl
                    && ((AudioChannelImpl) player.audioChannel).isOwnedBy(owner)
                    || isOwnedBy(player.encoder, owner) || isOwnedBy(callback, owner)) {
                ownedPlayers.add(player);
            }
        }
        stopAndJoin(ownedPlayers.toArray(new AudioPlayerImpl[0]));
    }

    private static void stopAndJoin(AudioPlayerImpl[] players) {
        for (AudioPlayerImpl player : players) {
            player.stoppedCallbackEnabled = false;
            player.onStopped = null;
            player.interrupt();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        for (AudioPlayerImpl player : players) {
            if (player == Thread.currentThread()) continue;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) break;
            try {
                player.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int alive = 0;
        for (AudioPlayerImpl player : players) {
            if (player.isAlive()) alive++;
        }
        if (alive > 0 && Voicechat.LOGGER != null) {
            Voicechat.LOGGER.warn("{} audio player thread(s) did not stop within 2 seconds", alive);
        }
        ACTIVE_PLAYERS.removeIf(player -> !player.isAlive());
    }

    private static boolean isOwnedBy(@Nullable Object object, ClassLoader owner) {
        return object != null && object.getClass().getClassLoader() == owner;
    }

}
