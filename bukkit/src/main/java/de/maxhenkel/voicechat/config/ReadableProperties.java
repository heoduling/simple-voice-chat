package de.maxhenkel.voicechat.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** ConfigBuilder reads UTF-8 but escapes non-ASCII values when saving. Keep its output human-readable. */
public final class ReadableProperties {
    private ReadableProperties() {}

    public static void normalize(Path file) {
        try {
            String original = Files.readString(file, StandardCharsets.UTF_8);
            String readable = decode(original);
            if (!readable.equals(original)) Files.writeString(file, readable, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("无法保存 UTF-8 中文配置：" + file.getFileName(), exception);
        }
    }

    public static String decode(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length();) {
            if (text.charAt(i) != '\\') { result.append(text.charAt(i++)); continue; }
            int start = i;
            while (i < text.length() && text.charAt(i) == '\\') i++;
            int slashes = i - start;
            int value = i < text.length() && text.charAt(i) == 'u' ? hex(text, i + 1) : -1;
            if ((slashes & 1) == 1 && value >= 128 && !Character.isSurrogate((char) value)) {
                result.append("\\".repeat(slashes - 1)).append((char) value);
                i += 5;
            } else if ((slashes & 1) == 1 && value >= 0 && Character.isHighSurrogate((char) value)
                    && text.startsWith("\\u", i + 5)) {
                int low = hex(text, i + 7);
                if (low >= 0 && Character.isLowSurrogate((char) low)) {
                    result.append("\\".repeat(slashes - 1)).append((char) value).append((char) low);
                    i += 11;
                } else result.append(text, start, i);
            } else result.append(text, start, i);
        }
        return result.toString();
    }

    private static int hex(String text, int start) {
        if (start + 4 > text.length()) return -1;
        int value = 0;
        for (int i = start; i < start + 4; i++) {
            int digit = Character.digit(text.charAt(i), 16);
            if (digit < 0) return -1;
            value = value * 16 + digit;
        }
        return value;
    }
}
