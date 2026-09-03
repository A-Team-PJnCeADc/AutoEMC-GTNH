package com.gtnh.autoemc.mixins;

import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;
import com.gtnewhorizon.gtnhmixins.builders.IMixins;

/**
 * LATE mixin 加载器(注入普通 mod 类,如 ProjectE):@LateMixin 注解让 unimixins 在
 * FML CONSTRUCTING 阶段(所有 mod jar 已进 classpath)实例化本类并询问要加载的 mixin。
 * mixin 注册在 {@link Mixins} 枚举(IMixins 方式),这里只提供配置文件名。
 */
@LateMixin
public class LateMixinsLoader implements ILateMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.autoemcgtnh.late.json";
    }

    @Nonnull
    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        // 具体 mixin 是否启用由 Mixins 枚举里每个 builder 的 setApplyIf/phase 决定
        return IMixins.getLateMixins(Mixins.class, loadedMods);
    }
}
