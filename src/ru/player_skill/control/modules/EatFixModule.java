package ru.player_skill.control.modules;

import net.minecraft.server.v1_12_R1.EntityLiving;
import net.minecraft.server.v1_12_R1.Item;
import net.minecraft.server.v1_12_R1.ItemFood;
import net.minecraft.server.v1_12_R1.ItemPotion;
import net.minecraft.server.v1_12_R1.ItemStack;
import org.bukkit.craftbukkit.v1_12_R1.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import ru.player_skill.control.ControlPlugin;
import ru.player_skill.control.Module;

import java.lang.reflect.Field;

public class EatFixModule implements Module, Listener {
    private final ControlPlugin plugin;
    private final Field activeItemStackField;
    private final boolean blockOnAttack;
    private final boolean blockOnNpcClick;
    private boolean warned = false;

    public EatFixModule(ControlPlugin plugin) throws NoSuchFieldException {
        this.plugin = plugin;
        activeItemStackField = EntityLiving.class.getDeclaredField("activeItemStack");
        activeItemStackField.setAccessible(true);

        blockOnAttack = plugin.getConfig().getBoolean("eat-fix.block-on-attack", true);
        blockOnNpcClick = plugin.getConfig().getBoolean("eat-fix.block-on-npc-click", true);
    }

    @Override
    public String getName() {
        return "EatFix";
    }

    @Override
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
    }

    // --- Удар (замах любой рукой) — покрывает и основную руку, и вторую ---
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        if (!blockOnAttack) return;
        clearActiveItem(event.getPlayer());
    }

    // --- Клик по НПС и другим сущностям ---
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityClick(PlayerInteractEntityEvent event) {
        if (!blockOnNpcClick) return;
        clearActiveItem(event.getPlayer());
    }

    // --- Клик в точку сущности (стойки для брони и подобное) ---
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityClickAt(PlayerInteractAtEntityEvent event) {
        if (!blockOnNpcClick) return;
        clearActiveItem(event.getPlayer());
    }

    private void clearActiveItem(Player player) {
        EntityLiving handle = ((CraftPlayer) player).getHandle();
        try {
            ItemStack active = (ItemStack) activeItemStackField.get(handle);
            if (active != null && isConsumable(active.getItem())) {
                activeItemStackField.set(handle, null);
            }
        } catch (IllegalAccessException ex) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("EatFix: потерян доступ к полю activeItemStack: " + ex.getMessage());
            }
        }
    }

    private boolean isConsumable(Item item) {
        return item instanceof ItemFood || item instanceof ItemPotion;
    }
}
