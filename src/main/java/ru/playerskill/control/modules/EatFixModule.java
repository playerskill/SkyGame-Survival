package ru.playerskill.control.modules;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import ru.playerskill.control.ControlPlugin;
import ru.playerskill.control.Module;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Фикс MC-849: при ударе клиент сам отменяет использование еды или зелья,
 * а сервер нет — через 1,6 секунды предмет расходуется без анимации.
 * Ловим замах и сбрасываем активный предмет на сервере.
 *
 * Клик по сущностям (НПС Citizens, Shopkeepers) не обрабатывается.
 *
 * Имена полей и методов NMS в Spigot обфусцированы, поэтому:
 *  - поле активного предмета ищем по типу (единственный нестатический,
 *    нефинальный ItemStack в EntityLiving), а не по имени;
 *  - тип предмета определяем через CraftItemStack.asBukkitCopy и Material.
 */
public class EatFixModule implements Module, Listener {

    private final ControlPlugin plugin;
    private final Field activeItemStackField;
    private final Method asBukkitCopy;
    private final Method getHandleMethod;
    private final boolean debug;
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

        Class<?> craftItemStack = Class.forName("org.bukkit.craftbukkit.v1_12_R1.inventory.CraftItemStack");
        asBukkitCopy = craftItemStack.getMethod("asBukkitCopy", nmsItemStack);

        Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.v1_12_R1.entity.CraftPlayer");
        getHandleMethod = craftPlayer.getMethod("getHandle");

        debug = plugin.getConfig().getBoolean("eat-fix.debug", false);

        plugin.getLogger().info("EatFix активен.");
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
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent event) {
        debug(event.getPlayer(), "замах: сбрасываю активный предмет");
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
                debug(player, "активный предмет сброшен: " + material);
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

    private void debug(Player player, String message) {
        if (debug) {
            plugin.getLogger().info("[EatFix][DEBUG] " + player.getName() + ": " + message);
        }
    }
}
