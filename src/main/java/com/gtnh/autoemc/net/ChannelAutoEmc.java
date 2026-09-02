package com.gtnh.autoemc.net;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/** AutoEMC 网络频道(S2C:配方树对齐链,分片)。在 preInit 注册。 */
public final class ChannelAutoEmc {

    public static final String CHANNEL = "AUTOEMC";

    private static SimpleNetworkWrapper wrapper;

    private ChannelAutoEmc() {}

    public static void init() {
        if (wrapper != null) {
            return;
        }
        wrapper = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL);
        wrapper.registerMessage(ViewTreeChainMessage.Handler.class, ViewTreeChainMessage.class, 0, Side.CLIENT);
    }

    /** 把对齐链(节点行)分片发给玩家 */
    public static void sendChain(EntityPlayerMP player, String info, List<String> nodeLines) {
        if (wrapper == null || player == null || nodeLines == null || nodeLines.isEmpty()) {
            return;
        }
        int total = (nodeLines.size() + ViewTreeChainMessage.CHUNK_SIZE - 1) / ViewTreeChainMessage.CHUNK_SIZE;
        for (int chunk = 0; chunk < total; chunk++) {
            int from = chunk * ViewTreeChainMessage.CHUNK_SIZE;
            int to = Math.min(nodeLines.size(), from + ViewTreeChainMessage.CHUNK_SIZE);
            List<String> part = new ArrayList<>(nodeLines.subList(from, to));
            wrapper.sendTo(new ViewTreeChainMessage(total, chunk, chunk == 0 ? info : null, part), player);
        }
    }
}
