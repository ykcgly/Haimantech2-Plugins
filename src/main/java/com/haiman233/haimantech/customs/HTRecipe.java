package com.haiman233.haimantech.customs;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.inventory.ItemStack;

/**
 * 机器工作配方（recipe_machines.yml 的 recipes 段 / 材料生成器的单一配方）。
 *
 * <p>与 RSC 语义对齐：</p>
 * <ul>
 *   <li>耗时单位为配置里的 {@code seconds}，实际粘液刻 = seconds * 2</li>
 *   <li>每个输入条目可带 {@code noConsume: true}（合成后不消耗该材料）</li>
 *   <li>每个输出条目可带 {@code chance}（默认 100，百分比概率产出）</li>
 *   <li>{@code chooseOne: true} 时在命中的输出中随机取一个</li>
 *   <li>{@code forDisplay: true} 的配方仅用于指南展示，不参与实际匹配</li>
 *   <li>{@code hide: true} 的配方不在指南展示</li>
 *   <li>{@code version} 由加载期判定，不满足的配方根本不会构造成 HTRecipe</li>
 * </ul>
 */
public final class HTRecipe {

    /** 单个输入条目：模板（不含数量语义）+ 需求量 + 是否不消耗。 */
    public record Input(ItemStack template, int amount, boolean noConsume) {}

    /** 单个输出条目：产物 + 产出概率（1-100）。 */
    public record Output(ItemStack item, int chance) {}

    private final int ticks;
    private final List<Input> inputs;
    private final List<Output> outputs;
    private final boolean chooseOne;
    private final boolean forDisplay;
    private final boolean hide;

    public HTRecipe(int seconds, List<Input> inputs, List<Output> outputs,
                    boolean chooseOne, boolean forDisplay, boolean hide) {
        this.ticks = Math.max(0, seconds) * 2;
        this.inputs = List.copyOf(inputs);
        this.outputs = List.copyOf(outputs);
        this.chooseOne = chooseOne;
        this.forDisplay = forDisplay;
        this.hide = hide;
    }

    private HTRecipe(int ticks, List<Input> inputs, List<Output> outputs, boolean chooseOne) {
        this.ticks = Math.max(1, ticks);
        this.inputs = List.copyOf(inputs);
        this.outputs = List.copyOf(outputs);
        this.chooseOne = chooseOne;
        this.forDisplay = false;
        this.hide = false;
    }

    /** 以“粘液刻”直接指定耗时的工厂（材料生成器的 tickRate 不做 ×2 换算）。 */
    public static HTRecipe ofTicks(int ticks, List<Input> inputs, List<Output> outputs, boolean chooseOne) {
        return new HTRecipe(ticks, inputs, outputs, chooseOne);
    }

    public int getTicks() { return ticks; }

    public List<Input> getInputs() { return inputs; }

    public List<Output> getOutputs() { return outputs; }

    public boolean isChooseOne() { return chooseOne; }

    public boolean isForDisplay() { return forDisplay; }

    public boolean isHide() { return hide; }

    /** 无输入配方（材料生成器）判定。 */
    public boolean hasNoInputs() { return inputs.isEmpty(); }

    /**
     * 按概率 rolls 出本配方实际要产出的物品。
     * chooseOne=true 时在命中的产物中随机取一个，否则返回全部命中产物。
     */
    public List<ItemStack> rollOutputs() {
        List<ItemStack> hit = new ArrayList<>();
        for (Output out : outputs) {
            if (matchesChance(out.chance())) hit.add(out.item().clone());
        }
        if (hit.isEmpty() || !chooseOne) return hit;
        int idx = outputs.size() == 1 ? 0 : ThreadLocalRandom.current().nextInt(hit.size());
        return List.of(hit.get(idx));
    }

    private static boolean matchesChance(int chance) {
        if (chance >= 100) return true;
        if (chance < 1) return false;
        return new SecureRandom().nextInt(100) < chance;
    }
}
