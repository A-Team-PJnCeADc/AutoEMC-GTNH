package com.gtnh.autoemc.api.registry;

import java.util.Map;

/**
 * 非物品 EMC 注册表接口 —— 供其他 mod 调用(服务端)。实现见
 * {@link EmcRegistry#instance()}。
 *
 * <p>
 * 语义与 ProjectE 一致:值为 ≥0 的整数 EMC;查询不到 = 0。注册时机与 ProjectE
 * registerCustomEMC 相同(仅 mod 加载期 PRE~POST + AutoEMC 自己的 serverStarted 全量
 * 注册),运行期随意 setValue 会被忽略并告警 —— 值在 AutoEMC 下次全量重算/重载时统一生效。
 *
 * <p>
 * 线程约定:实现非线程安全;读写只在服务端线程(mod 加载期 / serverStarted / AutoEMC
 * 重载流程)发生,AutoEMC 的后台求值线程只读、且与写错开(流水线保证)。
 */
public interface IEmcRegistry {

    /** 该键当前是否有值(>0 或显式 0)。 */
    boolean hasValue(EmcKey key);

    /** 该键的 EMC;无值返回 0。 */
    int getValue(EmcKey key);

    /**
     * 注册/覆盖一个非物品 EMC 值。
     *
     * @throws IllegalStateException 调用时机非法(加载期之后、且非 AutoEMC 内部重载)
     */
    void setValue(EmcKey key, int value);

    /** 移除一个值;返回是否原本存在。 */
    boolean removeValue(EmcKey key);

    /** 某类型下全部 {id:value} 快照(读用,不修改)。 */
    Map<String, Integer> snapshot(EmcRegistryType type);

    /** 清空全部非物品值(重载/重置用)。 */
    void clearAll();

    // 便捷重载(省得每处构造 EmcKey)

    default boolean hasFluidValue(String fluidRegistryName) {
        return hasValue(EmcKey.fluid(fluidRegistryName));
    }

    default int getFluidValue(String fluidRegistryName) {
        return getValue(EmcKey.fluid(fluidRegistryName));
    }

    default void setFluidValue(String fluidRegistryName, int value) {
        setValue(EmcKey.fluid(fluidRegistryName), value);
    }

    default boolean hasAspectValue(String aspectTag) {
        return hasValue(EmcKey.aspect(aspectTag));
    }

    default int getAspectValue(String aspectTag) {
        return getValue(EmcKey.aspect(aspectTag));
    }

    default void setAspectValue(String aspectTag, int value) {
        setValue(EmcKey.aspect(aspectTag), value);
    }
}
