package com.gtnh.autoemc.emc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.item.Item;

import com.gtnh.autoemc.api.recipe.RecipeSource;

/**
 * emc-values.json 本地缓存。
 * 结构:{"schemaVersion":2,"fingerprint":"<sha256>","values":{"注册名@damage":emc,...},
 * "chains":{"注册名@damage":["child1",...],...}}
 * values:启动时若指纹一致则预载这些值,只计算缺少的物品(diff),不从头全量求值。
 * chains:每个 AutoEMC 定价物品「引擎选中配方各输入槽取用的物品」,与值配套,供
 * /projecte_autoemc view 回放对齐链。链跨指纹累积:值一旦进 PE/缓存即不再重算,
 * 链也要在之后任意一次启动仍可回放。
 */
public final class ValueStore {

    /** 公式语义版本:改了求值规则就 +1,强制全量重算 */
    public static final int FORMULA_VERSION = 20;
    private static final int SCHEMA = 2;

    private static final Pattern FINGERPRINT_PATTERN = Pattern.compile("\"fingerprint\"\\s*:\\s*\"([0-9a-f]{16,64})\"");
    private static final Pattern ENTRY_PATTERN = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\\d+)");
    private static final Pattern CHAIN_ENTRY_PATTERN = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\[([^\\]]*)\\]");

    private ValueStore() {}

    /** 指纹:配方集合(数量级)+ 配置 + 公式版本。配方大改/配置变化 -> 指纹变化 -> 全量重算 */
    public static String computeFingerprint(EmcStats stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("formula=")
            .append(FORMULA_VERSION)
            .append('\n');
        // 配方集合指纹 = 每个配方源自己声明的指纹行(按注册顺序拼接,内置三源与历史
        // craft/smelt/gtmaps+map 行逐字节一致)。新增配方源时,若它贡献的配方集合可能变化,
        // 必须在 RecipeSource.fingerprintLines 里给出随集合变化的行,否则缓存指纹覆盖不到
        // 该源 -> 命中旧缓存静默沿用旧值(契约见 RecipeSource#fingerprintLines)。
        for (RecipeSource src : RecipeCollector.sources()) {
            for (String line : src.fingerprintLines(stats)) {
                sb.append(line)
                    .append('\n');
            }
        }
        List<String> cfg = new ArrayList<>();
        for (String s : AutoEmcConfig.multiMaps) {
            cfg.add("multi:" + s);
        }
        for (String s : AutoEmcConfig.steamMaps) {
            cfg.add("steam:" + s);
        }
        java.util.Collections.sort(cfg);
        for (String s : cfg) {
            sb.append(s)
                .append('\n');
        }
        sb.append("steamMaxEUt=")
            .append(AutoEmcConfig.steamMaxEUt)
            .append('\n');
        sb.append("unpricedIsZero=")
            .append(AutoEmcConfig.unpricedIsZero)
            .append('\n');
        return sha256(sb.toString());
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }

    /** 读缓存文件里的指纹;文件缺失/损坏返回空串 */
    public static String readFingerprint(File file) {
        String content = readFile(file);
        if (content == null) {
            return "";
        }
        Matcher m = FINGERPRINT_PATTERN.matcher(content);
        return m.find() ? m.group(1) : "";
    }

    /** 读缓存值;文件缺失/损坏/条目物品不存在则跳过。返回空表而非异常 */
    public static Map<ItemKey, Integer> load(File file) {
        Map<ItemKey, Integer> values = new HashMap<>();
        String content = readFile(file);
        if (content == null) {
            return values;
        }
        Matcher m = ENTRY_PATTERN.matcher(content);
        while (m.find()) {
            String key = m.group(1);
            int value;
            try {
                value = Integer.parseInt(m.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            if (value <= 0) {
                continue;
            }
            ItemKey itemKey = parseKey(key);
            if (itemKey != null) {
                values.put(itemKey, value);
            }
        }
        return values;
    }

    /**
     * 读持久化对齐链(每个定价物品的配方输入,含数量)。与指纹无关:值一旦进 PE/缓存就定格,
     * 链也要跨启动/跨指纹回放(指纹变化只影响新缺失物品的计算)。损坏/缺失返回空表。
     * 条目格式:"child注册名@meta*qty"。
     */
    public static Map<ItemKey, List<Pick>> loadChains(File file) {
        Map<ItemKey, List<Pick>> chains = new HashMap<>();
        String content = readFile(file);
        if (content == null) {
            return chains;
        }
        int ci = content.indexOf("\"chains\"");
        if (ci < 0) {
            return chains; // 老版本缓存(schema 1)没有 chains 段
        }
        int open = content.indexOf('{', ci);
        if (open < 0) {
            return chains;
        }
        int depth = 1;
        int i = open + 1;
        while (i < content.length() && depth > 0) {
            char c = content.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
            i++;
        }
        if (depth != 0) {
            return chains; // 结构损坏
        }
        String block = content.substring(open + 1, i - 1);
        Matcher m = CHAIN_ENTRY_PATTERN.matcher(block);
        while (m.find()) {
            ItemKey key = parseKey(m.group(1));
            if (key == null) {
                continue;
            }
            List<Pick> children = new ArrayList<>();
            String body = m.group(2)
                .trim();
            if (!body.isEmpty()) {
                for (String part : body.split(",")) {
                    String s = part.trim();
                    if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                        Pick pick = parsePick(s.substring(1, s.length() - 1));
                        if (pick != null) {
                            children.add(pick);
                        }
                    }
                }
            }
            if (!children.isEmpty()) {
                chains.put(key, children);
            }
        }
        return chains;
    }

    /** "注册名@meta*qty" -> Pick;数量缺省 1;解析失败返回 null */
    private static Pick parsePick(String s) {
        int star = s.lastIndexOf('*');
        String keyPart = s;
        int qty = 1;
        if (star > 0 && star < s.length() - 1) {
            keyPart = s.substring(0, star);
            try {
                qty = Integer.parseInt(s.substring(star + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (qty <= 0) {
            qty = 1;
        }
        ItemKey key = parseKey(keyPart);
        return key == null ? null : new Pick(key, qty);
    }

    private static ItemKey parseKey(String key) {
        int at = key.lastIndexOf('@');
        if (at <= 0 || at == key.length() - 1) {
            return null;
        }
        String name = key.substring(0, at);
        int damage;
        try {
            damage = Integer.parseInt(key.substring(at + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        Object obj = Item.itemRegistry.getObject(name);
        if (!(obj instanceof Item)) {
            return null;
        }
        return new ItemKey((Item) obj, damage);
    }

    /** 写缓存(values 只存 >0 的;chains 只存有输入的条目,格式 child*qty) */
    public static void save(File file, String fingerprint, Map<ItemKey, Integer> values,
        Map<ItemKey, List<Pick>> chains) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return;
            }
            TreeMap<String, Integer> sorted = new TreeMap<>();
            for (Map.Entry<ItemKey, Integer> e : values.entrySet()) {
                if (e.getValue() > 0) {
                    sorted.put(keyOf(e.getKey()), e.getValue());
                }
            }
            TreeMap<String, List<String>> sortedChains = new TreeMap<>();
            if (chains != null) {
                for (Map.Entry<ItemKey, List<Pick>> e : chains.entrySet()) {
                    if (e.getValue() == null || e.getValue()
                        .isEmpty()) {
                        continue;
                    }
                    List<String> entries = new ArrayList<>(
                        e.getValue()
                            .size());
                    for (Pick child : e.getValue()) {
                        entries.add(keyOf(child.key) + "*" + child.qty);
                    }
                    sortedChains.put(keyOf(e.getKey()), entries);
                }
            }
            StringBuilder sb = new StringBuilder(64 + sorted.size() * 24 + sortedChains.size() * 48);
            sb.append("{\n  \"schemaVersion\": ")
                .append(SCHEMA)
                .append(",\n");
            sb.append("  \"fingerprint\": \"")
                .append(fingerprint)
                .append("\",\n");
            sb.append("  \"values\": {");
            boolean first = true;
            for (Map.Entry<String, Integer> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('\n')
                    .append("    \"")
                    .append(e.getKey())
                    .append("\": ")
                    .append(e.getValue());
            }
            if (!first) {
                sb.append('\n');
            }
            sb.append("  },\n");
            sb.append("  \"chains\": {");
            first = true;
            for (Map.Entry<String, List<String>> e : sortedChains.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('\n')
                    .append("    \"")
                    .append(e.getKey())
                    .append("\": [");
                boolean cf = true;
                for (String child : e.getValue()) {
                    if (!cf) {
                        sb.append(',');
                    }
                    cf = false;
                    sb.append('"')
                        .append(child)
                        .append('"');
                }
                sb.append(']');
            }
            if (!first) {
                sb.append('\n');
            }
            sb.append("  }\n}\n");

            try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                w.write(sb.toString());
            }
        } catch (IOException e) {
            // 缓存写失败不影响主流程
        }
    }

    private static String keyOf(ItemKey key) {
        return Item.itemRegistry.getNameForObject(key.item) + "@" + key.damage;
    }

    private static String readFile(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line)
                    .append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }
}
