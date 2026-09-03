package com.gtnh.autoemc.api.recipe;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.gtnh.autoemc.emc.EmcRecipe;
import com.gtnh.autoemc.emc.EmcStats;
import com.gtnh.autoemc.emc.ItemKey;

/**
 * 配方源:把某一来源的"产出配方"收集进统一产物表 producers(ItemKey -> 候选配方列表)。
 *
 * <p>
 * 每个支持的配方来源 = 一个实现(一个独立类文件):
 *
 * <ul>
 * <li>内置三源(crafting / smelting / gt)见 {@code RecipeCollector};</li>
 * <li>为某个 mod 增加支持时,新建一个实现类,把它唯一的引用放进 RecipeCollector.register()。
 * 目标是 GTNH 里所有不走"工作台 + 熔炉 + GT RecipeMap"的配方来源(IC2/Thaumcraft/Railcraft/
 * AE2/市场/祭坛……)各占一个源。</li>
 * </ul>
 *
 * <p>
 * 安全约定(防"没装某 mod 就 NoClassDefFoundError"):
 *
 * <ul>
 * <li>依赖目标 mod 的 import 只允许出现在该实现类内部;</li>
 * <li>门禁写在调用方:isAvailable() 返回 false 时 collect()/fingerprintLines() 都不会被调用,
 * 实现类的依赖类也只可能在 collect() 运行时才被加载(懒加载兜底,不可用时整个类不加载最稳);</li>
 * <li>collect() 内单条坏配方只应计入 EmcStats 跳过统计或抛出由外层源的 try 兜住,
 * 不得中断整个收集(单源失败由 RecipeCollector 隔离,不影响其他源)。</li>
 * </ul>
 *
 * <p>
 * 计价约定(与引擎一致):参与消耗的物品槽才计入成本;工具(oredict {@code craftingTool*} / GT 工具 /
 * 有容器不消耗)与流体输入不产生成本,但配方仍有效。
 */
public interface RecipeSource {

    /**
     * 稳定唯一 id(日志 / 指纹 / 未来配置开关都用它;发布后不要改名)。
     * 内置:crafting / smelting / gt。
     */
    String id();

    /** 人类可读描述(启动日志、/projecte_autoemc sources)。 */
    String description();

    /**
     * 该源的依赖是否可用(如 GT 源 = gregtech 已加载;无依赖的源默认 true)。
     * 可用性变化(如 config 关掉某 mod 源)应让指纹随之变化 -> 自动触发全量重算,参见
     * {@link #fingerprintLines}。
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 收集配方到统一产物表。实现内尽量使用快照副本迭代(配方集合可能在主线程并发变化)。
     *
     * <p>
     * 隔离分层(参照 ProjectE-Integration 的三层):
     * <ol>
     * <li>源级:collect() 整体抛异常由 RecipeCollector 捕获,只废掉本源;</li>
     * <li>recipe 级:批量迭代走 {@link RecipeScan#forEachRecipe} + {@link RecipeHandler}
     * —— 单条配方抛异常只丢这一条(含 LinkageError:换依赖版本时 API 漂移抛的是
     * NoSuchMethodError/NoClassDefFoundError);</li>
     * <li>ingredient 级:单个材料槽展开/解析的异常在本实现内自行捕获并跳过该配方,
     * 错误日志带槽位上下文(ore 名/物品 reg@dmg/变体数)。</li>
     * </ol>
     * 被隔离跳过的配方不计入指纹计数(登记产出者时才 +1),保证缓存指纹 == 实际登记集。
     *
     * @param producers outKey -> 该产物的全部候选配方(引擎按 类别/等级/形态/液体/成本 挑选)
     * @param stats     采集统计计数(日志/排查用)
     */
    void collect(Map<ItemKey, List<EmcRecipe>> producers, EmcStats stats);

    /**
     * 指纹行(可选):随"该源贡献的配方集合是否变化"而变化的文本行,并入 emc-values.json 的
     * 缓存指纹;默认空 = 该源不影响指纹。
     *
     * <p>
     * 契约:只要该源贡献的配方集合可能变化(配方增删、机器加入/移除、config 影响收集范围),
     * 就必须返回随集合内容变化的行,且粒度要细到能区分两次不同集合(参照 GT 源:
     * "gtmaps=N" + 每张 RecipeMap 一行 "map:&lt;mapName&gt;=&lt;count&gt;")。漏实现会让缓存
     * 指纹感知不到该源的变化 -> 命中旧缓存,静默沿用旧值(只会在下一次指纹因别的原因变化时
     * 自愈,期间值与树不一致)。
     *
     * <p>
     * collect 未运行 / 源零贡献时,返回内容应与"零贡献"一致(0 计数或空),避免源不可用时
     * 产生与可用时不同的指纹行、每次抖动都触发无谓全量重算。行由 ValueStore 按源注册顺序
     * 拼接 —— 内置三源的指纹行与历史指纹逐字节一致,升级不触发缓存失效。
     */
    default List<String> fingerprintLines(EmcStats stats) {
        return Collections.emptyList();
    }
}
