package com.gtnh.autoemc.api.registry;

/**
 * EMC 值的"注册类型"(Registry Type):除 ProjectE 原生的物品(Item/Block/ItemStack)之外,
 * AutoEMC 用 mixin 给 ProjectE 注册的扩展类型。每种类型 = 一类可以被"定价/注册/查询"的键。
 *
 * <p>
 * v1 冻结口径:
 * <ul>
 * <li>{@link #FLUID} —— 流体,键 = FluidRegistry 注册名。1.7.10 GT 的气体底层也是
 * FluidRegistry 的 Fluid,因此气体并入流体(同一套键),不再单列 GAS;</li>
 * <li>{@link #ASPECT} —— 神秘时代(Thaumcraft)要素,键 = Aspect tag(透镜扫描/研究界面
 * 看到的那串,如 aer/terra/ignis…,复合要素同为其 tag)。覆盖蒸馏源质(essentia,
 * 与要素同名);</li>
 * <li>电力(EU)暂不考虑,枚举将来可扩展(扩展即加常量,存储/查询走同一套 {@link EmcKey})。</li>
 * </ul>
 */
public enum EmcRegistryType {

    /** 流体(液体+气体)。键 = FluidRegistry name(如 "molten.iron"、"sulfuricacid") */
    FLUID("fluid"),
    /** 神秘时代要素/源质。键 = Aspect tag(如 "aer"、"vitreus") */
    ASPECT("aspect");

    /** 持久化/序列化用的稳定短名(type:id 串的前缀),发布后不要改 */
    public final String shortName;

    EmcRegistryType(String shortName) {
        this.shortName = shortName;
    }

    /** 按短名反查;未知返回 null */
    public static EmcRegistryType byShortName(String shortName) {
        for (EmcRegistryType t : values()) {
            if (t.shortName.equals(shortName)) {
                return t;
            }
        }
        return null;
    }
}
