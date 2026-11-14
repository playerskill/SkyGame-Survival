package ru.player_skill.skygamecontrol.modules;

import org.bukkit.configuration.file.FileConfiguration;

public interface ControlModule {
    String getName();

    void enable(FileConfiguration config) throws Exception;

    void disable();
}
