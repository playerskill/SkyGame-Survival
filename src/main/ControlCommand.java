package me.playerskill.skygamecontrol;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;

public final class ControlCommand implements CommandExecutor, TabCompleter {
    private final SkyGameControl plugin;

    public ControlCommand(SkyGameControl plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (!sender.hasPermission("control.use")) {
            plugin.sendMessage(sender, "no-permission");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.sendMessage(sender, "reload-start");

            try {
                // Перечитываем только в память: файл и его комментарии не меняются.
                plugin.reloadConfig();
                int active = plugin.getModuleManager().reloadModules();

                sender.sendMessage(plugin.message("reload-done")
                        .replace("{count}", String.valueOf(active)));
            } catch (Exception ex) {
                plugin.getLogger().severe("Ошибка перезагрузки конфигурации: "
                        + ex.getMessage());
                ex.printStackTrace();
                sender.sendMessage("§cНе удалось перезагрузить конфигурацию. "
                        + "Подробности в консоли.");
            }
            return true;
        }

        plugin.sendMessage(sender, "usage");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("control.use")) {
            return Collections.singletonList("reload");
        }
        return Collections.emptyList();
    }
}
