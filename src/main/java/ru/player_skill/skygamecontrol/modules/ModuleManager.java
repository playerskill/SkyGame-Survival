package ru.player_skill.skygamecontrol.modules;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import ru.player_skill.skygamecontrol.modules.mc849.Mc849Module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

public final class ModuleManager {
    private final JavaPlugin plugin;
    private final List<ControlModule> activeModules = new ArrayList<>();

    public ModuleManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized int reload(FileConfiguration config) {
        disableAll();

        // Реестр модулей. Новые модули добавляются сюда.
        List<ControlModule> modules = new ArrayList<>();
        modules.add(new Mc849Module(plugin));

        for (ControlModule module : modules) {
            String path = "modules." + module.getName() + ".enabled";

            if (!config.getBoolean(path, false)) {
                continue;
            }

            try {
                module.enable(config);
                activeModules.add(module);
                plugin.getLogger().info("Module enabled: " + module.getName());
            } catch (Exception exception) {
                try {
                    module.disable();
                } catch (Exception disableException) {
                    plugin.getLogger().log(
                            Level.WARNING,
                            "Failed to clean up module " + module.getName(),
                            disableException
                    );
                }

                plugin.getLogger().log(
                        Level.SEVERE,
                        "Could not enable module " + module.getName()
                                + ". Other modules will continue loading.",
                        exception
                );
            }
        }

        return activeModules.size();
    }

    public synchronized void disableAll() {
        for (int i = activeModules.size() - 1; i >= 0; i--) {
            ControlModule module = activeModules.get(i);
            try {
                module.disable();
            } catch (Exception exception) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Could not disable module " + module.getName(),
                        exception
                );
            }
        }

        activeModules.clear();
    }

    public synchronized List<String> getActiveModuleNames() {
        List<String> names = new ArrayList<>();
        for (ControlModule module : activeModules) {
            names.add(module.getName());
        }
        return Collections.unmodifiableList(names);
    }
}
