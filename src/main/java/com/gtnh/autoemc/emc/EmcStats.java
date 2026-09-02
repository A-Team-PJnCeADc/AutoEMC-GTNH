package com.gtnh.autoemc.emc;

/** 配方采集阶段的统计计数,仅用于日志/排查。 */
public final class EmcStats {

    public int craftingRecipes;
    /** 合成配方里被忽略(不参与计价)的工具槽数量 */
    public int craftingToolSlots;
    public int craftingSkippedUnknownType;
    public int craftingSkippedWildcard;
    public int smeltRecipes;
    public int smeltSkippedWildcard;
    public int gtMaps;
    public int gtRecipes;
    /** 每张 map 的有效配方数(指纹用) */
    public java.util.TreeMap<String, Integer> gtMapRecipeCounts = new java.util.TreeMap<>();
    /** GT 配方里带流体输入的数量(流体不参与计价,配方仍有效) */
    public int gtRecipesWithFluid;
    public int gtSkippedDisabled;
    public int gtSkippedChance;
    public int gtSkippedWildcard;
    public int gtSkippedNoOutput;
    /** 回收类配方(逆向粉碎/逆向冶炼/电弧炉回收,RECYCLE 元数据或 *_recycling 分类) */
    public int gtSkippedRecycle;
    /** GT 机器配方里的催化剂输入(编程电路等,不消耗) */
    public int gtToolSlots;
}
