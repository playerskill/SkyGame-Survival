package ru.player_skill.skygamecontrol.command;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import ru.player_skill.skygamecontrol.modules.ModuleManager;

public final class ControlCommand implements CommandExecutor {
    private final JavaPlugin plugin;
    private final ModuleManager moduleManager;

    public ControlCommand(JavaPlugin plugin, ModuleManager moduleManager) {
        this.plugin = plugin;
        this.moduleManager = moduleManager;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!sender.hasPermission("control.use")) {
            sender.sendMessage(message("messages.no-permission",
                    "&cУ вас нет прав на эту команду."));
            return true;
        }

        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage(message("messages.usage",
                    "&eИспользование: /control reload"));
            return true;
        }

        try {
            // Перечитываем существующий файл, не сохраняя его обратно.
            plugin.reloadConfig();
            int activeCount = moduleManager.reload(plugin.getConfig());

            String text = message("messages.reload-success",
                    "&aКонфигурация перечитана. Активных модулей: &f{active}");
            sender.sendMessage(text.replace("{active}", String.valueOf(activeCount)));
        } catch (Exception exception) {
            plugin.getLogger().severe("Configuration reload failed:");
            exception.printStackTrace();
            sender.sendMessage(message("messages.reload-error",
                    "&cНе удалось перечитать конфигурацию. Подробности в консоли."));
        }

        return true;
    }

    private String message(String path, String fallback) {
        String value = plugin.getConfig().getString(path, fallback);
        return ChatColor.translateAlternateColorCodes('&', value);
    }
}
