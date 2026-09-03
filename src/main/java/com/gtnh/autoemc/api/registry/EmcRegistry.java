package com.gtnh.autoemc.api.registry;

import com.gtnh.autoemc.registry.EmcRegistryImpl;

/**
 * 非物品 EMC 注册表的静态入口 —— 其他 mod 只依赖本类(及其返回的 {@link IEmcRegistry})
 * 即可注册/查询 流体、神秘时代要素 等非物品 EMC 值:
 *
 * <pre>
 * IEmcRegistry reg = EmcRegistry.instance();
 * reg.setFluidValue("sulfuricacid", 16); // 加载期(PRE/INIT/POST)调用
 * int v = reg.getFluidValue("molten.iron"); // 随时可查(服务端)
 * reg.setAspectValue("ignis", 128);
 * </pre>
 *
 * <p>
 * 值的生效时机与 ProjectE registerCustomEMC 相同:下次 AutoEMC 全量重算(serverStarted /
 * /projecte_autoemc reload)统一写入 ProjectE(物品部分)与类型表(非物品部分)。
 */
public final class EmcRegistry {

    private static volatile IEmcRegistry instance;

    private EmcRegistry() {}

    /** 获取全局实例(懒初始化,线程安全)。 */
    public static IEmcRegistry instance() {
        IEmcRegistry r = instance;
        if (r == null) {
            synchronized (EmcRegistry.class) {
                r = instance;
                if (r == null) {
                    r = new EmcRegistryImpl();
                    instance = r;
                }
            }
        }
        return r;
    }
}
