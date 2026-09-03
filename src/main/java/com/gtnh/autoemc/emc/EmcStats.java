package com.gtnh.autoemc.emc;

import java.util.ArrayList;
import java.util.Map;

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
    /** 流体反推产者(零物品输出、单一流体输出的配方)条数;入指纹(gt 源 fingerprintLines) */
    public int gtFluidRecipes;
    /**
     * GT 装配线(Assembly Line):配方存 GTRecipe.RecipeAssemblyLine.sAssemblylineRecipes
     * (数据棒注册表),不走 RecipeMap —— RecipeMap 里只有 NEI 展示用的假配方图
     * (gt.recipe.fakeAssemblylineProcess,mFakeRecipe=true 被过滤)。
     */
    public int alRecipes;
    public int alSkipped;
    public int alSkippedError;
    /**
     * Avaritia 大工作台(ExtremeCraftingManager 注册表):配方是 IRecipe、与 NEI 同源;
     * 消费语义每格 1 个(匹配不查数量)。奇点类"1000/7296 材料"配方不走这里(中子压缩机
     * 属 GT RecipeMap),由 gt 源负责。
     */
    public int avRecipes;
    public int avSkipped;
    public int avSkippedError;
    /**
     * recipe 级隔离(RecipeScan.forEachRecipe):单条配方处理抛异常被跳过 —— 畸形数据、
     * 目标 mod API 版本漂移(NoSuchMethodError/NoClassDefFoundError)等。内容性跳过
     * (wildcard/disabled/chance/recycle 等)不算在这里。
     */
    public int craftingSkippedError;
    public int smeltSkippedError;
    public int gtSkippedError;
    /** ingredient 级隔离:解析某条材料槽抛异常,整条配方被跳过(仅工作台路径,含展开/容器检查) */
    public int craftingSkippedIngredient;
    /**
     * 被跳过的合成配方按配方类名聚合的计数(craftingSkippedUnknownType 的分项明细)。
     * 不入指纹;仅用于定位"哪个 mod 的哪类自定义 IRecipe 没被识别/解析"。
     */
    public final java.util.TreeMap<String, Integer> skippedUnknownByClass = new java.util.TreeMap<>();

    /** 记录一条被跳过的合成配方(计数 + 按类聚合),保持 sum(skippedUnknownByClass) == craftingSkippedUnknownType */
    public void noteUnknownRecipeClass(Class<?> recipeClass) {
        craftingSkippedUnknownType++;
        String name = recipeClass == null ? "?" : recipeClass.getName();
        skippedUnknownByClass.put(name, skippedUnknownByClass.getOrDefault(name, 0) + 1);
    }

    /**
     * 跳过明细格式化:按次数降序、类名升序,最多 cap 条,超出记 "…+N more"。
     *
     * @return 空串 = 没有按类记录的跳过(全部跳过发生在无类可归因的路径)
     */
    public String topSkippedUnknownClasses(int cap) {
        if (skippedUnknownByClass.isEmpty()) {
            return "";
        }
        ArrayList<Map.Entry<String, Integer>> entries = new ArrayList<>(skippedUnknownByClass.entrySet());
        entries.sort((a, b) -> {
            int c = b.getValue()
                .compareTo(a.getValue());
            return c != 0 ? c
                : a.getKey()
                    .compareTo(b.getKey());
        });
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(cap, entries.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(
                entries.get(i)
                    .getKey())
                .append('=')
                .append(
                    entries.get(i)
                        .getValue());
        }
        if (entries.size() > cap) {
            sb.append(", …+")
                .append(entries.size() - cap)
                .append(" more");
        }
        sb.append(" (total=")
            .append(craftingSkippedUnknownType)
            .append(')');
        return sb.toString();
    }
}
