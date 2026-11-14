package ru.player_skill.skygamecontrol;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import ru.player_skill.skygamecontrol.command.ControlCommand;
import ru.player_skill.skygamecontrol.modules.ModuleManager;

public final class SkyGameControl extends JavaPlugin {
    private ModuleManager moduleManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        moduleManager = new ModuleManager(this);

        PluginCommand command = getCommand("control");
        if (command == null) {
            getLogger().severe("Команда control не объявлена в plugin.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        command.setExecutor(new ControlCommand(this, moduleManager));

        try {
            int active = moduleManager.reload(getConfig());
            getLogger().info("SkyGame-Control enabled. Active modules: " + active);
        } catch (Exception exception) {
            getLogger().severe("Не удалось загрузить модули:");
            exception.printStackTrace();
        }
    }

    @Override
    public void onDisable() {
        if (moduleManager != null) {
            moduleManager.disableAll();
        }
    }
}
