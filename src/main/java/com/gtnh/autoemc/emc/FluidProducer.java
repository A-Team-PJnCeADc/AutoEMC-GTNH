package com.gtnh.autoemc.emc;

import java.util.List;

/**
 * 一条"流体产出配方"(fluid-only producer):零物品输出、恰好一种流体输出的 GT 机器配方。
 * 用于给"没有材料锭对应"的流体(酸、溶液等)按配方反推价值:
 * 流体每 144L 价值 = 整条配方成本(物品输入 + 流体输入)按输出量折算。
 * 纯流体链(distillery/蒸馏 等 流体->流体)经 {@link #fluids} 递归计入。
 */
public final class FluidProducer {

    /** 物品输入(消耗,计价) */
    public final List<EmcIngredient> inputs;
    /** 流体输入(消耗,计价,递归) */
    public final List<FluidUse> fluids;
    /** 单次产出的流体量(L) */
    public final int outputL;
    /** 来源 map 名(日志/排查),如 gt.recipe.chemicalreactor */
    public final String source;

    public FluidProducer(List<EmcIngredient> inputs, List<FluidUse> fluids, int outputL, String source) {
        this.inputs = inputs;
        this.fluids = fluids;
        this.outputL = Math.max(1, outputL);
        this.source = source;
    }
}
