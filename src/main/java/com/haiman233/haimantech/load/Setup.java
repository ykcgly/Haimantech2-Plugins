package com.haiman233.haimantech.load;

import com.haiman233.haimantech.HT;
import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * 内容加载编排：按依赖顺序注册 groups → recipe_types → 预加载展示物品 → 各内容文件。
 *
 * <p>顺序与 RSC {@code ProjectAddonLoader} 的 T1(preload)/T2(register)/T3(lateInit) 同构：
 * 先建立物品组与配方类型，再把所有可被 {@code material_type: slimefun} 引用的展示堆
 * 预加载进 {@link HT#PRELOAD}，最后才真正注册，使跨文件前向引用能够正确解析。</p>
 */
public final class Setup {

    private Setup() {}

    /** 含有可被其它配方以 material_type:slimefun 引用的“物品”的文件（需预加载展示堆）。 */
    private static final String[] ITEM_FILES = {
            "items.yml", "recipe_machines.yml", "mat_generators.yml", "generators.yml",
            "solar_generators.yml", "capacitors.yml", "simple_machines.yml",
            "mob_drops.yml", "geo_resources.yml"
    };

    public static void loadAll() {
        long t = System.currentTimeMillis();
        HT.MISSING.clear();

        GroupLoader.load();
        RecipeTypes.load();
        preloadDisplays();

        // 物品类（纯物品 → 掉落 → 地质资源）
        ItemsLoader.load();
        MobDropsLoader.load();
        GeoLoader.load();

        // 菜单（menus.yml）须在机器类之前就绪：机器按"机器 ID == 菜单 ID"应用同名自定义 GUI
        MenusLoader.load();

        // 机器类（电容/简单机器不依赖其它机器，发电机与配方机器可引用任意已预加载物品）
        CapacitorsLoader.load();
        SimpleMachinesLoader.load();
        GeneratorsLoader.load();
        SolarGeneratorsLoader.load();
        MaterialGeneratorsLoader.load();
        RecipeMachinesLoader.load();

        // 代码定义的特殊机器：粘液书皮压缩机（粘液书兜底合成，不耗电）
        com.haiman233.haimantech.customs.HTSlimeBookCompressor.registerMachine();

        // 研究必须最后注册（依赖全部物品已就位）
        ResearchesLoader.load();

        // 矿物生成（依赖物品已注册，且须在预加载缓存清理前解析）
        registerPopulator(GenerationsLoader.load());

        // 缺失项汇总（避免未装软依赖时逐条刷屏）
        report();
        // 释放加载期缓存与中间产物（约 MB 级解析树 + 数千个 ItemStack）
        Yaml.clearCache();
        HT.PRELOAD.clear();
        Read.clearCache();
        HT.plugin.getLogger().info("基础内容加载完成，耗时 " + (System.currentTimeMillis() - t) + "ms");
    }

    /**
     * 第一遍：把各文件中的展示堆加入 {@link HT#PRELOAD}，使后续注册与配方解析能跨文件按 id 引用。
     * 逐条 try/catch 故障隔离，避免单条坏数据（如无效头颅贴图）中断整个加载流程。
     */
    private static void preloadDisplays() {
        for (String file : ITEM_FILES) {
            YamlConfiguration y = Yaml.loadResource(file);
            for (String id : y.getKeys(false)) {
                try {
                    ConfigurationSection s = y.getConfigurationSection(id);
                    if (s == null) continue;
                    ConfigurationSection itemSec = s.getConfigurationSection("item");
                    if (itemSec == null) continue;
                    // 版本过滤：同一 ID 的多个版本条目（DIAOYUJI / VERSION_DIAOYUJI）共用 id_alias，
                    // 预加载表按 effId 后写覆盖，不过滤会留下与服务端不匹配那版的展示堆。
                    if (!RegisterConditions.passVersion(s)) continue;
                    ItemStack display = Read.item(itemSec, false);
                    if (display == null) continue;
                    String effId = s.getString("id_alias", id).toUpperCase(Locale.ROOT);
                    HT.putPreload(effId, display);
                } catch (Exception e) {
                    HT.warn("预加载展示物品 " + file + ":" + id + " 失败，跳过: " + e);
                }
            }
        }
        HT.log("预加载展示物品 " + HT.PRELOAD.size() + " 个");
    }

    /** 注册矿物生成器到所有已加载世界（对应 RSC 主类的做法；新世界不追加，与 RSC 行为一致）。 */
    private static void registerPopulator(java.util.List<com.haiman233.haimantech.customs.HTOrePopulator.Info> infos) {
        if (infos.isEmpty()) return;
        org.bukkit.generator.BlockPopulator pop = new com.haiman233.haimantech.customs.HTOrePopulator(infos);
        for (org.bukkit.World world : org.bukkit.Bukkit.getWorlds()) {
            world.getPopulators().add(pop);
        }
        HT.log("矿物生成器已注册到 " + org.bukkit.Bukkit.getWorlds().size() + " 个世界");
    }

    private static void report() {
        if (HT.MISSING.isEmpty()) return;
        int total = 0;
        StringBuilder sb = new StringBuilder();
        for (var e : HT.MISSING.entrySet()) {
            total += e.getValue();
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append(" x").append(e.getValue());
        }
        HT.plugin.getLogger().warning("以下引用未完全按原样解析（多为未安装对应软依赖，或 1.20.x 旧格式 saveditem 已程序化重建），共 "
                + total + " 处: " + sb);
    }
}
