package com.gtnh.autoemc.api.recipe;

import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * recipe 级隔离驱动器(隔离第二层)。
 *
 * <p>
 * 用法:RecipeSource 实现把"单条配方处理"写成 {@link RecipeHandler},整批交给本工具迭代 ——
 * 单条配方抛异常(畸形数据、目标 mod API 版本漂移等)只记日志、丢这一条,其余配方照常,
 * 源级(第一层,RecipeCollector 的 per-source try)不再需要兜住单配方异常。
 *
 * <p>
 * 对照 ProjectE-Integration:每个 mapper 的 handleRecipe 自带的 per-recipe try/catch
 * (ARecipeTypeMapper.handleRecipe 捕获 ClassCastException 与 Exception)就是这一层;
 * 这里把同一保护统一成批量驱动器,并对 GTNH 场景补了 LinkageError —— 换依赖版本时
 * API 漂移抛的是 NoSuchMethodError / NoClassDefFoundError(Error,不是 Exception),
 * 只 catch Exception 会漏掉它们、仍然断掉整批。
 *
 * <p>
 * 不接住的:VirtualMachineError / ThreadDeath(照常上抛,由源级/入口 catch 处理)。
 */
public final class RecipeScan {

    private static final Logger LOG = LogManager.getLogger("AutoEMC");

    private RecipeScan() {}

    /** 一批配方的处理结果。 */
    public static final class Result {

        /** 成功登记为产出者的配方数(handler 返回 true 的次数) */
        public final int handled;

        /** 抛异常被隔离跳过的配方数 */
        public final int errors;

        Result(int handled, int errors) {
            this.handled = handled;
            this.errors = errors;
        }
    }

    /**
     * 逐条处理一批配方;handler 抛出的任何 {@link Exception} 或 {@link LinkageError} 都会被
     * 捕获并记录(带 scope、配方类名与描述),该条跳过,批次继续。
     *
     * @param sourceId  配方源 id(日志归属)
     * @param scope     批内上下文(如 GT map 名 / "crafting"),用于日志定位
     * @param recipes   配方快照(调用方自行快照,防止并发注册 CME)
     * @param describer 单条配方的可读描述(如输出物品 reg@dmg);为 null 时退回 String.valueOf;
     *                  describer 自身抛异常不影响处理,回落 "?"
     * @param handler   单配方处理器
     * @return handled / errors 统计
     */
    public static <R> Result forEachRecipe(String sourceId, String scope, Iterable<R> recipes,
        Function<? super R, String> describer, RecipeHandler<R> handler) {
        int handled = 0;
        int errors = 0;
        int index = 0;
        for (R recipe : recipes) {
            try {
                if (handler.handle(recipe)) {
                    handled++;
                }
            } catch (LinkageError | Exception t) {
                errors++;
                String clazz = recipe == null ? "null"
                    : recipe.getClass()
                        .getName();
                String desc;
                try {
                    desc = describer == null ? String.valueOf(recipe) : String.valueOf(describer.apply(recipe));
                } catch (Throwable ignored) {
                    desc = "?";
                }
                LOG.error(
                    "Recipe source '{}' failed on recipe #{} (scope '{}', class {}): {}; recipe skipped, remaining recipes continue.",
                    sourceId,
                    index,
                    scope,
                    clazz,
                    desc,
                    t);
            }
            index++;
        }
        return new Result(handled, errors);
    }
}
