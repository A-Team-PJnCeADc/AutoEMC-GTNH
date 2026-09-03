package com.gtnh.autoemc.command;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;

import com.gtnh.autoemc.api.recipe.RecipeSource;
import com.gtnh.autoemc.emc.EmcRuntime;
import com.gtnh.autoemc.emc.EmcRuntime.ChainItem;
import com.gtnh.autoemc.emc.ItemKey;
import com.gtnh.autoemc.emc.Pick;
import com.gtnh.autoemc.emc.RecipeCollector;
import com.gtnh.autoemc.net.ChannelAutoEmc;

/**
 * /projecte_autoemc view [&lt;namespace&gt;:&lt;name&gt;[@&lt;meta&gt;]]
 * - 必须走 view 子命令(不使用裸根命令);不带物品参数 = 查看手持物品;
 * - 装了 NEI-RecipeTree 时:客户端打开配方树,并按 AutoEMC 引擎选中的配方整树强制对齐
 * (对齐链由服务端按最近一次求值的 chosen/picks 递归构建、分片下发),查看完毕关闭后自动结束对齐。
 *
 * <p>
 * /projecte_autoemc sources — 列出已注册配方源及可用性(诊断:新加的 mod 源是否被注册)。
 */
public class CommandProjecteAutoEmc extends CommandBase {

    @Override
    public String getCommandName() {
        return "projecte_autoemc";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/projecte_autoemc <view [<namespace>:<name>[@<meta>]] | sources>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP)) {
            sender.addChatMessage(new ChatComponentText("\u00a7e[AutoEMC] 仅玩家可用。"));
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;

        // 必须走 view 子命令,不使用裸根命令
        String sub = args.length > 0 ? args[0] : "";
        if ("sources".equalsIgnoreCase(sub)) {
            listSources(player);
            return;
        }
        if (!"view".equalsIgnoreCase(sub)) {
            player.addChatMessage(new ChatComponentText("\u00a7e[AutoEMC] 用法:" + getCommandUsage(sender)));
            return;
        }

        ItemStack target;
        if (args.length >= 2) {
            target = parseItemSpec(args[1]);
            if (target == null) {
                player.addChatMessage(
                    new ChatComponentText(
                        "\u00a7e[AutoEMC] 找不到物品 \"" + args[1]
                            + "\",格式:<namespace>:<name>[@<meta>],如 minecraft:iron_ingot"));
                return;
            }
        } else {
            // view 不带物品参数 -> 查看手持物品
            target = player.getHeldItem();
            if (target == null) {
                player.addChatMessage(
                    new ChatComponentText("\u00a7e[AutoEMC] 请手持物品,或用 /projecte_autoemc view <namespace>:<name>。"));
                return;
            }
        }

        ItemKey targetKey = ItemKey.of(target);
        String info = EmcRuntime.describe(targetKey);
        if (info == null) {
            // EmcRuntime 只有最近一次 run 的新值;AutoEMC 已定价(PE 锚定)的物品从 PE 现查
            try {
                moze_intel.projecte.api.proxy.IEMCProxy proxy = moze_intel.projecte.api.ProjectEAPI.getEMCProxy();
                if (proxy != null && proxy.hasValue(target)) {
                    info = "EMC=" + proxy.getValue(target) + ", 来源=ProjectE";
                }
            } catch (Throwable ignored) {
                // 查询失败就不显示 EMC 信息
            }
        }
        List<String> nodeLines = buildChainLines(targetKey);
        if (nodeLines == null || nodeLines.isEmpty()) {
            // 引擎没有该物品的链记录 -> 只发根节点,客户端按普通方式打开(不强制对齐)
            nodeLines = new ArrayList<>();
            nodeLines.add(keyOf(target));
        }
        ChannelAutoEmc.sendChain(player, info, nodeLines);
    }

    /** 列出已注册配方源及可用性(可用于验证 register() 是否生效)。 */
    private static void listSources(EntityPlayerMP player) {
        List<RecipeSource> sources = RecipeCollector.sources();
        player.addChatMessage(new ChatComponentText("\u00a7e[AutoEMC] 已注册配方源(" + sources.size() + "):"));
        for (RecipeSource s : sources) {
            String state;
            try {
                state = s.isAvailable() ? "\u00a7a可用" : "\u00a77不可用(依赖 mod 未加载)";
            } catch (Throwable t) {
                state = "\u00a74可用性检测异常";
            }
            player.addChatMessage(
                new ChatComponentText("\u00a7f - " + s.id() + " " + state + "\u00a77 " + s.description()));
        }
    }

    /** 递归链 -> 节点行;target 不在引擎记录里返回 null */
    private static List<String> buildChainLines(ItemKey target) {
        List<ChainItem> chain = EmcRuntime.buildChain(target);
        if (chain == null) {
            return null;
        }
        List<String> lines = new ArrayList<>(chain.size());
        for (ChainItem item : chain) {
            // 节点行:key|source|outQty|child1*qty1|child2*qty2|...
            StringBuilder sb = new StringBuilder(keyOf(item.key.toStack()));
            sb.append('|')
                .append(sanitize(item.source))
                .append('|')
                .append(item.outputQty);
            for (Pick p : item.inputs) {
                sb.append('|')
                    .append(keyOf(p.key.toStack()))
                    .append('*')
                    .append(p.qty);
            }
            lines.add(sb.toString());
        }
        return lines;
    }

    /** 配方来源串只用于展示,替换掉可能破坏行格式的分隔符 */
    private static String sanitize(String s) {
        if (s == null) {
            return "?";
        }
        return s.replace('|', '_')
            .replace('*', '_');
    }

    private static String keyOf(ItemStack stack) {
        String name = Item.itemRegistry.getNameForObject(stack.getItem());
        return (name == null ? "?" : name) + "@" + stack.getItemDamage();
    }

    /** 解析 "ns:name" 或 "ns:name@meta"(meta 缺省 0);失败返回 null */
    private static ItemStack parseItemSpec(String spec) {
        if (spec == null || spec.isEmpty()) {
            return null;
        }
        String name = spec;
        int meta = 0;
        int at = spec.lastIndexOf('@');
        if (at > 0 && at < spec.length() - 1) {
            name = spec.substring(0, at);
            try {
                meta = Integer.parseInt(spec.substring(at + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (!name.contains(":")) {
            return null;
        }
        try {
            Object obj = Item.itemRegistry.getObject(name);
            if (!(obj instanceof Item)) {
                return null;
            }
            Item item = (Item) obj;
            if (meta < 0 || meta > 32767) {
                return null;
            }
            return new ItemStack(item, 1, meta);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
