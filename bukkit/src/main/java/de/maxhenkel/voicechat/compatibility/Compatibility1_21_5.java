package de.maxhenkel.voicechat.compatibility;

import de.maxhenkel.voicechat.BukkitVersion;
import org.bukkit.entity.Player;
import org.json.JSONArray;
import org.json.JSONObject;

public class Compatibility1_21_5 extends Compatibility1_20_3 {

    public static final BukkitVersion VERSION_1_21_5 = BukkitVersion.parseBukkitVersion("1.21.5-R0.1");
    public static final BukkitVersion VERSION_1_21_6 = BukkitVersion.parseBukkitVersion("1.21.6-R0.1");
    public static final BukkitVersion VERSION_1_21_7 = BukkitVersion.parseBukkitVersion("1.21.7-R0.1");
    public static final BukkitVersion VERSION_1_21_8 = BukkitVersion.parseBukkitVersion("1.21.8-R0.1");
    public static final BukkitVersion VERSION_1_21_9 = BukkitVersion.parseBukkitVersion("1.21.9-R0.1");
    public static final BukkitVersion VERSION_1_21_10 = BukkitVersion.parseBukkitVersion("1.21.10-R0.1");
    public static final BukkitVersion VERSION_1_21_11 = BukkitVersion.parseBukkitVersion("1.21.11-R0.1");
    public static final BukkitVersion VERSION_26_1 = BukkitVersion.parseBukkitVersion("26.1-R0.1");
    public static final BukkitVersion VERSION_26_1_1 = BukkitVersion.parseBukkitVersion("26.1.1-R0.1");
    public static final BukkitVersion VERSION_26_1_2 = BukkitVersion.parseBukkitVersion("26.1.2-R0.1");
    public static final BukkitVersion VERSION_26_2 = BukkitVersion.parseBukkitVersion("26.2-R0.1");

    public static final Compatibility1_21_5 INSTANCE = new Compatibility1_21_5();

    @Override
    public String createTranslationMessage(String key, String... args) {
        return new JSONObject().put("text", FallbackCompatibility.fallbackTranslation(key, args)).toString();
    }

    @Override
    public void sendIncompatibleMessage(Player player, String pluginVersion, String pluginName) {
        de.maxhenkel.voicechat.config.Translations translations = de.maxhenkel.voicechat.Voicechat.TRANSLATIONS;
        if (translations != null) {
            player.sendMessage(String.format(translations.voicechatNotCompatibleMessage.get(), pluginVersion, pluginName).replace("\\n", "\n"));
        }
    }

    @Override
    public void sendInviteMessage(Player player, String commandSenderName, String groupName, String joinCommand) {
        sendJsonMessage(player, constructInviteMessage(commandSenderName, groupName, joinCommand));
    }

    public static String constructInviteMessage(String commandSenderName, String groupName, String joinCommand) {
        JSONObject msg = new JSONObject();
        msg.put("text", commandSenderName + " 邀请你加入语音群组 ");

        JSONArray args = new JSONArray();


        JSONObject groupNameObj = new JSONObject();
        groupNameObj.put("color", "gray");
        groupNameObj.put("text", groupName);
        args.put(groupNameObj);

        JSONObject acceptInBrackets = new JSONObject();
        acceptInBrackets.put("text", " [");
        acceptInBrackets.put("color", "green");
        JSONArray withBrackets = new JSONArray();

        JSONObject accept = new JSONObject();
        accept.put("text", "接受邀请");

        JSONObject clickEvent = new JSONObject();
        clickEvent.put("action", "run_command");
        clickEvent.put("command", joinCommand);
        accept.put("click_event", clickEvent);

        JSONObject hoverEvent = new JSONObject();
        hoverEvent.put("action", "show_text");
        JSONObject contents = new JSONObject();
        contents.put("text", "点击加入该语音群组");
        hoverEvent.put("value", contents);
        accept.put("hover_event", hoverEvent);

        withBrackets.put(accept);
        withBrackets.put(new JSONObject().put("text", "]"));

        acceptInBrackets.put("extra", withBrackets);
        args.put(acceptInBrackets);

        msg.put("extra", args);

        return msg.toString();
    }

}
