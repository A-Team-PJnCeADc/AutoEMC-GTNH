package com.gtnh.autoemc.emc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.gtnh.autoemc.api.registry.EmcKey;
import com.gtnh.autoemc.api.registry.EmcRegistry;
import com.gtnh.autoemc.api.registry.IEmcRegistry;
import com.gtnh.autoemc.registry.EmcRegistryImpl;

/**
 * 神秘时代(Thaumcraft)要素/源质 EMC 定价(用户规则):
 * 6 种元始要素(aer/terra/ignis/aqua/ordo/perditio,components 为空的要素)= 256 EMC;
 * 其余复合要素 = 其两个子要素价值递归相加(多轮组合得来 = 元始多重性 × 256)。
 *
 * <p>
 * 纯反射实现(thaumcraft.api.aspects.Aspect:static LinkedHashMap aspects、getTag、
 * getComponents),不编译依赖 Thaumcraft —— 类只在本方法执行时才加载,Thaumcraft 未装
 * 则 available() 短路。值经 {@link EmcRegistry}(ASPECT 类型)注册并镜像到 PE 侧类型表,
 * 其他 mod 可查询(EmcRegistry.instance().getAspectValue(tag))。
 *
 * <p>
 * 组件环/未知组件(理论上 TC 不会出现)的要素跳过并计数,不阻塞其他要素。
 */
public final class AspectSeeder {

    private static final Logger LOG = LogManager.getLogger("AutoEMC");
    private static final int PRIMAL_EMC = 256;

    private AspectSeeder() {}

    public static boolean available() {
        return cpw.mods.fml.common.Loader.isModLoaded("Thaumcraft");
    }

    /** 在 serverStarted(Server 线程)调用:给全部已注册要素定价并写入 Registry。 */
    public static void seed() {
        if (!available()) {
            return;
        }
        try {
            Class<?> aspectCls = Class.forName("thaumcraft.api.aspects.Aspect");
            Field aspectsField = aspectCls.getField("aspects");
            Object raw = aspectsField.get(null);
            if (!(raw instanceof Map)) {
                return;
            }
            Method getTag = aspectCls.getMethod("getTag");
            Method getComponents = aspectCls.getMethod("getComponents");

            Map<String, Object> byTag = new HashMap<>();
            for (Object a : ((Map<?, ?>) raw).values()) {
                String tag = (String) getTag.invoke(a);
                if (tag != null && !tag.isEmpty()) {
                    byTag.put(tag, a);
                }
            }

            Map<String, Integer> values = new HashMap<>();
            Set<String> broken = new HashSet<>();
            for (Map.Entry<String, Object> e : byTag.entrySet()) {
                Integer v = compute(e.getValue(), e.getKey(), getTag, getComponents, values, new HashSet<>(), broken);
                if (v == null) {
                    values.remove(e.getKey()); // 环/不可分解:不注册
                }
            }

            IEmcRegistry reg = EmcRegistry.instance();
            int seeded = 0;
            int skipped = 0;
            for (Map.Entry<String, Integer> e : values.entrySet()) {
                if (e.getValue() == null || e.getValue() <= 0) {
                    skipped++;
                    continue;
                }
                // serverStarted 已过 PE/加载期窗口 -> 走 unchecked;实现类未变时兜底常规注册
                if (reg instanceof EmcRegistryImpl) {
                    ((EmcRegistryImpl) reg).setValueUnchecked(EmcKey.aspect(e.getKey()), e.getValue());
                } else {
                    try {
                        reg.setValue(EmcKey.aspect(e.getKey()), e.getValue());
                    } catch (IllegalStateException ex) {
                        skipped++;
                        continue;
                    }
                }
                seeded++;
            }
            if (!broken.isEmpty()) {
                LOG.warn(
                    "Thaumcraft aspect EMC: {} cyclic/undecomposable aspects skipped: {}",
                    broken.size(),
                    String.join(", ", new java.util.TreeSet<>(broken)));
            }
            LOG.info(
                "Thaumcraft aspect EMC seeded: {} of {} aspects (6 primal = {} each, compound = sum of components).",
                seeded,
                byTag.size(),
                PRIMAL_EMC);
        } catch (Throwable t) {
            LOG.error("Thaumcraft aspect EMC seeding failed (aspects left unregistered).", t);
        }
    }

    /**
     * 递归求值:components 为空(元始)= 256;复合 = 两个子要素之和。
     *
     * @return 价值;环/无法分解返回 null(该要素不注册)
     */
    private static Integer compute(Object aspect, String tag, Method getTag, Method getComponents,
        Map<String, Integer> values, Set<String> visiting, Set<String> broken) {
        Integer memo = values.get(tag);
        if (memo != null) {
            return memo;
        }
        if (!visiting.add(tag)) {
            broken.add(tag);
            return null; // 组件环
        }
        try {
            Object[] comps = (Object[]) getComponents.invoke(aspect);
            int v;
            if (comps == null || comps.length == 0) {
                v = PRIMAL_EMC; // 元始要素
            } else {
                v = 0;
                for (Object c : comps) {
                    if (c == null) {
                        broken.add(tag);
                        return null;
                    }
                    String ctag = (String) getTag.invoke(c);
                    Integer cv = compute(c, ctag, getTag, getComponents, values, visiting, broken);
                    if (cv == null) {
                        broken.add(tag);
                        return null;
                    }
                    v += cv;
                }
            }
            values.put(tag, v);
            return v;
        } catch (Throwable t) {
            broken.add(tag);
            return null;
        } finally {
            visiting.remove(tag);
        }
    }
}
