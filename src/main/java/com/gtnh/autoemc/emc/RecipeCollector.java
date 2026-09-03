package com.gtnh.autoemc.emc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.gtnh.autoemc.api.recipe.RecipeScan;
import com.gtnh.autoemc.api.recipe.RecipeSource;

/**
 * 配方源聚合器:把"产出配方"从各来源收集进统一产物表 producers。
 *
 * <p>
 * 来源 = 一个 {@link RecipeSource} 实现:
 * <ol>
 * <li>crafting — 工作台(CraftingManager 全部 IRecipe,含大量 mod 注册进合成列表的自定义子类);</li>
 * <li>smelting — 原版熔炉冶炼(FurnaceRecipes,视为蒸汽时代以下的单方块机器);</li>
 * <li>gt — GT 机器配方(GtMachines 遍历 RecipeMap.ALL_RECIPE_MAPS,单方块/多方块按配置区分,
 * 含 GT++/bartworks 等附属注册进 RecipeMap 的机器)。</li>
 * </ol>
 * 新增某 mod 的支持 = 实现 {@link RecipeSource} 并在 {@link #register} 注册(必须在首次
 * collect 前,即本 mod 的 preInit/init/postInit 阶段)。
 *
 * <p>
 * 计价规则:配方里参与消耗的"材料"才计入成本;工具(oredict craftingTool* / GT 工具物品 /
 * 有容器物品且合成后不消耗)与流体输入视为不参与物品总价值计算 —— 该槽位不产生成本,
 * 但配方仍然有效。
 */
public final class RecipeCollector {

    private static final Logger LOG = LogManager.getLogger("AutoEMC");

    /**
     * 已注册配方源。注册顺序 = 收集顺序 = 指纹行拼接顺序:内置三源(crafting -> smelting -> gt)
     * 的相对顺序与历史指纹逐字节对应,不要调换;新源只能追加。
     */
    private static final List<RecipeSource> SOURCES = new ArrayList<>();

    static {
        SOURCES.add(new CraftingSource());
        SOURCES.add(new SmeltingSource());
        SOURCES.add(new GtSource());
        SOURCES.add(new AssemblyLineSource());
        SOURCES.add(new AvaritiaSource());
    }

    private RecipeCollector() {}

    /** 已注册配方源(不可变视图,注册顺序 = 收集顺序 = 指纹行顺序)。 */
    public static List<RecipeSource> sources() {
        return Collections.unmodifiableList(SOURCES);
    }

    /**
     * 注册额外配方源(为某个 mod 加配方支持时调用)。
     * 必须在首次 {@link #collect} 之前注册 —— 收集发生在 FMLServerStartedEvent,所以注册点
     * 只能放在本 mod 的 preInit / init / postInit(或同样更早的 mod 加载期代码)。
     * 运行中注册不会生效,重复 id 会被忽略并告警。
     */
    public static void register(RecipeSource source) {
        if (source == null) {
            return;
        }
        for (RecipeSource s : SOURCES) {
            if (s.id()
                .equals(source.id())) {
                LOG.warn("Recipe source '{}' already registered, duplicate ignored.", source.id());
                return;
            }
        }
        SOURCES.add(source);
        LOG.info("Registered recipe source: {} ({})", source.id(), source.description());
    }

    /** 遍历所有可用源收集配方。单源异常被隔离(记日志继续),不影响其他源与后续求值。 */
    public static void collect(Map<ItemKey, List<EmcRecipe>> producers, EmcStats stats) {
        List<RecipeSource> all = sources();
        StringBuilder avail = new StringBuilder();
        for (RecipeSource s : all) {
            if (avail.length() > 0) {
                avail.append(", ");
            }
            avail.append(s.id());
            try {
                avail.append(s.isAvailable() ? "(on)" : "(off)");
            } catch (Throwable t) {
                avail.append("(?)");
            }
        }
        LOG.info("Recipe sources [{}]: {}", all.size(), avail);

        for (RecipeSource s : all) {
            boolean ok;
            try {
                ok = s.isAvailable();
            } catch (Throwable t) {
                LOG.error("Recipe source {} availability check failed, source skipped.", s.id(), t);
                continue;
            }
            if (!ok) {
                LOG.debug("Recipe source {} unavailable ({}), skipped.", s.id(), s.description());
                continue;
            }
            long t0 = System.nanoTime();
            try {
                s.collect(producers, stats);
                LOG.debug("Recipe source {} collected in {} ms.", s.id(), (System.nanoTime() - t0) / 1_000_000);
            } catch (Throwable t) {
                // 单源失败只废掉该源:注册/求值流程继续,产物表里已有其他源的数据
                LOG.error("Recipe source {} failed to collect recipes, remaining sources continue.", s.id(), t);
            }
        }
    }

    // ============================================================
    // 源 1:工作台合成
    // ============================================================

    private static final class CraftingSource implements RecipeSource {

        @Override
        public String id() {
            return "crafting";
        }

        @Override
        public String description() {
            return "工作台合成 (CraftingManager 全部 IRecipe)";
        }

        @Override
        public List<String> fingerprintLines(EmcStats stats) {
            // 与历史指纹的 craft=N 行逐字节一致
            return Collections.singletonList("craft=" + stats.craftingRecipes);
        }

        @Override
        public void collect(Map<ItemKey, List<EmcRecipe>> producers, EmcStats stats) {
            // 后台线程扫描:主线程可能仍在注册配方(serverStarted 之后),快照副本迭代避免 CME
            List<IRecipe> recipes = new ArrayList<>();
            for (Object o : new ArrayList<>(
                CraftingManager.getInstance()
                    .getRecipeList())) {
                if (o instanceof IRecipe) {
                    recipes.add((IRecipe) o);
                }
            }
            // recipe 级隔离(RecipeScan):单条配方(输出解析/材料解析)抛异常只丢这一条,
            // 其余配方照常;内容性跳过(wildcard/未知类型等)在 handler 内部计数,不算 error
            RecipeScan.Result res = RecipeScan.forEachRecipe(
                id(),
                "crafting-manager",
                recipes,
                r -> outOf(r),
                r -> handleCraftingRecipe(r, producers, stats));
            stats.craftingRecipes += res.handled;
            stats.craftingSkippedError += res.errors;
        }

        /** 单条工作台配方 -> 是否登记为产出者;内容性跳过在内部计数并返回 false。抛异常 = 该配方失败。 */
        private static boolean handleCraftingRecipe(IRecipe recipe, Map<ItemKey, List<EmcRecipe>> producers,
            EmcStats stats) {
            ItemStack out = recipe.getRecipeOutput();
            if (out == null || out.getItem() == null) {
                return false;
            }
            if (out.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
                stats.craftingSkippedWildcard++;
                return false;
            }
            if (out.stackSize <= 0) {
                return false;
            }

            List<EmcIngredient> inputs = parseCraftingInputs(recipe, stats);
            if (inputs == null) {
                // 已计入跳过原因
                return false;
            }
            ItemKey outKey = ItemKey.of(out);
            producers.computeIfAbsent(outKey, k -> new ArrayList<>())
                .add(new EmcRecipe(outKey, out.stackSize, inputs, EmcRecipe.CAT_CRAFTING, 0, "crafting", 3, 0));
            return true;
        }

        /** 配方输出描述(reg@dmg),用于错误日志;失败回落 "?"。 */
        private static String outOf(IRecipe recipe) {
            try {
                if (recipe == null) {
                    return "?";
                }
                ItemStack out = recipe.getRecipeOutput();
                return out == null || out.getItem() == null ? "?"
                    : ItemKey.of(out)
                        .toString();
            } catch (Throwable t) {
                return "?";
            }
        }

        /**
         * 解析合成配方的材料槽。
         * 返回 null = 整条配方不可用(未知类型/通配 meta/无材料);
         * 工具槽(不消耗的容器物品、craftingTool oredict、GT 工具)被丢弃,不产生成本。
         */
        private static List<EmcIngredient> parseCraftingInputs(IRecipe recipe, EmcStats stats) {
            List<Object> slots = new ArrayList<>();
            boolean knownType = true;
            boolean shaped = true;
            if (recipe instanceof ShapedRecipes) {
                ItemStack[] items = ((ShapedRecipes) recipe).recipeItems;
                for (ItemStack s : items) {
                    if (s != null) {
                        slots.add(s);
                    }
                }
            } else if (recipe instanceof ShapelessRecipes) {
                shaped = false;
                for (ItemStack s : ((ShapelessRecipes) recipe).recipeItems) {
                    if (s != null) {
                        slots.add(s);
                    }
                }
            } else if (recipe instanceof ShapedOreRecipe) {
                Object[] input = ((ShapedOreRecipe) recipe).getInput();
                if (input != null) {
                    for (Object s : input) {
                        if (s != null) {
                            slots.add(s);
                        }
                    }
                }
            } else if (recipe instanceof ShapelessOreRecipe) {
                shaped = false;
                for (Object s : ((ShapelessOreRecipe) recipe).getInput()) {
                    if (s != null) {
                        slots.add(s);
                    }
                }
            } else {
                knownType = false;
            }

            if (!knownType) {
                stats.noteUnknownRecipeClass(recipe.getClass());
                return null;
            }
            if (slots.isEmpty()) {
                // 无输入凭空产出:不算配方
                stats.noteUnknownRecipeClass(recipe.getClass());
                return null;
            }

            List<EmcIngredient> inputs = new ArrayList<>(slots.size());
            // ingredient 级隔离(第三层):每个材料槽独立 try —— 展开/解析某槽抛异常
            // (畸形 ore 名、物品注册表查询失败、mod 物品的 copy/meta 异常等)只丢这一条
            // 配方并记录槽位上下文,不炸整个源(对照 PEI 的 Utils.getMatchingStacks/addIngredient
            // 对单个 ingredient 展开的 try/catch)。
            for (int si = 0; si < slots.size(); si++) {
                Object slot = slots.get(si);
                try {
                    if (slot instanceof String) {
                        String oreName = (String) slot;
                        if (oreName.startsWith("craftingTool")) {
                            // 工具不参与计价
                            stats.craftingToolSlots++;
                            continue;
                        }
                        List<ItemKey> options = new ArrayList<>();
                        for (ItemStack s : OreDictionary.getOres(oreName)) {
                            if (s == null || s.getItem() == null) {
                                continue;
                            }
                            if (s.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
                                continue;
                            }
                            options.add(ItemKey.of(s));
                        }
                        if (options.isEmpty()) {
                            // 该 ore 名在服务器上没有任何注册物品:整条配方不可用
                            stats.noteUnknownRecipeClass(recipe.getClass());
                            return null;
                        }
                        inputs.add(EmcIngredient.alternatives(options, 1));
                    } else if (slot instanceof ItemStack) {
                        ItemStack s = ((ItemStack) slot).copy();
                        if (s.getItem() == null) {
                            continue;
                        }
                        if (s.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
                            stats.craftingSkippedWildcard++;
                            return null;
                        }
                        if (isToolLike(s)) {
                            // 工具不参与计价
                            stats.craftingToolSlots++;
                            continue;
                        }
                        // 形格:每格 1 个;无序合成:stackSize 就是所需数量
                        int qty = shaped ? 1 : Math.max(1, s.stackSize);
                        inputs.add(EmcIngredient.fixed(ItemKey.of(s), qty));
                    } else if (slot instanceof List) {
                        // ore 配方的可选项(通常来自 oredict 展开):剔除工具选项,其余参与计价
                        List<ItemKey> options = new ArrayList<>();
                        int qty = 1;
                        for (Object opt : (List<?>) slot) {
                            if (!(opt instanceof ItemStack)) {
                                continue;
                            }
                            ItemStack s = ((ItemStack) opt).copy();
                            if (s.getItem() == null || s.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
                                continue;
                            }
                            if (isToolLike(s)) {
                                stats.craftingToolSlots++;
                                continue;
                            }
                            options.add(ItemKey.of(s));
                            qty = Math.max(qty, s.stackSize);
                        }
                        if (options.isEmpty()) {
                            // 这个槽位全是工具(或空)-> 槽位不参与计价,配方仍有效
                            continue;
                        }
                        inputs.add(EmcIngredient.alternatives(options, qty));
                    } else {
                        stats.noteUnknownRecipeClass(recipe.getClass());
                        return null;
                    }
                } catch (LinkageError | Exception t) {
                    stats.craftingSkippedIngredient++;
                    LOG.error(
                        "Crafting recipe {} (class {}) failed resolving ingredient slot #{} ({}), recipe skipped: {}",
                        outOf(recipe),
                        recipe.getClass()
                            .getName(),
                        si,
                        slotLabel(slot),
                        t);
                    return null;
                }
            }
            if (inputs.isEmpty()) {
                // 所有材料都是工具/流体类 -> 无消耗材料,配方视为不可用
                stats.noteUnknownRecipeClass(recipe.getClass());
                return null;
            }
            return inputs;
        }

        /** 槽位内容描述,用于 ingredient 级错误日志(oregistry 名/物品 reg@dmg/变体数)。 */
        private static String slotLabel(Object slot) {
            if (slot instanceof String) {
                return "ore:" + slot;
            }
            if (slot instanceof ItemStack) {
                try {
                    ItemStack s = (ItemStack) slot;
                    return s.getItem() == null ? "stack:?"
                        : ItemKey.of(s)
                            .toString();
                } catch (Throwable t) {
                    return "stack:?";
                }
            }
            if (slot instanceof List) {
                return "list(" + ((List<?>) slot).size() + " variants)";
            }
            return slot == null ? "null"
                : slot.getClass()
                    .getName();
        }

        /**
         * "工具"判定:
         * 1. GT 工具物品(MetaGeneratedTool 子类)
         * 2. 有容器物品且合成后不返还(如桶/瓶这类参与但不消耗的)
         */
        private static boolean isToolLike(ItemStack stack) {
            if (GtMachines.available() && GtMachines.isGtToolItem(stack.getItem())) {
                return true;
            }
            if (GtMachines.available() && GtMachines.isOneTimeItem(stack)) {
                return true;
            }
            try {
                return !stack.getItem()
                    .doesContainerItemLeaveCraftingGrid(stack);
            } catch (Exception e) {
                return false;
            }
        }
    }

    // ============================================================
    // 源 2:原版熔炉冶炼
    // ============================================================

    private static final class SmeltingSource implements RecipeSource {

        @Override
        public String id() {
            return "smelting";
        }

        @Override
        public String description() {
            return "原版熔炉冶炼 (FurnaceRecipes)";
        }

        @Override
        public List<String> fingerprintLines(EmcStats stats) {
            // 与历史指纹的 smelt=N 行逐字节一致
            return Collections.singletonList("smelt=" + stats.smeltRecipes);
        }

        @Override
        public void collect(Map<ItemKey, List<EmcRecipe>> producers, EmcStats stats) {
            Map<ItemStack, ItemStack> list = FurnaceRecipes.smelting()
                .getSmeltingList();
            List<Map.Entry<ItemStack, ItemStack>> entries = new ArrayList<>(list.entrySet());
            // recipe 级隔离:单条冶炼配方抛异常只丢这一条
            RecipeScan.Result res = RecipeScan.forEachRecipe(
                id(),
                "furnace-smelting-list",
                entries,
                e -> smeltOutOf(e.getValue()),
                e -> handleSmeltingEntry(e, producers, stats));
            stats.smeltRecipes += res.handled;
            stats.smeltSkippedError += res.errors;
        }

        /** 单条冶炼配方 -> 是否登记为产出者;内容性跳过在内部计数并返回 false。 */
        private static boolean handleSmeltingEntry(Map.Entry<ItemStack, ItemStack> entry,
            Map<ItemKey, List<EmcRecipe>> producers, EmcStats stats) {
            ItemStack in = entry.getKey();
            ItemStack out = entry.getValue();
            if (in == null || out == null || in.getItem() == null || out.getItem() == null) {
                return false;
            }
            if (in.getItemDamage() == OreDictionary.WILDCARD_VALUE
                || out.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
                stats.smeltSkippedWildcard++;
                return false;
            }
            ItemKey outKey = ItemKey.of(out);
            List<EmcIngredient> inputs = new ArrayList<>(1);
            inputs.add(EmcIngredient.fixed(ItemKey.of(in), Math.max(1, in.stackSize)));
            producers.computeIfAbsent(outKey, k -> new ArrayList<>())
                .add(
                    new EmcRecipe(
                        outKey,
                        Math.max(1, out.stackSize),
                        inputs,
                        EmcRecipe.CAT_SINGLE,
                        EmcRecipe.TIER_STEAM,
                        "smelting",
                        3,
                        0));
            return true;
        }

        /** 冶炼输出描述(reg@dmg),用于错误日志;失败回落 "?"。 */
        private static String smeltOutOf(ItemStack out) {
            try {
                return out == null || out.getItem() == null ? "?"
                    : ItemKey.of(out)
                        .toString();
            } catch (Throwable t) {
                return "?";
            }
        }
    }

    // ============================================================
    // 源 3:GT 机器配方
    // ============================================================

    private static final class GtSource implements RecipeSource {

        @Override
        public String id() {
            return "gt";
        }

        @Override
        public String description() {
            return "GT 机器配方 (RecipeMap.ALL_RECIPE_MAPS,含 GT++/bartworks 等)";
        }

        @Override
        public boolean isAvailable() {
            return GtMachines.available();
        }

        @Override
        public List<String> fingerprintLines(EmcStats stats) {
            // 与历史指纹的 gtmaps=N + 每 map 一行 逐字节一致;未装 GT 时 gtmaps=0 同样要给出
            List<String> lines = new ArrayList<>(stats.gtMapRecipeCounts.size() + 2);
            lines.add("gtmaps=" + stats.gtMaps);
            for (Map.Entry<String, Integer> e : stats.gtMapRecipeCounts.entrySet()) {
                lines.add("map:" + e.getKey() + "=" + e.getValue());
            }
            // 流体反推产者条数:变化 -> 流体价值变化 -> 依赖流体成本的物品值变化,必须重算
            lines.add("fluidrecs=" + stats.gtFluidRecipes);
            return lines;
        }

        @Override
        public void collect(Map<ItemKey, List<EmcRecipe>> producers, EmcStats stats) {
            GtMachines.collect(producers, stats);
        }
    }

    // ============================================================
    // 源 4:GT 装配线(Assembly Line,数据棒注册表,不走 RecipeMap)
    // ============================================================

    private static final class AssemblyLineSource implements RecipeSource {

        @Override
        public String id() {
            return "gt-assemblyline";
        }

        @Override
        public String description() {
            return "GT 装配线 (RecipeAssemblyLine 数据棒注册表,非 RecipeMap)";
        }

        @Override
        public boolean isAvailable() {
            return GtMachines.available();
        }

        @Override
        public List<String> fingerprintLines(EmcStats stats) {
            // 新增源:装配线配方集合(实际登记数);该行加入后指纹变化 -> 自动全量重算一次
            return Collections.singletonList("assemblyline=" + stats.alRecipes);
        }

        @Override
        public void collect(Map<ItemKey, List<EmcRecipe>> producers, EmcStats stats) {
            GtMachines.collectAssemblyLine(producers, stats);
        }
    }

    // ============================================================
    // 源 5:Avaritia 大工作台(ExtremeCraftingManager,每格 1 材料)
    // ============================================================

    private static final class AvaritiaSource implements RecipeSource {

        @Override
        public String id() {
            return "avaritia";
        }

        @Override
        public String description() {
            return "Avaritia 大工作台 (ExtremeCraftingManager,每格 1 材料)";
        }

        @Override
        public boolean isAvailable() {
            return AvaritiaRecipes.available();
        }

        @Override
        public List<String> fingerprintLines(EmcStats stats) {
            // 新增源:大工作台实际登记配方数;加入后指纹变化 -> 自动全量重算一次
            return Collections.singletonList("avaritia=" + stats.avRecipes);
        }

        @Override
        public void collect(Map<ItemKey, List<EmcRecipe>> producers, EmcStats stats) {
            AvaritiaRecipes.collect(producers, stats);
        }
    }
}
