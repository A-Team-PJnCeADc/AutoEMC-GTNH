package com.gtnh.autoemc.mixins;

import javax.annotation.Nonnull;

import com.gtnewhorizon.gtnhmixins.builders.IMixins;
import com.gtnewhorizon.gtnhmixins.builders.MixinBuilder;

import cpw.mods.fml.common.Loader;

/**
 * 本 mod 的全部 mixin 声明(GTNH IMixins 方式)。早/晚(mixin 注入 Minecraft/Forge vs 注入普通 mod 类)都在
 * 这一个枚举里声明:
 *
 * <ul>
 * <li>EARLY —— 注入 Minecraft/Forge/CoreMod 类,由 EarlyMixinsLoader(IFMLLoadingPlugin)
 * 在 bootstrap 期注册;</li>
 * <li>LATE —— 注入普通 mod 类(如 ProjectE 的 EMCMapper),由 {@link LateMixinsLoader}
 * 在 FML CONSTRUCTING 之后注册,目标 mod 必须已进 classpath(ProjectE 不在就跳过)。</li>
 * </ul>
 *
 * <p>
 * 我们目前只有 LATE 的 ProjectE 类型表注入;没有 EARLY mixin,所以也没有
 * EarlyMixinsLoader/coreModClass。
 */
public enum Mixins implements IMixins {

    /**
     * 往 ProjectE 的 EMCMapper 注册"新 Registry Types"值表(流体/源质等非物品 EMC):
     * 只有 ProjectE 加载时才应用(LATE phase,目标类在 bootstrap 期不存在)。
     */
    PROJECTE_EMC_TYPES(new MixinBuilder().setPhase(Phase.LATE)
        .setApplyIf(() -> Loader.isModLoaded("ProjectE"))
        .addCommonMixins("EmcMapperTypeMixin"));

    private final MixinBuilder builder;

    Mixins(MixinBuilder builder) {
        this.builder = builder;
    }

    @Nonnull
    @Override
    public MixinBuilder getBuilder() {
        return builder;
    }
}
