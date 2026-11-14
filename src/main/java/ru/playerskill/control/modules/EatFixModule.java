package ru.playerskill.control.modules;

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
import org.bukkit.event.player.PlayerInteractEntityEvent;
import ru.playerskill.control.ControlPlugin;
import ru.playerskill.control.Module;

import java.lang.reflect.Field;

/**
 * Фикс MC-849: клиент при ударе/клике по сущности сам отменяет использование
 * еды или зелья, а сервер нет — через 1,6 секунды предмет расходуется без
 * анимации. Мы синхронизируем сервер с уже свершившимся фактом клиента.
 */
public class EatFixModule implements Module, Listener {

    private final ControlPlugin plugin;
    private final Field activeItemStackField;
    private final boolean blockOnAttack;
    private final boolean blockOnNpcClick;
    private boolean warned = false;

    public EatFixModule(ControlPlugin plugin) throws NoSuchFieldException {
        this.plugin = plugin;

        // Поле одно на игрока — покрывает и основную руку, и вторую.
        // Проверка руки не нужна: сервер хранит только один активный предмет.
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

    /**
     * Любой PlayerAnimationEvent в 1.12.2 — это замах. Фильтр по ARM_SWING
     * не ставим: он рискует пропустить замах второй рукой.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        if (!blockOnAttack) {
            return;
        }
        clearActiveItem(event.getPlayer());
    }

    /**
     * Клик по любой сущности: НПС, мобы, игроки, стойки брони
     * (InteractAtEntityEvent — наследник этого события и ловится им же).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityClick(PlayerInteractEntityEvent event) {
        if (!blockOnNpcClick) {
            return;
        }
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

    /** Молоко не фильтруем — осознанное решение. При необходимости добавить ItemBucketMilk сюда. */
    private boolean isConsumable(Item item) {
        return item instanceof ItemFood || item instanceof ItemPotion;
    }
}
