package com.gtnh.autoemc.api.recipe;

/**
 * 单条配方的处理回调(recipe 级隔离单元)。
 *
 * <p>
 * 实现只处理"一条配方":返回 true = 该配方被登记为产出者(计入 fingerprint 相关计数);
 * false = 该配方因内容性原因被跳过(原因应已计入 EmcStats 对应跳过计数)。
 * 抛异常 = 该配方意外失败 —— 由 {@link RecipeScan#forEachRecipe} 按配方隔离捕获:记日志、
 * 跳过这一条、其余配方继续,异常不要在这里自行吞掉。
 *
 * <p>
 * 对照 ProjectE-Integration:相当于每个 mapper 的 handleRecipe(内部自带 per-recipe
 * try/catch);这里把同样的保护下沉成批量驱动器,让所有 RecipeSource 的实现共用。
 */
public interface RecipeHandler<R> {

    /**
     * @param recipe 单条配方;允许为 null(实现自行决定,通常返回 false)
     * @return true = 配方已登记为产出者
     * @throws Exception 该配方处理失败(会被驱动器隔离捕获并跳过)
     */
    boolean handle(R recipe) throws Exception;
}
