package com.gtnh.autoemc.emc;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.item.Item;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import moze_intel.projecte.api.ProjectEAPI;
import moze_intel.projecte.api.proxy.IEMCProxy;
import moze_intel.projecte.config.CustomEMCParser;
import moze_intel.projecte.emc.EMCMapper;
import moze_intel.projecte.emc.mappers.APICustomEMCMapper;
import moze_intel.projecte.handlers.TileEntityHandler;
import moze_intel.projecte.network.PacketHandler;

/**
 * AutoEMC 主流程,在 FMLServerStartedEvent(服务端)触发。
 *
 * <p>
 * 按服务器类型分两种执行模式:
 *
 * <ul>
 * <li><b>单机集成服务器(含局域网主机)— serverStarted 内同步 compute + apply。</b>
 * 客户端与服务器在同一 JVM,共享 ProjectE 的 EMCMapper.emc 静态表。玩家登录时 PE
 * ConnectionHandler 补发全量 EMC,客户端线程在 SyncEmcPKT.Handler 里 clear/重建这张表
 * 并 cacheFullKnowledge 迭代它;若 map#2(clearMaps + map,Server 线程,结构性写同一张表)
 * 落在玩家登录之后,两线程并发改同一 LinkedHashMap -> ConcurrentModificationException ->
 * "fatal error during network handshake" 崩连接(实测:cache miss 全量重算 ~3s,原
 * "下一 tick 应用"恰好撞上登录同步窗口)。FMLServerStartedEvent 在玩家登录之前触发,
 * 同步做完天然无竞态;玩家登录后由 PE 自己的 login 补发拿到最终映射。代价只是
 * cache miss 时世界加载多等几秒(加载画面,无实际卡顿)。</li>
 * <li><b>专用服务器 — 后台线程 compute + Server tick apply。</b>客户端在别的 JVM,
 * 不存在共享静态表竞态;后台算完由 ServerTickHandler 在 Server 线程应用,启动不被阻塞、
 * 玩家可立即进入。PE 的服务器端状态只在 Server 线程被改(等价于 /projecte reloadEMC,
 * 该命令本就允许在线玩家存在时执行)。</li>
 * </ul>
 */
public final class EmcRunner {

    private static final Logger LOG = LogManager.getLogger("AutoEMC");

    private EmcRunner() {}

    /** compute + apply 的中间产物(同步路径直接传递;专用服务器路径由后台线程发布、tick 消费) */
    private static final class PendingResult {

        final Map<ItemKey, Integer> finalValues;
        final Map<ItemKey, List<Pick>> mergedChains;
        final EmcEngine engine;

        PendingResult(Map<ItemKey, Integer> finalValues, Map<ItemKey, List<Pick>> mergedChains, EmcEngine engine) {
            this.finalValues = finalValues;
            this.mergedChains = mergedChains;
            this.engine = engine;
        }
    }

    /** 专用服务器路径:后台线程算好的结果,由 Server thread tick 应用;volatile 保证可见性 */
    private static volatile PendingResult pending;

    /** 入口:serverStarted(Server 线程)触发,按服务器类型选择执行模式。 */
    public static void run() {
        if (!AutoEmcConfig.enabled) {
            LOG.info("AutoEMC disabled by config, skip.");
            return;
        }
        if (!cpw.mods.fml.common.Loader.isModLoaded("ProjectE")) {
            LOG.warn("ProjectE not loaded, AutoEMC cannot run.");
            return;
        }
        final IEMCProxy proxy = ProjectEAPI.getEMCProxy();
        if (proxy == null) {
            LOG.error("ProjectE EMC proxy unavailable, AutoEMC aborted.");
            return;
        }
        // 神秘时代要素/源质 EMC(规则:6 元始=256,复合=子要素递归相加);写 Registry(ASPECT 类型)
        // 并镜像到 PE 侧类型表 —— 需在 Server 线程、EMCMapper 类已初始化(clinit)后执行。
        AspectSeeder.seed();
        net.minecraft.server.MinecraftServer srv = net.minecraft.server.MinecraftServer.getServer();
        boolean dedicated = srv != null && srv.isDedicatedServer();
        if (!dedicated) {
            // 单机集成服务器:客户端同 JVM 共享 EMCMapper.emc,登录 EMC 同步包(客户端线程)与
            // map#2(Server 线程)并发改表会 CME 崩连接 -> 在玩家登录前同步做完 compute + apply。
            try {
                PendingResult r = computeAndPublish(proxy);
                if (r != null) {
                    apply(r);
                }
            } catch (Throwable t) {
                LOG.error("AutoEMC compute/apply failed:", t);
            }
        } else {
            // 专用服务器:无共享静态表竞态 -> 后台线程算(不阻塞启动),Server tick 应用。
            Thread worker = new Thread(() -> {
                try {
                    pending = computeAndPublish(proxy);
                } catch (Throwable t) {
                    LOG.error("AutoEMC background compute failed:", t);
                }
            }, "AutoEMC-worker");
            worker.setDaemon(true);
            worker.start();
        }
    }

    /**
     * 专用服务器路径:Server thread tick 应用后台算好的结果。
     * 若结果在集成服务器上发布(理论上不会,防御):丢弃并告警——tick 应用必然落在玩家登录后,
     * 会重演客户端线程并发改 EMCMapper.emc 的 CME 崩连接。
     */
    public static void applyPendingIfReady() {
        PendingResult r = pending;
        if (r == null) {
            return;
        }
        pending = null;
        net.minecraft.server.MinecraftServer srv = net.minecraft.server.MinecraftServer.getServer();
        if (srv != null && !srv.isDedicatedServer()) {
            LOG.warn("Dropping pending AutoEMC result on integrated server: tick apply would race client EMC sync.");
            return;
        }
        apply(r);
    }

    /**
     * 配方扫描 + 求值 + 汇总 + 缓存落盘。集成服务器路径在 serverStarted 的 Server 线程上执行,
     * 专用服务器路径在 AutoEMC-worker 后台线程上执行(配方集合此时已全部注册完毕;快照迭代
     * 保留以防御注册并发)。
     */
    private static PendingResult computeAndPublish(IEMCProxy proxy) {
        long t0 = System.currentTimeMillis();
        Map<ItemKey, List<EmcRecipe>> producers = new HashMap<>();
        EmcStats stats = new EmcStats();
        RecipeCollector.collect(producers, stats);

        LOG.info(
            "Recipe scan: crafting={} (toolSlotsIgnored={}, unknown={}, wildcard={}), smelting={}, GT maps={} recipes={} (fluidInput={}, skipped disabled={} chance={} wildcard={} noout={} recycle={} tool={})",
            stats.craftingRecipes,
            stats.craftingToolSlots,
            stats.craftingSkippedUnknownType,
            stats.craftingSkippedWildcard,
            stats.smeltRecipes,
            stats.gtMaps,
            stats.gtRecipes,
            stats.gtRecipesWithFluid,
            stats.gtSkippedDisabled,
            stats.gtSkippedChance,
            stats.gtSkippedWildcard,
            stats.gtSkippedNoOutput,
            stats.gtSkippedRecycle,
            stats.gtToolSlots);
        if (stats.alRecipes > 0 || stats.alSkipped > 0) {
            // 装配线配方在 RecipeMap 之外(数据棒注册表),单独报告
            LOG.info("Assembly line recipes: registered={} (skipped={})", stats.alRecipes, stats.alSkipped);
        }
        if (stats.avRecipes > 0 || stats.avSkipped > 0) {
            // Avaritia 大工作台配方独立注册表,单独报告
            LOG.info("Avaritia extreme-crafting recipes: registered={} (skipped={})", stats.avRecipes, stats.avSkipped);
        }
        String skippedBreakdown = stats.topSkippedUnknownClasses(15);
        if (!skippedBreakdown.isEmpty()) {
            // 定位"哪个 mod 的哪类自定义 IRecipe 没被识别/解析":按配方类名聚合的跳过明细
            // (总量 == 上一行的 unknown=;若干条后是驱动 RecipeCollector.register() 新源的依据)
            LOG.info("Crafting skipped-unknown breakdown by recipe class: {}", skippedBreakdown);
        }
        // 隔离层跳过的配方(异常类,非内容性):内容性跳过已经体现在上面的计数里,这些是
        // 畸形数据/API 漂移导致的单配方失败 —— 详情见各 RecipeScan error 日志(带 map/槽位/输出)
        int isolationErrors = stats.craftingSkippedError + stats.craftingSkippedIngredient
            + stats.smeltSkippedError
            + stats.gtSkippedError
            + stats.alSkippedError
            + stats.avSkippedError;
        if (isolationErrors > 0) {
            LOG.warn(
                "Recipe scan isolation errors (recipes skipped): craftingRecipe={}, craftingIngredient={}, smelting={}, gt={}, assemblyline={}, avaritia={}",
                stats.craftingSkippedError,
                stats.craftingSkippedIngredient,
                stats.smeltSkippedError,
                stats.gtSkippedError,
                stats.alSkippedError,
                stats.avSkippedError);
        }
        LOG.info("Producer targets: {}", producers.size());

        // 流体反推产者表:零物品输出、单一流体输出的 GT 配方(给无材料锭的流体按配方成本定价);
        // 计数入 stats -> 并入指纹(gt 源 fingerprintLines 的 fluidrecs= 行),产者集合变化即重算。
        Map<String, List<FluidProducer>> fluidProducers = new HashMap<>();
        if (GtMachines.available()) {
            GtMachines.collectFluidProducers(fluidProducers, stats);
            if (stats.gtFluidRecipes > 0) {
                LOG.info(
                    "Fluid producers (fluid-only recipes, for reverse pricing): {} producers for {} fluids",
                    stats.gtFluidRecipes,
                    fluidProducers.size());
            }
        }

        String fingerprint = ValueStore.computeFingerprint(stats);
        File cacheFile = AutoEmcConfig.getCacheFile();
        boolean cacheOk = AutoEmcConfig.cacheJson && !AutoEmcConfig.forceRebuild
            && fingerprint.equals(ValueStore.readFingerprint(cacheFile));
        Map<ItemKey, Integer> cached = cacheOk ? ValueStore.load(cacheFile) : new HashMap<>();
        // 对齐链与指纹无关地累积读取:AutoEMC 定价物品的值进 PE/缓存后不再重算,
        // 链要从持久文件恢复,才能保证 /projecte_autoemc view 在任意一次启动都回放完整配方树。
        Map<ItemKey, List<Pick>> persistedChains = AutoEmcConfig.cacheJson ? ValueStore.loadChains(cacheFile)
            : new HashMap<>();
        // 旧规则时代存下的链可能把"工具/一次性物品"当材料输入(如 ggfab 单次工具/GT 工具),
        // 新鲜求值绝不会产生这类子节点 -> 加载即清洗,防止 /view 树里展开工具。
        int prunedPersisted = pruneToolChildren(persistedChains);
        if (prunedPersisted > 0) {
            LOG.info(
                "Pruned {} tool children from {} persisted chains (stale rule-era data).",
                prunedPersisted,
                persistedChains.size());
        }
        if (cacheOk && !cached.isEmpty() && persistedChains.isEmpty()) {
            // 旧缓存升级:有值但链从未保存(schema 1 无 chains 段)-> 本次放弃预载,
            // 全量求值一次把链建全;否则 preload 短路求值,链永远为空。
            LOG.info("Cache has {} values but no chain data (legacy upgrade): full re-evaluation.", cached.size());
            cacheOk = false;
            cached = new HashMap<>();
        }
        LOG.info(
            "Cache {} ({} values, fingerprint {})",
            cacheOk ? "hit" : (AutoEmcConfig.forceRebuild ? "forced rebuild" : "miss"),
            cached.size(),
            fingerprint);

        EmcEngine engine = new EmcEngine(producers, fluidProducers, proxy);
        engine.preload(cached);

        // GTMoreEMC 移植:GT 材料形态直接按 质量×72×形态系数 定价(命中即定,不再走配方图;
        // 不覆盖 PE 锚点)。注入到 known 里,优先于一切配方求值。
        Map<ItemKey, Integer> seeds = new HashMap<>();
        if (GtMachines.available()) {
            seeds.putAll(GtMachines.collectMaterialSeeds());
            seeds.putAll(GtMachines.collectFixedSeeds());
        }
        int seededCount = engine.addSeeds(seeds);
        if (seededCount > 0) {
            LOG.info("GT material mass-seeding (mass*72, GTMoreEMC form table): seeded {} form values.", seededCount);
        }

        int computed = 0;
        int zeroValued = 0;
        for (ItemKey key : producers.keySet()) {
            if (engine.knownValue(key) != null || engine.isAnchoredByPe(key)) {
                continue;
            }
            if (engine.evalTarget(key) > 0) {
                computed++;
            } else {
                zeroValued++;
            }
        }

        // 假电路板(无产出配方的 circuit<等级> oredict 成员,如 dreamcraft CircuitMV)扫尾:
        // 在主求值循环之后、resolveDeferred 之前按"同级有价成员均值"定价 —— 这样第一遍因
        // 基础未定价而缓存 0 的引用配方,在第二遍重估时能拿到均值。
        int circuitBoardsPriced = engine.resolveCircuitBoards();
        if (circuitBoardsPriced > 0) {
            LOG.info("Circuit board averaging: priced {} fake circuit boards (same-tier mean).", circuitBoardsPriced);
        }

        // 第二遍:修复顺序依赖 sticky-0(纯环保持 0,有基础配方的项得到正确正值)
        int deferredFixed = engine.resolveDeferred();

        // 同等级电路板统一价(规则:任意电路板 = 同等级总价/数量,含真实板的覆盖)
        int tierChanged = engine.uniformCircuitBoardTiers();

        // 一致性重估:本次运行只要有 0->有价 的变化(假电路板扫尾定价、延迟修正 sticky-0、
        // 电路板同级统一),就可能存在按旧值(0)算过并滞留无价 pick 的依赖方 —— 例如求值时
        // 某槽所有透镜都还没价,0 透镜被当免费选中、成本记 0;等透镜定价后该产品值偏低、
        // /view 里还显示无价透镜。清空除规则叶子外的估值重算一遍,让它们在最终价上重选配方。
        // 稳态(cache hit 全量预载)三者都为 0,不触发,启动速度不受影响。
        if (circuitBoardsPriced > 0 || deferredFixed > 0 || tierChanged > 0) {
            int recomputed = engine.recomputeAfterTierUniform();
            LOG.info(
                "Consistency re-evaluation after {} board-averaged + {} deferred fixes + {} tier-uniform changes: recomputed {} downstream values.",
                circuitBoardsPriced,
                deferredFixed,
                tierChanged,
                recomputed);
        }

        // 流体第二遍(cache-miss 全量求值):第一遍物品求值时流体价值可能尚未解析(环/顺序依赖)
        // 或锚未定价,导致吃流体的配方候选失效/被当免费压价。清空重跑后,已解析的流体价值
        // (材料流体 144L=锭价、无锭按产者配方反推)参与最终选择;电路板同级统一价顺带重做一次。
        if (!cacheOk && (stats.gtFluidRecipes > 0 || stats.gtRecipesWithFluid > 0)) {
            int fluidRecomputed = engine.fluidRecompute();
            engine.uniformCircuitBoardTiers();
            LOG.info(
                "Fluid-cost second pass (fluid inputs now valued): recomputed {} item values; {} fluid values resolved.",
                fluidRecomputed,
                engine.fluidValueCount());
        }

        Map<ItemKey, Integer> finalValues = engine.collectFinalValues();
        int newCount = 0;
        for (ItemKey key : finalValues.keySet()) {
            if (!engine.isPreloaded(key)) {
                newCount++;
            }
        }
        LOG.info(
            "Evaluation done: known={}, newly computed>0={}, zero-valued={}, sticky-0 fixed={}; registering {} values ({} new since last cache)",
            engine.sizeOfKnown(),
            computed,
            zeroValued,
            deferredFixed,
            finalValues.size(),
            newCount);

        // 全量链 = 持久链 ∪ 本次新求值的 picks(新值覆盖旧条目;未重算的物品保留历史链)。
        // 加载时已清洗工具子节点;此处对合并结果再清洗一次(防御,新鲜 picks 不应含工具)。
        Map<ItemKey, List<Pick>> mergedChains = new HashMap<>(persistedChains);
        for (ItemKey key : finalValues.keySet()) {
            List<Pick> picks = engine.picksOf(key);
            if (picks != null && !picks.isEmpty()) {
                mergedChains.put(key, new ArrayList<>(picks));
            }
        }
        int prunedMerged = pruneToolChildren(mergedChains);
        if (prunedMerged > 0) {
            LOG.info("Pruned {} tool children from merged chains.", prunedMerged);
        }
        // 规则叶子(质量种子形态 / 同级平均电路板)没有配方输入:旧规则时代存下的机器配方链已过期,
        // 保留会让 /view 继续展开已经被种子/平均价定死价的形态 -> 剔除,避免树里出现与价格来源矛盾的展开。
        int prunedLeafChains = 0;
        Iterator<Map.Entry<ItemKey, List<Pick>>> cit = mergedChains.entrySet()
            .iterator();
        while (cit.hasNext()) {
            if (engine.isDefinedLeaf(
                cit.next()
                    .getKey())) {
                cit.remove();
                prunedLeafChains++;
            }
        }
        if (prunedLeafChains > 0) {
            LOG.info(
                "Pruned {} stale chains for rule-priced leaf nodes (mass seeds / tier-averaged boards).",
                prunedLeafChains);
        }

        if (!finalValues.isEmpty()) {
            ValueStore.save(cacheFile, fingerprint, finalValues, mergedChains);
        }
        if (AutoEmcConfig.dumpCsv) {
            writeCsv(new File(AutoEmcConfig.getConfigDir(), "emc-values.csv"), finalValues, engine);
        }
        LOG.info(
            "AutoEMC compute finished in {} ms; applying on serverStarted thread now.",
            System.currentTimeMillis() - t0);
        return new PendingResult(finalValues, mergedChains, engine);
    }

    /** 注册自定义 EMC + 重建映射 + 运行时 capture(必须主线程) */
    private static void apply(PendingResult r) {
        if (r.finalValues.isEmpty()) {
            LOG.info("Nothing to register (all targets already priced by ProjectE).");
            EmcRuntime.capture(r.finalValues, r.engine.snapshotChosen(), r.mergedChains, r.engine.snapshotAveraged());
            return;
        }
        try {
            // 不能走 ProjectEAPI IEMCProxy.registerCustomEMC:PE1.10.1 fork 只允许在 FML
            // LoaderState PRE/INITIALIZATION/POSTINITIALIZATION(mod 加载期)注册,serverStarted
            // 阶段调用会抛 IllegalStateException("tried to register EMC at an invalid time")。
            // 直调 APICustomEMCMapper.instance(public,无 loader-state 门禁):值写入内存表,
            // 由下方 map#2 的 addMappings 消费(activeModContainer 为 null -> "unknown mod",
            // 走 modlessCustomEMCPriority,默认 1,0 禁用)。
            for (Map.Entry<ItemKey, Integer> e : r.finalValues.entrySet()) {
                APICustomEMCMapper.instance.registerCustomEMC(
                    e.getKey()
                        .toStack(),
                    e.getValue());
            }
            LOG.info("Registered {} custom EMC values.", r.finalValues.size());

            // 复刻 /projecte reloadEMC:重建映射并推送给已连接玩家
            long t1 = System.currentTimeMillis();
            EMCMapper.clearMaps();
            CustomEMCParser.readUserData();
            EMCMapper.map();
            TileEntityHandler.checkAllCondensers();
            PacketHandler.sendFragmentedEmcPacketToAll();
            LOG.info("EMC mapping rebuilt (map#2) in {} ms", System.currentTimeMillis() - t1);
        } catch (Throwable t) {
            // 注册/重建失败只记日志:值下次启动会自愈重试,链照常 capture 供 view 回放
            LOG.error("AutoEMC register/map#2 failed:", t);
        }
        EmcRuntime.capture(r.finalValues, r.engine.snapshotChosen(), r.mergedChains, r.engine.snapshotAveraged());
    }

    /**
     * 剔除链里"工具/一次性物品"子节点(旧规则时代残留,新鲜求值不可能产生;子节点全为
     * 工具的链整条丢弃——该产品的引擎 picks 本就不含工具,回放树会自动退化为普通打开)。
     *
     * @return 剔除的子节点总数
     */
    private static int pruneToolChildren(Map<ItemKey, List<Pick>> chains) {
        if (chains == null || chains.isEmpty() || !GtMachines.available()) {
            return 0;
        }
        int removed = 0;
        Iterator<Map.Entry<ItemKey, List<Pick>>> it = chains.entrySet()
            .iterator();
        while (it.hasNext()) {
            Map.Entry<ItemKey, List<Pick>> e = it.next();
            List<Pick> kept = new ArrayList<>(
                e.getValue()
                    .size());
            for (Pick p : e.getValue()) {
                if (p != null && GtMachines.isToolItemForChains(p.key)) {
                    removed++;
                } else if (p != null) {
                    kept.add(p);
                }
            }
            if (kept.isEmpty()) {
                it.remove();
            } else if (kept.size() != e.getValue()
                .size()) {
                    e.setValue(kept);
                }
        }
        return removed;
    }

    private static void writeCsv(File file, Map<ItemKey, Integer> values, EmcEngine engine) {
        try (BufferedWriter w = new BufferedWriter(
            new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            w.write("item,damage,value,category,tier,recipe\n");
            List<ItemKey> keys = new ArrayList<>(values.keySet());
            keys.sort((a, b) -> {
                String an = Item.itemRegistry.getNameForObject(a.item) + "@" + a.damage;
                String bn = Item.itemRegistry.getNameForObject(b.item) + "@" + b.damage;
                return an.compareTo(bn);
            });
            for (ItemKey key : keys) {
                EmcRecipe r = engine.chosenFor(key);
                String cat = r == null ? "" : EmcRecipe.categoryName(r.category);
                String tier = r == null ? "" : EmcRecipe.tierName(r.tier);
                String src = r == null ? "" : r.source;
                if (r == null && engine.isSeeded(key)) {
                    cat = "seed";
                    src = "mass72";
                } else if (r == null && engine.isAveraged(key)) {
                    cat = "circuitAvg";
                    src = "tier-average";
                }
                w.write(
                    Item.itemRegistry.getNameForObject(key.item) + "@"
                        + key.damage
                        + ","
                        + key.damage
                        + ","
                        + values.get(key)
                        + ","
                        + cat
                        + ","
                        + tier
                        + ","
                        + src
                        + "\n");
            }
        } catch (IOException e) {
            LOG.error("Failed to write CSV dump", e);
        }
    }
}
