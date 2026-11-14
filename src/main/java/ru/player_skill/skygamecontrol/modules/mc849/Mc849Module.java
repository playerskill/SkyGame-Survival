package ru.player_skill.skygamecontrol.modules.mc849;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.player_skill.skygamecontrol.modules.ControlModule;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class Mc849Module implements ControlModule, Listener {
    private final JavaPlugin plugin;
    private final Map<UUID, Long> recentCombatActions = new HashMap<>();

    private boolean enabled;
    private boolean blockOnAttack;
    private boolean blockOnEntityClick;
    private long detectionWindowMs;

    public Mc849Module(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "mc-849";
    }

    @Override
    public void enable(FileConfiguration config) {
        blockOnAttack = config.getBoolean(
                "modules.mc-849.block-on-attack", true);
        blockOnEntityClick = config.getBoolean(
                "modules.mc-849.block-on-entity-click", true);
        detectionWindowMs = Math.max(
                1L,
                config.getLong("modules.mc-849.detection-window-ms", 250L));

        recentCombatActions.clear();
        enabled = true;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {
        enabled = false;
        recentCombatActions.clear();
        org.bukkit.event.HandlerList.unregisterAll(this);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        if (!enabled || !blockOnAttack) {
            return;
        }

        Entity damager = event.getDamager();
        if (damager instanceof Player) {
            markAction((Player) damager);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityClick(PlayerInteractEntityEvent event) {
        if (!enabled || !blockOnEntityClick) {
            return;
        }

        markAction(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemConsume(PlayerItemConsumeEvent event) {
        if (!enabled || (!blockOnAttack && !blockOnEntityClick)) {
            return;
        }

        UUID playerId = event.getPlayer().getUniqueId();
        Long actionTime = recentCombatActions.get(playerId);

        if (actionTime == null) {
            return;
        }

        long elapsed = System.currentTimeMillis() - actionTime;
        if (elapsed < 0L || elapsed > detectionWindowMs) {
            recentCombatActions.remove(playerId);
            return;
        }

        ItemStack item = event.getItem();
        if (!isFoodOrPotion(item)) {
            return;
        }

        event.setCancelled(true);
        recentCombatActions.remove(playerId);
    }

    private void markAction(Player player) {
        recentCombatActions.put(player.getUniqueId(), System.currentTimeMillis());
    }

    private boolean isFoodOrPotion(ItemStack item) {
        if (item == null) {
            return false;
        }

        Material type = item.getType();

        // PlayerItemConsumeEvent охватывает еду и напитки.
        // Проверка оставлена явно, чтобы не затрагивать прочие предметы,
        // если событие вызывается сторонним плагином.
        switch (type) {
            case POTION:
            case GOLDEN_APPLE:
            case APPLE:
            case BREAD:
            case COOKED_BEEF:
            case RAW_BEEF:
            case COOKED_CHICKEN:
            case RAW_CHICKEN:
            case COOKED_FISH:
            case RAW_FISH:
            case PORK:
            case GRILLED_PORK:
            case COOKIE:
            case MELON:
            case MUSHROOM_SOUP:
            case PUMPKIN_PIE:
            case RABBIT:
            case COOKED_RABBIT:
            case RABBIT_STEW:
            case MUTTON:
            case COOKED_MUTTON:
            case CHORUS_FRUIT:
            case BEETROOT:
            case BEETROOT_SOUP:
                return true;
            default:
                return false;
        }
    }
}
