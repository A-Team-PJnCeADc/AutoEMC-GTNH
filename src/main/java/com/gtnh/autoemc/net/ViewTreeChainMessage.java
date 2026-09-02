package com.gtnh.autoemc.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.PacketBuffer;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * S2C:AutoEMC 对齐链(分片发送,防止超包)。
 *
 * 每片携带 total/chunk 序号与若干"节点行";节点行格式:
 * &lt;stackKey&gt;|&lt;childKey1&gt;|&lt;childKey2&gt;...(stackKey = 注册名@meta)
 * 行内以 '|' 分隔,children 可能为空。客户端收齐后组装成 ViewRequest 并打开对齐的配方树。
 */
public class ViewTreeChainMessage implements IMessage {

    public static final int CHUNK_SIZE = 100;

    public int totalChunks;
    public int chunkIndex;
    public String info;
    public List<String> nodeLines;

    public ViewTreeChainMessage() {}

    public ViewTreeChainMessage(int totalChunks, int chunkIndex, String info, List<String> nodeLines) {
        this.totalChunks = totalChunks;
        this.chunkIndex = chunkIndex;
        this.info = info == null ? "" : info;
        this.nodeLines = nodeLines;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        try {
            PacketBuffer pb = new PacketBuffer(buf);
            totalChunks = pb.readVarIntFromBuffer();
            chunkIndex = pb.readVarIntFromBuffer();
            info = pb.readStringFromBuffer(Short.MAX_VALUE);
            int count = pb.readVarIntFromBuffer();
            nodeLines = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                nodeLines.add(pb.readStringFromBuffer(Short.MAX_VALUE));
            }
        } catch (IOException e) {
            totalChunks = 0;
            nodeLines = new ArrayList<>();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        try {
            PacketBuffer pb = new PacketBuffer(buf);
            pb.writeVarIntToBuffer(totalChunks);
            pb.writeVarIntToBuffer(chunkIndex);
            pb.writeStringToBuffer(info);
            pb.writeVarIntToBuffer(nodeLines == null ? 0 : nodeLines.size());
            if (nodeLines != null) {
                for (String line : nodeLines) {
                    pb.writeStringToBuffer(line);
                }
            }
        } catch (IOException e) {
            // 写失败静默
        }
    }

    public static class Handler implements IMessageHandler<ViewTreeChainMessage, IMessage> {

        @Override
        public IMessage onMessage(ViewTreeChainMessage message, MessageContext ctx) {
            if (ctx.side == Side.CLIENT && message.nodeLines != null) {
                com.gtnh.autoemc.client.TreeOpenQueue
                    .onChainChunk(message.totalChunks, message.chunkIndex, message.info, message.nodeLines);
            }
            return null;
        }
    }
}
