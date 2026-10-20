package de.maxhenkel.voicechat.config;

import java.net.URI;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import de.maxhenkel.voicechat.configbuilder.entry.ConfigEntry;

public final class ReloadValidation {
    private ReloadValidation() {}

    public static Properties readProperties(Path path) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(path)) {
            try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        return properties;
    }

    /** Validate original values before ConfigBuilder's defaulting/clamping can hide mistakes. */
    public static void validateValues(ServerConfig next, Properties raw) {
        for (ConfigEntry<?> entry : List.of(next.voiceChatPort, next.voiceChatDistance, next.whisperDistance,
                next.voiceChatCodec, next.voiceChatMtuSize, next.tcpRateLimit, next.keepAlive, next.groupsEnabled,
                next.allowRecording, next.spectatorInteraction, next.spectatorPlayerPossession,
                next.forceVoiceChat, next.loginTimeout, next.broadcastRange, next.allowPings)) {
            String value = raw.getProperty(entry.getKey());
            if (value == null) continue; // Missing keys are upgraded using their defaults.
            Object expected = entry.get();
            try {
                if (expected instanceof Boolean) {
                    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) throw new IllegalArgumentException();
                } else if (expected instanceof Integer) {
                    if (!expected.equals(Integer.valueOf(value))) throw new IllegalArgumentException();
                } else if (expected instanceof Double) {
                    double number = Double.parseDouble(value);
                    if (!Double.isFinite(number) || Double.compare(number, (Double) expected) != 0) throw new IllegalArgumentException();
                } else if (expected instanceof Enum<?> mode && !mode.name().equals(value)) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("配置项 " + entry.getKey() + " 的值无效或超出范围：" + value + "；本次未应用或保存配置", exception);
            }
        }
    }

    public static void validate(ServerConfig current, ServerConfig next) {
        if (!current.voiceChatPort.get().equals(next.voiceChatPort.get())
                || !current.voiceChatBindAddress.get().equals(next.voiceChatBindAddress.get())
                || !current.tcpRateLimit.get().equals(next.tcpRateLimit.get())) {
            throw new IllegalArgumentException("port、bind_address、tcp_rate_limit 需要重启插件；本次未应用任何配置");
        }
        validateHost(next.voiceHost.get());
    }

    public static void validateHost(String host) {
        if (host.isEmpty()) return;
        try {
            if (host.matches("[0-9]+")) {
                int port = Integer.parseInt(host);
                if (port < 1 || port > 65535) throw new IllegalArgumentException();
                return;
            }
            URI uri = URI.create("voicechat://" + host);
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())
                    || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("voice_host 必须是有效的主机名、IP、主机名:端口或端口号", exception);
        }
    }
}
