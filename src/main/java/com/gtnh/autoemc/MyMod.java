package com.gtnh.autoemc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.gtnh.autoemc.emc.AutoEmcConfig;
import com.gtnh.autoemc.emc.EmcRunner;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

@Mod(
    modid = MyMod.MODID,
    version = Tags.VERSION,
    name = "AutoEMCGTNH",
    acceptedMinecraftVersions = "[1.7.10]",
    dependencies = "required-after:ProjectE")
public class MyMod {

    public static final String MODID = "autoemcgtnh";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = "com.gtnh.autoemc.ClientProxy", serverSide = "com.gtnh.autoemc.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
        // 服务端 tick 处理器:仅专用服务器路径使用(单机集成服务器走 serverStarted 同步流程,
        // 不会发布 pending;此处理器常驻无害)。见 EmcRunner/ServerTickHandler 注释。
        FMLCommonHandler.instance()
            .bus()
            .register(new ServerTickHandler());
    }

    @Mod.EventHandler
    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    // register server commands in this event handler (Remove if not needed)
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }

    /**
     * AutoEMC 主流程:在所有 mod 的 serverStarting(含 ProjectE 的 map#1)之后运行,
     * 读取 ProjectE 已算好的锚点值,补全缺失物品的 EMC,再触发一次映射重建。
     * 仅服务端(含单机集成服务器)执行;纯客户端连接远程服务器不会触发。
     */
    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        if (FMLCommonHandler.instance()
            .getEffectiveSide()
            .isServer() && net.minecraft.server.MinecraftServer.getServer() != null
            && AutoEmcConfig.getConfigDir() != null) {
            EmcRunner.run();
        }
    }
}
