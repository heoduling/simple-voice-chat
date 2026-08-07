package de.maxhenkel.voicechat.debug;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class CooldownTimer {

    private static ConcurrentHashMap<String, Long> cooldowns;
    private static final AtomicInteger calls = new AtomicInteger();

    static {
        cooldowns = new ConcurrentHashMap<>();
    }

    public static void run(String id, long time, Runnable runnable) {
        long now = System.currentTimeMillis();
        if (calls.incrementAndGet() % 256 == 0) {
            cooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
        Long previous = cooldowns.putIfAbsent(id, now + time);
        if (previous == null || previous <= now && cooldowns.replace(id, previous, now + time)) {
            runnable.run();
        }
    }

    public static void run(String id, Runnable runnable) {
        run(id, 10_000L, runnable);
    }

    public static void clear() {
        cooldowns.clear();
        calls.set(0);
    }

}
