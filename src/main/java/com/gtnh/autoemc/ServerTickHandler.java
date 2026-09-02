package com.gtnh.autoemc;

import com.gtnh.autoemc.emc.EmcRunner;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 服务端 tick:把 EmcRunner 后台算好的结果应用到 Server 线程。
 *
 * <p>
 * <b>仅专用服务器路径使用</b>(EmcRunner.run 按 isDedicatedServer 分流:专用服务器才发布
 * pending)。单机集成服务器走 serverStarted 内同步 compute+apply,永不发布 pending;
 * applyPendingIfReady 里还有集成服务器防御(丢弃 + 告警),防止 tick 应用落在玩家登录后
 * 与客户端线程并发改 EMCMapper.emc 重演 CME 崩连接。
 *
 * <p>
 * 顶层 public 类(FML bus ASM 包装器跨 classloader 访问,Java 9+ 嵌套类会 IllegalAccessError)。
 */
public final class ServerTickHandler {

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            EmcRunner.applyPendingIfReady();
        }
    }
}
