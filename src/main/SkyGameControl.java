package me.playerskill.skygamecontrol;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class SkyGameControl extends JavaPlugin {
    private ModuleManager moduleManager;

    @Override
    public void onEnable() {
        saveDefaultConfig(); // Создаёт файл только при первом запуске.
        moduleManager = new ModuleManager(this);

        getCommand("control").setExecutor(new ControlCommand(this));
        getCommand("control").setTabCompleter(new ControlCommand(this));

        moduleManager.reloadModules();
        getLogger().info("SkyGame-Control создан для PlayeR_SkiLL.");
    }

    @Override
    public void onDisable() {
        if (moduleManager != null) {
            moduleManager.disableAll();
        }
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    public String message(String key) {
        String value = getConfig().getString("messages." + key, "");
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    public void sendMessage(CommandSender sender, String key) {
        sender.sendMessage(message(key));
    }
}
