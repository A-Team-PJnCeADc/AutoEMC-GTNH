package com.gtnh.autoemc.compat;

import java.lang.reflect.Method;
import java.math.BigInteger;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import cpw.mods.fml.common.Loader;

/**
 * 与 ProjectE-Expansion-GTNH(PE-E-GTNH)的软依赖桥接(全反射,无编译期依赖)。
 *
 * <p>
 * 装了 PE-E-GTNH 时:
 * <ul>
 * <li><b>读</b>:AutoEMC 取基础价时优先用它的 BigInteger 精确表 —— GT 物品的价可能被 ProjectE 的
 * int 表夹在 21 亿,而 PE-E-GTNH 的表是精确的(AutoEMC 引擎内部现在也是 BigInteger,
 * 精确值原样参与求值,只在写回 ProjectE 的 int 表 / 展示等边界才收敛回 int)。</li>
 * <li><b>写</b>:AutoEMC 算出的值同时注册进它的精确表
 * ({@code PXEmcValues.register(Item, BigInteger)}),由它镜像进 ProjectE 的 int 表,
 * 于是转化桌/冷凝器/机器都能按精确值记账。</li>
 * </ul>
 *
 * <p>
 * 没装时所有方法返回 null/false,调用方走回原逻辑,行为与从前完全一致。
 */
public final class ProjectExpansionCompat {

    private static final String API_CLASS = "com.projecte.expansion.api.PXEmcValues";

    private static boolean resolved;
    private static Method getValueStack;
    private static Method hasValueStack;
    private static Method registerStack;
    private static Method registerItem;

    private ProjectExpansionCompat() {}

    /** @return 是否装了 PE-E-GTNH 且 API 可用。 */
    public static boolean isPresent() {
        resolve();
        return registerItem != null;
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        if (!Loader.isModLoaded("projectexpansiongtnh")) {
            return;
        }
        try {
            Class<?> api = Class.forName(API_CLASS);
            getValueStack = api.getMethod("getValue", ItemStack.class);
            hasValueStack = api.getMethod("hasValue", ItemStack.class);
            // 优先用栈级重载:GT metaitem 靠 damage 区分,按 Item 注册会把其它变体丢价
            try {
                registerStack = api.getMethod("register", ItemStack.class, BigInteger.class);
            } catch (NoSuchMethodException older) {
                registerStack = null;
            }
            registerItem = api.getMethod("register", Item.class, BigInteger.class);
        } catch (Throwable t) {
            // 版本不含这些方法:当作未安装,走原逻辑
            getValueStack = null;
            hasValueStack = null;
            registerStack = null;
            registerItem = null;
        }
    }

    /** @return PE-E-GTNH 的精确值;null 表示没有(未安装 / 它也没有这个物品的价)。 */
    public static BigInteger exactValue(ItemStack stack) {
        resolve();
        if (getValueStack == null || stack == null) {
            return null;
        }
        try {
            return (BigInteger) getValueStack.invoke(null, stack);
        } catch (Throwable t) {
            return null;
        }
    }

    /** @return PE-E-GTNH 是否有这个物品的价。 */
    public static boolean hasExactValue(ItemStack stack) {
        resolve();
        if (hasValueStack == null || stack == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(hasValueStack.invoke(null, stack));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 把值写进 PE-E-GTNH 的精确表(它会镜像进 ProjectE 的 int 表)。
     *
     * @return true 表示已由 PE-E-GTNH 处理(调用方不必再直调 APICustomEMCMapper);
     *         false 表示未安装/失败,调用方按原逻辑写 ProjectE
     */
    public static boolean register(ItemStack stack, BigInteger value) {
        resolve();
        if (registerItem == null || stack == null || stack.getItem() == null || value == null) {
            return false;
        }
        try {
            if (registerStack != null) {
                registerStack.invoke(null, stack, value);
            } else {
                // 旧版 PE-E-GTNH 只有按 Item 的重载:带元数据的物品会有损,记一次日志便于排查
                registerItem.invoke(null, stack.getItem(), value);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
