package ru.player_skill.control;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import ru.player_skill.control.modules.EatFixModule;

import java.util.ArrayList;
import java.util.List;

public class ControlPlugin extends JavaPlugin {
    private final List<Module> modules = new ArrayList<Module>();
    private final CommandHandler commandHandler = new CommandHandler(this);

    @Override
    public void onEnable() {
        // Пишет config.yml только если файла ещё нет — комментарии не затираются.
        // saveConfig() нигде не вызывается по той же причине.
        saveDefaultConfig();

        enableModules();

        PluginCommand control = getCommand("control");
        if (control != null) {
            control.setExecutor(commandHandler);
        }
    }

    @Override
    public void onDisable() {
        disableModules();
    }

    /** Полный цикл перезагрузки: снять старые модули, перечитать конфиг, поднять новые. */
    public void reloadModules() {
        reloadConfig();
        enableModules();
    }

    /** Поднимает модули согласно конфигу. Сначала гарантированно выключает старые. */
    private void enableModules() {
        disableModules();
        modules.clear();

        // --- Регистрация модулей. Каждый — в своём try-catch,
        // чтобы сбой одного не уронил весь плагин. ---
        if (getConfig().getBoolean("eat-fix.enabled", true)) {
            try {
                modules.add(new EatFixModule(this));
            } catch (NoSuchFieldException ex) {
                getLogger().warning("EatFix: поле activeItemStack не найдено, версия сервера несовместима. Модуль отключён.");
            } catch (Exception ex) {
                getLogger().warning("EatFix: не удалось загрузить модуль: " + ex.getMessage());
            }
        }

        // Новые модули добавляйте здесь по той же схеме:
        // if (getConfig().getBoolean("имя.enabled", true)) { try { modules.add(new ...); } catch (...) {...} }

        for (Module module : modules) {
            try {
                module.enable();
            } catch (Exception ex) {
                getLogger().warning("Модуль " + module.getName() + " не запустился: " + ex.getMessage());
            }
        }
        getLogger().info("Активных модулей: " + modules.size());
    }

    private void disableModules() {
        for (Module module : modules) {
            try {
                module.disable();
            } catch (Exception ex) {
                getLogger().warning("Модуль " + module.getName() + " завершился с ошибкой: " + ex.getMessage());
            }
        }
        modules.clear();
    }

    public List<Module> getModules() {
        return modules;
    }
}
