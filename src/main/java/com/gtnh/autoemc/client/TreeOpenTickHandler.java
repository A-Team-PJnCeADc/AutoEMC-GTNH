package com.gtnh.autoemc.client;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** 客户端主线程 tick:消费网络包入队的"打开配方树"请求。 */
public class TreeOpenTickHandler {

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            TreeOpenQueue.consume();
        }
    }
}
