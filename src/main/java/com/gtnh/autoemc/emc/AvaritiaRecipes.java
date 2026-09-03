package com.gtnh.autoemc.emc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.oredict.ShapelessOreRecipe;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.gtnh.autoemc.api.recipe.RecipeScan;

import fox.spiteful.avaritia.crafting.ExtremeCraftingManager;
import fox.spiteful.avaritia.crafting.ExtremeShapedOreRecipe;
import fox.spiteful.avaritia.crafting.ExtremeShapedRecipe;
import fox.spiteful.avaritia.crafting.ExtremeShapelessRecipe;

/**
 * Avaritia(无尽贪婪 GTNH fork)大工作台配方收集。
 *
 * <p>
 * 大工作台(Extreme Crafting Table)配方在 {@link ExtremeCraftingManager#getRecipeList()}
 * 里(与 NEI 同源),分三类:ExtremeShapedRecipe(格子=ItemStack[])、ExtremeShapedOreRecipe
 * (getInput() 展开后的格子:ore 名 String / ItemStack / List)、ExtremeShapelessRecipe;
 * manager.addShapelessOreRecipe 还会把原版 Forge ShapelessOreRecipe 放进同一张表。
 *
 * <p>
 * 计价语义(字节码核对 Avaritia-1.77):ExtremeShapedRecipe/ExtremeShapedOreRecipe 的
 * checkMatch 只按"格子里是什么物品"匹配,不比较数量 —— 消费按每格 1 个,一张 9x9 配方
 * 最多表达 81 个材料。因此每格按 1 个计价(格子 ItemStack.stackSize&gt;1 也按 1,与匹配
 * 语义一致;若出现 &gt;1 格子说明装的是别的语义,见 singularity 日志核对)。
 * 铁奇点之类"1000/7296 个材料"配方不走大工作台格子(如 中子压缩机 7296 铁块 -> 奇点,
 * 属 GT RecipeMap 的 neutroniumcompressor map,由 gt 源负责),不属于本源。
 *
 * <p>
 * 软依赖:仅当 Avaritia mod 加载时才引用其类(available() 门禁在调用方先判)。
 */
public final class AvaritiaRecipes {

    private static final Logger LOG = LogManager.getLogger("AutoEMC");

    private AvaritiaRecipes() {}

    public static boolean available() {
        return cpw.mods.fml.common.Loader.isModLoaded("Avaritia");
    }

    /** 遍历大工作台配方注册表,登记成生产者。单配方异常由 RecipeScan 隔离。 */
    public static void collect(Map<ItemKey, List<EmcRecipe>> producers, EmcStats stats) {
        if (!available()) {
            return;
        }
        List<IRecipe> recipes;
        try {
            recipes = new ArrayList<>(
                ExtremeCraftingManager.getInstance()
                    .getRecipeList());
        } catch (Throwable t) {
            stats.avSkippedError++;
            LOG.error("Avaritia ExtremeCraftingManager recipe list unavailable, source skipped.", t);
            return;
        }
        RecipeScan.Result res = RecipeScan.forEachRecipe(
            "avaritia",
            "extreme-crafting-manager",
            recipes,
            r -> outDesc(r),
            r -> collectOne(r, producers, stats));
        stats.avRecipes += res.handled;
        stats.avSkippedError += res.errors;
    }

    /** 单条大工作台配方 -> 是否登记为产出者;内容性跳过(未知类型/通配/无输入)内部计数并返回 false。 */
    private static boolean collectOne(IRecipe recipe, Map<ItemKey, List<EmcRecipe>> producers, EmcStats stats) {
        if (recipe == null) {
            return false;
        }
        ItemStack out = recipe.getRecipeOutput();
        if (out == null || out.getItem() == null) {
            return false;
        }
        if (out.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
            stats.avSkipped++;
            return false;
        }
        List<EmcIngredient> inputs = parseInputs(recipe, stats);
        if (inputs == null || inputs.isEmpty()) {
            return false;
        }
        ItemKey outKey = ItemKey.of(out);
        producers.computeIfAbsent(outKey, k -> new ArrayList<>())
            .add(new EmcRecipe(outKey, Math.max(1, out.stackSize), inputs, EmcRecipe.CAT_MULTI, 0, "avaritia", 3, 0));
        logSingularityIfAny(outKey, out, inputs);
        return true;
    }

    /** 解析单条配方输入;null = 配方不可用(原因已计数)。 */
    private static List<EmcIngredient> parseInputs(IRecipe recipe, EmcStats stats) {
        if (recipe instanceof ExtremeShapedRecipe) {
            return parseCells(((ExtremeShapedRecipe) recipe).recipeItems, stats);
        }
        if (recipe instanceof ExtremeShapedOreRecipe) {
            return parseCells(((ExtremeShapedOreRecipe) recipe).getInput(), stats);
        }
        if (recipe instanceof ExtremeShapelessRecipe) {
            List<EmcIngredient> inputs = new ArrayList<>();
            for (ItemStack s : ((ExtremeShapelessRecipe) recipe).recipeItems) {
                if (s == null) {
                    continue;
                }
                if (!addFixed(inputs, s, stats, recipe)) {
                    return null;
                }
            }
            return inputs;
        }
        if (recipe instanceof ShapelessOreRecipe) {
            // manager.addShapelessOreRecipe 返回原版 Forge 无序 ore 配方,也在同一张表里
            List<EmcIngredient> inputs = new ArrayList<>();
            for (Object slot : ((ShapelessOreRecipe) recipe).getInput()) {
                if (!addSlot(inputs, slot, stats, recipe)) {
                    return null;
                }
            }
            return inputs;
        }
        stats.avSkipped++;
        return null;
    }

    /** 格子数组(9x9 展开,可能含 null)-> 材料列表;失败返回 null(已计数)。 */
    private static List<EmcIngredient> parseCells(Object[] cells, EmcStats stats) {
        if (cells == null || cells.length == 0) {
            stats.avSkipped++;
            return null;
        }
        List<EmcIngredient> inputs = new ArrayList<>(cells.length);
        for (Object cell : cells) {
            if (cell == null) {
                continue;
            }
            if (!addSlot(inputs, cell, stats, null)) {
                return null;
            }
        }
        return inputs;
    }

    /** 单个槽/格子(ore 名 String / ItemStack / List<ItemStack> 变体)-> 加入 inputs;false = 整条配方不可用。 */
    private static boolean addSlot(List<EmcIngredient> inputs, Object slot, EmcStats stats, IRecipe recipe) {
        if (slot instanceof String) {
            String oreName = (String) slot;
            List<ItemKey> options = new ArrayList<>();
            for (ItemStack s : OreDictionary.getOres(oreName)) {
                if (s == null || s.getItem() == null || s.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
                    continue;
                }
                options.add(ItemKey.of(s));
            }
            if (options.isEmpty()) {
                stats.avSkipped++;
                return false;
            }
            inputs.add(EmcIngredient.alternatives(options, 1));
            return true;
        }
        if (slot instanceof ItemStack) {
            return addFixed(inputs, (ItemStack) slot, stats, recipe);
        }
        if (slot instanceof List) {
            List<ItemKey> options = new ArrayList<>();
            for (Object opt : (List<?>) slot) {
                if (!(opt instanceof ItemStack)) {
                    continue;
                }
                ItemStack s = (ItemStack) opt;
                if (s.getItem() == null || s.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
                    continue;
                }
                options.add(ItemKey.of(s));
            }
            if (options.isEmpty()) {
                stats.avSkipped++;
                return false;
            }
            inputs.add(EmcIngredient.alternatives(options, 1));
            return true;
        }
        // 未知格子内容(String 行/Character 定义等) -> 该配方结构未按预期展开,跳过
        stats.avSkipped++;
        return false;
    }

    private static boolean addFixed(List<EmcIngredient> inputs, ItemStack stack, EmcStats stats, IRecipe recipe) {
        if (stack.getItem() == null) {
            stats.avSkipped++;
            return false;
        }
        if (stack.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
            stats.avSkipped++;
            return false;
        }
        // 大工作台消费语义:每格 1 个(匹配不查数量);见类注释
        inputs.add(EmcIngredient.fixed(ItemKey.of(stack), 1));
        return true;
    }

    /** 输出描述(reg@dmg),用于错误日志。 */
    private static String outDesc(IRecipe recipe) {
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
     * 输出含 "singular"(如各种奇点)的大工作台配方,把格子清单打到 INFO —— 用于核对
     * 计价语义与 NEI 是否一致(格子是否带数量、材料构成是否如预期)。奇点本应走 GT 机器
     * (中子压缩机 7296 铁块->奇点 之类),若这里出现说明还有格子编码没被理解。
     */
    private static void logSingularityIfAny(ItemKey outKey, ItemStack out, List<EmcIngredient> inputs) {
        try {
            String name = ItemKey.of(out)
                .toString();
            if (!name.toLowerCase()
                .contains("singular")) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (EmcIngredient ing : inputs) {
                if (sb.length() > 0) {
                    sb.append(" + ");
                }
                sb.append(ing.qty)
                    .append('x');
                if (ing.options.size() == 1) {
                    sb.append(ing.options.get(0));
                } else {
                    sb.append('[')
                        .append(ing.options.size())
                        .append(" alt: ")
                        .append(ing.options.get(0))
                        .append(" …]");
                }
            }
            LOG.info(
                "Avaritia extreme recipe with singularity-like output: {} = {}",
                name,
                sb.length() == 0 ? "(no inputs)" : sb.toString());
        } catch (Throwable t) {
            // 日志失败不影响计价
        }
    }
}
