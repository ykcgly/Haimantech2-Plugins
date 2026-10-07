package com.haiman233.haimantech.customs;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.inventory.ItemStack;

/**
 * 材料生成器的"RSC 兼容垫片"基类（仅承载识别字段，不改变任何运行时行为）。
 *
 * <p>逻辑工艺（LogiTech）的 {@code RecipeSupporter} 只对<b>完整类名以
 * {@code .CustomMaterialGenerator} 结尾</b>的机器走 RSC 兼容分支
 * （{@code ReflectUtils.isExtendedFrom} 按"FQN 后缀"沿父类链匹配），
 * 通过反射读取本类的四个字段构建 MGeneratorRecipe，
 * 使材料生成器进入<b>堆叠生成器</b>（STACKMGENERATOR_LIST）：</p>
 * <ul>
 *   <li>{@code output}（List&lt;ItemStack&gt;）：产出列表（含数量）</li>
 *   <li>{@code tickRate}（int）：产出一个物品所需的粘液刻数</li>
 *   <li>{@code chances}（List&lt;Integer&gt;）：每个产出的概率；全部 100 时为 null（按原样产出）</li>
 *   <li>{@code chooseOne}（boolean）：命中产出中随机取一个</li>
 * </ul>
 *
 * <p>材料生成器每台只有单一配方（MaterialGeneratorsLoader 保证），与该分支的
 * 单配方字段模型精确对齐；{@code tickRate} 单位与本附属运行时一致（粘液刻），速度不变。</p>
 */
public abstract class CustomMaterialGenerator extends HTMachine {

    /** RSC 兼容字段：产出列表（与配置一致，含数量）。 */
    protected final List<ItemStack> output;
    /** RSC 兼容字段：产出一个物品所需的粘液刻数。 */
    protected final int tickRate;
    /** RSC 兼容字段：每个产出的概率（1-100）；全部为 100 时为 null。 */
    protected final List<Integer> chances;
    /** RSC 兼容字段：命中产出中随机取一个。 */
    protected final boolean chooseOne;

    protected CustomMaterialGenerator(ItemGroup group, SlimefunItemStack item, RecipeType recipeType,
                                      ItemStack[] recipe, int[] outputSlots, int energyPerTick, int capacity,
                                      List<HTRecipe> recipes, HTMenu menu) {
        super(group, item, recipeType, recipe, new int[0], outputSlots, energyPerTick, capacity, 1, recipes, menu);
        HTRecipe single = recipes.isEmpty() ? null : recipes.get(0);
        if (single == null) {
            this.output = List.of();
            this.tickRate = 0;
            this.chances = null;
            this.chooseOne = false;
            return;
        }
        List<HTRecipe.Output> outputs = single.getOutputs();
        List<ItemStack> out = new ArrayList<>(outputs.size());
        List<Integer> chanceList = new ArrayList<>(outputs.size());
        boolean allDefault = true;
        for (HTRecipe.Output o : outputs) {
            out.add(o.item().clone());
            chanceList.add(o.chance());
            if (o.chance() < 100) allDefault = false;
        }
        this.output = List.copyOf(out);
        this.tickRate = single.getTicks();
        this.chances = allDefault ? null : List.copyOf(chanceList);
        this.chooseOne = single.isChooseOne();
    }
}
