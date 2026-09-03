package com.gtnh.autoemc.emc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import moze_intel.projecte.api.proxy.IEMCProxy;

/**
 * 递归 EMC 求值器。
 *
 * 规则:
 * - 已被 ProjectE 定价的物品(含玩家 /setemc、custom_emc.json 手动设置)是锚点,永不覆盖;
 * - 没有产出配方的原材料按 0 计(unpricedIsZero=true 时,0 也会作为成本参与);
 * - 配方选择:(工作台 > 单方块机器 > 多方块),同级选低等级(蒸汽 < ULV < LV …),再比总成本;
 * - 递归环(配方互相依赖)使该条边失效,不会自我抬价;最终仍无法定价的物品按 0。
 */
public final class EmcEngine {

    /** 该值仅表示"递归进行中不可用"的边,不对外暴露 */
    private static final int UNKNOWN = -1;

    private final Map<ItemKey, List<EmcRecipe>> producers;
    private final IEMCProxy proxy;

    /** 所有已求出的值(锚点/预载缓存/新算) */
    private final Map<ItemKey, Integer> known = new HashMap<>();
    /** 预载缓存(JSON)里已有的 key,用于统计"本次新增" */
    private final java.util.Set<ItemKey> preloaded = new java.util.HashSet<>();
    /** PE hasValue 记忆 */
    private final Map<ItemKey, Boolean> peHasCache = new HashMap<>();
    /** 为每个物品选中的配方(日志用) */
    private final Map<ItemKey, EmcRecipe> chosen = new HashMap<>();
    /** 为每个物品选中配方时,每个输入槽实际取用的选项+数量(对齐配方树用) */
    private final Map<ItemKey, List<Pick>> pickedOptions = new HashMap<>();
    /** 按"同级平均"定价的假电路板(无产出配方,非 producers 成员;最终值同样要注册) */
    private final Set<ItemKey> circuitAveraged = new HashSet<>();
    /** GTMoreEMC mass-seeding:GT material forms priced directly (mass*72*form multiplier). */
    private final Map<ItemKey, Integer> seeded = new HashMap<>();

    public EmcEngine(Map<ItemKey, List<EmcRecipe>> producers, IEMCProxy proxy) {
        this.producers = producers;
        this.proxy = proxy;
    }

    /** 把上次缓存的值(数量>0)预载为已知,不求值它们;已被 PE(含玩家手动)定价的跳过 */
    public void preload(Map<ItemKey, Integer> cached) {
        for (Map.Entry<ItemKey, Integer> e : cached.entrySet()) {
            if (e.getValue() <= 0 || peHas(e.getKey())) {
                continue;
            }
            known.put(e.getKey(), e.getValue());
            preloaded.add(e.getKey());
        }
    }

    public boolean isPreloaded(ItemKey key) {
        return preloaded.contains(key);
    }

    /**
     * 注入 GTMoreEMC 质量定价种子(preload 之后调用,覆盖缓存旧值):形态物品直接按
     * 质量×72×形态系数定值(known 命中优先于配方求值),不覆盖 PE 已锚定的物品。
     * 返回实际注入条数(>0 且非 PE 锚点)。
     */
    public int addSeeds(Map<ItemKey, Integer> seeds) {
        int added = 0;
        for (Map.Entry<ItemKey, Integer> e : seeds.entrySet()) {
            Integer v = e.getValue();
            if (v == null || v <= 0) {
                continue;
            }
            if (peHas(e.getKey())) {
                continue;
            }
            known.put(e.getKey(), v);
            seeded.put(e.getKey(), v);
            added++;
        }
        return added;
    }

    /** 该 key 是否为本次质量定价种子(形态物品,无 chosen/picks,是叶子节点) */
    public boolean isSeeded(ItemKey key) {
        return seeded.containsKey(key);
    }

    /** 该物品当前是否已被 PE 定价(含玩家手动设置)→ 属于"锚点",不算我们的成果 */
    public boolean isAnchoredByPe(ItemKey key) {
        return peHas(key);
    }

    public EmcRecipe chosenFor(ItemKey key) {
        return chosen.get(key);
    }

    /** 该物品选中配方时各输入槽实际取用的选项+数量;从未求值(PE 锚点/预载缓存)返回 null */
    public List<Pick> picksOf(ItemKey key) {
        return pickedOptions.get(key);
    }

    public Integer knownValue(ItemKey key) {
        return known.get(key);
    }

    private boolean peHas(ItemKey key) {
        Boolean b = peHasCache.get(key);
        if (b == null) {
            b = proxy.hasValue(key.toStack());
            peHasCache.put(key, b);
        }
        return b;
    }

    private int peValue(ItemKey key) {
        ItemStack stack = key.toStack();
        int v = proxy.getValue(stack);
        return Math.max(0, v);
    }

    /** 目标物品求值:永不返回 UNKNOWN,最后兜底 0 */
    public int evalTarget(ItemKey key) {
        int v = eval(key, new ArrayDeque<>());
        return v == UNKNOWN ? 0 : v;
    }

    /**
     * 供 GtMachines 份量折算递归使用。共享外层求值栈:这样 粉↔小撮粉 之类的折算环
     * 会被 stack.contains 正确判为环返回 UNKNOWN,而不是用独立新栈绕过环检测无限递归
     * (StackOverflowError)。返回 0 = 当前环上无法折算。
     */
    public int evalFraction(ItemKey key, Deque<ItemKey> stack) {
        int v = eval(key, stack);
        return v == UNKNOWN ? 0 : v;
    }

    /** 返回 UNKNOWN 仅表示当前递归路径上的环;≥0 表示已定值(含 0) */
    private int eval(ItemKey key, Deque<ItemKey> stack) {
        Integer cached = known.get(key);
        if (cached != null) {
            return cached;
        }
        if (peHas(key)) {
            int v = peValue(key);
            known.put(key, v);
            return v;
        }
        // 份量形态(粉/小撮粉/小堆粉/粒/螺栓/螺丝):直接按材料份量折算,不展开配方,
        // 结果缓存到 known 复用(最大的粉=锭、螺栓/螺丝=杆/2)。
        if (GtMachines.isFractionForm(key)) {
            int v = GtMachines.materialFractionValue(key, this, stack);
            known.put(key, v);
            return v;
        }
        List<EmcRecipe> list = producers.get(key);
        if (list == null || list.isEmpty()) {
            // 无产出配方:先试 GT 材料份量折算(小撮/小堆粉、粒按同材料粉/锭折算);
            // 再试"假电路板"平均价——circuit<等级> oredict 成员(如 dreamcraft CircuitMV)没有
            // 任何产出配方,价格 = 同 oredict 其他有价成员的平均(规则:任意电路板价格 =
            // 同等级电路板总价/数量,只对无价成员生效)。共享当前栈以保留环检测
            // (假电路之间互相求平均的环返回 UNKNOWN)。
            int v = GtMachines.materialFractionValue(key, this, stack);
            if (v <= 0 && GtMachines.isCircuitBoardKey(key)) {
                if (stack.contains(key)) {
                    return UNKNOWN;
                }
                stack.addLast(key);
                v = GtMachines.circuitBoardAverage(key, this, stack);
                stack.removeLast();
                if (v > 0) {
                    circuitAveraged.add(key);
                    known.put(key, v);
                    return v;
                }
                // 是电路板但同级暂无有价成员(顺序依赖):不缓存 0、返回 UNKNOWN,
                // 由 resolveCircuitBoards()/resolveDeferred() 在基础成员定价后重估。
                return UNKNOWN;
            }
            known.put(key, v);
            return v;
        }
        if (stack.contains(key)) {
            return UNKNOWN;
        }
        stack.addLast(key);
        EmcRecipe best = null;
        long bestUnitCost = 0;
        boolean bestPositive = false;
        List<Pick> bestPicks = null;
        List<Pick> tmpPicks = new ArrayList<>();
        for (EmcRecipe r : list) {
            tmpPicks.clear();
            long cost = costOf(r, stack, tmpPicks);
            if (cost < 0) {
                continue;
            }
            long unitCost = cost / Math.max(1, r.outputQty);
            boolean positive = cost > 0;
            // 正成本配方优先于零成本配方:零成本要么是"循环回收配方"(如能量水晶→粉
            // 的 macerator,依赖的环被拆成 0),要么是"全无价材料",两者都不应压过真正
            // 有基础材料成本的配方(哪怕后者等级更高)。同级同成本再用 (类别,等级,单位成本)。
            boolean take = best == null || (positive && !bestPositive)
                || (positive == bestPositive && better(r, unitCost, best, bestUnitCost));
            if (take) {
                best = r;
                bestUnitCost = unitCost;
                bestPositive = positive;
                bestPicks = new ArrayList<>(tmpPicks);
            }
        }
        stack.removeLast();
        if (best == null) {
            // 所有配方都因环失效(无逃逸):先按 0 缓存,保证第一遍全量 memoization、不重算。
            // 顺序依赖导致的 sticky-0(本物品其实有正值、只因基础配方还没被求出)由
            // resolveDeferred() 第二遍重估修正。
            known.put(key, 0);
            return 0;
        }
        int value = (int) Math.min(Integer.MAX_VALUE - 1, Math.max(0, bestUnitCost));
        known.put(key, value);
        chosen.put(key, best);
        pickedOptions.put(key, bestPicks == null ? new ArrayList<>() : bestPicks);
        return value;
    }

    private long costOf(EmcRecipe r, Deque<ItemKey> stack, List<Pick> outPicks) {
        long sum = 0;
        for (EmcIngredient ing : r.inputs) {
            long bestOption = Long.MAX_VALUE;
            ItemKey bestKey = null;
            boolean anyOption = false;
            for (ItemKey opt : ing.options) {
                long v = eval(opt, stack);
                if (v == UNKNOWN) {
                    continue; // 该选项正处在当前递归环上,换下一个选项
                }
                anyOption = true;
                if (v < bestOption) {
                    bestOption = v;
                    bestKey = opt;
                }
            }
            if (!anyOption) {
                return UNKNOWN;
            }
            if (!AutoEmcConfig.unpricedIsZero && bestOption <= 0) {
                return UNKNOWN; // 不允许把无价材料当 0 用时,这条配方失效
            }
            sum += bestOption * ing.qty;
            if (sum < 0) {
                return UNKNOWN; // 溢出保护
            }
            outPicks.add(new Pick(bestKey, ing.qty));
        }
        return sum;
    }

    /** (类别, 等级, 组装机系, 输入形态等级, 流体量, 单位成本) 字典序;后四者只用于同类别同等级之间比较 */
    private boolean better(EmcRecipe r, long unitCost, EmcRecipe best, long bestUnitCost) {
        if (r.category != best.category) {
            return r.category < best.category;
        }
        if (r.tier != best.tier) {
            return r.tier < best.tier;
        }
        // 同类别同等级打平:组装机系配方(组装机/电路组装机等)优先于其他单方块机器
        boolean rAsm = r.isAssembler();
        boolean bAsm = best.isAssembler();
        if (rAsm != bAsm) {
            return rAsm;
        }
        if (r.formRank != best.formRank) {
            return r.formRank > best.formRank; // 形态等级越高越优先(锭>粉>小撮粉>矿石)
        }
        if (r.fluidAmount != best.fluidAmount) {
            return r.fluidAmount < best.fluidAmount; // 流体越少越优先(合成路径液体少)
        }
        return unitCost < bestUnitCost;
    }

    /**
     * 本次运行最终要写回 PE 的值:known 里所有 >0 且非 PE/玩家锚点的条目。统一扫 known 而不是只扫
     * producers —— 非 producer 的物品(同级平均的假电路板、质量定价种子、预载缓存的无配方旧值)也
     * 必须每次启动重注册,否则重启后(PE 值不持久、靠 AutoEMC 每次重注册)树/EMC 里它们价格缺失
     * 或归零(假电路板正是这种:无产出配方,不在 producers 里,上次平均的板子靠本次扫尾注册)。
     */
    public Map<ItemKey, Integer> collectFinalValues() {
        Map<ItemKey, Integer> result = new HashMap<>();
        for (ItemKey key : known.keySet()) {
            Integer v = known.get(key);
            if (v == null || v <= 0) {
                continue;
            }
            if (peHas(key)) {
                continue; // PE 已定价(含玩家手动)→ 不覆盖
            }
            result.put(key, v);
        }
        return result;
    }

    /**
     * 扫尾定价假电路板:主求值循环(只遍历 producers)之后调用 —— 遍历所有 circuit* oredict
     * 成员,给仍无价且非一次性工具的成员求值(无产出配方者走 circuitBoardAverage 取同级均值)。
     * 必须在 resolveDeferred() 之前调用:这样引用假电路板、第一遍因基础未定价而缓存 0 的
     * 配方,在第二遍重估时能拿到均值。返回本次定价的数量。
     */
    public int resolveCircuitBoards() {
        int priced = 0;
        if (!GtMachines.available()) {
            return 0;
        }
        try {
            for (String oreName : OreDictionary.getOreNames()) {
                if (oreName == null || !oreName.startsWith("circuit")) {
                    continue;
                }
                for (ItemStack member : new ArrayList<>(OreDictionary.getOres(oreName))) {
                    if (member == null || member.getItem() == null
                        || member.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
                        continue;
                    }
                    if (GtMachines.isOneTimeItem(member)) {
                        continue; // 编程电路等一次性工具不套平均价
                    }
                    ItemKey key = ItemKey.of(member);
                    if (known.containsKey(key) || peHas(key)) {
                        continue;
                    }
                    int v = eval(key, new ArrayDeque<>());
                    if (v > 0) {
                        priced++;
                    }
                }
            }
        } catch (Throwable t) {
            // 扫尾失败不阻塞主流程
        }
        return priced;
    }

    /**
     * 同等级电路板统一价(规则:任意电路板价格 = 同等级电路板总价 / 数量):遍历所有 circuit*
     * oredict,把每个成员的价统一为该级当前有价成员的均值 —— 覆盖真实电路板的配方价,假板同样
     * 落位。PE/玩家手动锚点不覆盖(但计入总价)。统一后成员是规则叶子:清掉 chosen/picks(不再
     * 展开配方链),记入 circuitAveraged(注册与展示标签用)。返回价格被改动(相对原有 known 值)
     * 的成员数 —— 改动 >0 表示有依赖方是按旧价算的,调用方应跑 {@link #recomputeAfterTierUniform()}。
     */
    public int uniformCircuitBoardTiers() {
        int changed = 0;
        if (!GtMachines.available()) {
            return 0;
        }
        try {
            for (String oreName : OreDictionary.getOreNames()) {
                if (oreName == null || !oreName.startsWith("circuit")) {
                    continue;
                }
                List<ItemStack> members = GtMachines.circuitOredictMembers(oreName);
                if (members.isEmpty()) {
                    continue;
                }
                long sum = 0;
                int count = 0;
                for (ItemStack ms : members) {
                    int v = currentValue(ItemKey.of(ms));
                    if (v > 0) {
                        sum += v;
                        count++;
                    }
                }
                if (count <= 0) {
                    continue;
                }
                int mean = (int) Math.min(Integer.MAX_VALUE - 1, Math.max(1, sum / count));
                for (ItemStack ms : members) {
                    ItemKey mk = ItemKey.of(ms);
                    if (peHas(mk)) {
                        continue; // 锚点不覆盖
                    }
                    Integer old = known.get(mk);
                    known.put(mk, mean);
                    chosen.remove(mk);
                    pickedOptions.remove(mk);
                    if (old == null || old != mean) {
                        changed++;
                    }
                    circuitAveraged.add(mk);
                }
            }
        } catch (Throwable t) {
            // 统一失败不阻塞主流程(部分成员可能已统一)
        }
        return changed;
    }

    /**
     * 统一价改动后的依赖重建:清空除规则叶子(质量种子 / 统一电路板)与 PE 锚点外全部已估值,
     * 重跑主求值 + 第二遍 —— 使所有引用电路板的配方/产品在统一价上重算(否则下游保留旧的最便宜
     * 成员价,值与树里展示的板价对不上)。返回本次重估出的 >0 值数量。
     */
    public int recomputeAfterTierUniform() {
        for (ItemKey key : new ArrayList<>(known.keySet())) {
            if (seeded.containsKey(key) || circuitAveraged.contains(key)) {
                continue;
            }
            known.remove(key);
            chosen.remove(key);
            pickedOptions.remove(key);
        }
        int computed = 0;
        for (ItemKey key : producers.keySet()) {
            if (knownValue(key) != null || isAnchoredByPe(key)) {
                continue;
            }
            if (evalTarget(key) > 0) {
                computed++;
            }
        }
        return computed + resolveDeferred();
    }

    /** 该 key 是否为规则直接定价的叶子(质量种子 / 同级平均电路板):价不来自配方,树不展开其配方链 */
    public boolean isDefinedLeaf(ItemKey key) {
        return seeded.containsKey(key) || circuitAveraged.contains(key);
    }

    /** 该 key 是否本次按同级电路板平均定价(CSV/展示用) */
    public boolean isAveraged(ItemKey key) {
        return circuitAveraged.contains(key);
    }

    private int currentValue(ItemKey key) {
        Integer v = known.get(key);
        if (v != null) {
            return v;
        }
        return peHas(key) ? peValue(key) : 0;
    }

    /** 每个物品选中的配方快照(命令展示用) */
    public Map<ItemKey, EmcRecipe> snapshotChosen() {
        return new HashMap<>(chosen);
    }

    /** 本次按同级平均定价的假电路板集合(EmcRuntime 展示来源用) */
    public Set<ItemKey> snapshotAveraged() {
        return new HashSet<>(circuitAveraged);
    }

    /** 每个物品选中配方时各输入槽实际取用的选项+数量快照(配方树对齐用) */
    public Map<ItemKey, List<Pick>> snapshotPickedOptions() {
        Map<ItemKey, List<Pick>> copy = new HashMap<>();
        for (Map.Entry<ItemKey, List<Pick>> e : pickedOptions.entrySet()) {
            copy.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return copy;
    }

    public int sizeOfKnown() {
        return known.size();
    }

    /**
     * 第二遍:修复"先按 0 缓存"带来的顺序依赖(sticky-0)。
     * 反复重估仍为 0 的项,直到没有任何项从 0 变正(或达到迭代上限)。
     * 纯环(无逃逸)始终保持 0;有基础配方但因环被误判 0 的项在此得到正确正值。
     * 返回被修正为正值的项数。
     */
    public int resolveDeferred() {
        int resolved = 0;
        int guard = 0;
        boolean changed;
        do {
            changed = false;
            for (ItemKey key : new ArrayList<>(producers.keySet())) {
                Integer v = known.get(key);
                if (v == null || v > 0) {
                    continue;
                }
                known.remove(key);
                chosen.remove(key);
                pickedOptions.remove(key);
                int nv = eval(key, new ArrayDeque<>());
                if (nv > 0) {
                    resolved++;
                    changed = true;
                }
            }
            guard++;
        } while (changed && guard < 8);
        return resolved;
    }
}
