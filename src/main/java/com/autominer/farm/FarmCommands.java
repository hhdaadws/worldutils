package com.autominer.farm;

import com.autominer.config.ModConfig;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * /farm 指令集：区域选择、小区域分类、作物定义、箱子/浇水器/水源绑定、
 * 成熟特征学习、方块/虚拟实体信息 dump、启停。
 */
public final class FarmCommands {
    // 小区域选区两角（内存态）
    private static BlockPos sel1 = null;
    private static BlockPos sel2 = null;

    private FarmCommands() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("farm")
                // ---- 选区 ----
                .then(ClientCommandManager.literal("pos1").executes(ctx -> setPos(ctx.getSource(), true)))
                .then(ClientCommandManager.literal("pos2").executes(ctx -> setPos(ctx.getSource(), false)))
                // ---- 小区域 ----
                .then(ClientCommandManager.literal("zone")
                        .then(ClientCommandManager.literal("add")
                                .then(ClientCommandManager.argument("crop", StringArgumentType.greedyString())
                                        .executes(ctx -> zoneAdd(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "crop").trim()))))
                        .then(ClientCommandManager.literal("list").executes(ctx -> {
                            FarmConfig cfg = FarmConfig.get();
                            StringBuilder sb = new StringBuilder("小区域(" + cfg.zones.size() + "):");
                            for (int i = 0; i < cfg.zones.size(); i++) {
                                sb.append("\n §e#").append(i + 1).append(" ").append(cfg.zones.get(i));
                            }
                            feedback(ctx.getSource(), sb.toString());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommandManager.literal("remove")
                                .then(ClientCommandManager.argument("index", IntegerArgumentType.integer(1))
                                        .executes(ctx -> {
                                            int idx = IntegerArgumentType.getInteger(ctx, "index") - 1;
                                            FarmConfig cfg = FarmConfig.get();
                                            if (idx >= cfg.zones.size()) {
                                                feedback(ctx.getSource(), "§c没有这个编号，见 /farm zone list");
                                                return 0;
                                            }
                                            FarmConfig.Zone z = cfg.zones.remove(idx);
                                            FarmConfig.save();
                                            FarmBot.INSTANCE.invalidateCache();
                                            feedback(ctx.getSource(), "§a已移除小区域: " + z);
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                // ---- 作物定义 ----
                .then(ClientCommandManager.literal("crop")
                        .then(ClientCommandManager.literal("add")
                                .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> cropAdd(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name").trim()))))
                        .then(ClientCommandManager.literal("list").executes(ctx -> {
                            FarmConfig cfg = FarmConfig.get();
                            StringBuilder sb = new StringBuilder("作物(" + cfg.crops.size() + "):");
                            for (var e : cfg.crops.entrySet()) {
                                FarmConfig.Crop c = e.getValue();
                                sb.append("\n §e").append(e.getKey())
                                        .append("§r 种子=").append(c.seedName).append("(").append(c.seedItemId).append(")")
                                        .append(" 种子箱=").append(c.seedChest != null ? c.seedChest : "§c未绑§r")
                                        .append(" 成熟特征=").append(c.mature.size()).append("条");
                            }
                            feedback(ctx.getSource(), sb.toString());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommandManager.literal("remove")
                                .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "name").trim();
                                            if (FarmConfig.get().crops.remove(name) == null) {
                                                feedback(ctx.getSource(), "§c没有这个作物: " + name);
                                                return 0;
                                            }
                                            FarmConfig.save();
                                            feedback(ctx.getSource(), "§a已删除作物: " + name);
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                // ---- 成熟特征 ----
                .then(ClientCommandManager.literal("learn")
                        .then(ClientCommandManager.argument("crop", StringArgumentType.greedyString())
                                .executes(ctx -> learn(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "crop").trim()))))
                .then(ClientCommandManager.literal("unlearn")
                        .then(ClientCommandManager.argument("crop", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "crop").trim();
                                    FarmConfig.Crop c = FarmConfig.get().crops.get(name);
                                    if (c == null) {
                                        feedback(ctx.getSource(), "§c没有这个作物: " + name);
                                        return 0;
                                    }
                                    c.mature.clear();
                                    FarmConfig.save();
                                    feedback(ctx.getSource(), "§a已清空「" + name + "」的成熟特征");
                                    return Command.SINGLE_SUCCESS;
                                })))
                // ---- 绑定 ----
                .then(ClientCommandManager.literal("bindseed")
                        .then(ClientCommandManager.argument("crop", StringArgumentType.greedyString())
                                .executes(ctx -> bindSeedChest(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "crop").trim()))))
                .then(ClientCommandManager.literal("bindcrop").executes(ctx -> bindCropChest(ctx.getSource())))
                .then(ClientCommandManager.literal("unbindcrop").executes(ctx -> unbindCropChest(ctx.getSource())))
                .then(ClientCommandManager.literal("bindwaterer").executes(ctx -> bindWaterer(ctx.getSource())))
                .then(ClientCommandManager.literal("unbindwaterer").executes(ctx -> unbindWaterer(ctx.getSource())))
                .then(ClientCommandManager.literal("bindcan").executes(ctx -> bindWateringCan(ctx.getSource())))
                .then(ClientCommandManager.literal("unbindcan").executes(ctx -> unbindWateringCan(ctx.getSource())))
                .then(ClientCommandManager.literal("bindwater").executes(ctx -> bindWaterSource(ctx.getSource())))
                .then(ClientCommandManager.literal("bindfood").executes(ctx -> bindFood(ctx.getSource())))
                .then(ClientCommandManager.literal("unbindfood").executes(ctx -> unbindFood(ctx.getSource())))
                .then(ClientCommandManager.literal("bindfoodchest").executes(ctx -> bindFoodChest(ctx.getSource())))
                .then(ClientCommandManager.literal("unbindfoodchest").executes(ctx -> unbindFoodChest(ctx.getSource())))
                // ---- 参数 ----
                .then(ClientCommandManager.literal("interval")
                        .then(ClientCommandManager.argument("minutes", IntegerArgumentType.integer(1, 1440))
                                .executes(ctx -> {
                                    FarmConfig.get().waterIntervalMinutes =
                                            IntegerArgumentType.getInteger(ctx, "minutes");
                                    FarmConfig.save();
                                    feedback(ctx.getSource(), "§a浇水间隔已设置: §e"
                                            + FarmConfig.get().waterIntervalMinutes + " 分钟");
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(ClientCommandManager.literal("canuses")
                        .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 16))
                                .executes(ctx -> setCanUses(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count")))))
                // 兼容旧版命令名
                .then(ClientCommandManager.literal("buckets")
                        .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 16))
                                .executes(ctx -> setCanUses(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count")))))
                .then(ClientCommandManager.literal("harvestmode")
                        .then(ClientCommandManager.literal("break").executes(ctx -> {
                            FarmConfig.get().harvestMode = "break";
                            FarmConfig.save();
                            feedback(ctx.getSource(), "§a收获方式: §e左键挖掉(break)");
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommandManager.literal("use").executes(ctx -> {
                            FarmConfig.get().harvestMode = "use";
                            FarmConfig.save();
                            feedback(ctx.getSource(), "§a收获方式: §e拿对应种子右键，自动收获并复种(use)");
                            return Command.SINGLE_SUCCESS;
                        })))
                // ---- 信息 ----
                .then(ClientCommandManager.literal("info").executes(ctx -> {
                    String text = BlockInfoDumper.dump(MinecraftClient.getInstance());
                    if (text == null) {
                        feedback(ctx.getSource(), "§c请把准星对准要查看的方块或虚拟目标再执行");
                        return 0;
                    }
                    feedback(ctx.getSource(), text);
                    return Command.SINGLE_SUCCESS;
                }))
                // ---- 启停 ----
                .then(ClientCommandManager.literal("start").executes(ctx -> {
                    FarmBot.INSTANCE.start();
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommandManager.literal("stop").executes(ctx -> {
                    FarmBot.INSTANCE.stop("已手动停止");
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommandManager.literal("status").executes(ctx -> {
                    feedback(ctx.getSource(), FarmBot.INSTANCE.statusText());
                    return Command.SINGLE_SUCCESS;
                }))
                .executes(ctx -> {
                    feedback(ctx.getSource(), """
                            §2AutoFarm 用法:§r
                            §e/farm pos1|pos2§7 - 用准星选择小区域的两个角(没指方块用脚下)
                            §e/farm zone add <作物>§7 - 把当前选区设为种<作物>的小区域 (list/remove 管理)
                            §e/farm crop add <作物>§7 - §f手持种子§7执行，记录种子物品+名字
                            §e/farm bindseed <作物>§7 - 准星对准该作物的种子箱
                            §e/farm bindcrop§7 - 绑定作物存放箱(可多个) §e/farm unbindcrop§7 移除
                            §e/farm bindwaterer§7 - 准星对准悬浮虚拟浇水器(可多个) §e/farm unbindwaterer§7 移除
                            §e/farm bindcan§7 - §f手持插件洒水壶§7执行，记录洒水壶物品
                            §e/farm bindwater§7 - 准星对准水面，设为洒水壶装水点
                            §e/farm bindfood§7 - §f手持食物§7记录物品 §e/farm bindfoodchest§7 - 绑定食物箱
                            §e/farm learn <作物>§7 - 准星对准§f成熟§7作物，学习成熟特征
                            §e/farm info§7 - 查看准星方块的详细信息(发给开发者用)
                            §e/farm interval <分钟>§7 - 浇水间隔(默认20) §e/farm canuses <n>§7 - 每浇水器用几壶
                            §e/farm harvestmode break|use§7 - 左键挖，或拿对应种子右键自动收获复种
                            §e/farm start | stop | status§7 - 开始/停止/状态 (快捷键 K)""");
                    return Command.SINGLE_SUCCESS;
                }));
    }

    // ---------- 实现 ----------

    private static int setPos(FabricClientCommandSource source, boolean first) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return 0;
        BlockPos pos = crosshairBlock(mc);
        if (pos == null) {
            pos = mc.player.getBlockPos(); // 没指方块 → 用脚下
        }
        if (first) sel1 = pos; else sel2 = pos;
        feedback(source, "§a已设置 pos" + (first ? 1 : 2) + ": §e" + pos.toShortString()
                + (sel1 != null && sel2 != null ? "\n§7两角已齐，可 /farm zone add <作物>" : ""));
        return Command.SINGLE_SUCCESS;
    }

    private static int zoneAdd(FabricClientCommandSource source, String cropName) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return 0;
        if (cropName.isEmpty()) {
            feedback(source, "§c作物名不能为空");
            return 0;
        }
        if (sel1 == null || sel2 == null) {
            feedback(source, "§c请先用 /farm pos1 和 /farm pos2 框出这个小区域");
            return 0;
        }
        FarmConfig cfg = FarmConfig.get();
        if (!checkDim(source, mc)) return 0;
        FarmConfig.Region box = new FarmConfig.Region(sel1, sel2);
        cfg.zones.add(new FarmConfig.Zone(cropName, box));
        FarmConfig.save();
        FarmBot.INSTANCE.invalidateCache();
        String hint = cfg.crops.containsKey(cropName)
                ? "" : "\n§7该作物还没定义种子：手持种子执行 /farm crop add " + cropName;
        feedback(source, "§a已添加小区域 #" + cfg.zones.size() + ": §e" + cropName + " @ " + box + hint);
        sel1 = null;
        sel2 = null;
        return Command.SINGLE_SUCCESS;
    }

    private static int cropAdd(FabricClientCommandSource source, String name) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || name.isEmpty()) return 0;
        ItemStack held = mc.player.getMainHandStack();
        if (held.isEmpty()) {
            feedback(source, "§c请把该作物的种子拿在主手再执行（会记录物品 id 和显示名）");
            return 0;
        }
        FarmConfig cfg = FarmConfig.get();
        FarmConfig.Crop crop = cfg.crops.computeIfAbsent(name, k -> new FarmConfig.Crop());
        crop.seedItemId = FarmItems.idOf(held);
        crop.seedName = held.getName().getString();
        FarmConfig.save();
        feedback(source, "§a已定义作物「" + name + "」\n 种子物品: §e" + crop.seedItemId
                + "§r  显示名: §e" + crop.seedName
                + "\n§7种子箱: /farm bindseed " + name
                + "  成熟特征: 对准成熟作物 /farm learn " + name);
        return Command.SINGLE_SUCCESS;
    }

    private static int learn(FabricClientCommandSource source, String name) {
        MinecraftClient mc = MinecraftClient.getInstance();
        FarmConfig.Crop crop = FarmConfig.get().crops.get(name);
        if (crop == null) {
            feedback(source, "§c没有这个作物: " + name + "（先 /farm crop add " + name + "）");
            return 0;
        }
        BlockPos pos = crosshairBlock(mc);
        if (pos == null) {
            feedback(source, "§c请把准星对准一株§f成熟§c的「" + name + "」再执行");
            return 0;
        }
        FarmConfig.StateSig sig = FarmScanner.sigOf(mc.world.getBlockState(pos));
        for (FarmConfig.StateSig s : crop.mature) {
            if (s.blockId.equals(sig.blockId) && s.props.equals(sig.props)) {
                feedback(source, "§e这个状态已经学过了: " + sig);
                return 0;
            }
        }
        crop.mature.add(sig);
        FarmConfig.save();
        feedback(source, "§a已学习「" + name + "」成熟特征 #" + crop.mature.size() + ": §e" + sig
                + "\n§7如果不同成熟外观有多种，对每种都 learn 一次");
        return Command.SINGLE_SUCCESS;
    }

    private static int bindSeedChest(FabricClientCommandSource source, String name) {
        MinecraftClient mc = MinecraftClient.getInstance();
        FarmConfig.Crop crop = FarmConfig.get().crops.get(name);
        if (crop == null) {
            feedback(source, "§c没有这个作物: " + name + "（先 /farm crop add " + name + "）");
            return 0;
        }
        BlockPos pos = crosshairContainer(source, mc);
        if (pos == null) return 0;
        if (!checkDim(source, mc)) return 0;
        crop.seedChest = new ModConfig.Pos(pos);
        FarmConfig.save();
        feedback(source, "§a已绑定「" + name + "」种子箱: §e" + crop.seedChest);
        return Command.SINGLE_SUCCESS;
    }

    private static int bindCropChest(FabricClientCommandSource source) {
        MinecraftClient mc = MinecraftClient.getInstance();
        BlockPos pos = crosshairContainer(source, mc);
        if (pos == null) return 0;
        if (!checkDim(source, mc)) return 0;
        FarmConfig cfg = FarmConfig.get();
        for (ModConfig.Pos p : cfg.cropChests) {
            if (p.x == pos.getX() && p.y == pos.getY() && p.z == pos.getZ()) {
                feedback(source, "§e这个作物箱已经绑定过了");
                return 0;
            }
        }
        cfg.cropChests.add(new ModConfig.Pos(pos));
        FarmConfig.save();
        feedback(source, "§a已绑定作物箱 #" + cfg.cropChests.size() + ": §e" + new ModConfig.Pos(pos));
        return Command.SINGLE_SUCCESS;
    }

    private static int unbindCropChest(FabricClientCommandSource source) {
        MinecraftClient mc = MinecraftClient.getInstance();
        BlockPos pos = crosshairBlock(mc);
        if (pos == null) {
            feedback(source, "§c请把准星对准要解绑的作物箱");
            return 0;
        }
        FarmConfig cfg = FarmConfig.get();
        boolean removed = cfg.cropChests.removeIf(p ->
                p.x == pos.getX() && p.y == pos.getY() && p.z == pos.getZ());
        FarmConfig.save();
        feedback(source, removed ? "§a已解绑该作物箱，剩余 " + cfg.cropChests.size() + " 个"
                : "§e该箱子未绑定");
        return removed ? Command.SINGLE_SUCCESS : 0;
    }

    private static int bindWaterer(FabricClientCommandSource source) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return 0;
        FarmConfig.WatererTarget target = crosshairWaterer(mc);
        if (target == null) {
            feedback(source, "§c请把准星对准悬浮虚拟浇水器再执行");
            return 0;
        }
        if (!checkDim(source, mc)) return 0;
        FarmConfig cfg = FarmConfig.get();
        for (FarmConfig.WatererTarget existing : cfg.waterers) {
            if (existing.squaredDistanceTo(target.targetPos()) <= 1.0) {
                feedback(source, "§e这个浇水器已经绑定过了");
                return 0;
            }
        }
        cfg.waterers.add(target);
        FarmConfig.save();
        feedback(source, "§a已绑定虚拟浇水器 #" + cfg.waterers.size() + ": §e" + target
                + "\n§7记得手持洒水壶 /farm bindcan，并用 /farm bindwater 绑定装水点");
        return Command.SINGLE_SUCCESS;
    }

    private static int unbindWaterer(FabricClientCommandSource source) {
        MinecraftClient mc = MinecraftClient.getInstance();
        FarmConfig.WatererTarget aimed = crosshairWaterer(mc);
        if (aimed == null) {
            feedback(source, "§c请把准星对准要解绑的虚拟浇水器");
            return 0;
        }
        FarmConfig cfg = FarmConfig.get();
        FarmConfig.WatererTarget nearest = cfg.waterers.stream()
                .min(java.util.Comparator.comparingDouble(w -> w.squaredDistanceTo(aimed.targetPos())))
                .orElse(null);
        boolean removed = nearest != null && nearest.squaredDistanceTo(aimed.targetPos()) <= 9.0
                && cfg.waterers.remove(nearest);
        FarmConfig.save();
        feedback(source, removed ? "§a已解绑该浇水器，剩余 " + cfg.waterers.size() + " 个"
                : "§e该浇水器未绑定");
        return removed ? Command.SINGLE_SUCCESS : 0;
    }

    private static int bindWateringCan(FabricClientCommandSource source) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return 0;
        ItemStack held = mc.player.getMainHandStack();
        if (held.isEmpty()) {
            feedback(source, "§c请把插件洒水壶拿在主手再执行 /farm bindcan");
            return 0;
        }
        FarmConfig cfg = FarmConfig.get();
        cfg.wateringCan = new FarmConfig.WateringCan(FarmItems.idOf(held), held.getName().getString());
        FarmConfig.save();
        feedback(source, "§a已绑定插件洒水壶: §e" + cfg.wateringCan
                + "\n§7机器人会拿它在水源右键装水，再右键悬浮浇水器");
        return Command.SINGLE_SUCCESS;
    }

    private static int unbindWateringCan(FabricClientCommandSource source) {
        FarmConfig cfg = FarmConfig.get();
        boolean had = cfg.wateringCan != null;
        cfg.wateringCan = null;
        FarmConfig.save();
        feedback(source, had ? "§a已解绑洒水壶" : "§e尚未绑定洒水壶");
        return had ? Command.SINGLE_SUCCESS : 0;
    }

    private static int bindWaterSource(FabricClientCommandSource source) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return 0;
        // 带流体的射线，方便直接指向水面
        BlockPos pos = null;
        HitResult hit = mc.player.raycast(6.0, 0.0f, true);
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            pos = bhr.getBlockPos();
        }
        if (pos == null) {
            feedback(source, "§c请把准星对准水面再执行");
            return 0;
        }
        if (!checkDim(source, mc)) return 0;
        boolean isWater = !mc.world.getBlockState(pos).getFluidState().isEmpty();
        FarmConfig.get().waterSource = new ModConfig.Pos(pos);
        FarmConfig.save();
        feedback(source, "§a已绑定洒水壶装水点: §e" + FarmConfig.get().waterSource
                + (isWater ? "" : "\n§e注意: 这个位置现在不是水方块，装水可能失败"));
        return Command.SINGLE_SUCCESS;
    }

    private static int bindFood(FabricClientCommandSource source) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return 0;
        ItemStack held = mc.player.getMainHandStack();
        if (held.isEmpty()) {
            feedback(source, "§c请把要自动吃的食物拿在主手再执行 /farm bindfood");
            return 0;
        }
        FarmConfig cfg = FarmConfig.get();
        cfg.food = new FarmConfig.FoodItem(FarmItems.idOf(held), held.getName().getString());
        FarmConfig.save();
        feedback(source, "§a已绑定食物: §e" + cfg.food
                + "\n§7再对准食物箱执行 /farm bindfoodchest");
        return Command.SINGLE_SUCCESS;
    }

    private static int unbindFood(FabricClientCommandSource source) {
        FarmConfig cfg = FarmConfig.get();
        boolean had = cfg.food != null;
        cfg.food = null;
        FarmConfig.save();
        feedback(source, had ? "§a已解绑食物" : "§e尚未绑定食物");
        return had ? Command.SINGLE_SUCCESS : 0;
    }

    private static int bindFoodChest(FabricClientCommandSource source) {
        MinecraftClient mc = MinecraftClient.getInstance();
        BlockPos pos = crosshairContainer(source, mc);
        if (pos == null) return 0;
        if (!checkDim(source, mc)) return 0;
        FarmConfig.get().foodChest = new ModConfig.Pos(pos);
        FarmConfig.save();
        feedback(source, "§a已绑定食物箱: §e" + FarmConfig.get().foodChest);
        return Command.SINGLE_SUCCESS;
    }

    private static int unbindFoodChest(FabricClientCommandSource source) {
        FarmConfig cfg = FarmConfig.get();
        boolean had = cfg.foodChest != null;
        cfg.foodChest = null;
        FarmConfig.save();
        feedback(source, had ? "§a已解绑食物箱" : "§e尚未绑定食物箱");
        return had ? Command.SINGLE_SUCCESS : 0;
    }

    private static int setCanUses(FabricClientCommandSource source, int count) {
        FarmConfig.get().canUsesPerWaterer = count;
        FarmConfig.save();
        feedback(source, "§a每个浇水器每轮使用 §e" + count + " §a壶水");
        return Command.SINGLE_SUCCESS;
    }

    private static FarmConfig.WatererTarget crosshairWaterer(MinecraftClient mc) {
        HitResult hit = mc.crosshairTarget;
        if (hit instanceof EntityHitResult ehr && hit.getType() == HitResult.Type.ENTITY) {
            Entity entity = ehr.getEntity();
            String type = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
            String customName = entity.getCustomName() == null ? null : entity.getCustomName().getString();
            return new FarmConfig.WatererTarget(ehr.getPos(), true, type, customName);
        }
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            // 服务器虚拟物品可能没有客户端碰撞箱：记录准星射线落点，运行时朝这里使用洒水壶。
            return new FarmConfig.WatererTarget(bhr.getPos(), false, null, null);
        }
        return null;
    }

    // ---------- 工具 ----------

    private static BlockPos crosshairBlock(MinecraftClient mc) {
        HitResult hit = mc.crosshairTarget;
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            return bhr.getBlockPos();
        }
        return null;
    }

    private static BlockPos crosshairContainer(FabricClientCommandSource source, MinecraftClient mc) {
        BlockPos pos = crosshairBlock(mc);
        if (pos == null) {
            feedback(source, "§c请把准星对准箱子再执行");
            return null;
        }
        if (!(mc.world.getBlockEntity(pos) instanceof Inventory)) {
            feedback(source, "§c准星指向的方块不是容器（箱子/木桶等）");
            return null;
        }
        return pos;
    }

    /** 所有绑定必须和农场同维度；维度未记录时以当前维度为准。 */
    private static boolean checkDim(FabricClientCommandSource source, MinecraftClient mc) {
        FarmConfig cfg = FarmConfig.get();
        String dim = mc.world.getRegistryKey().getValue().toString();
        if (cfg.dim == null) {
            cfg.dim = dim;
            return true;
        }
        if (!cfg.dim.equals(dim)) {
            feedback(source, "§c必须和已有小区域在同一维度（" + cfg.dim + "）");
            return false;
        }
        return true;
    }

    private static void feedback(FabricClientCommandSource source, String text) {
        source.sendFeedback(Text.literal(text));
    }
}
