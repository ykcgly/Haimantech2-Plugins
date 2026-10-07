package com.haiman233.haimantech.customs;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.List;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * 材料生成器（mat_generators.yml）：无需输入，按 tickRate 周期直接产出物品。
 *
 * <p>与 RSC 的 CustomMaterialGenerator + MaterialGeneartorMachineTicker 对齐：
 * 配置里 {@code status} 槽在运行期显示状态板（电力不足/空间不足/生产中），
 * {@code per} 为每刻耗电，{@code tickRate} 为产出一个物品所需的粘液刻数。</p>
 *
 * <p>继承 {@link CustomMaterialGenerator}（RSC 兼容垫片）后，逻辑工艺的
 * 堆叠生成器可按 RSC 兼容分支识别本类配方（MGeneratorRecipe），进入
 * STACKMGENERATOR_LIST；每台仅单一配方，与垫片的单配方字段模型精确对齐。</p>
 */
public class HTMaterialGenerator extends CustomMaterialGenerator {

    private static final ItemStack NO_POWER = new CustomItemStack(Material.RED_STAINED_GLASS_PANE, "&4电力不足", "");
    private static final ItemStack NO_SPACE = new CustomItemStack(Material.ORANGE_STAINED_GLASS_PANE, "&c空间不足", "");
    private static final ItemStack PROCESSING = new CustomItemStack(Material.LIME_STAINED_GLASS_PANE, "&a生产中", "");

    private final int statusSlot;

    public HTMaterialGenerator(ItemGroup group, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe,
                               int[] outputSlots, int energyPerTick, int capacity,
                               List<HTRecipe> recipes, int statusSlot, HTMenu menu) {
        super(group, item, recipeType, recipe, outputSlots, energyPerTick, capacity, recipes, menu);
        this.statusSlot = statusSlot;
    }

    @Override
    protected void decorateExtra(BlockMenuPreset preset) {
        if (statusSlot >= 0) {
            preset.addItem(statusSlot, ChestMenuUtils.getBackground());
            preset.addMenuClickHandler(statusSlot, ChestMenuUtils.getEmptyClickHandler());
        }
    }

    @Override
    protected void onStatus(Location l, BlockMenu inv, Status status) {
        if (statusSlot < 0 || !inv.hasViewer()) return;
        switch (status) {
            case NO_POWER -> inv.replaceExistingItem(statusSlot, NO_POWER.clone());
            case NO_SPACE -> inv.replaceExistingItem(statusSlot, NO_SPACE.clone());
            case PROCESSING -> inv.replaceExistingItem(statusSlot, PROCESSING.clone());
            case IDLE -> inv.replaceExistingItem(statusSlot, menu() != null
                    ? menu().progressItemAt(statusSlot, ChestMenuUtils.getBackground())
                    : ChestMenuUtils.getBackground());
        }
    }

    @Override
    public List<ItemStack> getDisplayRecipes() {
        // 指南展示：速度书 → 各产出，与 RSC 的展示一致
        List<ItemStack> list = new java.util.ArrayList<>();
        int ticks = recipes.isEmpty() ? 0 : recipes.get(0).getTicks();
        ItemStack speed = new CustomItemStack(Material.KNOWLEDGE_BOOK,
                "&a&l速度", "&a&l每 &b&l" + ticks + " &a&l个粘液刻生成一次");
        if (!recipes.isEmpty()) {
            for (HTRecipe.Output out : recipes.get(0).getOutputs()) {
                list.add(speed);
                list.add(out.item().clone());
            }
        }
        return list;
    }
}
