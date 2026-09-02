package com.gtnh.autoemc.client;

import net.minecraft.client.Minecraft;

import codechicken.nei.recipe.GuiRecipe;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import moe.takochan.neirecipetree.gui.GuiRecipeTree;

/**
 * 查看完毕(树/相关 NEI 界面关闭,或离开世界)→ 结束对齐会话。
 * 必须是顶层 public 类:FML 用 ASM 给 @SubscribeEvent 类生成包装子类并由独立
 * ASMClassLoader 加载,Java 9+ 下跨 loader 访问嵌套类(哪怕是 public static)会抛
 * IllegalAccessError 崩客户端(顶层类不受影响)。
 */
public final class WatchTickHandler {

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || RecipeTreeOpener.session == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        boolean treeActive = mc.currentScreen instanceof GuiRecipeTree;
        boolean neiOverlay = mc.currentScreen instanceof GuiRecipe;
        if (mc.thePlayer == null || (!treeActive && !neiOverlay)) {
            RecipeTreeOpener.endSession();
        }
    }
}
