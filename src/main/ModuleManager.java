package me.playerskill.skygamecontrol;

import org.bukkit.event.HandlerList;

import java.util.ArrayList;
import java.util.List;

public final class ModuleManager {
    private final SkyGameControl plugin;
    private final List<Mc849Module> modules = new ArrayList<>();

    public ModuleManager(SkyGameControl plugin) {
        this.plugin = plugin;
        modules.add(new Mc849Module(plugin));
    }

    public int reloadModules() {
        disableAll();

        int active = 0;
        for (Mc849Module module : modules) {
            String path = "modules." + module.getName();

            if (!plugin.getConfig().getBoolean(path + ".enabled", false)) {
                continue;
            }

            try {
                module.enable();
                active++;
                plugin.getLogger().info("Модуль включён: " + module.getName());
            } catch (Throwable error) {
                // Ошибка одного модуля не мешает запуску остальных.
                module.disable();
                plugin.getLogger().severe("Не удалось включить модуль "
                        + module.getName() + ": " + error.getMessage());
                error.printStackTrace();
            }
        }
        return active;
    }

    public void disableAll() {
        for (Mc849Module module : modules) {
            try {
                module.disable();
            } catch (Throwable error) {
                plugin.getLogger().warning("Ошибка отключения "
                        + module.getName() + ": " + error.getMessage());
            }
        }
    }

    public void unregister(Object listener) {
        HandlerList.unregisterAll((org.bukkit.event.Listener) listener);
    }
}
