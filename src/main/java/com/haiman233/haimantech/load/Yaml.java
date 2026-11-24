package com.haiman233.haimantech.load;

import com.haiman233.haimantech.HT;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 从 jar 自身资源加载 YAML（content/ 已打包至 jar 根目录，saveditems/ 保留子目录）。
 *
 * <p>加载期按文件名缓存，避免同一份大文件（如 items.yml 1.8w 行）被多个 Loader 重复解析。
 * 全部 Loader 只读不写，共享同一实例安全；加载结束由 {@link Setup#loadAll()} 释放。
 */
public final class Yaml {

    private Yaml() {}

    private static final Map<String, YamlConfiguration> CACHE = new HashMap<>();

    /** 加载 jar 根资源，如 "items.yml"。 */
    public static YamlConfiguration loadResource(String name) {
        return load(name, name);
    }

    /** 净化版配置中的旧格式标记键（供 Read 判定走重建路径）。 */
    public static final String LEGACY_MARK = "legacy_saveditem";

    /**
     * 加载 saveditems/ 下的资源，如 "HM_DEBUG_FISH"。
     * 1.20.x 实体标签格式（meta-type: ENTITY_TAG）在 1.21 无法反序列化，且炸点位于
     * YamlConfiguration 加载期（Bukkit 解析 YAML 时即实例化 ItemStack，CraftMetaEntityTag
     * 解码旧实体 NBT 抛 NoSuchElementException），故先预读文本识别，命中后用原生
     * SnakeYAML 解析为纯 Map，绕开 Bukkit 的对象实例化。
     */
    public static YamlConfiguration loadSavedItem(String name) {
        YamlConfiguration cached = CACHE.get("saveditem:" + name);
        if (cached != null) return cached;
        YamlConfiguration cfg = loadSavedItem0(name);
        CACHE.put("saveditem:" + name, cfg);
        return cfg;
    }

    /** TILE_ENTITY 旧格式的 {@code internal} 值：base64(gzip(NBT))。 */
    private static final Pattern INTERNAL_BLOB = Pattern.compile("internal:\\s*([A-Za-z0-9+/=]+)");
    /** NBT 中名为 "id" 的 TAG_String 字段头：type=8, nameLen=2, "id"。 */
    private static final byte[] ID_TAG = {8, 0, 2, 105, 100};

    private static YamlConfiguration loadSavedItem0(String name) {
        String path = "saveditems/" + name + ".yml";
        String raw = readResourceText(path);
        if (raw != null) {
            if (raw.contains("meta-type: ENTITY_TAG")) {
                return sanitizeLegacyEntitySavedItem(name, raw);
            }
            // 1.20.x 的 TILE_ENTITY 旧格式：internal NBT 的 BlockEntityTag 缺 "id" 时，
            // 1.21+ 反序列化会走 BlockEntity.loadStatic()，读不到 id 就打印
            // "Skipping block entity with invalid type: null" 并丢弃整个方块实体（含 LootTable）。
            // Paper 的补 id 兜底只在写回物品时生效，管不到反序列化阶段，因此这里绕开 Bukkit。
            if (raw.contains("meta-type: TILE_ENTITY") && !blockEntityIdPresent(raw)) {
                return sanitizeLegacyEntitySavedItem(name, raw);
            }
        }
        return doLoad(path, name);
    }

    /** internal NBT 是否自带 id；无 internal 或无法解码时分别按「无需检查」「按缺失」处理。 */
    private static boolean blockEntityIdPresent(String raw) {
        Matcher m = INTERNAL_BLOB.matcher(raw);
        if (!m.find()) return true;
        try {
            byte[] gz = Base64.getDecoder().decode(m.group(1));
            byte[] nbt;
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz));
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                in.transferTo(out);
                nbt = out.toByteArray();
            }
            outer:
            for (int i = 0; i <= nbt.length - ID_TAG.length; i++) {
                for (int j = 0; j < ID_TAG.length; j++) {
                    if (nbt[i + j] != ID_TAG[j]) continue outer;
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            // 解码失败说明数据本身已损坏，保守走重建，避免服务端 NMS 刷错误
            return false;
        }
    }

    /** 旧格式 saveditem 净化：仅提取 item.type / display-name / lore，供 Read 程序化重建。 */
    private static YamlConfiguration sanitizeLegacyEntitySavedItem(String name, String raw) {
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            // 全限定名调用：与本项目 com.haiman233.haimantech.load.Yaml 同名，不能 import
            Object root = new org.yaml.snakeyaml.Yaml().load(raw);
            if (root instanceof Map<?, ?> map && map.get("item") instanceof Map<?, ?> item) {
                if (item.get("type") != null) cfg.set("item.type", String.valueOf(item.get("type")));
                if (item.get("meta") instanceof Map<?, ?> meta) {
                    if (meta.get("display-name") instanceof String dn) {
                        cfg.set("item.meta.display-name", dn);
                    }
                    if (meta.get("lore") instanceof java.util.List<?> lore && !lore.isEmpty()) {
                        cfg.set("item.meta.lore", lore);
                    }
                }
            }
            cfg.set(LEGACY_MARK, true);
        } catch (Exception e) {
            HT.warn("saveditem " + name + " 旧格式净化解析失败: " + e);
        }
        return cfg;
    }

    /** 读取 jar 内资源为 UTF-8 文本；不存在或读取失败返回 null。 */
    private static String readResourceText(String path) {
        InputStream in = HT.plugin.getResource(path);
        if (in == null) return null;
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) > 0) sb.append(buf, 0, n);
            return sb.toString();
        } catch (IOException e) {
            HT.warn("读取 " + path + " 失败: " + e);
            return null;
        }
    }

    private static YamlConfiguration load(String path, String cacheKey) {
        YamlConfiguration cached = CACHE.get(cacheKey);
        if (cached != null) return cached;
        YamlConfiguration cfg = doLoad(path, cacheKey);
        CACHE.put(cacheKey, cfg);
        return cfg;
    }

    /**
     * 未加引号的 {@code version: >= 1.21.2}：SnakeYAML 会把行首 {@code >} 当作折叠块标量
     * 指示符并抛 ScannerException，导致整份大配置加载失败。这里在解析前把这类标量补上单引号，
     * 让配方版本条件既能按示例直写，也可自行加引号（已是字符串/带引号的行不受影响）。
     */
    private static final Pattern UNQUOTED_VERSION = Pattern.compile(
            "^(\\s*version:[ ]*)(>=|<=|==|!=|>|<|=)([^'\"\\r\\n]*)([ \\t]*\\r?)$");

    private static String quoteVersionScalars(String raw) {
        String[] lines = raw.split("\n", -1);
        StringBuilder sb = new StringBuilder(raw.length() + 64);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            String line = lines[i];
            Matcher m = UNQUOTED_VERSION.matcher(line);
            if (m.matches() && !m.group(3).isBlank()) {
                // 行尾注释留在引号外，避免被当成版本表达式的一部分
                String value = m.group(3);
                String comment = "";
                int hash = value.indexOf('#');
                if (hash >= 0) {
                    comment = " " + value.substring(hash);
                    value = value.substring(0, hash);
                }
                sb.append(m.group(1)).append('\'')
                        .append(m.group(2)).append(value.stripTrailing())
                        .append('\'').append(comment).append(m.group(4));
            } else {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static YamlConfiguration doLoad(String path, String cacheKey) {
        String raw = readResourceText(path);
        if (raw == null) {
            HT.missing("资源缺失:" + path);
            return new YamlConfiguration();
        }
        try (StringReader reader = new StringReader(quoteVersionScalars(raw))) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (RuntimeException e) {
            HT.warn("解析 " + path + " 失败: " + e);
            return new YamlConfiguration();
        }
    }

    public static void clearCache() {
        CACHE.clear();
    }
}
