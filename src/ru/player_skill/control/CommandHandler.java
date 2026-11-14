package ru.player_skill.control;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import ru.player_skill.control.Module;

public class CommandHandler implements CommandExecutor {
    private final ControlPlugin plugin;

    public CommandHandler(ControlPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("control.use")) {
            sendKey(sender, "no-permission");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadModules();
            sendRaw(sender, plugin.getConfig()
                    .getString("messages.reloaded", "&aПерезагружено.")
                    .replace("{count}", String.valueOf(plugin.getModules().size())));
            return true;
        }

        sendKey(sender, "usage");
        return true;
    }

    /** Отправка сообщения по ключу из секции messages. */
    private void sendKey(CommandSender sender, String key) {
        sendRaw(sender, plugin.getConfig().getString("messages." + key, key));
    }

    /** Отправка готовой строки с префиксом и цветами. */
    private void sendRaw(CommandSender sender, String message) {
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        sender.sendMessage(color(prefix + message));
    }

    private String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }
}
