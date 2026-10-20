package de.maxhenkel.voicechat.command;

import de.maxhenkel.voicechat.BuildConstants;
import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.compatibility.Compatibility;
import de.maxhenkel.voicechat.config.ServerConfig;
import de.maxhenkel.voicechat.config.Translations;
import de.maxhenkel.voicechat.net.NetManager;
import de.maxhenkel.voicechat.permission.PermissionManager;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import de.maxhenkel.voicechat.voice.server.ClientConnection;
import de.maxhenkel.voicechat.voice.server.Group;
import de.maxhenkel.voicechat.voice.server.PingManager;
import de.maxhenkel.voicechat.voice.server.Server;
import de.maxhenkel.voicechat.voice.server.ServerVoiceEvents;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class VoiceChatCommands implements CommandExecutor, TabCompleter {

    public static final String VOICECHAT_COMMAND = "voicechat";

    @Override
    public boolean onCommand(CommandSender commandSender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            return helpCommand(commandSender, command, label, args);
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!commandSender.hasPermission(PermissionManager.ADMIN_PERMISSION)) {
                commandSender.sendMessage("[voicechat] 你没有重载语音插件的权限。");
            } else if (Voicechat.ACTIVE && Voicechat.INSTANCE != null && getActiveServer() != null) {
                Voicechat.INSTANCE.reloadConfiguration(commandSender);
            } else {
                commandSender.sendMessage("[voicechat] 语音服务尚未就绪，无法重载。");
            }
            return true;
        }
        if (checkNoVoicechat(commandSender)) {
            return true;
        }
        if (!(commandSender instanceof Player)) {
            return true;
        }
        Player sender = (Player) commandSender;

        if (args.length >= 1) {
            if (args[0].equalsIgnoreCase("help")) {
                return helpCommand(sender, command, label, args);
            } else if (args[0].equalsIgnoreCase("test")) {
                if (commandSender.hasPermission(PermissionManager.ADMIN_PERMISSION)) {
                    return testCommand(sender, command, label, args);
                } else {
                    commandSender.sendMessage("[voicechat] 你没有检测语音连接的权限。");
                    return true;
                }
            } else if (args[0].equalsIgnoreCase("invite")) {
                return inviteCommand(sender, command, label, args);
            } else if (args[0].equalsIgnoreCase("join")) {
                return joinCommand(sender, command, label, args);
            } else if (args[0].equalsIgnoreCase("leave")) {
                return leaveCommand(sender, command, label, args);
            }
        }
        return helpCommand(sender, command, label, args);
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return tabCompleteList(args[0], commandSender.hasPermission(PermissionManager.ADMIN_PERMISSION)
                    ? Arrays.asList("help", "test", "invite", "join", "leave", "reload")
                    : Arrays.asList("help", "test", "invite", "join", "leave"));
        }

        if (args.length == 2) {
            String arg = args[0];
            if (arg.equals("test") || arg.equals("invite")) {
                return null;
            } else if (arg.startsWith("join")) {
                return Collections.emptyList();
            }
        }

        if (args.length == 3) {
            String arg = args[0];
            if (arg.startsWith("join")) {
                return Collections.emptyList();
            }
        }

        return Collections.emptyList();
    }

    private List<String> tabCompleteList(String arg, List<String> list) {
        return list.stream().filter(s -> s.startsWith(arg)).collect(Collectors.toList());
    }

    private boolean helpCommand(CommandSender commandSender, Command command, String label, String[] args) {
        commandSender.sendMessage("[voicechat] 简单语音聊天 — 中文使用帮助");
        commandSender.sendMessage("/voicechat help — 查看本帮助，控制台和未安装 Mod 的玩家均可使用。");
        commandSender.sendMessage("/voicechat invite <玩家> — 邀请玩家加入你当前所在的语音群组。");
        commandSender.sendMessage("/voicechat join <群组名称或UUID> [密码] — 加入群组，含空格的名称请用双引号包围。");
        commandSender.sendMessage("/voicechat leave — 离开当前语音群组。");
        commandSender.sendMessage("建群请在客户端 Mod 的群组界面操作；加入、离开、邀请需在游戏内安装兼容的 Mod。");
        if (commandSender.hasPermission(PermissionManager.ADMIN_PERMISSION)) {
            commandSender.sendMessage("/voicechat test <玩家> — 检测该玩家的 UDP 语音连接与响应耗时。");
            commandSender.sendMessage("/voicechat reload — 重读配置并让在线语音重连，保留群组和成员关系。");
            commandSender.sendMessage("管理权限：voicechat.admin（OP 默认拥有）。");
            commandSender.sendMessage("网络配置：plugins/voicechat/voicechat-server.properties；修改 voice_host 后 reload 生效。");
            commandSender.sendMessage("port、bind_address、tcp_rate_limit 修改后需重启插件；无效重载不会部分应用配置。");
            commandSender.sendMessage("变量显示：plugins/voicechat/translations.properties；支持原样传递 MiniMessage 给显示插件。");
            commandSender.sendMessage("%voicechat_talking_icon% — 正在说话的图标，默认 🎙，停说时默认隐藏。");
            commandSender.sendMessage("%voicechat_talking% / %voicechat_is_talking% — 正在说话时默认 1，否则 0。");
            commandSender.sendMessage("%voicechat_installed% / %voicechat_not_installed% — 已通过/未通过 Mod 兼容性检查时的自定义显示。");
            commandSender.sendMessage("%voicechat_disabled% — 玩家主动禁用语音时的自定义显示。");
            commandSender.sendMessage("未装/不兼容提示示例：placeholder_api_voicechat_not_installed=<red>🔇</red>。");
            commandSender.sendMessage("新配置项会在启用或 reload 时自动补齐，无需手工复制；自定义值保留。");
        }
        return true;
    }

    private boolean testCommand(Player commandSender, Command command, String label, String[] args) {
        if (args.length < 2) {
            commandSender.sendMessage("[voicechat] 用法：/voicechat test <玩家>");
            return true;
        }

        Player player = parsePlayer(commandSender, args[1]);

        if (player == null) {
            NetManager.sendMessage(commandSender, "argument.entity.notfound.player");
            return true;
        }

        ServerVoiceEvents events = Voicechat.SERVER;
        Server server = events == null ? null : events.getServer();
        if (server == null) {
            return true;
        }

        if (!events.isCompatible(player)) {
            NetManager.sendMessage(commandSender, "message.voicechat.player_no_voicechat", player.getDisplayName(), BuildConstants.PLUGIN_NAME);
            return true;
        }

        ClientConnection clientConnection = server.getConnections().get(player.getUniqueId());

        if (clientConnection == null) {
            NetManager.sendMessage(commandSender, "message.voicechat.client_not_connected");
            return true;
        }

        try {
            NetManager.sendMessage(commandSender, "message.voicechat.sending_ping");
            server.getPingManager().sendPing(clientConnection, 500, 10, new PingManager.PingListener() {

                @Override
                public void onPong(int attempts, long pingMilliseconds) {
                    if (attempts <= 1) {
                        NetManager.sendMessage(commandSender, "message.voicechat.ping_received", String.valueOf(pingMilliseconds));
                    } else {
                        NetManager.sendMessage(commandSender, "message.voicechat.ping_received_attempt", String.valueOf(pingMilliseconds), String.valueOf(attempts));
                    }
                }

                @Override
                public void onFailedAttempt(int attempts) {
                    NetManager.sendMessage(commandSender, "message.voicechat.ping_retry");
                }

                @Override
                public void onTimeout(int attempts) {
                    NetManager.sendMessage(commandSender, "message.voicechat.ping_timed_out", String.valueOf(attempts));
                }
            });
            NetManager.sendMessage(commandSender, "message.voicechat.ping_sent_waiting");
        } catch (Exception e) {
            NetManager.sendMessage(commandSender, "message.voicechat.failed_to_send_ping", e.getMessage());
            Voicechat.LOGGER.warn("Failed to send ping", e);
        }
        return true;
    }

    private boolean inviteCommand(Player commandSender, Command command, String label, String[] args) {
        if (args.length < 2) {
            commandSender.sendMessage("[voicechat] 用法：/voicechat invite <玩家>");
            return true;
        }

        Player player = parsePlayer(commandSender, args[1]);

        if (player == null) {
            NetManager.sendMessage(commandSender, "argument.entity.notfound.player");
            return true;
        }

        ServerVoiceEvents events = Voicechat.SERVER;
        Server server = events == null ? null : events.getServer();
        if (server == null) {
            return true;
        }
        PlayerState state = server.getPlayerStateManager().getState(commandSender.getUniqueId());

        if (state == null || !state.hasGroup()) {
            NetManager.sendMessage(commandSender, "message.voicechat.not_in_group");
            return true;
        }

        Group group = server.getGroupManager().getGroup(state.getGroup());
        if (group == null) {
            return true;
        }

        if (!events.isCompatible(player)) {
            NetManager.sendMessage(commandSender, "message.voicechat.player_no_voicechat", player.getDisplayName(), BuildConstants.PLUGIN_NAME);
            return true;
        }

        String passwordSuffix = group.getPassword() == null ? "" : " \"" + group.getPassword() + "\"";
        String cmd = "/voicechat join " + group.getId().toString() + passwordSuffix;
        Compatibility compatibility = Voicechat.compatibility;
        if (compatibility == null) {
            return true;
        }
        String senderName = commandSender.getName();
        compatibility.runForPlayer(player, () -> {
            if (player.isOnline() && Voicechat.compatibility == compatibility) {
                compatibility.sendInviteMessage(player, senderName, group.getName(), cmd);
            }
        });

        NetManager.sendMessage(commandSender, "message.voicechat.invite_successful", player.getName());

        return true;
    }

    private boolean joinCommand(Player commandSender, Command command, String label, String[] args) {
        if (args.length < 2) {
            commandSender.sendMessage("[voicechat] 用法：/voicechat join <群组名称或UUID> [密码]");
            return true;
        }

        int argIndex = 1;
        UUID groupUUID;
        try {
            groupUUID = UUID.fromString(args[argIndex]);
        } catch (Exception e) {
            String groupName;
            if (args[argIndex].startsWith("\"")) {
                StringBuilder sb = new StringBuilder();
                for (; argIndex < args.length; argIndex++) {
                    sb.append(args[argIndex]).append(" ");
                    if (args[argIndex].endsWith("\"") && !args[argIndex].endsWith("\\\"")) {
                        break;
                    }
                }
                groupName = sb.toString().trim();
                String[] split = groupName.split("\"");
                if (split.length > 1) {
                    groupName = split[1];
                }
            } else {
                groupName = args[argIndex];
            }
            Server server = getActiveServer();
            if (server == null) {
                return true;
            }
            groupUUID = getGroupUUID(commandSender, server, groupName);
        }

        if (groupUUID == null) {
            return true;
        }

        argIndex++;

        String password = null;
        if (args.length >= argIndex + 1) {
            StringBuilder sb = new StringBuilder();
            for (; argIndex < args.length; argIndex++) {
                sb.append(args[argIndex]).append(" ");
            }
            password = sb.toString().trim();
            if (password.startsWith("\"")) {
                String[] split = password.split("\"");
                if (split.length > 1) {
                    password = split[1];
                }
            }
        }

        joinGroup(commandSender, groupUUID, password);
        return true;
    }

    private UUID getGroupUUID(Player commandSender, Server server, String groupName) {
        List<Group> groups = server.getGroupManager().getGroups().values().stream().filter(group -> group.getName().equals(groupName)).collect(Collectors.toList());

        if (groups.isEmpty()) {
            NetManager.sendMessage(commandSender, "message.voicechat.group_does_not_exist");
            return null;
        }

        if (groups.size() > 1) {
            NetManager.sendMessage(commandSender, "message.voicechat.group_name_not_unique");
            return null;
        }

        return groups.get(0).getId();
    }

    private static void joinGroup(Player commandSender, UUID groupID, @Nullable String password) {
        ServerConfig config = Voicechat.SERVER_CONFIG;
        if (config == null || !config.groupsEnabled.get()) {
            NetManager.sendMessage(commandSender, "message.voicechat.groups_disabled");
            return;
        }

        Server server = getActiveServer();
        if (server == null) {
            return;
        }

        if (!commandSender.hasPermission(PermissionManager.GROUPS_PERMISSION)) {
            NetManager.sendMessage(commandSender, "message.voicechat.no_group_permission");
            return;
        }

        Group group = server.getGroupManager().getGroup(groupID);

        if (group == null) {
            NetManager.sendMessage(commandSender, "message.voicechat.group_does_not_exist");
            return;
        }

        if (server.getGroupManager().tryJoinGroup(group, commandSender, password)) {
            NetManager.sendMessage(commandSender, "message.voicechat.join_successful", group.getName()); //TODO Make group name green
        }
    }

    private boolean leaveCommand(Player commandSender, Command command, String label, String[] args) {
        ServerConfig config = Voicechat.SERVER_CONFIG;
        if (config == null || !config.groupsEnabled.get()) {
            NetManager.sendMessage(commandSender, "message.voicechat.groups_disabled");
            return true;
        }

        Server server = getActiveServer();
        if (server == null) {
            return true;
        }
        PlayerState state = server.getPlayerStateManager().getState(commandSender.getUniqueId());
        if (state == null || !state.hasGroup()) {
            NetManager.sendMessage(commandSender, "message.voicechat.not_in_group");
            return true;
        }

        if (server.getGroupManager().tryLeaveGroup(commandSender)) {
            NetManager.sendMessage(commandSender, "message.voicechat.leave_successful");
        }
        return true;
    }

    private static boolean checkNoVoicechat(CommandSender commandSender) {
        ServerVoiceEvents events = Voicechat.SERVER;
        Translations translations = Voicechat.TRANSLATIONS;
        if (events == null || translations == null) {
            return true;
        }
        if (commandSender instanceof Player) {
            if (events.isCompatible((Player) commandSender)) {
                return false;
            }
            commandSender.sendMessage(String.format(translations.voicechatNeededForCommandMessage.get(), "Simple Voice Chat"));
        } else {
            commandSender.sendMessage(translations.playerCommandMessage.get());
        }
        return true;
    }

    @Nullable
    private static Server getActiveServer() {
        ServerVoiceEvents events = Voicechat.SERVER;
        return events == null ? null : events.getServer();
    }

    @Nullable
    public static Player parsePlayer(CommandSender commandSender, String playerArg) {
        Player player = commandSender.getServer().getPlayer(playerArg);
        if (player != null) {
            return player;
        }
        try {
            UUID uuid = UUID.fromString(playerArg);
            return commandSender.getServer().getPlayer(uuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
