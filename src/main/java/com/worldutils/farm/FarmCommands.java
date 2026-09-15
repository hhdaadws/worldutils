package com.worldutils.farm;

import com.worldutils.config.ModConfig;
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
                .then(ClientCommandManager.literal("bindcrop")
                        .then(ClientCommandManager.argument("crop", StringArgumentType.greedyString())
                                .executes(ctx -> bindCropChestFor(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "crop").trim())))
                        .executes(ctx -> bindCropChest(ctx.getSource())))
                .then(ClientCommandManager.literal("unbindcrop")
                        .then(ClientCommandManager.literal("all").executes(ctx -> {
                            FarmConfig cfg = FarmConfig.get();
                            int n = cfg.cropChests.size();
                            for (FarmConfig.Crop c : cfg.crops.values()) {
                                if (c.depositChests != null) {
                                    n += c.depositChests.size();
                                    c.depositChests.clear();
                                }
                            }
                            if (n == 0) {
                                feedback(ctx.getSource(), "§e当前没有绑定任何作物箱");
                                return 0;
                            }
                            cfg.cropChests.clear();
                            FarmConfig.save();
                            feedback(ctx.getSource(), "§a已一键解绑全部 " + n + " 个作物箱（含各作物专箱）");
                            return Command.SINGLE_SUCCESS;
                        }))
                        .executes(ctx -> unbindCropChest(ctx.getSource())))
                .then(ClientCommandManager.literal("bindwaterer").executes(ctx -> bindWaterer(ctx.getSource())))
                .then(ClientCommandManager.literal("unbindwaterer")
                        .then(ClientCommandManager.literal("all").executes(ctx -> {
                            FarmConfig cfg = FarmConfig.get();
                            int n = cfg.waterers.size();
                            if (n == 0) {
                                feedback(ctx.getSource(), "§e当前没有绑定任何浇水器");
                                return 0;
                            }
                            cfg.waterers.clear();
                            FarmConfig.save();
                            feedback(ctx.getSource(), "§a已一键解绑全部 " + n + " 个浇水器");
                            return Command.SINGLE_SUCCESS;
                        }))
                        .executes(ctx -> unbindWaterer(ctx.getSource())))
                .then(ClientCommandManager.literal("toggle")
                        .then(ClientCommandManager.literal("water").executes(ctx -> {
                            FarmConfig cfg = FarmConfig.get();
                            cfg.wateringEnabled = !cfg.wateringEnabled;
                            FarmConfig.save();
                            feedback(ctx.getSource(), "§a定时洒水: "
                                    + (cfg.wateringEnabled ? "§a已开启" : "§c已关闭（/farm water 仍可手动强制）"));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommandManager.argument("crop", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "crop").trim();
                                    FarmConfig cfg = FarmConfig.get();
                                    FarmConfig.Crop crop = cfg.crops.get(name);
                                    if (crop == null) {
                                        feedback(ctx.getSource(), "§c没有这个作物: " + name
                                                + "（/farm crop list 查看）");
                                        return 0;
                                    }
                                    crop.enabled = !crop.enabled;
                                    FarmConfig.save();
                                    feedback(ctx.getSource(), "§a作物「" + name + "」: "
                                            + (crop.enabled ? "§a已启用（正常收获+种植）"
                                            : "§c已停用（不收获、不种植、不取种子）"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(ClientCommandManager.literal("water").executes(ctx -> {
                    FarmBot.INSTANCE.waterNow();
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommandManager.literal("bindcan").executes(ctx -> bindWateringCan(ctx.getSource())))
                .then(ClientCommandManager.literal("unbindcan").executes(ctx -> unbindWateringCan(ctx.getSource())))
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
                .then(ClientCommandManager.literal("ratio")
                        .then(ClientCommandManager.argument("percent", IntegerArgumentType.integer(1, 100))
                                .executes(ctx -> {
                                    FarmConfig.get().harvestMaturePercent =
                                            IntegerArgumentType.getInteger(ctx, "percent");
                                    FarmConfig.save();
                                    feedback(ctx.getSource(), "§azone 收获成熟率门槛已设置: §e"
                                            + FarmConfig.get().harvestMaturePercent + "%"
                                            + "\n§7成熟数/(成熟+生长中) 达到该比例的 zone 才会开收");
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .executes(ctx -> {
                            feedback(ctx.getSource(), "§7当前收获成熟率门槛: §e"
                                    + FarmConfig.get().harvestMaturePercent
                                    + "%§7，用 /farm ratio <1-100> 修改（默认 50）");
                            return Command.SINGLE_SUCCESS;
                        }))
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
                .then(ClientCommandManager.literal("strategy")
                        .then(ClientCommandManager.literal("route").executes(ctx -> {
                            FarmConfig.get().harvestStrategy = "route";
                            FarmConfig.save();
                            feedback(ctx.getSource(), "§a遍历策略: §e最短路线(route)"
                                    + "\n§7进区时按当前成熟/空地目标规划贪心+2-opt 路线，总路程最短");
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommandManager.literal("random").executes(ctx -> {
                            FarmConfig.get().harvestStrategy = "random";
                            FarmConfig.save();
                            feedback(ctx.getSource(), "§a遍历策略: §e随机模式(random)"
                                    + "\n§7每次进区随机蛇形/斜向/螺旋/隔行，轨迹无固定特征");
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(ClientCommandManager.literal("speed")
                        .then(ClientCommandManager.literal("normal").executes(ctx -> setSpeed(ctx.getSource(), "normal")))
                        .then(ClientCommandManager.literal("medium").executes(ctx -> setSpeed(ctx.getSource(), "medium")))
                        .then(ClientCommandManager.literal("fast").executes(ctx -> setSpeed(ctx.getSource(), "fast")))
                        .executes(ctx -> {
                            feedback(ctx.getSource(), "§7当前速度模式: §e" + FarmConfig.get().speedMode
                                    + "§7，用 /farm speed normal|medium|fast 切换");
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(ClientCommandManager.literal("fast").executes(ctx ->
                        // 旧开关保留：normal ↔ fast 互切（medium 用 /farm speed medium）
                        setSpeed(ctx.getSource(), FarmConfig.get().isFast() ? "normal" : "fast")))
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
                .then(ClientCommandManager.literal("license")
                        .executes(ctx -> {
                            feedback(ctx.getSource(), com.worldutils.license.LicenseManager.remainingText()
                                    + "\n§7激活请执行 §e/farm license <6位验证码>");
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(ClientCommandManager.argument("code", StringArgumentType.word())
                                .executes(ctx -> {
                                    String code = StringArgumentType.getString(ctx, "code");
                                    if (com.worldutils.license.LicenseManager.activate(code)) {
                                        feedback(ctx.getSource(), "§a激活成功！有效期 "
                                                + com.worldutils.license.LicenseManager.grantDays() + " 天\n"
                                                + com.worldutils.license.LicenseManager.remainingText());
                                    } else {
                                        feedback(ctx.getSource(), "§c验证码错误或已过期，请向作者索取最新的 6 位码");
                                    }
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(ClientCommandManager.literal("login")
                        .then(ClientCommandManager.argument("user", StringArgumentType.word())
                                .then(ClientCommandManager.argument("password", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            com.worldutils.license.AccountAuth.login(
                                                    StringArgumentType.getString(ctx, "user"),
                                                    StringArgumentType.getString(ctx, "password").trim());
                                            return Command.SINGLE_SUCCESS;
                                        }))))
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
                            §2/farm 用法:§r
                            §e/farm pos1|pos2§7 - 用准星选择小区域的两个角(没指方块用脚下)
                            §e/farm zone add <作物>§7 - 把当前选区设为种<作物>的小区域 (list/remove 管理)
                            §e/farm crop add <作物>§7 - §f手持种子§7执行，记录种子物品+名字
                            §e/farm bindseed <作物>§7 - 准星对准该作物的种子箱
                            §e/farm bindcrop [作物]§7 - 绑定全局作物箱；带作物名=该作物专箱(按产出名分拣) §e/farm unbindcrop [all]§7 移除
                            §e/farm bindwaterer§7 - 准星对准浇水站立点绑坐标(可水下≤3格,可多个) §e/farm unbindwaterer [all]§7 移除
                            §e/farm water§7 - 立即手动浇水一轮(无视间隔)
                            §e/farm toggle <作物>§7 - 开关该作物(收获+种植) §e/farm toggle water§7 - 开关定时洒水
                            §e/farm bindcan§7 - §f手持插件洒水壶§7执行，记录洒水壶物品
                            §e/farm bindfood§7 - §f手持食物§7记录物品 §e/farm bindfoodchest§7 - 绑定食物箱
                            §e/farm learn <作物>§7 - 准星对准§f成熟§7作物，学习成熟特征
                            §e/farm info§7 - 查看准星方块的详细信息(发给开发者用)
                            §e/farm interval <分钟>§7 - 浇水间隔(默认180=3小时)
                            §e/farm ratio <1-100>§7 - zone 收获成熟率门槛(默认50%)
                            §e/farm harvestmode break|use§7 - 左键挖，或拿对应种子右键自动收获复种
                            §e/farm strategy route|random§7 - 遍历策略: 最短路线(默认) / 随机轨迹
                            §e/farm speed normal|medium|fast§7 - 速度: 正常 / 不停步+指针加速对准 / 不停步不对准(最快最机械)
                            §e/farm license <码>§7 - 输入验证码激活授权 (无参数查看剩余时间)
                            §e/farm login <账号> <密码>§7 - 账号密码登录授权 (有效期到账号到期日)
                            §e/farm start | stop | status§7 - 开始/停止/状态 (快捷键 K)""");
                    return Command.SINGLE_SUCCESS;
                }));
    }

    // ---------- 实现 ----------

    private static int setSpeed(FabricClientCommandSource source, String mode) {
        FarmConfig cfg = FarmConfig.get();
        cfg.speedMode = mode;
        cfg.fastMode = cfg.isFast(); // 旧字段保持同步
        FarmConfig.save();
        String desc = switch (mode) {
            case "fast" -> "§afast§7：收菜不停步、不对准直接出手、全程不下蹲（最快但最机械）";
            case "medium" -> "§amedium§7：收菜不停步，出手前指针加速对准到目标、全程不下蹲（快且更像人）";
            default -> "§7normal：逐块贴身、平滑对准、区内下蹲（最像人）";
        };
        feedback(source, "§a速度模式: " + desc);
        return Command.SINGLE_SUCCESS;
    }

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

    /** 给指定作物绑定专用产出箱（产出显示名包含作物名的物品会分拣到这里）。 */
    private static int bindCropChestFor(FabricClientCommandSource source, String cropName) {
        MinecraftClient mc = MinecraftClient.getInstance();
        FarmConfig cfg = FarmConfig.get();
        FarmConfig.Crop crop = cfg.crops.get(cropName);
        if (crop == null) {
            feedback(source, "§c没有这个作物: " + cropName + "（先 /farm crop add " + cropName + "）");
            return 0;
        }
        BlockPos pos = crosshairContainer(source, mc);
        if (pos == null) return 0;
        if (!checkDim(source, mc)) return 0;
        if (crop.depositChests == null) crop.depositChests = new java.util.ArrayList<>();
        for (ModConfig.Pos p : crop.depositChests) {
            if (p.x == pos.getX() && p.y == pos.getY() && p.z == pos.getZ()) {
                feedback(source, "§e这个箱子已经绑定为「" + cropName + "」专箱了");
                return 0;
            }
        }
        crop.depositChests.add(new ModConfig.Pos(pos));
        FarmConfig.save();
        feedback(source, "§a已绑定「" + cropName + "」专用产出箱 #" + crop.depositChests.size()
                + ": §e" + new ModConfig.Pos(pos)
                + "\n§7产出显示名包含「" + cropName + "」的物品会存到这里；专箱满/未绑的走全局作物箱");
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
        StringBuilder where = new StringBuilder();
        if (removed) where.append("全局箱");
        // 各作物专箱里也找一遍（同一坐标可能绑给了某作物）
        for (var e : cfg.crops.entrySet()) {
            if (e.getValue().depositChests == null) continue;
            boolean r = e.getValue().depositChests.removeIf(p ->
                    p.x == pos.getX() && p.y == pos.getY() && p.z == pos.getZ());
            if (r) {
                removed = true;
                if (where.length() > 0) where.append("、");
                where.append("「").append(e.getKey()).append("」专箱");
            }
        }
        FarmConfig.save();
        feedback(source, removed ? "§a已解绑该箱子（" + where + "）"
                : "§e该箱子未绑定");
        return removed ? Command.SINGLE_SUCCESS : 0;
    }

    private static int bindWaterer(FabricClientCommandSource source) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return 0;
        // 绑定一个坐标（可在水下）：带流体射线取准星方块，没指到就用脚下位置
        BlockPos pos = null;
        HitResult hit = mc.player.raycast(6.0, 0.0f, true);
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            pos = bhr.getBlockPos();
        }
        if (pos == null) {
            pos = mc.player.getBlockPos();
        }
        if (!checkDim(source, mc)) return 0;
        FarmConfig.WatererTarget target = new FarmConfig.WatererTarget(
                Vec3d.ofCenter(pos), false, null, null);
        FarmConfig cfg = FarmConfig.get();
        for (FarmConfig.WatererTarget existing : cfg.waterers) {
            if (existing.squaredDistanceTo(target.targetPos()) <= 1.0) {
                feedback(source, "§e这个浇水点已经绑定过了");
                return 0;
            }
        }
        cfg.waterers.add(target);
        FarmConfig.save();
        feedback(source, "§a已绑定浇水点 #" + cfg.waterers.size() + ": §e" + pos.toShortString()
                + "\n§7到点后会走到这里：拿洒水壶低头右键 3 次，再抬头 shift+右键 1 次"
                + "\n§7（站立点可在 ≤3 格深的水下；记得手持洒水壶 /farm bindcan）");
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
