package com.gtnh.autoemc.emc;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import net.minecraftforge.common.config.Configuration;

import cpw.mods.fml.common.Loader;

/**
 * AutoEMC 配置(preInit 时从 config/AutoEMC.cfg 加载)。
 */
public final class AutoEmcConfig {

    public static boolean enabled = true;
    /** 无价物品按 0 计 */
    public static boolean unpricedIsZero = true;
    /** 蒸汽机可处理配方的 EU/t 上限:低于等于它且 map 在蒸汽名单内 → 按"蒸汽时代"计 */
    public static int steamMaxEUt = 30;
    /** 多方块结构独占的 RecipeMap(unlocalizedName 列表) */
    public static final Set<String> multiMaps = new HashSet<>();
    /** 存在蒸汽单方块机器的 RecipeMap:低 EU/t 配方优先按蒸汽时代计 */
    public static final Set<String> steamMaps = new HashSet<>();
    /** 是否把 mHidden 的 GT 配方也计入 */
    public static boolean includeHiddenRecipes = false;
    /** 缓存 JSON:启动时只补 diff */
    public static boolean cacheJson = true;
    /** 忽略缓存 JSON,全量重算 */
    public static boolean forceRebuild = false;
    /** 无法求值的物品最多打印多少条 */
    public static int unresolvedLogLimit = 100;
    /** 把算出的 EMC 值 dump 到 config/AutoEMC/emc-values.csv 便于核对 */
    public static boolean dumpCsv = true;

    private static File configDir;

    private AutoEmcConfig() {}

    public static File getConfigDir() {
        return configDir;
    }

    public static File getCacheFile() {
        return new File(configDir, "emc-values.json");
    }

    public static void init(File configFile) {
        configDir = configFile.getParentFile();
        Configuration cfg = new Configuration(configFile);
        cfg.load();

        enabled = cfg.getBoolean("enabled", "general", true, "是否启用自动 EMC 补全");
        unpricedIsZero = cfg.getBoolean("unpricedIsZero", "general", true, "求值时遇到没有价格的材料按 0 计(否则该配方无法参与定价)");
        steamMaxEUt = cfg.getInt(
            "steamMaxEUt",
            "tier",
            30,
            0,
            Integer.MAX_VALUE,
            "低于等于该 EU/t 的配方在蒸汽名单(map)内时按'蒸汽时代'计(默认 30≈LV 配方上限)");
        includeHiddenRecipes = cfg.getBoolean("includeHiddenRecipes", "recipes", false, "是否把 GT 的 mHidden 配方也纳入求值");
        cacheJson = cfg
            .getBoolean("cacheJson", "cache", true, "把结果缓存到 config/AutoEMC/emc-values.json,下次启动只计算缺少的部分(diff)");
        forceRebuild = cfg.getBoolean("forceRebuild", "cache", false, "忽略缓存 JSON 全量重算一次(配方大改/觉得数值不对时开一次再关掉)");
        unresolvedLogLimit = cfg.getInt("unresolvedLogLimit", "debug", 100, 0, 100000, "无法求值的物品最多打印多少条到日志");
        dumpCsv = cfg.getBoolean("dumpCsv", "debug", true, "把结果 dump 到 config/AutoEMC/emc-values.csv");

        String[] defaultsMulti = { "gt.recipe.blastfurnace", "gt.recipe.primitiveblastfurnace",
            "gt.recipe.fusionreactor", "gt.recipe.distillationtower", "gt.recipe.largechemicalreactor",
            "gt.recipe.craker", "gt.recipe.solarfactory", "gt.recipe.pcbfactory", "gt.recipe.nanoforge",
            "gt.recipe.plasmaforge", "gt.recipe.transcendentplasmamixerrecipes", "gt.recipe.quantumcomputer",
            "gt.recipe.purificationplantclarifier", "gt.recipe.purificationplantdegasifier",
            "gt.recipe.purificationplantflocculation", "gt.recipe.purificationplantozonation",
            "gt.recipe.purificationplantphadjustment", "gt.recipe.purificationplantplasmaheating",
            "gt.recipe.purificationplantquarkextractor", "gt.recipe.purificationplantuvtreatment",
            "gt.recipe.fakespaceprojects", "gt.recipe.entropic-processing", };
        String[] defaultsSteam = { "gt.recipe.macerator", "gt.recipe.compressor", "gt.recipe.extractor",
            "gt.recipe.furnace", "gt.recipe.alloysmelter", "gt.recipe.hammer", };

        multiMaps.clear();
        multiMaps.addAll(
            Arrays.asList(
                cfg.getStringList(
                    "multiblockMaps",
                    "recipes",
                    defaultsMulti,
                    "多方块结构独占的 RecipeMap unlocalizedName(这些 map 的配方在机器分类里排最后)")));
        steamMaps.clear();
        steamMaps.addAll(
            Arrays.asList(
                cfg.getStringList(
                    "steamMaps",
                    "tier",
                    defaultsSteam,
                    "存在蒸汽单方块机器的 RecipeMap unlocalizedName(低 EU/t 配方按蒸汽时代计)")));

        if (cfg.hasChanged()) {
            cfg.save();
        }
    }

    public static boolean hasGregTech() {
        return Loader.isModLoaded("gregtech");
    }
}
