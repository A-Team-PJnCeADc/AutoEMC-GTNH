package com.gtnh.autoemc.api.registry;

/**
 * 一条非物品 EMC 值的键:{@link EmcRegistryType 类型} + 类型内 id。
 *
 * <p>
 * 两个键相等 == 类型与 id 都相同。id 一律用类型内稳定名(小写、去歧义后):
 * <ul>
 * <li>FLUID:id = FluidRegistry 注册名;</li>
 * <li>ASPECT:id = Aspect tag。</li>
 * </ul>
 * 不要在外部拼 canonical 串,用静态工厂;需要持久化/网络传输时用 {@link #toCanonical()}
 * 与 {@link #fromCanonical(String)}。
 */
public final class EmcKey {

    public final EmcRegistryType type;
    public final String id;

    private EmcKey(EmcRegistryType type, String id) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (id == null || id.isEmpty() || id.indexOf(':') >= 0) {
            // id 不允许含 ':' —— canonical 串以 ':' 分隔,避免歧义
            throw new IllegalArgumentException("invalid id '" + id + "' for type " + type);
        }
        this.type = type;
        this.id = id;
    }

    // 工厂

    /** 流体键:id = FluidRegistry 注册名(液体/气体同一套键)。 */
    public static EmcKey fluid(String fluidRegistryName) {
        return new EmcKey(EmcRegistryType.FLUID, fluidRegistryName);
    }

    /** 流体键:从 net.minecraftforge.fluids.Fluid 取注册名;fluid 为 null 返回 null。 */
    public static EmcKey ofFluid(net.minecraftforge.fluids.Fluid fluid) {
        if (fluid == null) {
            return null;
        }
        String name = fluid.getName();
        return name == null || name.isEmpty() ? null : fluid(name);
    }

    /** 神秘时代要素键:id = Aspect tag(透镜扫描看到的那串)。 */
    public static EmcKey aspect(String aspectTag) {
        return new EmcKey(EmcRegistryType.ASPECT, aspectTag);
    }

    /**
     * 从 thaumcraft.api.aspects.Aspect 取键;aspect 为 null 返回 null。
     * 用字符串 tag 而非直接持 Aspect 实例:本 API 不编译依赖 Thaumcraft,
     * 调用方自行把 tag 从 Aspect#getTag() 取来即可。
     */
    public static EmcKey aspectOf(Object thaumcraftAspectOrNull) {
        if (thaumcraftAspectOrNull == null) {
            return null;
        }
        String tag = (String) invokeAspectTag(thaumcraftAspectOrNull);
        return tag == null || tag.isEmpty() ? null : aspect(tag);
    }

    private static Object invokeAspectTag(Object aspect) {
        try {
            java.lang.reflect.Method m = aspect.getClass()
                .getMethod("getTag");
            return m.invoke(aspect);
        } catch (Throwable t) {
            return null;
        }
    }

    // 序列化

    /** canonical 串 "typeShortName:id";用于 JSON/网络。 */
    public String toCanonical() {
        return type.shortName + ":" + id;
    }

    /** 解析 canonical 串;非法返回 null。 */
    public static EmcKey fromCanonical(String canonical) {
        if (canonical == null) {
            return null;
        }
        int p = canonical.indexOf(':');
        if (p <= 0 || p == canonical.length() - 1) {
            return null;
        }
        EmcRegistryType t = EmcRegistryType.byShortName(canonical.substring(0, p));
        return t == null ? null : new EmcKey(t, canonical.substring(p + 1));
    }

    // 值语义

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EmcKey)) {
            return false;
        }
        EmcKey key = (EmcKey) o;
        return type == key.type && id.equals(key.id);
    }

    @Override
    public int hashCode() {
        return 31 * type.ordinal() + id.hashCode();
    }

    @Override
    public String toString() {
        return toCanonical();
    }
}
