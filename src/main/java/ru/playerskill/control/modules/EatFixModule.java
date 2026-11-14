package ru.playerskill.control.modules;

import org.bukkit.Material;
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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Фикс MC-849: клиент при ударе/клике по сущности сам отменяет использование
 * еды или зелья, а сервер нет — через 1,6 секунды предмет расходуется без
 * анимации. Мы синхронизируем сервер с уже свершившимся фактом клиента.
 *
 * Имена полей и методов NMS в Spigot обфусцированы, поэтому:
 *  - поле активного предмета ищем по типу (единственный нестатический,
 *    нефинальный ItemStack в EntityLiving), а не по имени;
 *  - тип предмета определяем через CraftItemStack.asBukkitCopy и Material:
 *    классы CraftBukkit не обфусцированы.
 */
public class EatFixModule implements Module, Listener {

    private final ControlPlugin plugin;
    private final Field activeItemStackField;
    private final Method asBukkitCopy;
    private final Method getHandleMethod;
    private final boolean blockOnAttack;
    private final boolean blockOnNpcClick;
    private boolean warned = false;

    public EatFixModule(ControlPlugin plugin) throws Exception {
        this.plugin = plugin;

        Class<?> entityLiving = Class.forName("net.minecraft.server.v1_12_R1.EntityLiving");
        Class<?> nmsItemStack = Class.forName("net.minecraft.server.v1_12_R1.ItemStack");

        activeItemStackField = findActiveItemStackField(entityLiving, nmsItemStack);
        if (activeItemStackField == null) {
            throw new NoSuchFieldException("не удалось однозначно найти активный предмет в EntityLiving");
        }
        activeItemStackField.setAccessible(true);

        // CraftItemStack.asBukkitCopy(nms) — статический, имя метода не обфусцировано.
        Class<?> craftItemStack = Class.forName("org.bukkit.craftbukkit.v1_12_R1.inventory.CraftItemStack");
        asBukkitCopy = craftItemStack.getMethod("asBukkitCopy", nmsItemStack);

        // CraftPlayer.getHandle() — кешируем один раз, метод не обфусцирован.
        Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.v1_12_R1.entity.CraftPlayer");
        getHandleMethod = craftPlayer.getMethod("getHandle");

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
     * Сначала пробуем человекочитаемое имя (форки вроде Paper его оставляют),
     * затем — поиск по типу: в EntityLiving ровно один нестатический,
     * нефинальный ItemStack. Если таких несколько — не рискуем и отключаемся.
     */
    private Field findActiveItemStackField(Class<?> entityLiving, Class<?> nmsItemStack) {
        try {
            return entityLiving.getDeclaredField("activeItemStack");
        } catch (NoSuchFieldException ignored) {
        }

        Field found = null;
        for (Field field : entityLiving.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (field.getType() == nmsItemStack
                    && !Modifier.isStatic(mods)
                    && !Modifier.isFinal(mods)) {
                if (found != null) {
                    return null; // больше одного кандидата — неоднозначно
                }
                found = field;
            }
        }
        return found;
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
        try {
            Object handle = getHandleMethod.invoke(player);
            Object active = activeItemStackField.get(handle);
            if (active == null) {
                return;
            }

            Material material = ((org.bukkit.inventory.ItemStack) asBukkitCopy.invoke(null, active)).getType();
            if (isConsumable(material)) {
                activeItemStackField.set(handle, null);
            }
        } catch (Exception ex) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("EatFix: потерян доступ к активному предмету: " + ex.getMessage());
            }
        }
    }

    /** Еда + выпиваемые зелья. Молоко не трогаем — осознанное решение. */
    private boolean isConsumable(Material material) {
        if (material == Material.MILK_BUCKET) {
            return false;
        }
        return material.isEdible() || material == Material.POTION;
    }
}
