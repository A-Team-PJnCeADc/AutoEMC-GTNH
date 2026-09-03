package com.gtnh.autoemc.emc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 服务端保留最近一次 AutoEMC 求值的结果(值 + 每个物品选中的配方 + 各输入槽实际取用的选项+数量),
 * 供 /projecte_autoemc 命令展示与构建"对齐链"(把配方树按引擎选择的配方渲染)。
 * 纯数据,不持有任何运行时强引用。
 */
public final class EmcRuntime {

    /** 单条对齐链的最大节点数(防止组装消息过大) */
    public static final int MAX_CHAIN_NODES = 3000;

    private static volatile Map<ItemKey, Integer> lastValues = new HashMap<>();
    private static volatile Map<ItemKey, EmcRecipe> lastChosen = new HashMap<>();
    private static volatile Map<ItemKey, List<Pick>> lastPicks = new HashMap<>();
    /** 本次运行按同级平均定价的假电路板(describe 的来源标注用) */
    private static volatile Set<ItemKey> lastAveraged = new HashSet<>();

    /** 对齐链上的一个节点:物品 + 引擎选中配方 + 该配方每个输入槽取用的物品与数量 */
    public static final class ChainItem {

        public final ItemKey key;
        /** 配方来源(机器 map 名 / "crafting" / "smelting"),仅展示用 */
        public final String source;
        /** 该配方单次产出的数量 */
        public final int outputQty;
        /** 每个输入槽实际取用的物品与数量(渲染配方树用) */
        public final List<Pick> inputs;

        public ChainItem(ItemKey key, String source, int outputQty, List<Pick> inputs) {
            this.key = key;
            this.source = source;
            this.outputQty = outputQty;
            this.inputs = inputs;
        }
    }

    private EmcRuntime() {}

    public static void capture(Map<ItemKey, Integer> values, Map<ItemKey, EmcRecipe> chosen,
        Map<ItemKey, List<Pick>> picks, Set<ItemKey> averaged) {
        lastValues = new HashMap<>(values);
        lastChosen = new HashMap<>(chosen);
        Map<ItemKey, List<Pick>> picksCopy = new HashMap<>();
        for (Map.Entry<ItemKey, List<Pick>> e : picks.entrySet()) {
            picksCopy.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        lastPicks = picksCopy;
        lastAveraged = averaged == null ? new HashSet<>() : new HashSet<>(averaged);
    }

    /** 返回形如 "EMC=800, 来源=工作台/crafting";无记录返回 null */
    public static String describe(ItemKey key) {
        Integer v = lastValues.get(key);
        if (v == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("EMC=").append(v);
        EmcRecipe r = lastChosen.get(key);
        if (r != null) {
            sb.append(", 来源=")
                .append(EmcRecipe.categoryName(r.category))
                .append('/')
                .append(EmcRecipe.tierName(r.tier))
                .append(' ')
                .append(r.source);
        } else if (lastAveraged.contains(key)) {
            sb.append(", 来源=同级电路板平均");
        } else {
            sb.append(", 来源=缓存");
        }
        return sb.toString();
    }

    /**
     * 从 root 出发沿"引擎选中的配方输入"递归构建对齐链(BFS,按 ItemKey 去重)。
     * inputs 表 = 本次新求值 ∪ 持久化恢复(见 EmcRunner:链与值一样持久,重启/PE 锚定后仍可回放)。
     * root 在表中但没有配方输入(从未被引擎定价)返回 null;
     * 超出 MAX_CHAIN_NODES 截断(截断处节点不再深入)。
     */
    public static List<ChainItem> buildChain(ItemKey root) {
        if (root == null || !hasPicks(root)) {
            return null;
        }
        Map<ItemKey, ChainItem> out = new LinkedHashMap<>();
        Deque<ItemKey> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty() && out.size() < MAX_CHAIN_NODES) {
            ItemKey key = queue.poll();
            if (out.containsKey(key)) {
                continue;
            }
            List<Pick> inputs = lastPicks.get(key);
            if (inputs == null || inputs.isEmpty()) {
                continue;
            }
            EmcRecipe r = lastChosen.get(key);
            String source = r == null ? "autoemc-cache" : r.source;
            int outputQty = r == null ? 1 : r.outputQty;
            out.put(key, new ChainItem(key, source, outputQty, new ArrayList<>(inputs)));
            for (Pick p : inputs) {
                if (!out.containsKey(p.key) && hasPicks(p.key)) {
                    queue.add(p.key);
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    private static boolean hasPicks(ItemKey key) {
        List<Pick> c = lastPicks.get(key);
        return c != null && !c.isEmpty();
    }
}
