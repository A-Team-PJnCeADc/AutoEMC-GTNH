package com.gtnh.autoemc.registry;

import java.util.Map;

import com.gtnh.autoemc.api.registry.EmcKey;
import com.gtnh.autoemc.api.registry.EmcRegistry;
import com.gtnh.autoemc.api.registry.EmcRegistryType;
import com.gtnh.autoemc.api.registry.IEmcRegistry;

/**
 * AutoEMC 注册表 ⟷ ProjectE 侧"新 Registry Types 值表"的桥。
 *
 * <p>
 * PE 侧表由 {@code EmcMapperTypeMixin}(注入 EMCMapper)在 &lt;clinit&gt; 时创建并通过
 * {@link #onPeTypeTableReady} 交给本桥;此后 {@link EmcRegistryImpl} 每次写值都会镜像到
 * PE 表(键 = EmcKey canonical 串),EMCMapper.clearMaps() 清表时本桥同步清引用但值仍以
 * AutoEMC 注册表为准(下次注册重写)。
 *
 * <p>
 * PE 未加载 / mixin 未应用时 peTypeTable 为 null:注册表正常工作,只是没有 PE 侧镜像
 * (物品侧注册由 AutoEMC map#2 走 APICustomEMCMapper,与此桥无关)。
 */
public final class TypeTableBridge {

    private static volatile Map<String, Integer> peTypeTable;

    private TypeTableBridge() {}

    /** mixin 注入点:EMCMapper 静态表就绪后调用;回放注册表现有值,此后接管镜像。 */
    public static void onPeTypeTableReady(Map<String, Integer> table) {
        peTypeTable = table;
        replay(table);
    }

    private static void replay(Map<String, Integer> table) {
        IEmcRegistry reg = EmcRegistry.instance();
        for (EmcRegistryType t : EmcRegistryType.values()) {
            for (Map.Entry<String, Integer> e : reg.snapshot(t)
                .entrySet()) {
                table.put(t.shortName + ":" + e.getKey(), e.getValue());
            }
        }
    }

    /** 镜像写:key 为 EmcKey canonical。 */
    public static void put(EmcKey key, int value) {
        Map<String, Integer> t = peTypeTable;
        if (t == null) {
            return;
        }
        synchronized (t) {
            t.put(key.toCanonical(), value);
        }
    }

    public static void remove(EmcKey key) {
        Map<String, Integer> t = peTypeTable;
        if (t == null) {
            return;
        }
        synchronized (t) {
            t.remove(key.toCanonical());
        }
    }

    /** 清空 PE 侧表(mixin 的 clearMaps 注入也会直接清;此处供 clearAll 使用)。 */
    public static void clear() {
        Map<String, Integer> t = peTypeTable;
        if (t == null) {
            return;
        }
        synchronized (t) {
            t.clear();
        }
    }
}
