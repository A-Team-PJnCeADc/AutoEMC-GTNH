package com.gtnh.autoemc.emc;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.oredict.OreDictionary;

import com.gtnh.autoemc.api.registry.EmcRegistry;
import com.gtnh.autoemc.compat.ProjectExpansionCompat;

import moze_intel.projecte.api.proxy.IEMCProxy;

/**
 * 递归 EMC 求值器。
 *
 * 规则:
 * - 已被 ProjectE 定价的物品(含玩家 /setemc、custom_emc.json 手动设置)是锚点,永不覆盖;
 * - 可选材料槽(同 oredict 多副本,如 GT 盐 vs 其他 mod 盐):只要存在有价选项就选有价者,
 * 无价副本不以 0 压过有价副本;全部无价才按 0(受 unpricedIsZero 约束);
 * - 配方比较:含无价输入的配方让位给全有价输入的配方(如同一产物 7 个蚀刻配方各吃一颗不同
 * 透镜时,选透镜有价的那条,不选无价透镜当免费的);所有候选都含无价输入(材料写死、
 * 别无选择)才退回成本比较;
 * - 没有产出配方的原材料按 0 计(unpricedIsZero=true 时,0 也会作为成本参与);
 * - 配方选择:(工作台 > 单方块机器 > 多方块),同级选低等级(蒸汽 < ULV < LV …),再比总成本;
 * - 递归环(配方互相依赖)使该条边失效,不会自我抬价;最终仍无法定价的物品按 0。
 */
public final class EmcEngine {

    // 未知(原 int 版的哨兵 UNKNOWN = -1)在本引擎内部统一用 BigInteger 的 null 表示:
    // 任何 EMC 运算遇 null 一律传播为 null,绝不当作 0 参与计算。

    /** 地下流体兜底价:无法从锭/配方推导的流体按 1 L(mB)=1 EMC,即每 144L = 144。 */
    private static final BigInteger FLUID_UNDERGROUND_PER_144L = BigInteger.valueOf(144L);

    private final Map<ItemKey, List<EmcRecipe>> producers;

    private final IEMCProxy proxy;

    /** 流体反推产者表:fluidName -> 产出它的"零物品输出、单一流体输出"配方(可能为空) */
    private final Map<String, List<FluidProducer>> fluidProducers;

    /**
     * 已解析流体价值(每 144L 的 EMC;0 也缓存=免费,如无产者且非材料的流体)。
     * 未知(null)不缓存 —— 递归环上的流体下次重试;resolveDeferred/fluidRecompute
     * 会清掉 0 缓存让基础定价后的重估重算。
     */
    private final Map<String, BigInteger> fluidMemo = new HashMap<>();

    /** 流体递归环检测栈(流体->流体/流体->物品->流体),与物品求值栈配合防跨类环 */
    private final Deque<String> fluidStack = new ArrayDeque<>();

    /** 所有已求出的值(锚点/预载缓存/新算);EMC 值一律 BigInteger,未知(null)不落表 */
    private final Map<ItemKey, BigInteger> known = new HashMap<>();
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
    /**
     * "派生值"物品:价不由配方决定、而是从另一个物品(基准形态)的价派生出来的 ——
     * 份量形态(小堆粉/小撮粉/粒/螺栓/螺丝)与"无配方的粉兜底折算成锭价"。
     *
     * 求值期间这些值**不写 known**:每次都按当前基准价现算。否则会留下"按中途基数算出来的
     * 旧派生值"再也不刷新(实测:粉中途是 12800 时算出小堆粉 3200,粉最终 204800,小堆粉却
     * 一直停在 3200)。全部求值/补偿/镜像轮次结束后由 materializeDerived() 按最终基准价统一落表。
     */
    private final Set<ItemKey> derivedKeys = new HashSet<>();
    /**
     * 被"材料等价形态族"折叠掉的形态(锭/热锭/粉/小堆粉/小撮粉/粒/杆/螺栓/螺丝 里,
     * 不是族基准单价的那些):价 = 族单价 × 材料量比,不再展开自己的配方。
     * 它们在配方树里当"规则叶子"(isDefinedLeaf),链被剪掉 —— 即"到锭/粉就不再往下展开"。
     */
    private final Set<ItemKey> foldedForms = new HashSet<>();
    /** GTMoreEMC mass-seeding:GT material forms priced directly (mass*72*form multiplier). */
    private final Map<ItemKey, BigInteger> seeded = new HashMap<>();
    /**
     * 锭/热锭的"材料族补偿档 n"(规则:全套形态价 ×2^n):
     * ① 材料存在 blast furnace 产者配方(直接产锭或产热锭,map 名含 blastfurnace,回收类排除)
     * -> n = 这些高炉配方 EUt 电压档的最小值(高炉优先,不再看真空冷冻等低压产者,也不看温度);
     * ② 无高炉配方的材料 -> n = 该锭 GT 机器配方(回收排除)EUt 电压档最低值,蒸汽(-1)/ULV(0)
     * 按 0 计;③ 无机器配方/非锭返回 -1(不乘)。按锭 key 惰性计算缓存。
     */
    private final Map<ItemKey, Integer> voltageTierMemo = new HashMap<>();
    /** 本次实际按电压放大过价(×2^n, n>0)的锭/热锭 key —— 粉只镜像这些锭。 */
    private final Set<ItemKey> voltageScaledIngots = new HashSet<>();
    /** 惰性:材料代表锭 key -> 该材料 blast furnace 产者配方 EUt 档最小值(无高炉配方的材料不在表里)。 */
    private Map<ItemKey, Integer> blastMinTierByRep;

    public EmcEngine(Map<ItemKey, List<EmcRecipe>> producers, IEMCProxy proxy) {
        this(producers, java.util.Collections.<String, List<FluidProducer>>emptyMap(), proxy);
    }

    public EmcEngine(Map<ItemKey, List<EmcRecipe>> producers, Map<String, List<FluidProducer>> fluidProducers,
        IEMCProxy proxy) {
        this.producers = producers;
        this.fluidProducers = fluidProducers == null ? java.util.Collections.<String, List<FluidProducer>>emptyMap()
            : fluidProducers;
        this.proxy = proxy;
    }

    /** 把上次缓存的值(数量>0)预载为已知,不求值它们;已被 PE(含玩家手动)定价的跳过 */
    public void preload(Map<ItemKey, BigInteger> cached) {
        for (Map.Entry<ItemKey, BigInteger> e : cached.entrySet()) {
            BigInteger v = e.getValue();
            if (!EmcMath.isPositive(v) || peHas(e.getKey())) {
                continue;
            }
            known.put(e.getKey(), v);
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
    public int addSeeds(Map<ItemKey, BigInteger> seeds) {
        int added = 0;
        for (Map.Entry<ItemKey, BigInteger> e : seeds.entrySet()) {
            BigInteger v = e.getValue();
            if (!EmcMath.isPositive(v)) {
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

    /** 该物品当前是否已被 PE 定价(含玩家手动设置)-> 属于"锚点",不算我们的成果 */
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

    /** 已求出的 EMC 值;null 表示尚无记录(与"未知"哨兵语义一致:都没有值) */
    public BigInteger knownValue(ItemKey key) {
        return known.get(key);
    }

    private boolean peHas(ItemKey key) {
        Boolean b = peHasCache.get(key);
        if (b == null) {
            // PE-E-GTNH(装了就优先问它):它的 BigInteger 表里有价也算有
            b = proxy.hasValue(key.toStack()) || ProjectExpansionCompat.hasExactValue(key.toStack());
            peHasCache.put(key, b);
        }
        return b;
    }

    /**
     * 读 ProjectE(或 PE-E-GTNH 精确表)已有价值,作为锚点。
     * 装了 PE-E-GTNH 时优先用它的 BigInteger 精确值:ProjectE 的 int 表会把高价物品夹在 21 亿,
     * 而 GT 物品常参与高阶配方,截断会污染求值 —— 改造后精确值原样进入引擎,不再夹取。
     * 没有精确值时读 ProjectE 的 int 表(这里本来就是 int 域):负值按 0。
     */
    private BigInteger peValue(ItemKey key) {
        ItemStack stack = key.toStack();
        BigInteger exact = ProjectExpansionCompat.exactValue(stack);
        if (exact != null) {
            return EmcMath.max(exact, BigInteger.ZERO);
        }
        BigInteger v = EmcMath.of(proxy.getValue(stack));
        return v == null ? BigInteger.ZERO : v;
    }

    /** 目标物品求值:永不返回"未知",最后兜底 0 */
    public BigInteger evalTarget(ItemKey key) {
        BigInteger v = eval(key, new ArrayDeque<>());
        return v == null ? BigInteger.ZERO : v;
    }

    /**
     * 供 GtMachines 份量折算递归使用。共享外层求值栈:这样 粉↔小撮粉 之类的折算环
     * 会被 stack.contains 正确判为环返回未知(null),而不是用独立新栈绕过环检测无限递归
     * (StackOverflowError)。返回 null = 当前环上无法折算(原 int 版返回 0)。
     */
    public BigInteger evalFraction(ItemKey key, Deque<ItemKey> stack) {
        return eval(key, stack);
    }

    /** 返回 null 仅表示当前递归路径上的环;非 null 表示已定值(含 0) */
    private BigInteger eval(ItemKey key, Deque<ItemKey> stack) {
        BigInteger cached = known.get(key);
        if (cached != null) {
            return cached;
        }
        if (peHas(key)) {
            BigInteger v = peValue(key);
            known.put(key, v);
            return v;
        }
        // 份量形态(小撮粉/小堆粉/粒/螺栓/螺丝):按材料份量比折算到基准形态,
        // 不展开自己的配方(小撮粉=粉/9、小堆粉=粉/4、粒=锭/9、螺栓/螺丝=杆/2)。
        // 满粉不在此列:粉有自己的配方(化学反应釜等),由下面的 producers 比价决定。
        if (GtMachines.isFractionForm(key)) {
            // 派生值:按当前基准价现算、**不缓存**(基准价在后续轮次里还会变),
            // 由 materializeDerived() 在收尾时按最终基准价落表。
            // 基准还没定价时返回"未知"且不缓存 —— 0 在成本里等于免费,会让
            // "4 小堆粉 = 1 粉"这类份量恒等式以 0 成本胜出,把基准价钉死。
            derivedKeys.add(key);
            return GtMachines.materialFractionValue(key, this, stack);
        }
        List<EmcRecipe> list = producers.get(key);
        if (list == null || list.isEmpty()) {
            // 无产出配方:先试 GT 材料份量折算(小撮/小堆粉、粒按同材料粉/锭折算);
            // 再试"假电路板"平均价——circuit<等级> oredict 成员(如 dreamcraft CircuitMV)没有
            // 任何产出配方,价格 = 同 oredict 其他有价成员的平均(规则:任意电路板价格 =
            // 同等级电路板总价/数量,只对无价成员生效)。共享当前栈以保留环检测
            // (假电路之间互相求平均的环返回未知(null))。
            BigInteger v = GtMachines.materialFractionValue(key, this, stack);
            if (GtMachines.fractionBaseKey(key) != null) {
                // 无产出配方、但有份量基准(如没有配方的粉兜底折算成锭价):同样按派生值处理 ——
                // 不缓存、由 materializeDerived() 收尾落表;基准未定价时返回"未知"不缓存。
                derivedKeys.add(key);
                return v;
            }
            if (!EmcMath.isPositive(v) && GtMachines.isCircuitBoardKey(key)) {
                if (stack.contains(key)) {
                    return null;
                }
                stack.addLast(key);
                v = GtMachines.circuitBoardAverage(key, this, stack);
                stack.removeLast();
                if (EmcMath.isPositive(v)) {
                    circuitAveraged.add(key);
                    known.put(key, v);
                    return v;
                }
                // 是电路板但同级暂无有价成员(顺序依赖):不缓存 0、返回"未知"(null),
                // 由 resolveCircuitBoards()/resolveDeferred() 在基础成员定价后重估。
                return null;
            }
            if (v == null) {
                v = BigInteger.ZERO;
            }
            known.put(key, v);
            return v;
        }
        if (stack.contains(key)) {
            return null;
        }
        stack.addLast(key);
        EmcRecipe best = null;
        BigInteger bestUnitCost = null;
        boolean bestPositive = false;
        boolean bestFreeInput = false;
        List<Pick> bestPicks = null;
        // 无尽类物品(Avaritia / avaritiaddons 命名空间):只要候选里有无尽工作台配方就优先它
        boolean avaritiaItem = AvaritiaRecipes.isAvaritiaItem(key);
        // 透镜专用:若存在有效的车床 板->透镜 配方,优先选它(规则:透镜优先按车床板配方定价,
        // 压过精宝石/切割等其他产法);不存在时退回常规选择。
        boolean lensOutput = GtMachines.isLensKey(key);
        EmcRecipe bestLathe = null;
        BigInteger latheUnitCost = null;
        boolean lathePositive = false;
        boolean latheFreeInput = false;
        List<Pick> lathePicks = null;
        List<Pick> tmpPicks = new ArrayList<>();
        // 同材料等价形态自推(锭↔粉↔热锭、粉↔小堆粉…):成本恒等于本材料自身的价,参与比价
        // 就是把价钉死成"谁先求值谁定值"(实测 粉=锭=4×热锭 的任意定点)。仅当该物品存在
        // "独立产线"(至少一条不吃同材料形态的候选)时才屏蔽这些边;否则整个材料族一个价都
        // 没有,退回老行为(纯环材料仍按老办法打破环)。
        boolean blockSelfForm = false;
        if (GtMachines.isMaterialForm(key)) {
            for (EmcRecipe r : list) {
                if (!onlySameMaterialForms(r, key)) {
                    blockSelfForm = true;
                    break;
                }
            }
        }
        for (EmcRecipe r : list) {
            if (blockSelfForm && onlySameMaterialForms(r, key)) {
                continue; // 同材料形态自推:不参与比价,等价关系由 materializeMaterialFamilies 统一给
            }
            tmpPicks.clear();
            BigInteger cost = costOf(r, stack, tmpPicks);
            if (cost == null) {
                continue; // 当前递归路径上不可解析(环),换下一条候选
            }
            BigInteger unitCost = EmcMath.div(cost, Math.max(1, r.outputQty));
            boolean positive = EmcMath.isPositive(cost);
            // 含无价输入的配方让位给全有价输入的配方:同一产物常有多个配方,其中某条吃到的材料
            // 没价(如硅压印板 7 个蚀刻配方各吃一颗不同透镜,无价透镜按 0 计入成本会让那条看起来
            // 最便宜而被选中、树里出现无价材料;盐等跨 mod 同材料同理)。优先级:正成本 > 零成本,
            // 之后优先选无任何无价输入的配方;所有候选都含无价输入(材料被写死、别无选择)才退回
            // 常规成本比较,唯一配方不受影响。
            boolean freeInput = recipeUsesFreeInput(r, stack);
            boolean take;
            if (best == null) {
                take = true;
            } else if (positive && !bestPositive) {
                take = true;
            } else if (!positive && bestPositive) {
                take = false;
            } else if (!freeInput && bestFreeInput) {
                take = true;
            } else if (freeInput && !bestFreeInput) {
                take = false;
            } else if (avaritiaItem && r.isAvaritiaTable() != best.isAvaritiaTable()) {
                // 无尽类物品:无尽工作台(大工作台)配方优先,压过中子素压缩机等机器路径、
                // 普通合成台路径与成本比较(规则:无尽类用自己的合成台)。
                take = r.isAvaritiaTable();
            } else {
                take = better(r, unitCost, best, bestUnitCost);
            }
            if (take) {
                best = r;
                bestUnitCost = unitCost;
                bestPositive = positive;
                bestFreeInput = freeInput;
                bestPicks = new ArrayList<>(tmpPicks);
            }
            if (lensOutput && GtMachines.isLathePlateToLens(r)) {
                boolean ltake;
                if (bestLathe == null) {
                    ltake = true;
                } else if (positive && !lathePositive) {
                    ltake = true;
                } else if (!positive && lathePositive) {
                    ltake = false;
                } else if (!freeInput && latheFreeInput) {
                    ltake = true;
                } else if (freeInput && !latheFreeInput) {
                    ltake = false;
                } else {
                    ltake = better(r, unitCost, bestLathe, latheUnitCost);
                }
                if (ltake) {
                    bestLathe = r;
                    latheUnitCost = unitCost;
                    lathePositive = positive;
                    latheFreeInput = freeInput;
                    lathePicks = new ArrayList<>(tmpPicks);
                }
            }
        }
        if (lensOutput && bestLathe != null) {
            best = bestLathe;
            bestUnitCost = latheUnitCost;
            bestPositive = lathePositive;
            bestFreeInput = latheFreeInput;
            bestPicks = lathePicks;
        }
        stack.removeLast();
        if (best == null) {
            // 所有配方都因环失效(无逃逸):先按 0 缓存,保证第一遍全量 memoization、不重算。
            // 顺序依赖导致的 sticky-0(本物品其实有正值、只因基础配方还没被求出)由
            // resolveDeferred() 第二遍重估修正。
            known.put(key, BigInteger.ZERO);
            return BigInteger.ZERO;
        }
        // 单位成本即物品价;BigInteger 不再被 ProjectE 的 21 亿夹取(只在写回边界收敛)
        BigInteger value = EmcMath.max(bestUnitCost, BigInteger.ZERO);
        // 材料族电压补偿(规则:全套形态价 ×2^n)。n 见 ingotVoltageTier:高炉材料取该材料
        // blast furnace 产者配方的 EUt 档最小值(回收排除);无高炉配方材料取锭机器配方 EUt 档
        // 最低值。种子形态在 scaleSeededVoltageFamilies 已放大;这里兜配方求值的锭/热锭(无组分
        // 异星材料等没有种子),缓存前放大 —— 任何以它为输入的后续求值(costOf 递归 eval)自动
        // 读到放大价,顺序无关。同材料的锭与热锭用同一个 n(材料代表锭的),族内比价一致。
        // PE/玩家锚点锭走上面 peHas 分支,不进这里,不被改。
        ItemKey ingotRep = GtMachines.canonicalIngotKey(key);
        int ingotTierN = ingotRep == null ? -1 : ingotVoltageTier(ingotRep);
        if (ingotTierN > 0 && EmcMath.isPositive(value)) {
            value = scaleUp(value, ingotTierN);
            if (key.equals(ingotRep)) {
                voltageScaledIngots.add(key);
            }
        }
        known.put(key, value);
        chosen.put(key, best);
        pickedOptions.put(key, bestPicks == null ? new ArrayList<>() : bestPicks);
        return value;
    }

    /** 锭/热锭的"材料族补偿档 n":见 {@link #voltageTierMemo}。 */
    private int ingotVoltageTier(ItemKey key) {
        Integer n = voltageTierMemo.get(key);
        if (n != null) {
            return n;
        }
        int result = -1;
        if (key != null && GtMachines.isIngotItem(key)) {
            // 高炉优先:该材料(以代表锭为键)有 blast furnace 产者配方 -> n = 其中 EUt 档最小值
            ItemKey rep = GtMachines.canonicalIngotKey(key);
            if (rep != null) {
                if (blastMinTierByRep == null) {
                    blastMinTierByRep = GtMachines.blastFurnaceMinTierByIngot(producers);
                }
                Integer blastTier = blastMinTierByRep.get(rep);
                if (blastTier != null) {
                    result = blastTier; // 高炉材料只看高炉配方档(0 = 最低压高炉,不乘)
                    voltageTierMemo.put(key, result);
                    return result;
                }
            }
            // 非高炉材料:回落 该锭机器配方(回收排除)EUt 档最低值
            List<EmcRecipe> list = producers.get(key);
            if (list != null) {
                int minTier = Integer.MAX_VALUE;
                boolean anyMachine = false;
                for (EmcRecipe r : list) {
                    if (!isMachineRecipe(r)) {
                        continue;
                    }
                    if (r.source != null && r.source.toLowerCase()
                        .contains("recycl")) {
                        continue; // 回收防御(收集层已滤 recycle,这里按 source 名再滤一层)
                    }
                    anyMachine = true;
                    // 蒸汽(-1)/ULV(0)配方按 0 计:只有需要 ≥LV 机器才产生补偿
                    minTier = Math.min(minTier, Math.max(0, r.tier));
                }
                if (anyMachine) {
                    result = minTier;
                }
            }
        }
        voltageTierMemo.put(key, result);
        return result;
    }

    /**
     * 该产者是否为"GT 机器/装配线"配方(RecipeMap mapName 或 assemblyline):工作台
     * (crafting)/原版熔炉(smelting)/大工作台(avaritia)没有电压概念,不计入锭的电压档。
     */
    private static boolean isMachineRecipe(EmcRecipe r) {
        if (r == null || r.source == null) {
            return false;
        }
        String s = r.source;
        return !("crafting".equals(s) || "smelting".equals(s) || "avaritia".equals(s));
    }

    /** value × 2^n(n≤0 或未知原样返回);BigInteger 版不再钳到 Integer.MAX_VALUE - 1。 */
    private static BigInteger scaleUp(BigInteger value, int n) {
        if (!EmcMath.isPositive(value) || n <= 0) {
            return value;
        }
        return value.shiftLeft(Math.min(n, 30));
    }

    /**
     * 材料族电压补偿(质量种子阶段):种子直接 known.put 定价,不经 {@link #eval} 的放大钩子。
     * 这里在注入种子后、主求值循环前,把"该材料被补偿"(材料代表锭有 GT 机器配方、n>0)的
     * 材料其**全套形态种子**(锭/热锭/粉/板/杆/线/块…)按同一 n 等比放大 —— 族内比价不变,
     * 主循环里所有以这些形态为输入的配方自动读到放大后的价。返回本次改动的种子条数。
     */
    public int scaleSeededVoltageFamilies() {
        int scaled = 0;
        if (seeded.isEmpty()) {
            return 0;
        }
        for (ItemKey key : new ArrayList<>(seeded.keySet())) {
            if (key == null) {
                continue;
            }
            // 族系数 = 材料"代表锭"(普通锭,回落热锭)的 n;材料没有锭形态则整个族不乘
            ItemKey rep = GtMachines.canonicalIngotKey(key);
            if (rep == null) {
                continue;
            }
            int n = ingotVoltageTier(rep);
            if (n <= 0) {
                continue;
            }
            BigInteger v = known.get(key);
            if (!EmcMath.isPositive(v)) {
                continue;
            }
            BigInteger nv = scaleUp(v, n);
            if (!nv.equals(v)) {
                known.put(key, nv);
                scaled++;
            }
            if (key.equals(rep)) {
                voltageScaledIngots.add(key);
            }
        }
        return scaled;
    }

    /**
     * 粉随锭(收尾):所有求值/重估完成后,把"材料代表锭"(普通锭,材料只有热锭时回落热锭)的
     * 补偿后价镜像到同材料满粉 —— 种子族已在 scaleSeededVoltageFamilies 等比放大,粉锭同价;
     * 这里兜锭走配方求值(无组分异星材料等)或锭/粉机器配方档位不同的細差,保证 粉 = 锭价,
     * 不出现"买粉铸造/熔炼成锭"的 EMC 套利。只镜像本次实际被补偿过的锭。PE/玩家锚点不动。
     * 返回改动的粉条数。
     */
    public int mirrorDustPrices() {
        int changed = 0;
        for (ItemKey key : new ArrayList<>(known.keySet())) {
            // 只镜像"该材料的代表锭"(普通锭;材料只有热锭时回落热锭),且本次实际被补偿过:
            // 粉 = 代表锭的补偿后价,不随材料里另一形态(如热锭)的独立档位抖动。
            if (key == null || !voltageScaledIngots.contains(key) || !GtMachines.isIngotItem(key)) {
                continue;
            }
            ItemKey canonical = GtMachines.canonicalIngotKey(key);
            if (canonical == null || !canonical.equals(key)) {
                continue;
            }
            BigInteger ingotValue = known.get(key);
            if (!EmcMath.isPositive(ingotValue) || peHas(key)) {
                continue;
            }
            ItemKey dustKey = GtMachines.fullDustKey(key);
            if (dustKey == null || dustKey.equals(key) || peHas(dustKey)) {
                continue;
            }
            BigInteger old = known.get(dustKey);
            if (old == null || !old.equals(ingotValue)) {
                known.put(dustKey, ingotValue);
                changed++;
            }
        }
        return changed;
    }

    /**
     * 配方是否含无价输入(选中它等于把某 0 价物品当免费材料):任一输入槽按槽内选择逻辑
     * (有价优先)最终只能落到 0 —— 槽里所有选项都不是正价,或固定槽本身就是那颗无价物。
     * 返回 true 表示该配方在比价时应让位给全有价输入的配方;若所有候选都含无价输入
     * (该材料被写死、别无选择)则仍按常规成本比较,唯一配方不受影响。
     */
    private boolean recipeUsesFreeInput(EmcRecipe r, Deque<ItemKey> stack) {
        if (r == null || r.inputs == null) {
            return false;
        }
        for (EmcIngredient ing : r.inputs) {
            BigInteger minPriced = null;
            boolean anyOption = false;
            for (ItemKey opt : ing.options) {
                BigInteger v = eval(opt, stack);
                if (v == null) {
                    continue;
                }
                anyOption = true;
                if (EmcMath.isPositive(v) && (minPriced == null || EmcMath.cmp(v, minPriced) < 0)) {
                    minPriced = v;
                }
            }
            if (anyOption && minPriced == null) {
                return true; // 该槽没有任何正价选项 -> 落到的就是 0 价物品
            }
        }
        return false;
    }

    /**
     * 恒等式防线:opt 是否是"本次求值目标 self 的更小份量形态"(4 小堆粉=1 粉、9 粒=1 锭…)。
     * 同份量的形态不算:粉->锭(熔炼)、锭->粉(粉碎)是真实产线,只挡更小份量的重组配方。
     */
    private boolean isSelfFractionInput(ItemKey opt, ItemKey self) {
        if (self == null || opt == null || opt.equals(self)) {
            return false;
        }
        ItemKey base = GtMachines.fractionBaseKey(opt);
        if (base == null || !base.equals(self)) {
            return false;
        }
        long optAmount = GtMachines.materialAmount(opt);
        long selfAmount = GtMachines.materialAmount(self);
        return optAmount > 0 && selfAmount > 0 && optAmount < selfAmount;
    }

    private BigInteger costOf(EmcRecipe r, Deque<ItemKey> stack, List<Pick> outPicks) {
        BigInteger sum = BigInteger.ZERO;
        // 栈顶 = 本次正在求值的目标物品,用于恒等式防线
        ItemKey self = stack.peekLast();
        for (EmcIngredient ing : r.inputs) {
            BigInteger bestPriced = null;
            ItemKey bestPricedKey = null;
            BigInteger bestFree = BigInteger.ZERO;
            ItemKey bestFreeKey = null;
            boolean anyOption = false;
            for (ItemKey opt : ing.options) {
                if (isSelfFractionInput(opt, self)) {
                    // 拿"由目标物品自己折算出来的更小份量形态"当原料(4 小堆粉 = 1 粉、
                    // 9 小撮粉 = 1 粉、9 粒 = 1 锭 之类):成本恒等于目标物品本身,是恒等式
                    // 而非信息。让它参与比价只会把价钉死成旧值(锭->粉->小堆粉->粉 死循环)。
                    continue;
                }
                BigInteger v = eval(opt, stack);
                if (v == null) {
                    continue; // 该选项正处在当前递归环上,换下一个选项
                }
                anyOption = true;
                if (EmcMath.isPositive(v)) {
                    if (bestPriced == null || EmcMath.cmp(v, bestPriced) < 0) {
                        bestPriced = v;
                        bestPricedKey = opt;
                    }
                } else {
                    // 无价(0)选项:不立即选 —— 同一材料的多个 mod 副本(如 GT 盐 vs 其他 mod 的盐)
                    // 若允许无价副本以 0 压过有价副本,成本会被低估、树里还选中无价物品;
                    // 规则:只要存在有价选项就优先选有价者,全无价才退回 0。
                    if (bestFreeKey == null) {
                        bestFree = BigInteger.ZERO;
                        bestFreeKey = opt;
                    }
                }
            }
            if (!anyOption) {
                return null;
            }
            // 有价选项优先;全无价才退回槽里的 0(免费)
            BigInteger bestOption = bestPricedKey != null ? bestPriced : bestFree;
            ItemKey bestKey = bestPricedKey != null ? bestPricedKey : bestFreeKey;
            if (!AutoEmcConfig.unpricedIsZero && !EmcMath.isPositive(bestOption)) {
                return null; // 不允许把无价材料当 0 用时,这条配方失效
            }
            // BigInteger 无溢出:原 sum < 0 的溢出保护不再需要
            sum = EmcMath.add(sum, EmcMath.mul(bestOption, ing.qty));
            outPicks.add(new Pick(bestKey, ing.qty));
        }
        // 流体输入计入成本:每 144L = 流体价值(材料流体 144L=对应锭价;无锭配方反推;免费流体为 0)
        for (FluidUse fu : r.fluids) {
            BigInteger v144 = resolveFluidValue144(fu.fluidName, stack);
            if (v144 == null) {
                return null; // 流体价值递归中/当前不可解析 -> 该候选失效(等第二遍/重估)
            }
            if (!EmcMath.isPositive(v144)) {
                if (!AutoEmcConfig.unpricedIsZero) {
                    return null; // 不允许把无价流体当 0 用时,配方失效
                }
                continue; // 免费流体(水/蒸汽/未定价)不产生成本
            }
            // 向上取整:144L = 1 锭
            sum = EmcMath.add(sum, EmcMath.ceilDiv(EmcMath.mul(v144, fu.amountL), 144L));
        }
        return sum;
    }

    /**
     * 流体每 144L 的价值(null 表示当前不可解析,不缓存;0 缓存=免费流体):
     * <ol>
     * <li>EmcRegistry 注册值(其他 mod/用户经 Registry API 注册,语义=每 144L);</li>
     * <li>GT 材料流体:对应材料锭(回落热锭/粉/宝石)的物品价值 —— "144L = 1 锭";</li>
     * <li>配方反推:零物品输出、单一流体输出的机器配方,成本 = 物品输入 + 流体输入(递归),
     * 多条产者取每 144L 最便宜;</li>
     * <li>以上皆无 -> 地下流体兜底价 1 L(mB)=1 EMC,即每 144L = 144。</li>
     * </ol>
     * 递归环(流体↔物品/流体↔流体)返回 null(未知)不缓存,由 resolveDeferred / fluidRecompute
     * 在基础定价后重估。
     */
    private BigInteger resolveFluidValue144(String name, Deque<ItemKey> stack) {
        BigInteger memo = fluidMemo.get(name);
        if (memo != null) {
            return memo;
        }
        if (fluidStack.size() > 24) {
            return null; // 递归过深防御
        }
        // 1) 注册表(其他 mod / 未来 Registry Types 流体价)
        // 流体注册名可含 ':'(EmcKey canonical 用 ':' 分隔 type:id,构造器拒收)-> 含 ':' 的
        // 名字跳过注册表查询,仍可走材料锚/配方反推(那两条链用原始名作 map 键,不经 EmcKey)。
        // 注册表仍是 int 域(它镜像进 PE 的 int 类型表),这里只是读侧的边界转换:负数/0 都按无值
        BigInteger reg = BigInteger.ZERO;
        if (name.indexOf(':') < 0) {
            int rv = EmcRegistry.instance()
                .getFluidValue(name);
            if (rv > 0) {
                reg = BigInteger.valueOf(rv);
            }
        }
        if (EmcMath.isPositive(reg)) {
            fluidMemo.put(name, reg);
            return reg;
        }
        // 2) GT 材料流体 -> 锭/形态物品价值
        try {
            net.minecraftforge.fluids.Fluid flu = FluidRegistry.getFluid(name);
            if (flu != null) {
                ItemStack anchor = GtMachines.materialAnchorStack(flu);
                if (anchor != null) {
                    BigInteger av = eval(ItemKey.of(anchor), stack);
                    if (EmcMath.isPositive(av)) {
                        fluidMemo.put(name, av);
                        return av;
                    }
                    if (av == null) {
                        return null; // 锚在递归环上:不缓存,等基础定价后重估
                    }
                    // 锚 = 0(材料本身无价)-> 落入反推/免费
                }
            }
        } catch (Throwable t) {
            return null; // GT API 异常:按当前不可解析处理,不缓存
        }
        // 3) 配方反推(无产者 -> 4) 地下流体兜底价 1mB(=GT 的 L)=1 EMC,即每 144L = 144)
        List<FluidProducer> list = fluidProducers.get(name);
        if (list == null || list.isEmpty()) {
            fluidMemo.put(name, FLUID_UNDERGROUND_PER_144L);
            return FLUID_UNDERGROUND_PER_144L;
        }
        if (fluidStack.contains(name)) {
            return null; // 流体环
        }
        fluidStack.addLast(name);
        BigInteger best = null;
        try {
            for (FluidProducer p : list) {
                BigInteger cost = fluidProducerCost(p, stack);
                if (cost == null) {
                    continue;
                }
                // 每 144L 折算,向上取整
                BigInteger per144 = EmcMath.ceilDiv(EmcMath.mul(cost, 144), p.outputL);
                if (best == null || EmcMath.cmp(per144, best) < 0) {
                    best = per144;
                }
            }
        } finally {
            fluidStack.removeLast();
        }
        if (best == null) {
            return null; // 产者全部不可解析:不缓存,重估时再试
        }
        fluidMemo.put(name, best);
        return best;
    }

    /** 单条流体产者配方总成本(物品输入按槽有价优先选价 + 流体输入递归);任一部分不可解析返回 null。 */
    private BigInteger fluidProducerCost(FluidProducer p, Deque<ItemKey> stack) {
        BigInteger sum = BigInteger.ZERO;
        for (EmcIngredient ing : p.inputs) {
            BigInteger bestPriced = null;
            boolean any = false;
            boolean sawFree = false;
            for (ItemKey opt : ing.options) {
                BigInteger v = eval(opt, stack);
                if (v == null) {
                    continue;
                }
                any = true;
                if (EmcMath.isPositive(v)) {
                    if (bestPriced == null || EmcMath.cmp(v, bestPriced) < 0) {
                        bestPriced = v;
                    }
                } else {
                    sawFree = true;
                }
            }
            if (!any) {
                return null;
            }
            BigInteger opt = bestPriced != null ? bestPriced : BigInteger.ZERO;
            if (!sawFree && bestPriced == null) {
                return null;
            }
            if (!AutoEmcConfig.unpricedIsZero && !EmcMath.isPositive(opt)) {
                return null;
            }
            sum = EmcMath.add(sum, EmcMath.mul(opt, ing.qty));
        }
        for (FluidUse fu : p.fluids) {
            BigInteger v144 = resolveFluidValue144(fu.fluidName, stack);
            if (v144 == null) {
                return null;
            }
            if (!EmcMath.isPositive(v144)) {
                if (!AutoEmcConfig.unpricedIsZero) {
                    return null;
                }
                continue;
            }
            sum = EmcMath.add(sum, EmcMath.ceilDiv(EmcMath.mul(v144, fu.amountL), 144L));
        }
        return sum;
    }

    /** 已解析流体价值数(日志/排查用)。 */
    public int fluidValueCount() {
        return fluidMemo.size();
    }

    /**
     * 流体第二遍(cache-miss 全量求值时由 EmcRunner 调用):清空流体 memo 与(规则叶子外的)
     * 全部物品估值,重跑主求值 + resolveDeferred。第一遍因流体价值不可解析(环/顺序依赖)
     * 而失效的配方候选,在第二遍拿到解析好的流体价值后重选;也修正第一遍"免费流体压价"
     * 造成的低估。返回重估出的 >0 数量。
     */
    public int fluidRecompute() {
        fluidMemo.clear();
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
            if (EmcMath.isPositive(evalTarget(key))) {
                computed++;
            }
        }
        return computed + resolveDeferred();
    }

    /** (类别, 等级, 组装机系, 输入形态等级, 流体量, 单位成本) 字典序;后四者只用于同类别同等级之间比较 */
    private boolean better(EmcRecipe r, BigInteger unitCost, EmcRecipe best, BigInteger bestUnitCost) {
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
        return EmcMath.cmp(unitCost, bestUnitCost) < 0;
    }

    /**
     * 本次运行最终要写回 PE 的值:known 里所有 >0 且非 PE/玩家锚点的条目。统一扫 known 而不是只扫
     * producers —— 非 producer 的物品(同级平均的假电路板、质量定价种子、预载缓存的无配方旧值)也
     * 必须每次启动重注册,否则重启后(PE 值不持久、靠 AutoEMC 每次重注册)树/EMC 里它们价格缺失
     * 或归零(假电路板正是这种:无产出配方,不在 producers 里,上次平均的板子靠本次扫尾注册)。
     */
    public Map<ItemKey, BigInteger> collectFinalValues() {
        Map<ItemKey, BigInteger> result = new HashMap<>();
        for (ItemKey key : known.keySet()) {
            BigInteger v = known.get(key);
            if (!EmcMath.isPositive(v)) {
                continue;
            }
            if (peHas(key)) {
                continue; // PE 已定价(含玩家手动)-> 不覆盖
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
                    BigInteger v = eval(key, new ArrayDeque<>());
                    if (EmcMath.isPositive(v)) {
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
                BigInteger sum = BigInteger.ZERO;
                int count = 0;
                for (ItemStack ms : members) {
                    BigInteger v = currentValue(ItemKey.of(ms));
                    if (EmcMath.isPositive(v)) {
                        sum = EmcMath.add(sum, v);
                        count++;
                    }
                }
                if (count <= 0) {
                    continue;
                }
                // 同级均值(至少 1);BigInteger 版不再夹到 int 上限
                BigInteger mean = EmcMath.max(EmcMath.div(sum, count), BigInteger.ONE);
                for (ItemStack ms : members) {
                    ItemKey mk = ItemKey.of(ms);
                    if (peHas(mk)) {
                        continue; // 锚点不覆盖
                    }
                    BigInteger old = known.get(mk);
                    known.put(mk, mean);
                    chosen.remove(mk);
                    pickedOptions.remove(mk);
                    if (old == null || !old.equals(mean)) {
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
            if (EmcMath.isPositive(evalTarget(key))) {
                computed++;
            }
        }
        return computed + resolveDeferred();
    }

    /** 该 key 是否为规则直接定价的叶子(质量种子 / 同级平均电路板):价不来自配方,树不展开其配方链 */
    public boolean isDefinedLeaf(ItemKey key) {
        return seeded.containsKey(key) || circuitAveraged.contains(key) || foldedForms.contains(key);
    }

    /** 该 key 是否本次按同级电路板平均定价(CSV/展示用) */
    public boolean isAveraged(ItemKey key) {
        return circuitAveraged.contains(key);
    }

    private BigInteger currentValue(ItemKey key) {
        BigInteger v = known.get(key);
        if (v != null) {
            return v;
        }
        return peHas(key) ? peValue(key) : BigInteger.ZERO;
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
     * 候选是否"只吃本材料的等价形态"(锭↔粉↔热锭↔小堆粉…)。没有物品输入(纯流体/工具)不算。
     */
    private boolean onlySameMaterialForms(EmcRecipe r, ItemKey output) {
        if (r == null || r.inputs == null || r.inputs.isEmpty()) {
            return false;
        }
        for (EmcIngredient ing : r.inputs) {
            if (ing.options == null || ing.options.isEmpty()) {
                return false;
            }
            for (ItemKey opt : ing.options) {
                if (!GtMachines.sameMaterial(output, opt)) {
                    return false; // 有一条选项是别的材料 ⇒ 不是同材料自推
                }
            }
        }
        return true;
    }

    /**
     * 材料"等价形态族"收尾:锭/热锭/粉/小堆粉/小撮粉/粒/杆/螺栓/螺丝 按 GT 材料量比
     * (ingot=ingotHot=dust=M、dustSmall=M/4、dustTiny=nugget=M/9、stick=M/2、bolt=screw=M/8)
     * 视作同一材料的等价形态 —— 族内只留"最便宜的独立产线单价",其余形态 = 单价 × 材料量比,
     * 不再展开自己的配方(树里当规则叶子,链被剪掉):即"到锭/粉就不再往下展开"。
     *
     * 这一步让族内价**与求值顺序无关**,不再出现"粉=锭、锭=4×热锭、热锭=粉/4"这类互相钉死的
     * 任意定点(实测先落 3070、后落 204800 都是这么来的)。返回被重写的条目数。
     */
    public int materializeMaterialFamilies() {
        Map<Object, List<ItemKey>> byMaterial = new HashMap<>();
        List<ItemKey> seen = new ArrayList<>(known.keySet());
        seen.addAll(derivedKeys);
        for (ItemKey k : seen) {
            if (!GtMachines.isMaterialForm(k)) {
                continue;
            }
            Object material = GtMachines.materialIdentity(k);
            if (material == null) {
                continue;
            }
            byMaterial.computeIfAbsent(material, m -> new ArrayList<>());
        }
        int written = 0;
        for (Object material : new ArrayList<>(byMaterial.keySet())) {
            List<ItemKey> members = GtMachines.materialFormKeys(material);
            // 族基准 = 族内"值/材料量"最小者(最便宜的独立产线)
            ItemKey baseKey = null;
            BigInteger baseValue = null;
            long baseAmount = 0L;
            for (ItemKey k : members) {
                BigInteger v = known.get(k);
                long amt = GtMachines.materialAmount(k);
                if (!EmcMath.isPositive(v) || amt <= 0) {
                    continue;
                }
                if (baseKey == null || EmcMath.cmp(EmcMath.mul(v, baseAmount), EmcMath.mul(baseValue, amt)) < 0) {
                    baseKey = k;
                    baseValue = v;
                    baseAmount = amt;
                }
            }
            if (baseKey == null) {
                continue; // 族内没有任何独立产线价:保持原样(纯环材料仍按老办法破环)
            }
            for (ItemKey k : members) {
                long amt = GtMachines.materialAmount(k);
                if (amt <= 0) {
                    continue;
                }
                BigInteger v = EmcMath.div(EmcMath.mul(baseValue, amt), baseAmount);
                BigInteger old = known.get(k);
                if (old == null || EmcMath.cmp(old, v) != 0) {
                    known.put(k, v);
                    written++;
                }
                if (k.equals(baseKey)) {
                    foldedForms.remove(k); // 基准形态保留自己的独立配方(树里能看到它怎么来)
                } else {
                    foldedForms.add(k);
                    chosen.remove(k);
                    pickedOptions.remove(k);
                }
            }
        }
        return written;
    }

    /**
     * 收尾:把派生值(份量折算形态、无配方粉的兜底折算)**按最终基准价**重算并落 known。
     * 必须在所有求值/补偿/镜像轮次之后、collectFinalValues() 之前调用 —— 派生值只在这是终值,
     * 中途任何轮次里基数变了都不该被它们记下来(否则小堆粉永远停在"粉还是 12800 时"的 3200)。
     * 返回被(重新)落表的条目数。
     */
    public int materializeDerived() {
        int written = 0;
        // 派生链最长 2 层(份量形态->基准形态;真环在上面的求值里已被切断),两轮足够收敛
        for (int round = 0; round < 2; round++) {
            int changedNow = 0;
            for (ItemKey key : new ArrayList<>(derivedKeys)) {
                BigInteger v = GtMachines.materialFractionValue(key, this, new ArrayDeque<>());
                if (v == null) {
                    continue; // 基准始终不可得(真环/基准无价):保持无值,不注册
                }
                BigInteger old = known.get(key);
                if (old == null || EmcMath.cmp(old, v) != 0) {
                    known.put(key, v);
                    written++;
                    changedNow++;
                }
            }
            if (changedNow == 0) {
                break;
            }
        }
        return written;
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
            // 流体 0 缓存清掉:若其产者/锚在上轮被定价,本轮重估能取到正价
            java.util.Iterator<String> fit = fluidMemo.keySet()
                .iterator();
            while (fit.hasNext()) {
                BigInteger fv = fluidMemo.get(fit.next());
                if (!EmcMath.isPositive(fv)) {
                    fit.remove();
                }
            }
            for (ItemKey key : new ArrayList<>(producers.keySet())) {
                BigInteger v = known.get(key);
                if (v == null || EmcMath.isPositive(v)) {
                    continue;
                }
                known.remove(key);
                chosen.remove(key);
                pickedOptions.remove(key);
                BigInteger nv = eval(key, new ArrayDeque<>());
                if (EmcMath.isPositive(nv)) {
                    resolved++;
                    changed = true;
                }
            }
            guard++;
        } while (changed && guard < 8);
        return resolved;
    }
}
