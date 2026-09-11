package com.gtnh.autoemc.emc;

import java.math.BigInteger;

/**
 * EMC 值域工具。
 *
 * <p>
 * 引擎内部的 EMC 值一律用 {@link BigInteger},这样在装了 ProjectE-Expansion-GTNH(精确表)时
 * 不会被 ProjectE 的 int 表夹在 21 亿;{@code null} 表示"未知",对应改造前的哨兵 {@code UNKNOWN(-1)}
 * —— 所有运算遇到 null 一律返回 null(未知会传播,而不是当成 0 参与计算)。
 *
 * <p>
 * 只有两种地方收敛回 int,且都发生在<b>边界</b>,不损失引擎内部精度:
 * <ul>
 * <li>写回 ProjectE 的 int 表:{@link #clampToPeInt}</li>
 * <li>显示 / 网络包 / 需要 int 的原版接口:{@link #clampToInt}</li>
 * </ul>
 */
public final class EmcMath {

    /**
     * 单件物品价格上限(约 1e60):上游 Project Expansion 的量级就在这个档位,而 ProjectE 的玩家 EMC
     * 是 double(1.8e308)。超过这个量级的"价格"没有实际意义,却会让独立玩家卖一件东西就把余额顶到
     * {@code Double.MAX_VALUE}(看起来像 double 溢出),所以写回边界统一夹到这里。
     */
    public static final BigInteger PRICE_CAP = BigInteger.TEN.pow(60);

    /** ProjectE 侧一致使用的 int 上限(原实现即 {@code Integer.MAX_VALUE - 1}) */
    public static final BigInteger PE_INT_CAP = BigInteger.valueOf(Integer.MAX_VALUE - 1L);

    /** 未知(null 语义)的安全下限,用于比较时把 null 视作最小 */
    public static final BigInteger NEGATIVE_ONE = BigInteger.valueOf(-1L);

    private EmcMath() {}

    /** 负数视为"未知"(原实现里负值只可能是哨兵) */
    public static BigInteger of(long v) {
        return v < 0L ? null : BigInteger.valueOf(v);
    }

    public static BigInteger of(int v) {
        return v < 0 ? null : BigInteger.valueOf(v);
    }

    public static BigInteger add(BigInteger a, BigInteger b) {
        return (a == null || b == null) ? null : a.add(b);
    }

    public static BigInteger sub(BigInteger a, BigInteger b) {
        return (a == null || b == null) ? null : a.subtract(b);
    }

    public static BigInteger mul(BigInteger a, BigInteger b) {
        return (a == null || b == null) ? null : a.multiply(b);
    }

    public static BigInteger mul(BigInteger a, long n) {
        return (a == null || n < 0L) ? null : a.multiply(BigInteger.valueOf(n));
    }

    public static BigInteger mul(BigInteger a, int n) {
        return mul(a, (long) n);
    }

    /** 向零取整的整数除法(与原来的 int 除法语义一致);除数为 0 或负视为未知 */
    public static BigInteger div(BigInteger a, long n) {
        return (a == null || n <= 0L) ? null : a.divide(BigInteger.valueOf(n));
    }

    public static BigInteger div(BigInteger a, int n) {
        return div(a, (long) n);
    }

    /** 向上取整的整数除法(原实现里用 {@code Math.ceil} 表示的地方) */
    public static BigInteger ceilDiv(BigInteger a, long n) {
        if (a == null || n <= 0L) {
            return null;
        }
        BigInteger[] qr = a.divideAndRemainder(BigInteger.valueOf(n));
        return qr[1].signum() == 0 ? qr[0] : qr[0].add(BigInteger.ONE);
    }

    public static BigInteger max(BigInteger a, BigInteger b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.max(b);
    }

    public static BigInteger min(BigInteger a, BigInteger b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.min(b);
    }

    /** null(未知)视为最小 */
    public static int cmp(BigInteger a, BigInteger b) {
        if (a == null) return b == null ? 0 : -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }

    public static boolean isPositive(BigInteger v) {
        return v != null && v.signum() > 0;
    }

    public static boolean isKnown(BigInteger v) {
        return v != null;
    }

    /** 显示 / 网络包 / 需要 int 的原版接口:未知 -> 0,超过 int 上限 -> {@link #PE_INT_CAP} */
    public static int clampToInt(BigInteger v) {
        if (v == null) {
            return 0;
        }
        return v.compareTo(PE_INT_CAP) > 0 ? PE_INT_CAP.intValue() : Math.max(0, v.intValue());
    }

    /** 写回 ProjectE 的 int 表:未知 -> 0,超上限 -> {@link #PE_INT_CAP} */
    public static BigInteger clampToPeInt(BigInteger v) {
        if (v == null) {
            return BigInteger.ZERO;
        }
        return v.compareTo(PE_INT_CAP) > 0 ? PE_INT_CAP : v.max(BigInteger.ZERO);
    }

    /** 把价格夹到 {@link #PRICE_CAP};null 原样返回。 */
    public static BigInteger capPrice(BigInteger v) {
        return v == null || v.compareTo(PRICE_CAP) <= 0 ? v : PRICE_CAP;
    }

    /** 解析文本(CSV/JSON 缓存里的十进制整数);失败返回 null */
    public static BigInteger parse(String s) {
        if (s == null) {
            return null;
        }
        try {
            return new BigInteger(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 十进制字符串,null -> "0" */
    public static String text(BigInteger v) {
        return v == null ? "0" : v.toString();
    }
}
