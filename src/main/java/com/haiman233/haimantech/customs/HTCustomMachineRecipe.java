package com.haiman233.haimantech.customs;

import java.util.List;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import org.bukkit.inventory.ItemStack;

/**
 * 面向逻辑工艺（LogiTech）堆叠机识别的机器配方载体。
 *
 * <p>类名刻意以 {@code CustomMachineRecipe} 结尾：LogiTech 的
 * {@code RecipeSupporter.transferRSCRecipes} 对该后缀的 MachineRecipe 走 RSC 兼容通道，
 * 读取以下公共字段完成转换，使本附属机器进入堆叠配方机器（STACKMACHINE_LIST）：</p>
 * <ul>
 *   <li>{@code forDisplay}（boolean）：展示配方标记——本附属收集前已过滤，恒为 false</li>
 *   <li>{@code chances}（List&lt;Integer&gt;）：每个产出的概率（1-100），长度与输出数组一致</li>
 *   <li>{@code noConsume}（List&lt;Integer&gt;）：不消耗的输入下标（Iterable&lt;Number&gt; 兼容读取）</li>
 *   <li>{@code chooseOneIfHas}（boolean）：命中产出中随机取一个</li>
 * </ul>
 *
 * <p>注意：LogiTech 反射读取失败（字段缺失）会把整条配方丢弃，因此上述字段必须全部存在。</p>
 */
public class HTCustomMachineRecipe extends MachineRecipe {

    /** RSC 兼容字段：恒为 false（forDisplay 配方在收集前已被过滤）。 */
    public final boolean forDisplay;
    /** RSC 兼容字段：每个产出的概率（1-100），与输出数组一一对应。 */
    public final List<Integer> chances;
    /** RSC 兼容字段：不消耗输入的下标集合（空列表表示全部消耗）。 */
    public final List<Integer> noConsume;
    /** RSC 兼容字段：命中产出中随机取一个。 */
    public final boolean chooseOneIfHas;

    public HTCustomMachineRecipe(int ticks, ItemStack[] input, ItemStack[] output,
                                 List<Integer> chances, List<Integer> noConsume, boolean chooseOne) {
        super(Math.max(0, ticks), input, output);
        this.forDisplay = false;
        this.chances = List.copyOf(chances);
        this.noConsume = List.copyOf(noConsume);
        this.chooseOneIfHas = chooseOne;
    }
}
