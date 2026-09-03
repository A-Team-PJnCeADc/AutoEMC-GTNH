package com.gtnh.autoemc.emc;

/** 一条配方消耗的流体:{注册名, 数量(L/mB)}。数量单位与 GT 一致(1 锭 = 144L)。 */
public final class FluidUse {

    public final String fluidName;
    /** 消耗量,单位 L(mB);可为 0(忽略) */
    public final int amountL;

    public FluidUse(String fluidName, int amountL) {
        this.fluidName = fluidName;
        this.amountL = Math.max(0, amountL);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FluidUse)) {
            return false;
        }
        FluidUse f = (FluidUse) o;
        return amountL == f.amountL && fluidName.equals(f.fluidName);
    }

    @Override
    public int hashCode() {
        return 31 * fluidName.hashCode() + amountL;
    }

    @Override
    public String toString() {
        return amountL + "L " + fluidName;
    }
}
