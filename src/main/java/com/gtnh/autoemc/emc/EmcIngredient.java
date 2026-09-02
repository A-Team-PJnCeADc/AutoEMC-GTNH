package com.gtnh.autoemc.emc;

import java.util.ArrayList;
import java.util.List;

/**
 * 配方的一个输入槽:可能是固定物品(机器配方/普通合成),也可能是多个可选项
 * (矿物词典合成,如"任意木板")。求值时从可选项中取 EMC 最低的一个。
 */
public final class EmcIngredient {

    public final List<ItemKey> options;
    public final int qty;

    private EmcIngredient(List<ItemKey> options, int qty) {
        this.options = options;
        this.qty = qty;
    }

    public static EmcIngredient fixed(ItemKey key, int qty) {
        List<ItemKey> opts = new ArrayList<>(1);
        opts.add(key);
        return new EmcIngredient(opts, qty);
    }

    public static EmcIngredient alternatives(List<ItemKey> options, int qty) {
        return new EmcIngredient(options, qty);
    }

    public boolean isFixed() {
        return options.size() == 1;
    }

    @Override
    public String toString() {
        return options.size() == 1 ? options.get(0) + " x" + qty : options + " x" + qty;
    }
}
