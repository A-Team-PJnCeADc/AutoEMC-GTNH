package com.gtnh.autoemc.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;

import codechicken.nei.PositionedStack;
import codechicken.nei.api.IOverlayHandler;
import codechicken.nei.api.IRecipeOverlayRenderer;
import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.ICraftingHandler;

/**
 * 服务端下发的配方树节点对应的"合成 recipe handler":把 AutoEMC 引擎在服务端选中的配方
 * (机器名 + 输入槽物品×数量 + 输出×数量)包装成 NEI-RecipeTree 能渲染的 ICraftingHandler。
 * 客户端不再反查 NEI(尤其 GT 机器产物反查不到),纯渲染服务端数据。
 * 只用于喂给 NEIRecipeRef/MaterialTree 渲染,不参与 NEI 界面查询。
 */
public final class SyntheticRecipeHandler implements ICraftingHandler {

    private final String name;
    private final String handlerId;
    private final List<PositionedStack> inputs;
    private final PositionedStack result;

    public SyntheticRecipeHandler(String name, String handlerId, List<PositionedStack> inputs, PositionedStack result) {
        this.name = name;
        this.handlerId = handlerId;
        this.inputs = inputs == null ? new ArrayList<>() : inputs;
        this.result = result;
    }

    @Override
    public String getRecipeName() {
        return name;
    }

    @Override
    public String getHandlerId() {
        return handlerId;
    }

    @Override
    public int numRecipes() {
        return 1;
    }

    @Override
    public List<PositionedStack> getIngredientStacks(int recipe) {
        return inputs;
    }

    @Override
    public PositionedStack getResultStack(int recipe) {
        return result;
    }

    @Override
    public List<PositionedStack> getOtherStacks(int recipe) {
        return new ArrayList<>();
    }

    @Override
    public ICraftingHandler getRecipeHandler(String outputId, Object... results) {
        return this;
    }

    @Override
    public void drawBackground(int recipe) {}

    @Override
    public void drawForeground(int recipe) {}

    @Override
    public void onUpdate() {}

    @Override
    public boolean hasOverlay(GuiContainer gui, Container container, int recipe) {
        return false;
    }

    @Override
    public IRecipeOverlayRenderer getOverlayRenderer(GuiContainer gui, int recipe) {
        return null;
    }

    @Override
    public IOverlayHandler getOverlayHandler(GuiContainer gui, int recipe) {
        return null;
    }

    @Override
    public List<String> handleTooltip(GuiRecipe<?> gui, List<String> currenttip, int recipe) {
        return currenttip;
    }

    @Override
    public List<String> handleItemTooltip(GuiRecipe<?> gui, ItemStack stack, List<String> currenttip, int recipe) {
        return currenttip;
    }

    @Override
    public boolean keyTyped(GuiRecipe<?> gui, char keyChar, int keyCode, int recipe) {
        return false;
    }

    @Override
    public boolean mouseClicked(GuiRecipe<?> gui, int button, int recipe) {
        return false;
    }
}
