package com.gtnh.autoemc.emc;

import java.util.List;

/**
 * 一条"产出配方"(生产者)。EMC 求值只消费这类配方。
 * 选择优先级由 (category, tier) 决定,cost 仅在同级时用于比较。
 */
public final class EmcRecipe {

    /** 工作台(普通合成) */
    public static final int CAT_CRAFTING = 0;
    /** 单方块机器(含原版熔炉冶炼) */
    public static final int CAT_SINGLE = 1;
    /** 多方块结构机器 */
    public static final int CAT_MULTI = 2;
    /** 蒸汽时代(低于一切电压等级) */
    public static final int TIER_STEAM = -1;

    public final ItemKey output;
    /** 单次产出的数量(>0) */
    public final int outputQty;
    public final List<EmcIngredient> inputs;
    /** 流体输入(参与成本计价;工作台/熔炉/大工作台为空) */
    public final List<FluidUse> fluids;
    /** CAT_* 常量 */
    public final int category;
    /** TIER_STEAM=-1,否则为电压等级索引(0=ULV,1=LV,...) */
    public final int tier;
    /** 配方来源,仅用于日志/排查,如 "crafting"、"smelting"、"gt.recipe.macerator" */
    public final String source;
    /**
     * 输入材料的 GT 形态等级(锭/宝石=6,粉=5,小堆粉/粒=4,小撮粉=3,矿石=1,其他=3):
     * 用于"粉末优先锭粉碎 / 粉末合成粉末优先用最大粉末",越高越优先(更锚定到锭)。
     */
    public final int formRank;
    /** 流体输入总量(L):"合成路径液体少"优先,越低越优先(工作台/熔炉=0)。 */
    public final int fluidAmount;

    /**
     * 组装机系配方(组装机/电路组装机/太空组装机/组件装配线/精密组装机等,GTNH 标准生产路径):
     * 同类别同等级打平时优先选中。来源名统一含 "assembl"(大小写不敏感)。
     */
    public boolean isAssembler() {
        return source != null && source.toLowerCase()
            .contains("assembl");
    }

    /** 无流体输入的构造(工作台/熔炉/大工作台)。 */
    public EmcRecipe(ItemKey output, int outputQty, List<EmcIngredient> inputs, int category, int tier, String source,
        int formRank, int fluidAmount) {
        this(
            output,
            outputQty,
            inputs,
            category,
            tier,
            source,
            formRank,
            fluidAmount,
            java.util.Collections.<FluidUse>emptyList());
    }

    public EmcRecipe(ItemKey output, int outputQty, List<EmcIngredient> inputs, int category, int tier, String source,
        int formRank, int fluidAmount, List<FluidUse> fluids) {
        this.output = output;
        this.outputQty = outputQty;
        this.inputs = inputs;
        this.category = category;
        this.tier = tier;
        this.source = source;
        this.formRank = formRank;
        this.fluidAmount = fluidAmount;
        this.fluids = fluids == null ? java.util.Collections.<FluidUse>emptyList() : fluids;
    }

    public static String categoryName(int category) {
        switch (category) {
            case CAT_CRAFTING:
                return "crafting";
            case CAT_SINGLE:
                return "single";
            case CAT_MULTI:
                return "multi";
            default:
                return "?";
        }
    }

    public static String tierName(int tier) {
        return tier == TIER_STEAM ? "Steam" : String.valueOf(tier);
    }
}
