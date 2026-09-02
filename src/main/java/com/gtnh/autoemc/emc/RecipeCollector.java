package com.gtnh.autoemc.emc;

import java.util.ArrayList;
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

/**
 * 收集"产出配方"三类来源:
 * 1. 工作台(CraftingManager,优先)
 * 2. 原版熔炉冶炼(FurnaceRecipes,视为单方块机器)
 * 3. GT 机器配方(GtMachines,单方块/多方块按配置区分)
 *
 * 计价规则:配方里参与消耗的"材料"才计入成本;
 * 工具(oredict craftingTool* / GT 工具物品 / 有容器物品且合成后不消耗)与流体输入
 * 视为不参与物品总价值计算 —— 该槽位不产生成本,但配方仍然有效。
 */
public final class RecipeCollector {

    private RecipeCollector() {}

    public static void collect(Map<ItemKey, List<EmcRecipe>> producers, EmcStats stats) {
        collectCrafting(producers, stats);
        collectSmelting(producers, stats);
        GtMachines.collect(producers, stats);
    }

    // 工作台

    private static void collectCrafting(Map<ItemKey, List<EmcRecipe>> producers, EmcStats stats) {
        // 后台线程扫描:主线程可能仍在注册配方(serverStarted 之后),快照副本迭代避免 CME
        for (Object o : new ArrayList<>(
            CraftingManager.getInstance()
                .getRecipeList())) {
            if (!(o instanceof IRecipe)) {
                continue;
            }
            IRecipe recipe = (IRecipe) o;
            ItemStack out = recipe.getRecipeOutput();
            if (out == null || out.getItem() == null) {
                continue;
            }
            if (out.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
                stats.craftingSkippedWildcard++;
                continue;
            }
            if (out.stackSize <= 0) {
                continue;
            }

            List<EmcIngredient> inputs = parseCraftingInputs(recipe, stats);
            if (inputs == null) {
                // 已计入跳过原因
                continue;
            }
            ItemKey outKey = ItemKey.of(out);
            producers.computeIfAbsent(outKey, k -> new ArrayList<>())
                .add(new EmcRecipe(outKey, out.stackSize, inputs, EmcRecipe.CAT_CRAFTING, 0, "crafting", 3, 0));
            stats.craftingRecipes++;
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
            stats.craftingSkippedUnknownType++;
            return null;
        }
        if (slots.isEmpty()) {
            // 无输入凭空产出:不算配方
            stats.craftingSkippedUnknownType++;
            return null;
        }

        List<EmcIngredient> inputs = new ArrayList<>(slots.size());
        for (Object slot : slots) {
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
                    stats.craftingSkippedUnknownType++;
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
                    // 这个槽位全是工具(或空)→ 槽位不参与计价,配方仍有效
                    continue;
                }
                inputs.add(EmcIngredient.alternatives(options, qty));
            } else {
                stats.craftingSkippedUnknownType++;
                return null;
            }
        }
        if (inputs.isEmpty()) {
            // 所有材料都是工具/流体类 → 无消耗材料,配方视为不可用
            stats.craftingSkippedUnknownType++;
            return null;
        }
        return inputs;
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

    // 原版熔炉冶炼(单方块机器,时代 = 蒸汽以下)

    private static void collectSmelting(Map<ItemKey, List<EmcRecipe>> producers, EmcStats stats) {
        Map<ItemStack, ItemStack> list = FurnaceRecipes.smelting()
            .getSmeltingList();
        for (Map.Entry<ItemStack, ItemStack> entry : new ArrayList<>(list.entrySet())) {
            ItemStack in = entry.getKey();
            ItemStack out = entry.getValue();
            if (in == null || out == null || in.getItem() == null || out.getItem() == null) {
                continue;
            }
            if (in.getItemDamage() == OreDictionary.WILDCARD_VALUE
                || out.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
                stats.smeltSkippedWildcard++;
                continue;
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
            stats.smeltRecipes++;
        }
    }
}
