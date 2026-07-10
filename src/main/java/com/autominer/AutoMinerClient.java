package com.autominer;

import com.autominer.bot.Bot;
import com.autominer.bot.Recorder;
import com.autominer.config.ModConfig;
import com.autominer.farm.FarmBot;
import com.autominer.farm.FarmCommands;
import com.autominer.farm.FarmConfig;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.inventory.Inventory;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

public class AutoMinerClient implements ClientModInitializer {
    private static KeyBinding toggleKey;
    private static KeyBinding farmToggleKey;

    @Override
    public void onInitializeClient() {
        ModConfig.load();
        FarmConfig.load();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autominer.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                "category.autominer"));

        farmToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autominer.farmToggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "category.autominer"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                Bot.INSTANCE.toggle();
            }
            while (farmToggleKey.wasPressed()) {
                FarmBot.INSTANCE.toggle();
            }
            Bot.INSTANCE.tick(client);
            FarmBot.INSTANCE.tick(client);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            Bot.INSTANCE.hardStop();
            FarmBot.INSTANCE.hardStop();
            Recorder.cancel();
        });

        registerCommands();
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            FarmCommands.register(dispatcher);
            dispatcher.register(ClientCommandManager.literal("miner")
                        .then(ClientCommandManager.literal("bind").executes(ctx -> bindChest(ctx.getSource())))
                        .then(ClientCommandManager.literal("bindpotion").executes(ctx -> bindPotionChest(ctx.getSource())))
                        .then(ClientCommandManager.literal("unbindpotion").executes(ctx -> {
                            ModConfig.get().potionChest = null;
                            ModConfig.save();
                            feedback(ctx.getSource(), "§a已解绑药水箱（急迫功能关闭）");
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommandManager.literal("bindpick").executes(ctx ->
                                bindSpecialChest(ctx.getSource(), "pick")))
                        .then(ClientCommandManager.literal("unbindpick").executes(ctx -> {
                            ModConfig.get().pickChest = null;
                            ModConfig.save();
                            feedback(ctx.getSource(), "§a已解绑镐子箱（改用普通存物箱换镐）");
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommandManager.literal("bindfood").executes(ctx -> bindFoodChest(ctx.getSource())))
                        .then(ClientCommandManager.literal("unbindfood").executes(ctx -> {
                            ModConfig.get().foodChest = null;
                            ModConfig.save();
                            feedback(ctx.getSource(), "§a已解绑食物箱（自动进食关闭）");
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommandManager.literal("unbind").executes(ctx -> unbindChest(ctx.getSource())))
                        .then(ClientCommandManager.literal("chests").executes(ctx -> {
                            ModConfig cfg = ModConfig.get();
                            StringBuilder sb = new StringBuilder("绑定的箱子(" + cfg.chests.size() + "):");
                            for (int i = 0; i < cfg.chests.size(); i++) {
                                sb.append("\n §e#").append(i + 1).append(" ").append(cfg.chests.get(i));
                            }
                            feedback(ctx.getSource(), sb.toString());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommandManager.literal("sety")
                                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer(-64, 320))
                                        .executes(ctx -> {
                                            ModConfig.get().targetY = IntegerArgumentType.getInteger(ctx, "y");
                                            ModConfig.save();
                                            feedback(ctx.getSource(), "§a挖矿高度已设置: y=§e" + ModConfig.get().targetY);
                                            return Command.SINGLE_SUCCESS;
                                        })))
                        .then(ClientCommandManager.literal("face").executes(ctx -> {
                            MinecraftClient mc = MinecraftClient.getInstance();
                            if (mc.player == null) return 0;
                            ModConfig.get().facing = mc.player.getHorizontalFacing().name();
                            ModConfig.save();
                            feedback(ctx.getSource(), "§a挖矿朝向已设为当前朝向: §e" + ModConfig.get().facing);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommandManager.literal("tphome")
                                .then(ClientCommandManager.argument("cmd", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String cmd = strip(StringArgumentType.getString(ctx, "cmd"));
                                            ModConfig.get().tpHome = new ModConfig.MenuSeq(cmd, new java.util.ArrayList<>());
                                            ModConfig.save();
                                            feedback(ctx.getSource(), "§a回家操作已设置(纯指令): §e/" + cmd
                                                    + "\n§7如需点菜单请用 /miner record home <指令>");
                                            return Command.SINGLE_SUCCESS;
                                        })))
                        .then(ClientCommandManager.literal("tpmine")
                                .then(ClientCommandManager.argument("cmd", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String cmd = strip(StringArgumentType.getString(ctx, "cmd"));
                                            ModConfig.get().tpMine = new ModConfig.MenuSeq(cmd, new java.util.ArrayList<>());
                                            ModConfig.save();
                                            feedback(ctx.getSource(), "§a去矿点操作已设置(纯指令): §e/" + cmd
                                                    + "\n§7如需点菜单请用 /miner record mine <指令>");
                                            return Command.SINGLE_SUCCESS;
                                        })))
                        .then(ClientCommandManager.literal("record")
                                .then(ClientCommandManager.literal("mine")
                                        .then(ClientCommandManager.argument("cmd", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    Recorder.start("mine", StringArgumentType.getString(ctx, "cmd"));
                                                    return Command.SINGLE_SUCCESS;
                                                })))
                                .then(ClientCommandManager.literal("home")
                                        .then(ClientCommandManager.argument("cmd", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    Recorder.start("home", StringArgumentType.getString(ctx, "cmd"));
                                                    return Command.SINGLE_SUCCESS;
                                                })))
                                .then(ClientCommandManager.literal("cancel").executes(ctx -> {
                                    Recorder.cancel();
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .then(ClientCommandManager.literal("keep")
                                .then(ClientCommandManager.literal("add")
                                        .then(ClientCommandManager.argument("id", StringArgumentType.string())
                                                .executes(ctx -> {
                                                    String id = StringArgumentType.getString(ctx, "id");
                                                    if (!ModConfig.get().keepIds.contains(id)) {
                                                        ModConfig.get().keepIds.add(id);
                                                        ModConfig.save();
                                                    }
                                                    feedback(ctx.getSource(), "§a已加入保留列表: §e" + id);
                                                    return Command.SINGLE_SUCCESS;
                                                })))
                                .then(ClientCommandManager.literal("remove")
                                        .then(ClientCommandManager.argument("id", StringArgumentType.string())
                                                .executes(ctx -> {
                                                    String id = StringArgumentType.getString(ctx, "id");
                                                    ModConfig.get().keepIds.remove(id);
                                                    ModConfig.save();
                                                    feedback(ctx.getSource(), "§a已从保留列表移除: §e" + id);
                                                    return Command.SINGLE_SUCCESS;
                                                })))
                                .then(ClientCommandManager.literal("list").executes(ctx -> {
                                    feedback(ctx.getSource(), "保留列表: §e"
                                            + String.join(", ", ModConfig.get().keepIds));
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .then(ClientCommandManager.literal("durability")
                                .then(ClientCommandManager.argument("value", IntegerArgumentType.integer(1, 1000))
                                        .executes(ctx -> {
                                            ModConfig.get().minPickaxeDurability =
                                                    IntegerArgumentType.getInteger(ctx, "value");
                                            ModConfig.save();
                                            feedback(ctx.getSource(), "§a镐子耐久阈值已设置: §e"
                                                    + ModConfig.get().minPickaxeDurability);
                                            return Command.SINGLE_SUCCESS;
                                        })))
                        .then(ClientCommandManager.literal("start").executes(ctx -> {
                            Bot.INSTANCE.start();
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommandManager.literal("stop").executes(ctx -> {
                            Bot.INSTANCE.stop("已手动停止");
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommandManager.literal("status").executes(ctx -> {
                            feedback(ctx.getSource(), Bot.INSTANCE.statusText());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .executes(ctx -> {
                            feedback(ctx.getSource(), """
                                    §6AutoMiner 用法:§r
                                    §e/miner bind§7 - 绑定准星指向的箱子(可多个) §e/miner unbind§7 移除 §e/miner chests§7 列表
                                    §e/miner bindpotion§7 - 绑定急迫药水箱(效果没了自动拿药喝)
                                    §e/miner bindfood§7 - 绑定食物箱(自动吃/自动补货,保持疾跑)
                                    §e/miner bindpick§7 - 绑定镐子箱(坏镐放入,新镐取出)
                                    §e/miner sety <y>§7 - 设置挖矿高度(自动向下挖到该高度)
                                    §e/miner record mine <指令>§7 - 录制去矿点操作(命令+点菜单)
                                    §e/miner record home <指令>§7 - 录制回家操作(纯指令可用 /miner tphome)
                                    §e/miner keep add|remove|list§7 - 保留物品  §e/miner durability <n>§7 - 换镐阈值
                                    §e/miner face§7 - 重设挖矿朝向为当前朝向
                                    §e/miner start | stop | status§7 - 开始/停止/状态 (快捷键 J)""");
                            return Command.SINGLE_SUCCESS;
                        }));
        });
    }

    private static int bindChest(FabricClientCommandSource source) {
        MinecraftClient mc = MinecraftClient.getInstance();
        HitResult hit = mc.crosshairTarget;
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
            feedback(source, "§c请把准星对准要绑定的箱子再执行 /miner bind");
            return 0;
        }
        BlockPos pos = bhr.getBlockPos();
        if (!(mc.world.getBlockEntity(pos) instanceof Inventory)) {
            feedback(source, "§c准星指向的方块不是容器（箱子/木桶等）");
            return 0;
        }
        ModConfig cfg = ModConfig.get();
        String dim = mc.world.getRegistryKey().getValue().toString();
        if (cfg.chestDim != null && !cfg.chests.isEmpty() && !cfg.chestDim.equals(dim)) {
            feedback(source, "§c所有箱子必须在同一维度（已有: " + cfg.chestDim + "）");
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
        ModConfig.save();
        feedback(source, "§a已绑定箱子 #" + cfg.chests.size() + ": §e" + new ModConfig.Pos(pos)
                + " §7@ " + dim);
        return Command.SINGLE_SUCCESS;
    }

    private static int bindPotionChest(FabricClientCommandSource source) {
        MinecraftClient mc = MinecraftClient.getInstance();
        HitResult hit = mc.crosshairTarget;
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
            feedback(source, "§c请把准星对准药水箱再执行 /miner bindpotion");
            return 0;
        }
        BlockPos pos = bhr.getBlockPos();
        if (!(mc.world.getBlockEntity(pos) instanceof Inventory)) {
            feedback(source, "§c准星指向的方块不是容器");
            return 0;
        }
        ModConfig cfg = ModConfig.get();
        String dim = mc.world.getRegistryKey().getValue().toString();
        if (cfg.chestDim != null && !cfg.chestDim.equals(dim)) {
            feedback(source, "§c药水箱必须和存物箱在同一维度");
            return 0;
        }
        cfg.potionChest = new ModConfig.Pos(pos);
        ModConfig.save();
        feedback(source, "§a已绑定急迫药水箱: §e" + cfg.potionChest
                + "\n§7急迫效果消失后会自动来这里拿药喝");
        return Command.SINGLE_SUCCESS;
    }

    /** 绑定特殊功能箱（目前只有镐子箱走这里）。 */
    private static int bindSpecialChest(FabricClientCommandSource source, String kind) {
        MinecraftClient mc = MinecraftClient.getInstance();
        HitResult hit = mc.crosshairTarget;
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
            feedback(source, "§c请把准星对准箱子再执行");
            return 0;
        }
        BlockPos pos = bhr.getBlockPos();
        if (!(mc.world.getBlockEntity(pos) instanceof Inventory)) {
            feedback(source, "§c准星指向的方块不是容器");
            return 0;
        }
        ModConfig cfg = ModConfig.get();
        String dim = mc.world.getRegistryKey().getValue().toString();
        if (cfg.chestDim != null && !cfg.chestDim.equals(dim)) {
            feedback(source, "§c必须和存物箱在同一维度");
            return 0;
        }
        if ("pick".equals(kind)) {
            cfg.pickChest = new ModConfig.Pos(pos);
            ModConfig.save();
            feedback(source, "§a已绑定镐子箱: §e" + cfg.pickChest
                    + "\n§7坏镐放这里、新镐从这里拿（记得多放几把备用镐）");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int bindFoodChest(FabricClientCommandSource source) {
        MinecraftClient mc = MinecraftClient.getInstance();
        HitResult hit = mc.crosshairTarget;
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
            feedback(source, "§c请把准星对准食物箱再执行 /miner bindfood");
            return 0;
        }
        BlockPos pos = bhr.getBlockPos();
        if (!(mc.world.getBlockEntity(pos) instanceof Inventory)) {
            feedback(source, "§c准星指向的方块不是容器");
            return 0;
        }
        ModConfig cfg = ModConfig.get();
        String dim = mc.world.getRegistryKey().getValue().toString();
        if (cfg.chestDim != null && !cfg.chestDim.equals(dim)) {
            feedback(source, "§c食物箱必须和存物箱在同一维度");
            return 0;
        }
        cfg.foodChest = new ModConfig.Pos(pos);
        ModConfig.save();
        feedback(source, "§a已绑定食物箱: §e" + cfg.foodChest
                + "\n§7饿了会自动吃，没吃的会自动来这里拿，保证一直疾跑");
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
        ModConfig cfg = ModConfig.get();
        boolean removed = cfg.chests.removeIf(p ->
                p.x == pos.getX() && p.y == pos.getY() && p.z == pos.getZ());
        if (removed) {
            if (cfg.chests.isEmpty()) cfg.chestDim = null;
            ModConfig.save();
            feedback(source, "§a已解绑该箱子，剩余 " + cfg.chests.size() + " 个");
        } else {
            feedback(source, "§e该箱子未绑定");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static String strip(String cmd) {
        String c = cmd.trim();
        return c.startsWith("/") ? c.substring(1) : c;
    }

    private static void feedback(FabricClientCommandSource source, String text) {
        source.sendFeedback(Text.literal(text));
    }
}
