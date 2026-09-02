package com.gtnh.autoemc.emc;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * 一个"物品"的稳定键:Item + damage(忽略 NBT 与数量)。
 * GT 配方输出、PE 锚点、求值结果都用它做 key。
 */
public final class ItemKey {

    public final Item item;
    public final int damage;

    public ItemKey(Item item, int damage) {
        this.item = item;
        this.damage = damage;
    }

    public static ItemKey of(ItemStack stack) {
        return new ItemKey(stack.getItem(), stack.getItemDamage());
    }

    public ItemStack toStack() {
        return new ItemStack(item, 1, damage);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ItemKey)) {
            return false;
        }
        ItemKey key = (ItemKey) o;
        return damage == key.damage && item == key.item;
    }

    @Override
    public int hashCode() {
        return 31 * System.identityHashCode(item) + damage;
    }

    @Override
    public String toString() {
        return Item.itemRegistry.getNameForObject(item) + "@" + damage;
    }
}
