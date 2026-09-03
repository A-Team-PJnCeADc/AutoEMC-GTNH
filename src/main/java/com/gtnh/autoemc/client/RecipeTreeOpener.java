package com.gtnh.autoemc.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import codechicken.nei.PositionedStack;
import cpw.mods.fml.common.FMLCommonHandler;
import moe.takochan.neirecipetree.bom.BoM;
import moe.takochan.neirecipetree.gui.GuiRecipeTree;
import moe.takochan.neirecipetree.recipe.ItemStackKey;
import moe.takochan.neirecipetree.recipe.NEIRecipeRef;
import moe.takochan.neirecipetree.recipe.RecipeLookup;

/**
 * 配方树查看:
 * <ol>
 * <li>配方链式求值在服务端完成(引擎选择每个物品的配方与输入槽),结果经网络包下发;</li>
 * <li>客户端不反查 NEI(尤其 GT 机器产物反查不到),把服务端下发的配方包装成合成
 * handler 喂给 NEI-RecipeTree 渲染;</li>
 * <li>引擎没有记录的物品(PE 锚点/未定价)回退到 NEI 反查普通打开;</li>
 * <li>进入"对齐会话":进入前快照 BoM 状态,关闭配方树后还原。</li>
 * </ol>
 */
public final class RecipeTreeOpener {

    private static final Logger LOG = LogManager.getLogger("AutoEMC");

    private static boolean tickRegistered;
    /** 当前对齐会话(顶层 WatchTickHandler 同包访问) */
    static AlignSession session;

    private RecipeTreeOpener() {}

    /** BoM 状态快照(进入对齐前),用于结束时还原 */
    private static final class AlignSession {

        final Map<ItemStackKey, NEIRecipeRef> defaultRecipes = new HashMap<>();
        final Map<ItemStackKey, NEIRecipeRef> addedRecipes = new HashMap<>();
        final Set<NEIRecipeRef> disabledRecipes = new HashSet<>();
        final Set<ItemStackKey> userExpandedNodes = new HashSet<>();
        final Map<ItemStackKey, Integer> recipeIndices = new HashMap<>();
        boolean craftingMode;
    }

    /** 客户端主线程;由 TreeOpenQueue 经反射调用(保持双端安全) */
    public static void openAligned(ViewRequest request) {
        Minecraft mc = Minecraft.getMinecraft();
        if (request == null || request.nodes.isEmpty() || mc.thePlayer == null) {
            return;
        }
        try {
            // 解析节点
            List<ResolvedNode> nodes = new ArrayList<>();
            ItemStack root = null;
            for (ViewNode vn : request.nodes) {
                ItemStack stack = vn.resolve();
                if (stack == null) {
                    continue;
                }
                nodes.add(new ResolvedNode(stack, vn.source, vn.outputQty, vn.children));
                if (root == null) {
                    root = stack;
                }
            }
            if (nodes.isEmpty() || root == null) {
                chat(mc, "\u00a7e[AutoEMC] 无法解析目标物品。");
                return;
            }

            if (nodes.get(0).source == null) {
                // 引擎无该物品记录 -> NEI 反查普通打开(仅简单物品可工作)
                plainOpen(mc, root);
                return;
            }
            alignedOpen(mc, nodes, root);
        } catch (Throwable t) {
            LOG.warn("Failed to open aligned recipe tree: {}", t.toString());
            endSession();
            chat(mc, "\u00a7e[AutoEMC] 打开配方树失败:" + t);
        }
    }

    private static final class ResolvedNode {

        final ItemStack stack;
        final String source;
        final int outputQty;
        final List<ViewNode.Child> children;

        ResolvedNode(ItemStack stack, String source, int outputQty, List<ViewNode.Child> children) {
            this.stack = stack;
            this.source = source;
            this.outputQty = outputQty;
            this.children = children;
        }
    }

    /** 用服务端下发的配方树数据渲染(不反查 NEI) */
    private static void alignedOpen(Minecraft mc, List<ResolvedNode> nodes, ItemStack root) {
        endSession();
        AlignSession snapshot = capture();
        BoM.clear();
        session = snapshot;

        // 有配方的节点键集合
        Set<ItemStackKey> nodeKeys = new HashSet<>();
        for (ResolvedNode node : nodes) {
            ItemStackKey k = ItemStackKey.of(node.stack);
            if (k != null) {
                nodeKeys.add(k);
            }
        }

        int aligned = 0;
        NEIRecipeRef rootRef = null;
        for (ResolvedNode node : nodes) {
            NEIRecipeRef ref = syntheticRef(node);
            if (ref == null) {
                continue;
            }
            ItemStackKey key = ItemStackKey.of(node.stack);
            if (key != null) {
                BoM.addedRecipes.put(key, ref);
            }
            if (rootRef == null) {
                rootRef = ref;
            }
            aligned++;
        }

        // 叶子(作为输入出现、但没有配方的物品)加"空配方":阻止 BoM.getRecipe 回退到
        // NEI 反查(反查慢,且 GT 机器产物反查不到,会导致建树时卡住 = 预览界面未响应)。
        Map<ItemStackKey, ItemStack> leaves = new HashMap<>();
        for (ResolvedNode node : nodes) {
            for (ViewNode.Child c : node.children) {
                ItemStack cs = ViewRequest.resolveKey(c.key);
                if (cs == null) {
                    continue;
                }
                ItemStackKey ck = ItemStackKey.of(cs);
                if (ck != null && !nodeKeys.contains(ck) && !leaves.containsKey(ck)) {
                    leaves.put(ck, cs);
                }
            }
        }
        for (Map.Entry<ItemStackKey, ItemStack> e : leaves.entrySet()) {
            if (!BoM.addedRecipes.containsKey(e.getKey())) {
                BoM.addedRecipes.put(e.getKey(), dummyRef(e.getValue()));
            }
        }

        if (rootRef == null) {
            endSession();
            chat(mc, "\u00a7e[AutoEMC] 无法构造 " + root.getDisplayName() + " 的配方。");
            return;
        }
        BoM.setGoal(rootRef);
        mc.displayGuiScreen(new GuiRecipeTree(mc.currentScreen));
        chat(mc, "\u00a7a[AutoEMC] 已按 AutoEMC 引擎配方对齐(" + aligned + " 个节点),关闭配方树后自动恢复。");
        ensureTickRegistered();
    }

    /** 叶子物品的空配方(无输入),使其在配方树里成为"无配方"的终端,避免 NEI 反查 */
    private static NEIRecipeRef dummyRef(ItemStack leaf) {
        ItemStack out = leaf.copy();
        out.stackSize = 1;
        PositionedStack result = new PositionedStack(new ItemStack[] { out }, 0, 0);
        SyntheticRecipeHandler handler = new SyntheticRecipeHandler(
            "",
            "autoemc:leaf:" + leaf.getUnlocalizedName(),
            new ArrayList<PositionedStack>(),
            result);
        return new NEIRecipeRef(handler, 0);
    }

    /** 服务端节点 -> 合成 handler -> NEIRecipeRef */
    private static NEIRecipeRef syntheticRef(ResolvedNode node) {
        List<PositionedStack> inputs = new ArrayList<>();
        for (ViewNode.Child c : node.children) {
            ItemStack cs = ViewRequest.resolveKey(c.key);
            if (cs == null) {
                continue;
            }
            ItemStack s = cs.copy();
            s.stackSize = Math.max(1, c.qty);
            inputs.add(new PositionedStack(new ItemStack[] { s }, 0, 0));
        }
        ItemStack out = node.stack.copy();
        out.stackSize = Math.max(1, node.outputQty);
        PositionedStack result = new PositionedStack(new ItemStack[] { out }, 0, 0);
        String name = node.source == null ? "" : node.source;
        SyntheticRecipeHandler handler = new SyntheticRecipeHandler(
            name,
            "autoemc:" + node.stack.getUnlocalizedName(),
            inputs,
            result);
        return new NEIRecipeRef(handler, 0);
    }

    /** 引擎无记录 -> NEI 反查第一条产出它的配方 */
    private static void plainOpen(Minecraft mc, ItemStack root) {
        List<NEIRecipeRef> refs = RecipeLookup.findRecipes(root);
        NEIRecipeRef ref = null;
        for (NEIRecipeRef r : refs) {
            if (produces(r, root)) {
                ref = r;
                break;
            }
        }
        if (ref == null) {
            chat(mc, "\u00a7e[AutoEMC] 在 NEI 里找不到 " + root.getDisplayName() + " 的配方。");
            return;
        }
        BoM.setGoal(ref);
        mc.displayGuiScreen(new GuiRecipeTree(mc.currentScreen));
    }

    private static boolean produces(NEIRecipeRef ref, ItemStack target) {
        for (ItemStack out : ref.getAllOutputs()) {
            if (out != null && sameItem(out, target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameItem(ItemStack a, ItemStack b) {
        return a.getItem() == b.getItem() && a.getItemDamage() == b.getItemDamage();
    }

    // 对齐会话

    private static AlignSession capture() {
        AlignSession s = new AlignSession();
        s.defaultRecipes.putAll(BoM.defaultRecipes);
        s.addedRecipes.putAll(BoM.addedRecipes);
        s.disabledRecipes.addAll(BoM.disabledRecipes);
        s.userExpandedNodes.addAll(BoM.userExpandedNodes);
        s.recipeIndices.putAll(BoM.recipeIndices);
        s.craftingMode = BoM.craftingMode;
        return s;
    }

    private static void restore(AlignSession s) {
        BoM.clear();
        BoM.defaultRecipes.putAll(s.defaultRecipes);
        BoM.addedRecipes.putAll(s.addedRecipes);
        BoM.disabledRecipes.addAll(s.disabledRecipes);
        BoM.userExpandedNodes.addAll(s.userExpandedNodes);
        BoM.recipeIndices.putAll(s.recipeIndices);
        BoM.craftingMode = s.craftingMode;
    }

    /** 供顶层 WatchTickHandler 调用(同包访问):结束对齐会话 */
    static void endSession() {
        if (session != null) {
            restore(session);
            session = null;
        }
    }

    private static void ensureTickRegistered() {
        if (!tickRegistered) {
            tickRegistered = true;
            FMLCommonHandler.instance()
                .bus()
                .register(new WatchTickHandler());
        }
    }

    private static void chat(Minecraft mc, String text) {
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(text));
        }
    }
}
