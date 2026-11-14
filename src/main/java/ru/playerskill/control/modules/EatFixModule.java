package ru.playerskill.control.modules;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.playerskill.control.ControlPlugin;
import ru.playerskill.control.Module;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Фикс MC-849 и связанного с ним поведения клиента.
 *
 * Механизм 1 (сам баг): при ударе клиент отменяет еду, сервер нет —
 * предмет расходуется без анимации. Ловим замах и сбрасываем активный предмет.
 *
 * Механизм 2 (НПС): при правом клике по сущности, которую «не приняла»
 * игра (Citizens, Shopkeepers обрабатывают клик на своей стороне),
 * клиент откатывается к использованию предмета в руке — еда начинается
 * или продолжается ЗАНОВО уже после нашего сброса. Поэтому после клика
 * по сущности открываем короткое окно и отменяем использование
 * еды/зелья внутри него.
 *
 * Имена полей и методов NMS в Spigot обфусцированы, поэтому:
 *  - поле активного предмета ищем по типу (единственный нестатический,
 *    нефинальный ItemStack в EntityLiving), а не по имени;
 *  - тип предмета определяем через CraftItemStack.asBukkitCopy и Material:
 *    классы CraftBukkit не обфусцированы.
 *
 * ignoreCancelled на событиях сущности НЕ ставим: плагины НПС отменяют
 * взаимодействие, но клиент уже отменил еду — сервер обязан синхронизироваться
 * независимо от отмены события.
 */
public class EatFixModule implements Module, Listener {

    /** Окно, в течение которого после клика по сущности гасится использование предмета. */
    private static final long ENTITY_CLICK_WINDOW_MS = 150L;

    private final ControlPlugin plugin;
    private final Field activeItemStackField;
    private final Method asBukkitCopy;
    private final Method getHandleMethod;
    private final boolean blockOnAttack;
    private final boolean blockOnNpcClick;
    private final Map<UUID, Long> entityClickMarks = new HashMap<UUID, Long>();
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
        entityClickMarks.clear();
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
        if (!blockOnAttack) {
            return;
        }
        clearActiveItem(event.getPlayer());
    }

    /**
     * Клик по любой сущности: НПС, мобы, игроки, стойки брони.
     * Сбрасываем текущую еду и открываем окно против «отката» клиента
     * к использованию предмета.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityClick(PlayerInteractEntityEvent event) {
        if (!blockOnNpcClick) {
            return;
        }
        markAndClear(event.getPlayer());
    }

    /**
     * Вариант того же события с точкой попадания — у него свой список
     * слушателей, родительский обработчик его НЕ ловит. Нужен для
     * стоек брони и отдельных типов магазинов.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityClickAt(PlayerInteractAtEntityEvent event) {
        if (!blockOnNpcClick) {
            return;
        }
        markAndClear(event.getPlayer());
    }

    /**
     * Гасим «откат»: клиент после непринятого взаимодействия шлёт пакет
     * использования предмета. Если он приходит в окно после клика по
     * сущности и в руке еда/зелье — отменяем использование предмета.
     * Обычное поедание (клик по воздуху без клика по сущности) не трогаем.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractAfterEntity(PlayerInteractEvent event) {
        if (!blockOnNpcClick) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Long mark = entityClickMarks.get(event.getPlayer().getUniqueId());
        if (mark == null || System.currentTimeMillis() - mark > ENTITY_CLICK_WINDOW_MS) {
            return;
        }
        org.bukkit.inventory.ItemStack used = event.getItem();
        if (used == null || !isConsumable(used.getType())) {
            return;
        }
        event.setCancelled(true);
        clearActiveItem(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        entityClickMarks.remove(event.getPlayer().getUniqueId());
    }

    private void markAndClear(Player player) {
        entityClickMarks.put(player.getUniqueId(), System.currentTimeMillis());
        clearActiveItem(player);
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
