package de.maxhenkel.voicechat.config;

import de.maxhenkel.voicechat.configbuilder.ConfigBuilder;
import de.maxhenkel.voicechat.configbuilder.entry.ConfigEntry;
import de.maxhenkel.voicechat.configbuilder.MigratableConfig;

public class Translations {

    public final ConfigEntry<String> forceVoicechatKickMessage;
    public final ConfigEntry<String> rateLimitKickMessage;
    public final ConfigEntry<String> voicechatNotCompatibleMessage;
    public final ConfigEntry<String> voicechatNeededForCommandMessage;
    public final ConfigEntry<String> playerCommandMessage;
    public final ConfigEntry<String> placeholderVoicechatInstalled;
    public final ConfigEntry<String> placeholderVoicechatNotInstalled;
    public final ConfigEntry<String> placeholderVoicechatTalking;
    public final ConfigEntry<String> placeholderVoicechatNotTalking;
    public final ConfigEntry<String> placeholderVoicechatTalkingIcon;
    public final ConfigEntry<String> placeholderVoicechatNotTalkingIcon;
    public final ConfigEntry<String> placeholderVoicechatDisabled;

    public Translations(ConfigBuilder builder) {
        builder.header(
                "Simple Voice Chat 中文提示与变量显示配置",
                "修改后执行 /voicechat reload 生效，无需卸载插件。",
                "升级自动补齐缺失键及默认注释；旧版未修改的英文默认提示自动转为中文，自定义值保留。",
                "提示中的 %s 是程序填入的参数，请保留数量和顺序；\\n 表示换行。",
                "placeholder_api_* 控制 PlaceholderAPI 变量输出，支持原样传递 MiniMessage 给显示插件。",
                "文件使用 UTF-8；中文和 🎙、🔇 等图标可直接填写，无需手写 Unicode 转义。"
        );

        forceVoicechatKickMessage = builder.stringEntry(
                "force_voicechat_kick_message",
                "进入本服需要安装 %s %s。",
                "开启强制语音时，未安装兼容 Mod 的玩家被踢出时收到的提示。",
                "第一个 %s 是 Mod 名称，第二个 %s 是版本。"
        );
        rateLimitKickMessage = builder.stringEntry(
                "rate_limit_kick_message",
                "语音控制数据包发送过于频繁。",
                "旧版限流踢出提示，保留此键用于配置兼容；本版本控制包超限只丢弃，不踢人。"
        );
        voicechatNotCompatibleMessage = builder.stringEntry(
                "voicechat_not_compatible_message",
                "你的语音客户端版本与服务端不兼容。\\n请安装版本 %s 的 %s。",
                "玩家安装的语音 Mod 版本不兼容时显示的提示。",
                "第一个 %s 是兼容版本，第二个 %s 是 Mod 名称。"
        );
        voicechatNeededForCommandMessage = builder.stringEntry(
                "voicechat_needed_for_command_message",
                "使用此命令需要在客户端安装兼容的 %s。",
                "执行建群相关命令或连接检测时，未安装兼容 Mod 的提示；help 和 reload 不要求安装 Mod。",
                "%s 是 Mod 名称。"
        );
        playerCommandMessage = builder.stringEntry(
                "player_command_message",
                "此操作只能由游戏内玩家执行；控制台可使用 /voicechat help 或 /voicechat reload。",
                "控制台执行只适用于玩家的语音操作时显示的提示。"
        );
        placeholderVoicechatInstalled = builder.stringEntry(
                "placeholder_api_voicechat_installed",
                "",
                "%voicechat_installed%：玩家已通过兼容性检查时显示此值，否则为空。",
                "默认留空；此变量不表示玩家正在说话。支持 MiniMessage。"
        );
        placeholderVoicechatNotInstalled = builder.stringEntry(
                "placeholder_api_voicechat_not_installed",
                "",
                "%voicechat_not_installed%：玩家尚未通过兼容性检查时显示此值，否则为空。",
                "未安装、版本不兼容或尚未完成检查都会满足此条件，并非检测玩家电脑文件。",
                "例如 <red>🔇</red>；可与 %voicechat_talking_icon% 拼接使用。"
        );
        placeholderVoicechatTalking = builder.stringEntry(
                "placeholder_api_voicechat_talking",
                "1",
                "%voicechat_talking% 和 %voicechat_is_talking% 在玩家说话时显示的内容",
                "支持 MiniMessage，例如 <green>1</green>；标签会原样交给显示变量的插件处理"
        );
        placeholderVoicechatNotTalking = builder.stringEntry(
                "placeholder_api_voicechat_not_talking",
                "0",
                "%voicechat_talking% 和 %voicechat_is_talking% 在玩家未说话时显示的内容",
                "支持 MiniMessage，例如 <gray>0</gray>；留空可隐藏"
        );
        placeholderVoicechatTalkingIcon = builder.stringEntry(
                "placeholder_api_voicechat_talking_icon",
                "\uD83C\uDF99",
                "%voicechat_talking_icon% 在玩家说话时显示的内容",
                "支持 MiniMessage，例如 <green>\uD83C\uDF99</green>"
        );
        placeholderVoicechatNotTalkingIcon = builder.stringEntry(
                "placeholder_api_voicechat_not_talking_icon",
                "",
                "%voicechat_talking_icon% 在玩家未说话时显示的内容",
                "支持 MiniMessage；默认留空即不显示图标"
        );
        placeholderVoicechatDisabled = builder.stringEntry(
                "placeholder_api_voicechat_disabled",
                "",
                "%voicechat_disabled%：玩家在客户端禁用语音功能时显示此值，否则为空。",
                "这与未安装 Mod 不同；默认留空，支持 MiniMessage。"
        );
    }

    public static void migrateLegacyDefaults(MigratableConfig config) {
        replaceDefault(config, "force_voicechat_kick_message", "You need %s %s to play on this server", "进入本服需要安装 %s %s。");
        replaceDefault(config, "rate_limit_kick_message", "Kicked for exceeding packet rate limit", "语音控制数据包发送过于频繁。");
        replaceDefault(config, "voicechat_not_compatible_message", "Your voice chat client version is not compatible with the server-side version.\\nPlease install version %s of %s.", "你的语音客户端版本与服务端不兼容。\\n请安装版本 %s 的 %s。");
        replaceDefault(config, "voicechat_needed_for_command_message", "You need to have %s installed on your client to use this command", "使用此命令需要在客户端安装兼容的 %s。");
        replaceDefault(config, "player_command_message", "This command can only be executed as a player", "此操作只能由游戏内玩家执行；控制台可使用 /voicechat help 或 /voicechat reload。");
    }

    private static void replaceDefault(MigratableConfig config, String key, String previous, String chinese) {
        if (previous.equals(config.get(key))) config.set(key, chinese);
    }

}
