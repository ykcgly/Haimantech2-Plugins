package com.haiman233.haimantech.customs;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

/**
 * 机器菜单"锁定槽位"玩家交互防护（独立兜底层）。
 *
 * <p>状态槽等锁定槽位的常规点击由 Slimefun 自身的槽位点击处理器链拦截
 * （{@code BlockMenuPreset.addMenuClickHandler} + {@code MenuListener}），
 * 本插件已在注册时正确挂上该处理器。但以下交互路径<b>不经过目标槽位自身</b>的
 * 点击处理器，可绕过上述拦截：</p>
 *
 * <ul>
 *   <li><b>双击收集</b>（ClickType.DOUBLE_CLICK）：在输出槽等未锁定槽位上双击时，
 *       原版会把与光标物品相同的物品从<b>整个容器</b>集中到光标上——锁定槽内的
 *       状态板物品会被直接取走，而其槽位处理器全程不会被调用；</li>
 *   <li><b>创造模式交互</b>（{@link org.bukkit.event.inventory.InventoryCreativeEvent}，
 *       本事件的父类即 InventoryClickEvent，被本监听器一并覆盖）：创造模式下的
 *       中键复制等交互走 CREATIVE 点击类型，不触发普通点击处理器链；</li>
 *   <li><b>拖拽合并</b>（InventoryDragEvent）：光标持同类物品在锁定槽上拖拽会被
 *       原版合并放入，拖拽事件不经过任何槽位点击处理器。</li>
 * </ul>
 *
 * <p>本监听器按「机器 ID → 锁定槽位集合」注册表独立拦截上述全部路径，
 * 不依赖 Slimefun 的槽位点击处理器链路，作为第二道防线。</p>
 */
public final class HTSlotGuard implements Listener {

    /** 机器 ID（大写）→ 锁定槽位集合。 */
    private static final Map<String, Set<Integer>> PROTECTED_SLOTS = new ConcurrentHashMap<>();

    private HTSlotGuard() {}

    /** 为指定机器登记锁定槽位（机器 ID 统一大写，与注册 ID 规范一致）。 */
    public static void protect(String machineId, int... slots) {
        if (machineId == null || machineId.isBlank()) return;
        Set<Integer> set = PROTECTED_SLOTS.computeIfAbsent(
                machineId.toUpperCase(Locale.ROOT), k -> ConcurrentHashMap.newKeySet());
        for (int slot : slots) {
            if (slot >= 0) set.add(slot);
        }
    }

    /** 注册本监听器到插件。 */
    public static void register(org.bukkit.plugin.java.JavaPlugin plugin) {
        Bukkit.getPluginManager().registerEvents(new HTSlotGuard(), plugin);
    }

    // ---------------------------------------------------------------- click

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        BlockMenu menu = topBlockMenu(event.getView());
        if (menu == null) return;

        Set<Integer> locked = lockedSlotsOf(menu);
        if (locked == null || locked.isEmpty()) return;

        int topSize = event.getView().getTopInventory().getSize();
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= topSize) return;

        // 直接点击锁定槽（覆盖左键/右键/shift/数字键换位/双击起点/创造模式 CREATIVE 点击）
        if (locked.contains(slot)) {
            event.setCancelled(true);
            return;
        }

        // 双击收集：光标物品与任一锁定槽内容相同，原版会从锁定槽把物品集中到光标
        if (event.getClick() == ClickType.DOUBLE_CLICK) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                for (int lockedSlot : locked) {
                    ItemStack lockedItem = menu.getItemInSlot(lockedSlot);
                    if (lockedItem != null && cursor.isSimilar(lockedItem)) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------------- drag

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        BlockMenu menu = topBlockMenu(event.getView());
        if (menu == null) return;

        Set<Integer> locked = lockedSlotsOf(menu);
        if (locked == null || locked.isEmpty()) return;

        int topSize = event.getView().getTopInventory().getSize();
        for (int raw : event.getRawSlots()) {
            if (raw >= 0 && raw < topSize && locked.contains(raw)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private static BlockMenu topBlockMenu(InventoryView view) {
        if (view == null) return null;
        Inventory top = view.getTopInventory();
        if (top == null) return null;
        return top.getHolder(false) instanceof BlockMenu menu ? menu : null;
    }

    private static Set<Integer> lockedSlotsOf(BlockMenu menu) {
        SlimefunItem item = menu.getPreset() != null ? menu.getPreset().getSlimefunItem() : null;
        return item != null ? PROTECTED_SLOTS.get(item.getId()) : null;
    }
}
