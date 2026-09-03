package com.gtnh.autoemc.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** 一次"查看配方树"请求的纯数据形态(不引用任何 mod 类型,双端安全)。 */
public final class ViewRequest {

    /** nodes[0] 为根(目标物品) */
    public final List<ViewNode> nodes;
    /** EMC 说明文本(chat 用,可为 null) */
    public final String info;

    public ViewRequest(List<ViewNode> nodes, String info) {
        this.nodes = nodes == null ? new ArrayList<>() : nodes;
        this.info = info;
    }

    public static String keyOf(ItemStack stack) {
        String name = Item.itemRegistry.getNameForObject(stack.getItem());
        return name == null ? null : name + "@" + stack.getItemDamage();
    }

    /** "注册名@meta" -> ItemStack(1 个);解析失败返回 null */
    public static ItemStack resolveKey(String key) {
        if (key == null) {
            return null;
        }
        int at = key.lastIndexOf('@');
        if (at <= 0 || at == key.length() - 1) {
            return null;
        }
        String name = key.substring(0, at);
        int meta;
        try {
            meta = Integer.parseInt(key.substring(at + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        Object obj = Item.itemRegistry.getObject(name);
        if (!(obj instanceof Item)) {
            return null;
        }
        return new ItemStack((Item) obj, 1, meta);
    }
}
