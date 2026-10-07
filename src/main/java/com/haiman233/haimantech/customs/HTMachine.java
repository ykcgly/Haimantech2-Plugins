package com.haiman233.haimantech.customs;

import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.core.attributes.MachineProcessHolder;
import io.github.thebusybiscuit.slimefun4.core.attributes.RecipeDisplayItem;
import io.github.thebusybiscuit.slimefun4.core.machines.MachineProcessor;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType;
import io.github.thebusybiscuit.slimefun4.implementation.handlers.SimpleBlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.implementation.operations.CraftingOperation;
import io.github.thebusybiscuit.slimefun4.libraries.dough.blocks.BlockPosition;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Constructor;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.interfaces.InventoryBlock;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

/**
 * 海曼科技院通用配方机器（recipe_machines.yml）。
 *
 * <p>行为与 RSC 的 AdvancedCustomMachine + RecipeMachineTicker 对齐：</p>
 * <ul>
 *   <li>任意输入/输出槽位布局（yml 的 input/output 槽位表）</li>
 *   <li>默认界面与 RSC 默认菜单同布局：上下边框 + 输入/输出槽底纹 + 22 号进度槽</li>
 *   <li>耗电按 {@code energyPerCraft} 每粘液刻扣除；电力不足时暂停（操作保留）</li>
 *   <li>配方匹配与槽位顺序无关、跨槽聚合数量；单个输入条目可 noConsume</li>
 *   <li>产出前检查输出槽容量，装不下不启动（或标记空间不足）</li>
 *   <li>产出概率 chance / chooseOne / forDisplay / hide 与 RSC 同语义</li>
 * </ul>
 *
 * <p>速度（speed）在 RSC 中仅用于物品描述展示，实际耗时由配方 seconds 决定，
 * 这里保持一致并保留字段。</p>
 */
public class HTMachine extends SlimefunItem implements InventoryBlock, EnergyNetComponent,
        MachineProcessHolder<CraftingOperation>, RecipeDisplayItem {

    /** RSC 默认菜单布局常量。 */
    public static final int DEFAULT_PROGRESS_SLOT = 22;
    protected static final int[] DEFAULT_BORDER =
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 13, 31, 36, 37, 38, 39, 40, 41, 42, 43, 44};
    protected static final int[] DEFAULT_BORDER_IN = {9, 10, 11, 12, 18, 21, 27, 28, 29, 30};
    protected static final int[] DEFAULT_BORDER_OUT = {14, 15, 16, 17, 23, 26, 32, 33, 34, 35};

    protected final int[] inputSlots;
    protected final int[] outputSlots;
    private final int energyPerTick;
    private final int capacity;
    private final int speed;
    protected final List<HTRecipe> recipes;
    /** 同名自定义菜单（menus.yml）；无则使用默认布局。 */
    private final HTMenu menu;
    /** 进度槽位：菜单标记优先，否则默认 22。 */
    private final int progressSlot;
    /** 默认进度条物品（黑色玻璃板，与 RSC 默认一致）。 */
    private final ItemStack progressBar = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);

    protected final MachineProcessor<CraftingOperation> processor = new MachineProcessor<>(this);
    /** 运行中操作对应的配方（用于结束时按概率产出）。 */
    private final Map<BlockPosition, HTRecipe> active = new ConcurrentHashMap<>();
    /**
     * 空闲轮询节流：location -> 允许再次扫描配方的最早时间戳(ms)。
     * 防止空转机器每 tick 全表扫描配方（sftiming 占用的主要来源）。
     */
    private final Map<BlockPosition, Long> nextScan = new ConcurrentHashMap<>();
    /** 空闲机器在"有输入但未匹配到配方"时，两次配方扫描之间的最小间隔。 */
    private static final long IDLE_SCAN_INTERVAL_MS = 1000L;

    public HTMachine(ItemGroup group, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe,
                     int[] inputSlots, int[] outputSlots, int energyPerTick, int capacity, int speed,
                     List<HTRecipe> recipes, HTMenu menu) {
        this(group, item, recipeType, recipe, inputSlots, outputSlots, energyPerTick, capacity, speed,
                recipes, menu, menu != null && menu.progressSlot() >= 0 ? menu.progressSlot() : DEFAULT_PROGRESS_SLOT);
    }

    /** 进度槽可显式指定的内部构造（固定布局子类如转化机使用）。 */
    protected HTMachine(ItemGroup group, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe,
                        int[] inputSlots, int[] outputSlots, int energyPerTick, int capacity, int speed,
                        List<HTRecipe> recipes, HTMenu menu, int progressSlot) {
        super(group, item, recipeType, recipe);
        this.inputSlots = inputSlots;
        this.outputSlots = outputSlots;
        this.energyPerTick = Math.max(0, energyPerTick);
        this.capacity = Math.max(0, capacity);
        this.speed = Math.max(1, speed);
        this.recipes = List.copyOf(recipes);
        this.menu = menu;
        this.progressSlot = progressSlot;
        if (menu != null && menu.progressBar() != null) {
            processor.setProgressBar(menu.progressBar());
        } else {
            processor.setProgressBar(progressBar);
        }

        addItemHandler(new SimpleBlockBreakHandler() {
            @Override
            public void onBlockBreak(Block b) {
                BlockMenu inv = StorageCacheUtils.getMenu(b.getLocation());
                if (inv != null) {
                    dropItemsOnBreak(b, inv);
                }
                processor.endOperation(b.getLocation());
                active.remove(new BlockPosition(b.getLocation()));
                nextScan.remove(new BlockPosition(b.getLocation()));
            }
        });
        addItemHandler(new BlockTicker() {
            @Override
            public boolean isSynchronized() {
                return false;
            }

            @Override
            public void tick(Block b, SlimefunItem sf,
                             com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData data) {
                HTMachine.this.tick(b);
            }
        });
        // 标题与布局由菜单决定；无菜单时回退机器名 + 默认布局（对应 RSC MachineTicker.createPreset）
        String title = menu != null && menu.title() != null ? menu.title() : getItemName();
        createPreset(this, title, preset -> constructMenu(preset));
    }

    /** 供子类（状态槽恢复等）访问同名菜单。 */
    protected HTMenu menu() {
        return menu;
    }

    /** 当前生效的进度槽位。 */
    protected int progressSlot() {
        return progressSlot;
    }

    /** 构建默认界面（与 RSC 无自定义菜单时的布局一致）；供其它机器类复用。 */
    public static void buildDefaultMenu(BlockMenuPreset preset, ItemStack progressBar) {
        for (int i : DEFAULT_BORDER) {
            preset.addItem(i, ChestMenuUtils.getBackground());
            preset.addMenuClickHandler(i, ChestMenuUtils.getEmptyClickHandler());
        }
        for (int i : DEFAULT_BORDER_IN) {
            preset.addItem(i, ChestMenuUtils.getInputSlotTexture());
            preset.addMenuClickHandler(i, ChestMenuUtils.getEmptyClickHandler());
        }
        for (int i : DEFAULT_BORDER_OUT) {
            preset.addItem(i, ChestMenuUtils.getOutputSlotTexture());
            preset.addMenuClickHandler(i, ChestMenuUtils.getEmptyClickHandler());
        }
        preset.addItem(DEFAULT_PROGRESS_SLOT, progressBar.clone());
        preset.addMenuClickHandler(DEFAULT_PROGRESS_SLOT, ChestMenuUtils.getEmptyClickHandler());
    }

    /**
     * 构建机器界面：有同名菜单优先应用菜单装饰，否则回退默认布局；
     * 之后统一调用 {@link #decorateExtra} 让子类追加专属槽（如材料生成器的状态槽）。
     * 关键点：无论是否有自定义菜单，子类专属处理器都会被 {@code decorateExtra} 注册——
     * 这正是此前状态槽(9)能被玩家取下的根因修复点（旧逻辑在"有菜单"分支直接走
     * {@code menu.apply} 而跳过了状态槽处理器）。
     */
    protected void constructMenu(BlockMenuPreset preset) {
        if (menu != null) {
            menu.apply(preset);
        } else {
            buildDefaultMenu(preset, progressBar);
        }
        decorateExtra(preset);
    }

    /** 子类追加专属槽处理器（默认无操作）。例如 HTMaterialGenerator 用来注册状态槽的不可点击处理器。 */
    protected void decorateExtra(BlockMenuPreset preset) {
    }

    // ------------------------------------------------------------------ tick

    protected void tick(Block b) {
        Location l = b.getLocation();
        BlockMenu inv = StorageCacheUtils.getMenu(l);
        if (inv == null) return;

        CraftingOperation op = processor.getOperation(l);
        if (op == null) {
            // 空闲快速门：仅对"有输入槽且输入全空"的配方机生效——这类机器绝不可能匹配配方，
            // 直接跳过，避免每个空闲机器每 tick 全表扫描配方（sftiming 占用的主要来源）。
            // 注意：材料生成器 inputSlots 本就为空（无输入、靠空输入配方持续产出），不能在此跳过。
            if (inputSlots.length > 0 && isInputEmpty(inv)) {
                return;
            }
            // 空闲轮询节流：本秒已扫描过且未匹配到配方的机器跳过本次，限制每秒最多扫描一次。
            long now = System.currentTimeMillis();
            Long next = nextScan.get(new BlockPosition(l));
            if (next != null && now < next) {
                return;
            }
            if (energyPerTick > 0 && getCharge(l) < energyPerTick) {
                onStatus(l, inv, Status.NO_POWER);
                nextScan.put(new BlockPosition(l), now + IDLE_SCAN_INTERVAL_MS);
                return;
            }
            HTRecipe recipe = findAndConsumeRecipe(inv);
            if (recipe == null) {
                onStatus(l, inv, Status.IDLE);
                nextScan.put(new BlockPosition(l), now + IDLE_SCAN_INTERVAL_MS);
                return;
            }
            ItemStack[] results = recipe.getOutputs().stream()
                    .map(HTRecipe.Output::item).map(ItemStack::clone).toArray(ItemStack[]::new);
            // 此 Slimefun 版本的 CraftingOperation 校验 ingredients 非空：原先传空数组会让
            // 每次开工抛 IllegalArgumentException，且此时输入已在 findAndConsumeRecipe 中被
            // 消耗，造成材料白损。有输入的配方传输入模板副本；材料生成器无输入，用 AIR 占位。
            ItemStack[] ingredients = recipe.getInputs().isEmpty()
                    ? new ItemStack[]{new ItemStack(Material.AIR)}
                    : recipe.getInputs().stream().map(in -> in.template().clone()).toArray(ItemStack[]::new);
            op = new CraftingOperation(ingredients, results, Math.max(1, recipe.getTicks()));
            processor.startOperation(l, op);
            active.put(new BlockPosition(l), recipe);
            nextScan.remove(new BlockPosition(l));
        }

        if (energyPerTick > 0 && getCharge(l) < energyPerTick) {
            onStatus(l, inv, Status.NO_POWER);
            return;
        }
        if (energyPerTick > 0) removeCharge(l, energyPerTick);

        if (!op.isFinished()) {
            onStatus(l, inv, Status.PROCESSING);
            if (inv.hasViewer()) {
                updateProgressDisplay(inv, op);
            }
            op.addProgress(1);
            return;
        }

        HTRecipe recipe = active.remove(new BlockPosition(l));
        List<ItemStack> results = recipe != null ? recipe.rollOutputs() : List.of();
        boolean pushed = pushOutputs(inv, results);
        if (!pushed) {
            onStatus(l, inv, Status.NO_SPACE);
        } else if (inv.hasViewer()) {
            // 闲置恢复：有菜单时优先菜单装饰物品，否则闲置面板（与 RSC 恢复语义一致）
            inv.replaceExistingItem(progressSlot,
                    menu != null ? menu.progressItemAt(progressSlot, progressBar) : idleProgressItem());
        }
        processor.endOperation(l);
    }

    /**
     * 破坏机器时掉落内容物；默认掉输入+输出槽。
     * 子类可覆写（转化机覆写为全槽位掉落，便于旧布局机器里滞留物品的迁移回收）。
     */
    protected void dropItemsOnBreak(Block b, BlockMenu inv) {
        inv.dropItems(b.getLocation(), getInputSlots());
        inv.dropItems(b.getLocation(), getOutputSlots());
    }

    /**
     * 进度显示（仅在有旁观者时每刻调用）。
     * 默认写入黑色进度条；子类可覆写（转化机显示绿色"工作中 X tick 后产出"面板）。
     */
    protected void updateProgressDisplay(BlockMenu inv, CraftingOperation op) {
        processor.updateProgressBar(inv, progressSlot, op);
    }

    /** 闲置/完成时进度槽恢复显示的物品；默认黑色进度条，子类可覆写（转化机为橙色"空闲中"面板）。 */
    protected ItemStack idleProgressItem() {
        return progressBar.clone();
    }

    @Override
    public MachineProcessor<CraftingOperation> getMachineProcessor() {
        return processor;
    }

    /** 状态槽钩子：默认机器无状态槽，材料生成器覆写。 */
    protected void onStatus(Location l, BlockMenu inv, Status status) {
        // 默认无操作
    }

    protected enum Status {IDLE, PROCESSING, NO_POWER, NO_SPACE}

    /** 输入槽是否全空（用于空闲快速判定，O(输入槽数) 而非 O(配方数)）。 */
    private boolean isInputEmpty(BlockMenu inv) {
        for (int slot : inputSlots) {
            ItemStack it = inv.getItemInSlot(slot);
            if (it != null && !it.getType().isAir()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------- 配方匹配

    /**
     * 找到第一条可执行的配方并立即消耗输入（与 RSC findNextRecipe 的消耗时机一致）。
     * 匹配与槽位顺序无关、可跨槽聚合数量。
     */
    protected HTRecipe findAndConsumeRecipe(BlockMenu inv) {
        for (HTRecipe recipe : recipes) {
            if (recipe.isForDisplay()) continue;

            if (recipe.getInputs().isEmpty()) {
                // 材料生成器型：无输入，只看输出空间
                if (fits(inv, itemsOf(recipe))) {
                    return recipe;
                }
                continue;
            }

            // 匹配：为每个输入条目在输入槽中聚合找够数量
            Map<Integer, Integer> consumed = new HashMap<>();
            if (!matchInputs(inv, recipe, consumed)) continue;
            if (!fits(inv, itemsOf(recipe))) continue;

            consumeInputs(inv, consumed);
            return recipe;
        }
        return null;
    }

    private static List<ItemStack> itemsOf(HTRecipe recipe) {
        return recipe.getOutputs().stream().map(HTRecipe.Output::item).toList();
    }

    private boolean matchInputs(BlockMenu inv, HTRecipe recipe, Map<Integer, Integer> consumed) {
        Map<Integer, Integer> taken = new HashMap<>();
        for (HTRecipe.Input in : recipe.getInputs()) {
            int left = in.amount();
            for (int slot : inputSlots) {
                if (left <= 0) break;
                ItemStack cur = inv.getItemInSlot(slot);
                if (cur == null || cur.getType().isAir()) continue;
                int free = cur.getAmount() - taken.getOrDefault(slot, 0);
                if (free <= 0) continue;
                if (!isSimilar(cur, in.template())) continue;
                int take = Math.min(free, left);
                taken.merge(slot, take, Integer::sum);
                left -= take;
            }
            if (left > 0) return false;
        }
        // 只记录真正需要消耗的槽位（noConsume 条目不参与消耗）
        for (HTRecipe.Input in : recipe.getInputs()) {
            if (in.noConsume()) continue;
            int left = in.amount();
            for (int slot : inputSlots) {
                if (left <= 0) break;
                int free = taken.getOrDefault(slot, 0) - consumed.getOrDefault(slot, 0);
                if (free <= 0) continue;
                int take = Math.min(free, left);
                consumed.merge(slot, take, Integer::sum);
                left -= take;
            }
        }
        return true;
    }

    private static void consumeInputs(BlockMenu inv, Map<Integer, Integer> consumed) {
        for (Map.Entry<Integer, Integer> e : consumed.entrySet()) {
            if (e.getValue() > 0) inv.consumeItem(e.getKey(), e.getValue());
        }
    }

    private static boolean isSimilar(ItemStack a, ItemStack b) {
        return SlimefunUtils.isItemSimilar(a, b, true, false);
    }

    /** 输出槽容量检查（不改动内容物）。 */
    protected boolean fits(BlockMenu inv, List<ItemStack> outputs) {
        List<Integer> empty = new ArrayList<>();
        List<int[]> partial = new ArrayList<>(); // {slot, 剩余可叠}
        for (int slot : outputSlots) {
            ItemStack cur = inv.getItemInSlot(slot);
            if (cur == null || cur.getType().isAir()) empty.add(slot);
            else partial.add(new int[]{slot, cur.getMaxStackSize() - cur.getAmount()});
        }
        int emptyIdx = 0;
        for (ItemStack out : outputs) {
            int left = out.getAmount();
            int max = Math.max(1, out.getMaxStackSize());
            for (int[] p : partial) {
                if (p[1] <= 0) continue;
                ItemStack cur = inv.getItemInSlot(p[0]);
                if (cur == null || cur.getType().isAir() || !isSimilar(cur, out)) continue;
                int canAdd = Math.min(p[1], left);
                left -= canAdd;
                p[1] -= canAdd;
                if (left <= 0) break;
            }
            while (left > 0 && emptyIdx < empty.size()) {
                left -= max;
                emptyIdx++;
            }
            if (left > 0) return false;
        }
        return true;
    }

    /** 把结果推入输出槽；返回是否有物品被成功放入。 */
    protected boolean pushOutputs(BlockMenu inv, List<ItemStack> results) {
        boolean any = false;
        for (ItemStack result : results) {
            ItemStack rest = inv.pushItem(result.clone(), outputSlots);
            if (rest == null || rest.getType().isAir() || rest.getAmount() < result.getAmount()) any = true;
        }
        return any || results.isEmpty();
    }

    // -------------------------------------------------------------- 接口实现

    @Override
    public int[] getInputSlots() {
        return inputSlots;
    }

    @Override
    public int[] getOutputSlots() {
        return outputSlots;
    }

    @Override
    public EnergyNetComponentType getEnergyComponentType() {
        return EnergyNetComponentType.CONSUMER;
    }

    @Override
    public int getCapacity() {
        return capacity;
    }

    public int getSpeed() {
        return speed;
    }

    public int getEnergyPerTick() {
        return energyPerTick;
    }

    /**
     * 逻辑工艺（LogiTech）堆叠机读取的每刻耗电：tryGetMachineEnergy 优先反射
     * {@code getEnergyConsumption()} 方法分支（其次才是 energyPerTick 字段兜底）。
     */
    public int getEnergyConsumption() {
        return energyPerTick;
    }

    /** LogiTech {@code MGeneratorRecipe(int, ItemStack[], ItemStack[])} 构造器；未安装逻辑工艺时为 null。 */
    private static volatile Constructor<?> logiTechGeneratorCtor;
    private static volatile boolean logiTechCtorResolved;

    private static Constructor<?> logiTechGeneratorCtor() {
        if (!logiTechCtorResolved) {
            synchronized (HTMachine.class) {
                if (!logiTechCtorResolved) {
                    try {
                        Class<?> cls = Class.forName(
                                "me.matl114.logitech.utils.UtilClass.RecipeClass.MGeneratorRecipe");
                        logiTechGeneratorCtor =
                                cls.getConstructor(int.class, ItemStack[].class, ItemStack[].class);
                    } catch (Throwable t) {
                        logiTechGeneratorCtor = null;
                    }
                    logiTechCtorResolved = true;
                }
            }
        }
        return logiTechGeneratorCtor;
    }

    /**
     * 是否满足"材料生成器"配方语义（可进逻辑工艺<b>堆叠生成器</b>）：
     * 全部工作配方的输入<b>全部不消耗</b>（催化剂）、产出全部 100% 概率且非 chooseOne。
     * MGeneratorRecipe 不携带概率/chooseOne 元数据，StackMGenerator 的检测槽也不消耗输入，
     * 因此概率非 100% 或 chooseOne 或有真实消耗输入的机器必须留在堆叠配方机器（概率语义才能保留）。
     */
    private boolean isMaterialGeneratorRecipes() {
        boolean any = false;
        for (HTRecipe recipe : recipes) {
            if (recipe.isForDisplay()) continue;
            any = true;
            for (HTRecipe.Input input : recipe.getInputs()) {
                if (!input.noConsume()) return false;
            }
            if (recipe.isChooseOne()) return false;
            for (HTRecipe.Output output : recipe.getOutputs()) {
                if (output.chance() != 100) return false;
            }
        }
        return any;
    }

    /**
     * 逻辑工艺（LogiTech）堆叠机的配方识别入口：其 RecipeSupporter 对未知机器
     * 优先反射调用 {@code getMachineRecipes()} 方法收集 MachineRecipe 列表。
     *
     * <p>按配方语义自动分流（实测定案：输入不消耗的机器属于材料生成器，须进堆叠生成器）：</p>
     * <ul>
     *   <li><b>材料生成器语义</b>（全部工作配方输入不消耗、产出全 100%、非 chooseOne）：
     *       构造 LogiTech 的 {@code MGeneratorRecipe}（运行时反射，无编译依赖）——其
     *       {@code RecipeSupporter} 按类型直通（transferRSCRecipes 仅拦截
     *       CustomMachineRecipe 后缀），机器进入<b>堆叠生成器</b>（STACKMGENERATOR_LIST）；
     *       StackMGenerator 把机器物品放入机器槽、催化剂放入检测槽（不消耗），产出数=机器数×效率。
     *       逻辑工艺未安装或反射失败时降级为下述堆叠配方机器通道。</li>
     *   <li><b>常规配方</b>：转为 {@link HTCustomMachineRecipe}——类名以
     *       {@code CustomMachineRecipe} 结尾，命中 RSC 兼容通道，
     *       noConsume（催化剂）/ 产出概率 / chooseOne 语义完整保留，
     *       机器进入堆叠配方机器（STACKMACHINE_LIST）。</li>
     * </ul>
     * 时间单位一致（本类 ticks 即 Slimefun 刻），速度与耗电不变。
     */
    public List<MachineRecipe> getMachineRecipes() {
        List<MachineRecipe> list = new ArrayList<>(recipes.size());
        Constructor<?> mgCtor = isMaterialGeneratorRecipes() ? logiTechGeneratorCtor() : null;
        for (HTRecipe recipe : recipes) {
            if (recipe.isForDisplay()) continue;

            List<HTRecipe.Input> inputs = recipe.getInputs();
            ItemStack[] in = new ItemStack[inputs.size()];
            List<Integer> noConsume = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                HTRecipe.Input input = inputs.get(i);
                ItemStack template = input.template().clone();
                template.setAmount(Math.max(1, input.amount()));
                in[i] = template;
                if (input.noConsume()) noConsume.add(i);
            }

            List<HTRecipe.Output> outputs = recipe.getOutputs();
            ItemStack[] out = new ItemStack[outputs.size()];
            List<Integer> chances = new ArrayList<>(outputs.size());
            for (int i = 0; i < outputs.size(); i++) {
                HTRecipe.Output output = outputs.get(i);
                out[i] = output.item().clone();
                chances.add(output.chance());
            }

            if (mgCtor != null) {
                try {
                    list.add((MachineRecipe) mgCtor.newInstance(recipe.getTicks(), in, out));
                    continue;
                } catch (Throwable t) {
                    mgCtor = null; // 反射失败：本机降级为堆叠配方机器通道
                }
            }
            list.add(new HTCustomMachineRecipe(recipe.getTicks(), in, out, chances, noConsume, recipe.isChooseOne()));
        }
        return list;
    }

    @Override
    public List<ItemStack> getDisplayRecipes() {
        // 指南展示：每条配方以 首输入 → 逐个输出 成对展示；隐藏配方不展示
        List<ItemStack> list = new ArrayList<>();
        for (HTRecipe recipe : recipes) {
            if (recipe.isHide()) continue;
            ItemStack input = recipe.getInputs().isEmpty()
                    ? new ItemStack(Material.KNOWLEDGE_BOOK)
                    : recipe.getInputs().get(0).template().clone();
            for (HTRecipe.Output out : recipe.getOutputs()) {
                list.add(input);
                list.add(out.item().clone());
            }
        }
        return list;
    }
}
