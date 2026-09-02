package com.gtnh.autoemc;

import com.gtnh.autoemc.client.TreeOpenTickHandler;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;

public class ClientProxy extends CommonProxy {

    // Override CommonProxy methods here, if you want a different behaviour on the client (e.g. registering renders).
    // Don't forget to call the super methods as well.

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        // 主线程 tick 消费"打开配方树"请求(NEI 相关调用必须回到主线程)
        FMLCommonHandler.instance()
            .bus()
            .register(new TreeOpenTickHandler());
    }
}
