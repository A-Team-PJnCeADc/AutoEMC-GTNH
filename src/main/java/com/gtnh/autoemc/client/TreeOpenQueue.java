package com.gtnh.autoemc.client;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Loader;

/**
 * 客户端"待打开/对齐"队列:
 * - 网络包处理器在 Netty IO 线程收到分片只做组装(onChainChunk),不碰任何 GUI/NEI;
 * - 客户端主线程 tick(consume)统一消费:NEI-RecipeTree 未装则提示,已装则经反射
 * 调 RecipeTreeOpener(该类引用 NEI/RecipeTree 类型,不能在这里直接出现字节码引用)。
 */
public final class TreeOpenQueue {

    private static final Logger LOG = LogManager.getLogger("AutoEMC");
    private static final String MOD = "neirecipetree";
    private static final String OPENER = "com.gtnh.autoemc.client.RecipeTreeOpener";

    private static int expectedTotal;
    private static int nextChunk;
    private static String pendingInfo;
    private static final List<ViewNode> pendingNodes = new ArrayList<>();
    private static volatile ViewRequest pending;

    private TreeOpenQueue() {}

    /** Netty IO 线程:接收一个分片 */
    public static synchronized void onChainChunk(int total, int chunk, String info, List<String> lines) {
        if (chunk == 0) {
            // 新请求的第一个分片 → 重置组装状态
            expectedTotal = total;
            nextChunk = 0;
            pendingInfo = info;
            pendingNodes.clear();
        }
        if (expectedTotal <= 0 || chunk != nextChunk) {
            // 乱序/重复/无头分片 → 丢弃整个请求,避免错位
            expectedTotal = 0;
            pendingNodes.clear();
            return;
        }
        for (String line : lines) {
            if (line == null || line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\|");
            // parts[0]=key, [1]=source, [2]=outQty, [3..]=child*qty
            String key = parts[0];
            String source = parts.length > 1 ? parts[1] : null;
            int outQty = 1;
            if (parts.length > 2) {
                try {
                    outQty = Integer.parseInt(parts[2]);
                } catch (NumberFormatException e) {
                    outQty = 1;
                }
            }
            List<ViewNode.Child> children = new ArrayList<>();
            for (int i = 3; i < parts.length; i++) {
                String s = parts[i];
                if (s.isEmpty()) {
                    continue;
                }
                int star = s.lastIndexOf('*');
                String childKey = s;
                int qty = 1;
                if (star > 0 && star < s.length() - 1) {
                    childKey = s.substring(0, star);
                    try {
                        qty = Integer.parseInt(s.substring(star + 1));
                    } catch (NumberFormatException e) {
                        qty = 1;
                    }
                }
                if (qty <= 0) {
                    qty = 1;
                }
                children.add(new ViewNode.Child(childKey, qty));
            }
            pendingNodes.add(new ViewNode(key, source, outQty, children));
        }
        nextChunk++;
        if (nextChunk >= expectedTotal) {
            ViewRequest request = new ViewRequest(new ArrayList<>(pendingNodes), pendingInfo);
            pendingNodes.clear();
            expectedTotal = 0;
            pending = request;
        }
    }

    /** 客户端主线程 tick 调用 */
    public static void consume() {
        ViewRequest request = pending;
        if (request == null) {
            return;
        }
        pending = null;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || request.nodes.isEmpty()) {
            return;
        }
        if (request.info != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText("\u00a7e[AutoEMC] " + request.info));
        }
        if (!Loader.isModLoaded(MOD)) {
            mc.thePlayer.addChatMessage(new ChatComponentText("\u00a7e[AutoEMC] 未安装 NEI-RecipeTree,无法查看配方树。"));
            return;
        }
        try {
            Class<?> clazz = Class.forName(OPENER);
            Method open = clazz.getMethod("openAligned", ViewRequest.class);
            open.invoke(null, request);
        } catch (Throwable t) {
            LOG.warn("Failed to open aligned recipe tree: {}", t.toString());
            mc.thePlayer.addChatMessage(new ChatComponentText("\u00a7e[AutoEMC] 打开配方树失败:" + t));
        }
    }
}
