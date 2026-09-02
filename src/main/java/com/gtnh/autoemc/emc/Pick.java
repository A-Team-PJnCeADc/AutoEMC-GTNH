package com.gtnh.autoemc.emc;

/**
 * 配方输入槽的一个"实际取用"项:选中物品 + 所需数量。
 * 用于把引擎选中的配方输入槽完整下发到客户端渲染配方树。
 */
public final class Pick {

    public final ItemKey key;
    public final int qty;

    public Pick(ItemKey key, int qty) {
        this.key = key;
        this.qty = qty;
    }

    @Override
    public String toString() {
        return key + " x" + qty;
    }
}
