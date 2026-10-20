package de.maxhenkel.voicechat.config;

import de.maxhenkel.voicechat.configbuilder.ConfigBuilder;
import de.maxhenkel.voicechat.configbuilder.entry.ConfigEntry;
import de.maxhenkel.voicechat.BuildConstants;
import de.maxhenkel.voicechat.Voicechat;

public class ServerConfig {

    public final ConfigEntry<Integer> voiceChatPort;
    public final ConfigEntry<String> voiceChatBindAddress;
    public final ConfigEntry<Double> voiceChatDistance;
    public ConfigEntry<Double> whisperDistance;
    public final ConfigEntry<Codec> voiceChatCodec;
    public final ConfigEntry<Integer> voiceChatMtuSize;
    public final ConfigEntry<Integer> tcpRateLimit;
    public final ConfigEntry<Integer> keepAlive;
    public final ConfigEntry<Boolean> groupsEnabled;
    public final ConfigEntry<String> voiceHost;
    public final ConfigEntry<Boolean> allowRecording;
    public final ConfigEntry<Boolean> spectatorInteraction;
    public ConfigEntry<Boolean> spectatorPlayerPossession;
    public ConfigEntry<Boolean> forceVoiceChat;
    public ConfigEntry<Integer> loginTimeout;
    public ConfigEntry<Double> broadcastRange;
    public ConfigEntry<Boolean> allowPings;

    public ServerConfig(ConfigBuilder builder) {
        builder.header(String.format("%s 服务端配置 v%s", BuildConstants.PLUGIN_NAME, Voicechat.INSTANCE.getDescription().getVersion()),
                "插件升级或执行 /voicechat reload 时自动补齐缺失配置，保留已有配置值。",
                "使用帮助：/voicechat help；修改 voice_host 后执行 /voicechat reload，在线语音会短暂重连，群组保留。",
                "port、bind_address、tcp_rate_limit 是启动参数，修改后需要重启插件。",
                "变量显示与提示文字在同目录 translations.properties 中设置。");

        voiceChatPort = builder
                .integerEntry("port", 24454, -1, 65535,
                        "服务端监听语音的 UDP 端口，默认 24454；与游戏连接使用的 TCP 端口独立。",
                        "防火墙、端口映射或隧道必须放行/转发此 UDP 端口，仅开放 TCP 无法传输语音。",
                        "-1 表示使用 Minecraft 服务端端口号；避免与 Query 等服务占用同一 UDP 端口。",
                        "修改此启动参数后需要重启插件；/voicechat reload 会拒绝应用此项变动。"
                );
        voiceChatBindAddress = builder
                .stringEntry("bind_address", "",
                        "语音 UDP 服务绑定的本机 IP 地址，不是提供给玩家的公网域名。",
                        "留空使用 server.properties 的 server-ip；* 表示监听所有本机地址。",
                        "不要填写不属于本机的公网地址。修改此启动参数后需要重启插件。"
                );
        voiceChatDistance = builder
                .doubleEntry("max_voice_distance", 48D, 1D, 1_000_000D,
                        "正常说话能被听见的最大距离，单位：方块，默认 48。"
                );
        whisperDistance = builder
                .doubleEntry("whisper_distance", 24D, 1D, 1_000_000D,
                        "轻声说话能被听见的最大距离，单位：方块，默认 24。"
                );
        voiceChatCodec = builder
                .enumEntry("codec", Codec.VOIP,
                        "Opus 编码模式；配置值必须保留英文，不要翻译枚举名称。",
                        "VOIP：语音优先；AUDIO：音频优先；RESTRICTED_LOWDELAY：低延迟模式。"
                );
        voiceChatMtuSize = builder
                .integerEntry("mtu_size", 1024, 256, 2048,
                        "音频数据包最大大小，单位：字节，默认 1024，可用范围 256～2048。",
                        "网络出现大包丢失时可适当降低；修改后 reload 会重新下发给客户端。"
                );
        tcpRateLimit = builder
                .integerEntry("tcp_rate_limit", 16, -1, 1024,
                        "每名玩家每秒允许的语音控制包数量，默认 16；-1 关闭限制，其他值应大于 0。",
                        "只限制通过游戏连接发送的语音控制包，例如建群、加入/离开群组和状态更新。",
                        "本版本超限时丢弃控制包，不会因此踢出玩家。修改此启动参数后需要重启插件。"
                );
        keepAlive = builder
                .integerEntry("keep_alive", 1000, 1000, Integer.MAX_VALUE,
                        "语音连接心跳间隔，单位：毫秒，默认 1000（1 秒）。",
                        "间隔过长会延迟断线检测；通常保留默认值。"
                );
        groupsEnabled = builder
                .booleanEntry("enable_groups", true,
                        "是否允许群组语音；true 开启，false 关闭。现有群组不会因配置重载而删除。"
                );
        voiceHost = builder
                .stringEntry("voice_host", "",
                        "客户端连接语音所使用的地址；留空时使用玩家连接游戏服务器的地址和语音端口。",
                        "可填写域名、IP、域名:公网UDP端口，例如 pl.sjcmc.cn 或 pl.sjcmc.cn:24454。",
                        "端口映射/隧道的公网 UDP 端口与本机 port 不同时，必须在这里填写公网端口。",
                        "修改后执行 /voicechat reload 即可向在线玩家下发新地址，群组和成员关系保留。",
                        "此项不会自动修改 DNS、防火墙或隧道；该地址必须真实转发到本服语音 UDP 服务。"
                );
        allowRecording = builder
                .booleanEntry("allow_recording", true,
                        "是否允许玩家使用 Mod 的录音功能；true 允许，false 禁止。"
                );
        spectatorInteraction = builder
                .booleanEntry("spectator_interaction", false,
                        "是否允许旁观模式玩家与其他玩家交谈；默认 false。"
                );
        spectatorPlayerPossession = builder
                .booleanEntry("spectator_player_possession", false,
                        "是否允许旁观者与正在旁观的玩家交谈；默认 false。"
                );
        forceVoiceChat = builder
                .booleanEntry("force_voice_chat", false,
                        "是否强制玩家安装兼容的语音 Mod；true 时超过检查时间仍不兼容的玩家会被踢出。",
                        "默认 false；仅需要可选语音功能时不要开启。"
                );
        loginTimeout = builder
                .integerEntry("login_timeout", 10_000, 100, Integer.MAX_VALUE,
                        "等待玩家完成语音 Mod 检查的时间，单位：毫秒，默认 10000（10 秒）。",
                        "仅在 force_voice_chat=true 时使用。"
                );
        broadcastRange = builder
                .doubleEntry("broadcast_range", -1D, -1D, Double.MAX_VALUE,
                        "服务端向周围玩家转发音频的距离，单位：方块。",
                        "-1 表示使用 max_voice_distance；通常保留默认值。"
                );
        allowPings = builder
                .booleanEntry("allow_pings", true,
                        "是否响应外部语音服务探测请求；true 允许，false 禁止。"
                );
    }

    public enum Codec {
        VOIP, AUDIO, RESTRICTED_LOWDELAY;
    }

}
