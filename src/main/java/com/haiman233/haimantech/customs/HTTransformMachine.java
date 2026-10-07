package com.haiman233.haimantech.customs;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.implementation.operations.CraftingOperation;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.List;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

/**
 * 转化机（recipe_machines.yml 中"全部工作配方均为单一输入条目且 noConsume"的机器）。
 *
 * <p>界面与逻辑对齐逻辑工艺（LogiTech）的虚拟种植机 MMGenerator（LOGITECH_VIRTUAL_PLANT）：</p>
 * <ul>
 *   <li><b>4 号输入槽</b>（顶排中间）：放入对应物品，作为催化剂<b>不消耗</b></li>
 *   <li><b>13 号状态槽</b>（二排中间）：空闲橙色"空闲中" / 工作绿色"工作中，将在 X tick 后产出" /
 *       电力不足红色"电力不足" / 空间不足橙色"空间不足"</li>
 *   <li><b>18-53 输出槽</b>（下方四整排）</li>
 *   <li>边框：0/1/2/6/7/8 背景，3/5 输入纹理，9-12/14-17 输出纹理，全部不可点击</li>
 * </ul>
 *
 * <p>运行逻辑完全复用 {@link HTMachine}（空闲快速门 + 每秒配方扫描节流 + hasViewer 门控刷新），
 * 生成速度（seconds）与每刻耗电（energyPerCraft）沿用原配置不变。
 * yml 的 input/output 槽位表与同名菜单对本类不生效（布局固化，见 RecipeMachinesLoader）。</p>
 */
public class HTTransformMachine extends HTMachine {

    /** 输入槽：顶排中间（与 MMGenerator INPUT_SLOT 一致）。 */
    public static final int INPUT_SLOT = 4;
    /** 状态槽：二排中间（与 MMGenerator PROCESSOR_SLOT 一致）。 */
    public static final int STATUS_SLOT = 13;

    private static final int[] INPUT_SLOTS = {INPUT_SLOT};
    private static final int[] OUTPUT_SLOTS = {
            18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53
    };

    private static final ItemStack IDLE = new CustomItemStack(Material.ORANGE_STAINED_GLASS_PANE, "&6空闲中", "");
    private static final ItemStack NO_POWER = new CustomItemStack(Material.RED_STAINED_GLASS_PANE, "&4电力不足", "");
    private static final ItemStack NO_SPACE = new CustomItemStack(Material.ORANGE_STAINED_GLASS_PANE, "&c空间不足", "");

    public HTTransformMachine(ItemGroup group, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe,
                              int energyPerTick, int capacity, int speed, List<HTRecipe> recipes) {
        super(group, item, recipeType, recipe, INPUT_SLOTS, OUTPUT_SLOTS, energyPerTick, capacity, speed,
                recipes, null, STATUS_SLOT);
    }

    @Override
    protected void constructMenu(BlockMenuPreset preset) {
        // 关键：Slimefun 的 BlockMenuPreset 不会自动扩到 54 格，尺寸由"触碰过的最高槽位"决定。
        // 若只装饰 0-17 号槽，界面就只有 18 格（两行），输出区完全丢失——
        // 必须像 LogiTech 的 MMGenerator（constructMenu 首行 preset.setSize(54)）一样显式设定尺寸。
        preset.setSize(54);
        // 空白背景：0/1/2/6/7/8（顶排除输入槽两侧与输入槽本身）
        for (int i : new int[]{0, 1, 2, 6, 7, 8}) {
            preset.addItem(i, ChestMenuUtils.getBackground());
            preset.addMenuClickHandler(i, ChestMenuUtils.getEmptyClickHandler());
        }
        // 输入槽纹理边框：3/5
        for (int i : new int[]{3, 5}) {
            preset.addItem(i, ChestMenuUtils.getInputSlotTexture());
            preset.addMenuClickHandler(i, ChestMenuUtils.getEmptyClickHandler());
        }
        // 输出槽纹理边框：9-17（除状态槽 13）
        for (int i : new int[]{9, 10, 11, 12, 14, 15, 16, 17}) {
            preset.addItem(i, ChestMenuUtils.getOutputSlotTexture());
            preset.addMenuClickHandler(i, ChestMenuUtils.getEmptyClickHandler());
        }
        // 状态槽：初始空闲，不可点击
        preset.addItem(STATUS_SLOT, idleProgressItem());
        preset.addMenuClickHandler(STATUS_SLOT, ChestMenuUtils.getEmptyClickHandler());
    }

    @Override
    protected ItemStack idleProgressItem() {
        return IDLE.clone();
    }

    @Override
    protected void updateProgressDisplay(BlockMenu inv, CraftingOperation op) {
        // 对齐 LogiTech AbstractTransformer.getWorkingItem：绿色面板 + 剩余 tick 倒计时
        inv.replaceExistingItem(STATUS_SLOT, new CustomItemStack(Material.LIME_STAINED_GLASS_PANE,
                "&a工作中", "&7将在 &e" + Math.max(0, op.getRemainingTicks()) + " &7tick 后产出"));
    }

    @Override
    protected void onStatus(Location l, BlockMenu inv, Status status) {
        if (!inv.hasViewer()) return;
        switch (status) {
            case IDLE -> inv.replaceExistingItem(STATUS_SLOT, IDLE.clone());
            case NO_POWER -> inv.replaceExistingItem(STATUS_SLOT, NO_POWER.clone());
            case NO_SPACE -> inv.replaceExistingItem(STATUS_SLOT, NO_SPACE.clone());
            case PROCESSING -> {
                // 进度显示由 updateProgressDisplay 负责
            }
        }
    }

    /**
     * 破坏时全槽位掉落（跳过边框/状态等染色玻璃装饰板）：
     * 兼容旧布局机器——滞留在原输入/输出槽位的物品随拆卸掉落，不丢失。
     * 这类机器的真实物品（蘑菇/染料/铁轨/方块等）不会是玻璃板，按材质判定即可。
     */
    @Override
    protected void dropItemsOnBreak(Block b, BlockMenu inv) {
        for (int slot = 0; slot < 54; slot++) {
            ItemStack it = inv.getItemInSlot(slot);
            if (it == null || it.getType().isAir()) continue;
            boolean io = slot == INPUT_SLOT || (slot >= OUTPUT_SLOTS[0] && slot <= OUTPUT_SLOTS[OUTPUT_SLOTS.length - 1]);
            if (!io && it.getType().name().endsWith("_STAINED_GLASS_PANE")) continue;
            inv.dropItems(b.getLocation(), new int[]{slot});
        }
    }
}
