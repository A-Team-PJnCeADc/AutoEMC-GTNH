package com.gtnh.autoemc.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;

/** 对齐链上的一个节点(纯字符串形态,主线程消费时再解析成 ItemStack)。 */
public final class ViewNode {

    /** 注册名@meta */
    public final String stackKey;
    /** 配方来源(机器 map 名 / "crafting" / "smelting");为 null 表示引擎无该物品记录(普通打开) */
    public final String source;
    /** 该配方单次产出的数量 */
    public final int outputQty;
    /** 引擎选中配方各输入槽取用的物品+数量 */
    public final List<Child> children;

    public static final class Child {

        public final String key;
        public final int qty;

        public Child(String key, int qty) {
            this.key = key;
            this.qty = qty;
        }
    }

    public ViewNode(String stackKey, String source, int outputQty, List<Child> children) {
        this.stackKey = stackKey;
        this.source = source;
        this.outputQty = outputQty;
        this.children = children == null ? new ArrayList<>() : children;
    }

    /** 解析主物品(失败返回 null) */
    public ItemStack resolve() {
        return ViewRequest.resolveKey(stackKey);
    }
}
