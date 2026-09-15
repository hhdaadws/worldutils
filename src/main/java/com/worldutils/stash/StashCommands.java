package com.worldutils.stash;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.worldutils.config.ModConfig;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.Inventory;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

/**
 * /stash 指令：定时存物（每隔 N 分钟把背包索引 10..35 —— 快捷栏和保留格之外 —— 存进绑定箱）。
 */
public final class StashCommands {
    private StashCommands() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("stash")
                .then(ClientCommandManager.literal("bind").executes(ctx -> bindChest(ctx.getSource())))
                .then(ClientCommandManager.literal("unbind").executes(ctx -> unbindChest(ctx.getSource())))
                .then(ClientCommandManager.literal("chests").executes(ctx -> {
                    StashConfig cfg = StashConfig.get();
                    StringBuilder sb = new StringBuilder("定时存物箱(" + cfg.chests.size() + "):");
                    for (int i = 0; i < cfg.chests.size(); i++) {
                        sb.append("\n §e#").append(i + 1).append(" ").append(cfg.chests.get(i));
                    }
                    feedback(ctx.getSource(), sb.toString());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommandManager.literal("bindfood").executes(ctx -> bindFoodChest(ctx.getSource())))
                .then(ClientCommandManager.literal("unbindfood").executes(ctx -> {
                    StashConfig.get().foodChest = null;
                    StashConfig.save();
                    feedback(ctx.getSource(), "§a已解绑食物箱（自动进食关闭）");
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommandManager.literal("interval")
                        .then(ClientCommandManager.argument("minutes", IntegerArgumentType.integer(1, 1440))
                                .executes(ctx -> {
                                    StashConfig.get().intervalMinutes =
                                            IntegerArgumentType.getInteger(ctx, "minutes");
                                    StashConfig.save();
                                    StashBot.INSTANCE.rescheduleFromNow();
                                    feedback(ctx.getSource(), "§a存物间隔已设置: 每 §e"
                                            + StashConfig.get().intervalMinutes + "§a 分钟一次"
                                            + (StashBot.INSTANCE.isRunning() ? "§7（已重新计时）" : ""));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(ClientCommandManager.literal("start").executes(ctx -> {
                    StashBot.INSTANCE.start();
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommandManager.literal("stop").executes(ctx -> {
                    StashBot.INSTANCE.stop("已手动停止");
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommandManager.literal("now").executes(ctx -> {
                    StashBot.INSTANCE.triggerNow();
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommandManager.literal("status").executes(ctx -> {
                    feedback(ctx.getSource(), StashBot.INSTANCE.statusText());
                    return Command.SINGLE_SUCCESS;
                }))
                .executes(ctx -> {
                    feedback(ctx.getSource(), """
                            §6/stash 定时存物用法:§r
                            §e/stash bind§7 - 绑定准星指向的箱子(可多个轮换) §e/stash unbind§7 移除 §e/stash chests§7 列表
                            §e/stash interval <分钟>§7 - 每隔多少分钟存一次(默认 30)
                            §e/stash bindfood§7 - 绑定食物箱(饿了自动吃,没食物自动来拿) §e/stash unbindfood§7 解绑
                            §e/stash now§7 - 立即存一次(需已 start)
                            §e/stash start | stop | status§7 - 开启/关闭/状态
                            §7存放范围: 主背包(除按 E 最上排第一格)；快捷栏永不存入；存完自动走回原位""");
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static int bindChest(FabricClientCommandSource source) {
        MinecraftClient mc = MinecraftClient.getInstance();
        HitResult hit = mc.crosshairTarget;
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
            feedback(source, "§c请把准星对准要绑定的箱子再执行 /stash bind");
            return 0;
        }
        BlockPos pos = bhr.getBlockPos();
        if (!(mc.world.getBlockEntity(pos) instanceof Inventory)) {
            feedback(source, "§c准星指向的方块不是容器（箱子/木桶等）");
            return 0;
        }
        StashConfig cfg = StashConfig.get();
        String dim = mc.world.getRegistryKey().getValue().toString();
        if (cfg.chestDim != null && !cfg.chests.isEmpty() && !cfg.chestDim.equals(dim)) {
            feedback(source, "§c所有存物箱必须在同一维度（已有: " + cfg.chestDim + "）");
            return 0;
        }
        for (ModConfig.Pos p : cfg.chests) {
            if (p.x == pos.getX() && p.y == pos.getY() && p.z == pos.getZ()) {
                feedback(source, "§e这个箱子已经绑定过了");
                return 0;
            }
        }
        cfg.chestDim = dim;
        cfg.chests.add(new ModConfig.Pos(pos));
        StashConfig.save();
        feedback(source, "§a已绑定定时存物箱 #" + cfg.chests.size() + ": §e" + new ModConfig.Pos(pos)
                + " §7@ " + dim);
        return Command.SINGLE_SUCCESS;
    }

    private static int bindFoodChest(FabricClientCommandSource source) {
        MinecraftClient mc = MinecraftClient.getInstance();
        HitResult hit = mc.crosshairTarget;
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
            feedback(source, "§c请把准星对准食物箱再执行 /stash bindfood");
            return 0;
        }
        BlockPos pos = bhr.getBlockPos();
        if (!(mc.world.getBlockEntity(pos) instanceof Inventory)) {
            feedback(source, "§c准星指向的方块不是容器");
            return 0;
        }
        StashConfig cfg = StashConfig.get();
        String dim = mc.world.getRegistryKey().getValue().toString();
        if (cfg.chestDim != null && !cfg.chestDim.equals(dim)) {
            feedback(source, "§c食物箱必须和存物箱在同一维度");
            return 0;
        }
        cfg.foodChest = new ModConfig.Pos(pos);
        StashConfig.save();
        feedback(source, "§a已绑定食物箱: §e" + cfg.foodChest
                + "\n§7饥饿值低于 14 会自动吃，包里没食物会自动来这里拿（首次取出自动记录食物 id）");
        return Command.SINGLE_SUCCESS;
    }

    private static int unbindChest(FabricClientCommandSource source) {
        MinecraftClient mc = MinecraftClient.getInstance();
        HitResult hit = mc.crosshairTarget;
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
            feedback(source, "§c请把准星对准要解绑的箱子");
            return 0;
        }
        BlockPos pos = bhr.getBlockPos();
        StashConfig cfg = StashConfig.get();
        boolean removed = cfg.chests.removeIf(p ->
                p.x == pos.getX() && p.y == pos.getY() && p.z == pos.getZ());
        if (removed) {
            if (cfg.chests.isEmpty()) cfg.chestDim = null;
            StashConfig.save();
            feedback(source, "§a已解绑该箱子，剩余 " + cfg.chests.size() + " 个");
        } else {
            feedback(source, "§e该箱子未绑定");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void feedback(FabricClientCommandSource source, String text) {
        source.sendFeedback(Text.literal(text));
    }
}
