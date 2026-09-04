package com.gtnh.autoemc.mixins.late;

import java.util.HashMap;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnh.autoemc.registry.TypeTableBridge;

import moze_intel.projecte.emc.EMCMapper;

/**
 * 在 ProjectE 的 {@link EMCMapper}(物品 EMC 的运行时中枢)里挂一张"新 Registry Types"
 * 值表(autoemc$typeValues,键 = EmcKey canonical 串 "type:id",如 "fluid:molten.iron"):
 *
 * <ul>
 * <li>&lt;clinit&gt; TAIL —— 表建好后交给 {@link TypeTableBridge},由它接管
 * {@code EmcRegistry} 的镜像写入(PE 侧表与 AutoEMC 注册表保持一致);</li>
 * <li>clearMaps HEAD —— PE 重建/清空物品映射时同步清空类型表(生命周期与 PE 映射一致,
 * 值由 AutoEMC 每次全量注册时重新写入)。</li>
 * </ul>
 *
 * <p>
 * EMCMapper 是非混淆 mod 类(MCP 名),所有注入点必须 remap = false。
 * LATE phase:ProjectE 未加载时整类不应用(见 {@code Mixins.PROJECTE_EMC_TYPES})。
 */
@Mixin(EMCMapper.class)
public abstract class EmcMapperTypeMixin {

    @Unique
    private static final Map<String, Integer> autoemc$typeValues = new HashMap<>();

    @Inject(method = "<clinit>", at = @At("TAIL"), remap = false)
    private static void autoemc$registerTypeTable(CallbackInfo ci) {
        // 把刚建好的静态表交给桥:桥会先回放 AutoEMC 注册表已有值,再接管后续镜像写入
        TypeTableBridge.onPeTypeTableReady(autoemc$typeValues);
    }

    @Inject(method = "clearMaps", at = @At("HEAD"), remap = false)
    private static void autoemc$clearTypeTable(CallbackInfo ci) {
        // PE 清空物品映射(clearMaps+map 重建)时类型表一并清空;随后 map() 完成时由
        // autoemc$replayTypeTable 把 AutoEMC 注册表(源)回放回来,reload 后立即自愈。
        autoemc$typeValues.clear();
    }

    @Inject(method = "map", at = @At("RETURN"), remap = false)
    private static void autoemc$replayTypeTable(CallbackInfo ci) {
        // 每次 PE map() 完成(/projecte reloadEMC、AutoEMC 自己的 map#2、PE 首次 map)后,
        // 把 AutoEMC 注册表(源)全量回放到这张类型表 —— clearMaps 清掉的流体/源质值即时恢复,
        // 不依赖 AutoEMC 下次注册(旧行为:手动 reload 后类型值缺失到下次重注册)。
        TypeTableBridge.replayCurrent();
    }
}
