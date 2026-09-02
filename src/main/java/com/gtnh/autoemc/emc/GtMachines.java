package com.gtnh.autoemc.emc;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

import gregtech.api.enums.OrePrefixes;
import gregtech.api.enums.TierEU;
import gregtech.api.interfaces.INetworkUpdatableItem;
import gregtech.api.items.MetaGeneratedTool;
import gregtech.api.objects.ItemData;
import gregtech.api.recipe.RecipeCategory;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.util.GTOreDictUnificator;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTRecipeConstants;

/**
 * GregTech 配方采集(软依赖):只在 gregtech 加载时才真正引用 GT 类。
 * 结论均针对 GTNH 2.8.4 的 gregtech-5.09.51.482(新 RecipeMap API)。
 */
public final class GtMachines {

    /** TierEU.RECIPE_* 升序 —— 各级配方 EU/t 上限 */
    private static long[] recipeEUtCaps;

    private GtMachines() {}

    public static boolean available() {
        if (!AutoEmcConfig.hasGregTech()) {
            return false;
        }
        try {
            Class.forName("gregtech.api.recipe.RecipeMap");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** GT 工具物品(锯/锤/扳手/电钻…都挂在 MetaGeneratedTool 下) */
    public static boolean isGtToolItem(Item item) {
        return item instanceof MetaGeneratedTool;
    }

    /**
     * 链清洗判定:该物品是否属于"永远不该出现在配方输入链里"的工具/一次性物品。
     * 新鲜求值(RecipeCollector / GtMachines.collect)绝不会把这类物品选为材料输入;
     * 持久对齐链里出现它们 = 旧规则时代(工具过滤规则上线前)的残留数据,/view 回放前应剔除,
     * 否则树里会展开"工具怎么合成"。
     */
    public static boolean isToolItemForChains(ItemKey key) {
        if (!available() || key == null || key.item == null) {
            return false;
        }
        try {
            return isGtToolItem(key.item) || isOneTimeItem(key.toStack());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否属于任一 circuit* oredict(如 circuitMV)的成员 —— "电路板"候选(含假电路板) */
    public static boolean isCircuitBoardKey(ItemKey key) {
        if (!available() || key == null || key.item == null) {
            return false;
        }
        try {
            for (int oreId : OreDictionary.getOreIDs(key.toStack())) {
                String n = OreDictionary.getOreName(oreId);
                if (n != null && n.startsWith("circuit")) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // 忽略
        }
        return false;
    }

    /**
     * "假电路板"平均价:无产出配方的 circuit&lt;等级&gt; oredict 成员(如 dreamcraft CircuitMV,
     * GTNH 注册为 OrePrefixes.circuit + Materials.XXX 的替身电路,本身没有产出配方)取
     * 同 oredict 其他"有价"成员的平均价 —— 规则"任意电路板的价格 = 同等级电路板总价 / 数量"
     * (只对无价成员生效;有价成员是基准,不被改动;有价基准数量不足时返回 0 由调用方决定不缓存)。
     *
     * @return &gt;0 平均价;0 = 非电路板 / 一次性工具(编程电路等)/ 同级无任何有价成员
     */
    public static int circuitBoardAverage(ItemKey key, EmcEngine engine, Deque<ItemKey> stack) {
        if (!available() || key == null || engine == null) {
            return 0;
        }
        try {
            ItemStack self = key.toStack();
            if (isOneTimeItem(self)) {
                return 0; // 编程电路等一次性工具不套平均价规则
            }
            for (int oreId : OreDictionary.getOreIDs(self)) {
                String oreName = OreDictionary.getOreName(oreId);
                if (oreName == null || !oreName.startsWith("circuit")) {
                    continue;
                }
                long sum = 0;
                int count = 0;
                for (ItemStack member : new ArrayList<>(OreDictionary.getOres(oreName))) {
                    if (member == null || member.getItem() == null
                        || member.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
                        continue;
                    }
                    ItemKey mk = ItemKey.of(member);
                    if (mk.equals(key)) {
                        continue; // 自己不算进平均(只对"其他"有价成员取均值)
                    }
                    int v = engine.evalFraction(mk, stack);
                    if (v > 0) {
                        sum += v;
                        count++;
                    }
                }
                if (count > 0) {
                    return (int) Math.min(Integer.MAX_VALUE - 1, Math.max(1, sum / count));
                }
            }
        } catch (Throwable t) {
            // 求值失败按不可平均处理
        }
        return 0;
    }

    /**
     * GT 材料"份量"折算:小撮粉/小堆粉/粒 等没有产出配方(主要靠副产),但它们与同材料的
     * 粉/锭存在固定份量比(如 dustTiny = dust/9、dustSmall = dust/4、nugget = ingot/9)。
     * 无配方物品求值时若可折算到同材料基准份,则按份量比计价 —— 否则能量水晶这类
     * "粉→小撮粉→…"的链会在小撮粉处断掉。
     * 返回 0 = 不可折算(非 GT 材料/无基准份/基准份无价)。
     */
    public static int materialFractionValue(ItemKey key, EmcEngine engine, Deque<ItemKey> stack) {
        if (!available() || key == null || engine == null) {
            return 0;
        }
        try {
            ItemData d = GTOreDictUnificator.getAssociation(key.toStack());
            if (d == null || d.mPrefix == null || d.mMaterial == null || d.mMaterial.mMaterial == null) {
                return 0;
            }
            OrePrefixes base;
            if (d.mPrefix == OrePrefixes.dustTiny || d.mPrefix == OrePrefixes.dustSmall) {
                base = OrePrefixes.dust;
            } else if (d.mPrefix == OrePrefixes.dust) {
                base = OrePrefixes.ingot; // 最大的粉 = 锭
            } else if (d.mPrefix == OrePrefixes.nugget) {
                base = OrePrefixes.ingot;
            } else if (d.mPrefix == OrePrefixes.bolt || d.mPrefix == OrePrefixes.screw) {
                base = OrePrefixes.stick; // 螺栓/螺丝 = 杆/2
            } else {
                return 0;
            }
            if (d.mPrefix.mMaterialAmount <= 0 || base.mMaterialAmount <= 0) {
                return 0;
            }
            ItemStack baseStack = GTOreDictUnificator.get(base, d.mMaterial.mMaterial, 1);
            if (baseStack == null || baseStack.getItem() == null) {
                return 0;
            }
            ItemKey baseKey = ItemKey.of(baseStack);
            if (baseKey.equals(key)) {
                return 0;
            }
            int baseVal = engine.evalFraction(baseKey, stack);
            if (baseVal <= 0) {
                return 0;
            }
            long v = (long) baseVal * d.mPrefix.mMaterialAmount / base.mMaterialAmount;
            return (int) Math.min(Integer.MAX_VALUE - 1, Math.max(0, v));
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 份量形态:这些前缀直接按材料份量折算(最大的粉=锭、螺栓/螺丝=杆/2、小撮/小堆粉=粉/9…),
     * 求值时不再展开其机器/工作台配方,直接走 materialFractionValue + known 缓存复用。
     */
    public static boolean isFractionForm(ItemKey key) {
        if (!available() || key == null) {
            return false;
        }
        try {
            ItemData d = GTOreDictUnificator.getAssociation(key.toStack());
            if (d == null || d.mPrefix == null) {
                return false;
            }
            OrePrefixes p = d.mPrefix;
            return p == OrePrefixes.dust || p == OrePrefixes.dustTiny
                || p == OrePrefixes.dustSmall
                || p == OrePrefixes.nugget
                || p == OrePrefixes.bolt
                || p == OrePrefixes.screw;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 一次性物品/工具(编程/配置电路、ggfab 工具/模具/铸模等):机器配方里不消耗,不参与计价也不展开 */
    public static boolean isOneTimeItem(ItemStack in) {
        if (in == null || in.getItem() == null) {
            return false;
        }
        Item item = in.getItem();
        // 编程/配置电路:GT 的 ItemIntegratedCircuit、GT++ 的 GTPPIntegratedCircuitItem(优质电子电路版)
        // 都实现 INetworkUpdatableItem,机器配方里不消耗,是"一次性工具"。
        if (item instanceof INetworkUpdatableItem) {
            return true;
        }
        // ggfab(千兆克工厂)的哑物品 = 工具/模具/铸模(一次性,不消耗);按类名判断避免硬依赖
        String cn = item.getClass()
            .getName();
        return cn.startsWith("ggfab.");
    }

    /**
     * GT 材料"形态等级":锭/宝石=6(直接锚定 PE 锭),粉=5,小堆粉/粒=4,小撮粉=3,
     * 矿石=1(无价源头),其他(板/杆/齿轮/方块/电池等)=3 中性。等级越高越"锚定到锭"。
     * 用于"粉末优先锭粉碎、粉末合成粉末优先用最大粉末"。
     */
    public static int formRank(ItemStack stack) {
        if (!available() || stack == null || stack.getItem() == null) {
            return 3;
        }
        try {
            ItemData d = GTOreDictUnificator.getAssociation(stack);
            if (d == null || d.mPrefix == null) {
                return 3;
            }
            OrePrefixes p = d.mPrefix;
            if (p == OrePrefixes.ingot || p == OrePrefixes.gem) {
                return 6;
            }
            if (p == OrePrefixes.dust) {
                return 5;
            }
            if (p == OrePrefixes.dustSmall || p == OrePrefixes.nugget) {
                return 4;
            }
            if (p == OrePrefixes.dustTiny) {
                return 3;
            }
            if (p.name()
                .startsWith("ore")) {
                return 1;
            }
            return 3;
        } catch (Throwable t) {
            return 3;
        }
    }

    private static void ensureTierCaps() {
        if (recipeEUtCaps != null) {
            return;
        }
        List<Long> caps = new ArrayList<>();
        try {
            for (Field f : TierEU.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getName()
                    .startsWith("RECIPE_")) {
                    caps.add(f.getLong(null));
                }
            }
        } catch (Exception e) {
            // 反射失败时退化为空表,所有配方按等级 0 计
        }
        caps.sort(null);
        recipeEUtCaps = new long[caps.size()];
        for (int i = 0; i < caps.size(); i++) {
            recipeEUtCaps[i] = caps.get(i);
        }
        if (recipeEUtCaps.length == 0) {
            recipeEUtCaps = new long[] { 8, 32, 128, 512, 2048, 8192, 32768, 131072, 524288, 2097152 };
        }
    }

    /** mEUt → 电压等级索引(0=ULV,1=LV,...);超出上限返回最后一个索引+1 */
    private static int voltageRankOf(int eut) {
        ensureTierCaps();
        for (int i = 0; i < recipeEUtCaps.length; i++) {
            if ((long) eut <= recipeEUtCaps[i]) {
                return i;
            }
        }
        return recipeEUtCaps.length;
    }

    /**
     * 遍历 RecipeMap.ALL_RECIPE_MAPS,把每张 map 里可用的产出配方登记成生产者。
     * 只取主输出(mOutputs[0],数量=stackSize),副产物/概率产物不计入成本归属。
     */
    public static void collect(Map<ItemKey, List<EmcRecipe>> producers, EmcStats stats) {
        if (!available()) {
            return;
        }
        ensureTierCaps();

        for (RecipeMap<?> map : new ArrayList<>(RecipeMap.ALL_RECIPE_MAPS.values())) {
            String mapName = map.unlocalizedName;
            int category = AutoEmcConfig.multiMaps.contains(mapName) ? EmcRecipe.CAT_MULTI : EmcRecipe.CAT_SINGLE;
            boolean steamCapable = AutoEmcConfig.steamMaps.contains(mapName);
            int mapCount = 0;

            for (GTRecipe r : new ArrayList<>(map.getAllRecipes())) {
                if (r == null) {
                    continue;
                }
                if (!r.mEnabled || r.mFakeRecipe) {
                    stats.gtSkippedDisabled++;
                    continue;
                }
                if (r.mHidden && !AutoEmcConfig.includeHiddenRecipes) {
                    stats.gtSkippedDisabled++;
                    continue;
                }
                // 回收类配方不参与定价:GT 用 RECYCLE 元数据 + "*_recycling" 分类标记
                // (逆向粉碎/逆向冶炼/电弧炉回收,即把成品/材料打回粉末或锭,会形成环或低估价值)。
                if (r.getMetadataOrDefault(GTRecipeConstants.RECYCLE, false)) {
                    stats.gtSkippedRecycle++;
                    continue;
                }
                RecipeCategory rc = r.getRecipeCategory();
                if (rc != null && rc.unlocalizedName.contains("recycling")) {
                    stats.gtSkippedRecycle++;
                    continue;
                }
                boolean hasFluidInput = r.mFluidInputs != null && r.mFluidInputs.length > 0;
                if (hasFluidInput) {
                    // 流体不参与计价,配方仍有效
                    stats.gtRecipesWithFluid++;
                }
                if (r.mOutputs == null || r.mOutputs.length == 0 || r.mOutputs[0] == null) {
                    stats.gtSkippedNoOutput++;
                    continue;
                }
                boolean hasItemInput = r.mInputs != null && r.mInputs.length > 0;
                if (!hasItemInput && !hasFluidInput) {
                    // 无任何输入的"凭空产出"(如创造类)不算配方
                    stats.gtSkippedNoOutput++;
                    continue;
                }
                if (r.mChances != null && r.mChances.length > 0 && r.mChances[0] < 10000) {
                    // 主输出带概率 → 不可靠的产出者,跳过
                    stats.gtSkippedChance++;
                    continue;
                }

                ItemStack out = r.mOutputs[0];
                if (out.getItem() == null || out.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
                    stats.gtSkippedWildcard++;
                    continue;
                }
                ItemKey outKey = ItemKey.of(out);
                int outQty = Math.max(1, out.stackSize);

                int tier;
                if (steamCapable && r.mEUt <= AutoEmcConfig.steamMaxEUt) {
                    // 蒸汽时代就能做 → 不选 LV
                    tier = EmcRecipe.TIER_STEAM;
                } else {
                    tier = voltageRankOf(r.mEUt);
                }

                // 只把"物品输入"算进成本;流体输入忽略
                List<EmcIngredient> inputs = new ArrayList<>();
                boolean bad = false;
                if (hasItemInput) {
                    for (ItemStack in : r.mInputs) {
                        if (in == null || in.getItem() == null) {
                            stats.gtSkippedNoOutput++;
                            bad = true;
                            break;
                        }
                        if (in.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
                            stats.gtSkippedWildcard++;
                            bad = true;
                            break;
                        }
                        if (isOneTimeItem(in)) {
                            // 一次性物品(编程电路、ggfab 工具/模具/铸模):不消耗,不参与计价也不展开
                            stats.gtToolSlots++;
                            continue;
                        }
                        inputs.add(EmcIngredient.fixed(ItemKey.of(in), Math.max(1, in.stackSize)));
                    }
                }
                if (bad) {
                    continue;
                }
                // 纯流体输入配方(inputs 为空)→ 材料成本为 0,仍登记为产出者

                // 输入形态等级:取所有输入选项里最"锚定"的(锭/宝石>粉>小堆粉/粒>小撮粉>矿石),
                // 用于"粉末优先锭粉碎、粉末合成粉末优先用最大粉末"。
                int formRank = 3;
                for (EmcIngredient ing : inputs) {
                    for (ItemKey opt : ing.options) {
                        formRank = Math.max(formRank, formRank(opt.toStack()));
                    }
                }

                // 流体输入总量(L):"合成路径液体少"优先,越少越优先
                int fluidAmount = 0;
                if (r.mFluidInputs != null) {
                    for (FluidStack f : r.mFluidInputs) {
                        if (f != null) {
                            fluidAmount += f.amount;
                        }
                    }
                }

                producers.computeIfAbsent(outKey, k -> new ArrayList<>())
                    .add(new EmcRecipe(outKey, outQty, inputs, category, tier, mapName, formRank, fluidAmount));
                stats.gtRecipes++;
                mapCount++;
            }
            stats.gtMapRecipeCounts.put(mapName, mapCount);
        }
        stats.gtMaps = RecipeMap.ALL_RECIPE_MAPS.size();
    }
}
