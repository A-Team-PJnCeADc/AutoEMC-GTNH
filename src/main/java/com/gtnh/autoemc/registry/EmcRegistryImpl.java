package com.gtnh.autoemc.registry;

import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

import com.gtnh.autoemc.api.registry.EmcKey;
import com.gtnh.autoemc.api.registry.EmcRegistryType;
import com.gtnh.autoemc.api.registry.IEmcRegistry;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.LoaderState;

/**
 * 非物品 EMC 注册表默认实现。
 *
 * <p>
 * 公开写入窗口与 ProjectE registerCustomEMC 一致:仅 PRE/INITIALIZATION/POSTINITIALIZATION;
 * AutoEMC 自己在 serverStarted 的全量注册走 {@link #setValueUnchecked}(不校验窗口)。
 * 每次写值同时镜像到 ProjectE 侧类型表(经 {@link TypeTableBridge},mixin 注入的
 * EMCMapper 静态表) —— 键 = EmcKey canonical 串。
 *
 * <p>
 * 值的内存形态与 ProjectE 相同:每次启动由 AutoEMC 重算重注册,不跨会话持久化(持久化
 * 属于后续:并入 emc-values.json 或独立 config/emc-types.json)。
 */
public class EmcRegistryImpl implements IEmcRegistry {

    private final Map<EmcRegistryType, TreeMap<String, Integer>> values = new EnumMap<>(EmcRegistryType.class);

    public EmcRegistryImpl() {
        for (EmcRegistryType t : EmcRegistryType.values()) {
            values.put(t, new TreeMap<>());
        }
    }

    @Override
    public synchronized boolean hasValue(EmcKey key) {
        Integer v = values.get(key.type)
            .get(key.id);
        return v != null;
    }

    @Override
    public synchronized int getValue(EmcKey key) {
        Integer v = values.get(key.type)
            .get(key.id);
        return v == null ? 0 : v;
    }

    @Override
    public void setValue(EmcKey key, int value) {
        checkWritableWindow();
        doSet(key, value);
    }

    /** 不校验时机直接写(仅 AutoEMC 内部:serverStarted 全量注册/重载;调用方保证在服务端线程)。 */
    public void setValueUnchecked(EmcKey key, int value) {
        doSet(key, value);
    }

    private synchronized void doSet(EmcKey key, int value) {
        int v = Math.max(0, value);
        values.get(key.type)
            .put(key.id, v);
        TypeTableBridge.put(key, v);
    }

    @Override
    public boolean removeValue(EmcKey key) {
        Integer old;
        synchronized (this) {
            old = values.get(key.type)
                .remove(key.id);
        }
        if (old != null) {
            TypeTableBridge.remove(key);
        }
        return old != null;
    }

    @Override
    public synchronized Map<String, Integer> snapshot(EmcRegistryType type) {
        return new TreeMap<>(values.get(type));
    }

    @Override
    public void clearAll() {
        synchronized (this) {
            for (TreeMap<String, Integer> m : values.values()) {
                m.clear();
            }
        }
        TypeTableBridge.clear();
    }

    private static void checkWritableWindow() {
        Loader loader = Loader.instance();
        LoaderState state = loader.getLoaderState();
        if (state == LoaderState.PREINITIALIZATION || state == LoaderState.INITIALIZATION
            || state == LoaderState.POSTINITIALIZATION) {
            return;
        }
        throw new IllegalStateException(
            "AutoEMC type registry only writable during mod loading (PRE/INIT/POST), was " + state);
    }
}
