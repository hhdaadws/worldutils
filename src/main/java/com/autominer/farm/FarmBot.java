package com.autominer.farm;

import com.autominer.bot.Bot;
import com.autominer.bot.InputController;
import com.autominer.bot.PathExecutor;
import com.autominer.bot.Pathfinder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自动种地状态机：
 * SCAN（决定下一个任务，优先级：浇水到点 → 背包快满存作物 → 收成熟作物 → 种空地/拿种子）
 *   → WALK（A* 走过去）→ HARVEST/PICKUP | PLANT | TAKE_SEEDS | DEPOSIT | FILL_BUCKET/WATER
 *   → 回到 SCAN 无限循环。
 *
 * 成熟判定依赖 /farm learn 学习的方块状态签名（插件作物不是原版，无法直接判断）。
 */
public class FarmBot {
    public static final FarmBot INSTANCE = new FarmBot();

    public enum State {
        IDLE,
        SCAN,        // 决定下一个任务
        WALK,        // 走向目标（afterWalk 决定到达后进入的状态）
        HARVEST,     // 收获成熟作物
        PICKUP,      // 走向掉落物捡取
        PLANT,       // 右键种植
        TAKE_SEEDS,  // 从种子箱取种子
        DEPOSIT,     // 存作物
        FILL_BUCKET, // 在水源装水
        WATER,       // 右键浇水器加水
    }

    private State state = State.IDLE;
    private int ticksInState = 0;
    private int scanCooldown = 0;
    private long tickCounter = 0;

    // 走路
    private PathExecutor pathExec;
    private State afterWalk = State.SCAN;
    private BlockPos walkGoal;
    private double walkReach;
    private int pathRetries = 0;

    // 当前地块任务
    private BlockPos plotFarmland;
    private BlockPos plotCrop;
    private String plotCropName;
    private int actionAttempts = 0;
    private int graceTicks = 0;
    private boolean breakStarted = false;

    // 浇水
    private long lastWaterMs = 0; // 0 = 启动后立即先浇一轮
    private boolean wateringCycle = false;
    private final ArrayDeque<BlockPos> waterQueue = new ArrayDeque<>();
    private int bucketsLeftForWaterer = 0;
    private int preUseWaterBuckets = 0;

    // 箱子
    private ChestTakeController seedTake;
    private FarmDepositController deposit;
    private int cropChestIdx = 0;
    private String takeCropName;

    // 缓存与黑名单
    private List<BlockPos> farmlandCache = null;
    private long farmlandCacheAt = -1;
    private final Map<Long, Long> blacklist = new HashMap<>(); // 耕地 → 解禁 tick
    private final Set<String> warnedOnce = new HashSet<>();
    private final Map<String, Long> noSeedsUntilMs = new HashMap<>();

    private static final int BLACKLIST_TICKS = 20 * 60;      // 失败地块 1 分钟后重试
    private static final long NO_SEEDS_RETRY_MS = 5 * 60_000; // 种子箱空 5 分钟后重试
    private static final int PATH_MAX_NODES = 20000;

    private FarmBot() {}

    public boolean isRunning() {
        return state != State.IDLE;
    }

    public State getState() {
        return state;
    }

    public void toggle() {
        if (isRunning()) {
            stop("已手动停止");
        } else {
            start();
        }
    }

    /** 区域/绑定变更后调用，强制重扫耕地。 */
    public void invalidateCache() {
        farmlandCache = null;
    }

    public void start() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        if (Bot.INSTANCE.isRunning()) {
            msg("§c自动挖矿运行中，请先 /miner stop");
            return;
        }
        FarmConfig cfg = FarmConfig.get();
        List<String> missing = cfg.missing();
        if (!missing.isEmpty()) {
            msg("§c配置不完整，还需要: §e" + String.join("§7, §e", missing));
            return;
        }
        String dim = mc.world.getRegistryKey().getValue().toString();
        if (cfg.dim != null && !cfg.dim.equals(dim)) {
            msg("§c当前不在农场所在维度（" + cfg.dim + "）");
            return;
        }

        blacklist.clear();
        warnedOnce.clear();
        noSeedsUntilMs.clear();
        cropChestIdx = 0;
        lastWaterMs = 0; // 启动先浇一轮（有浇水器时）
        wateringCycle = false;
        waterQueue.clear();
        farmlandCache = null;
        clearControllers();
        scanCooldown = 0;

        msg("§a自动种地已启动"
                + (cfg.waterers.isEmpty() ? "" : "（先补一轮浇水器的水，之后每 " + cfg.waterIntervalMinutes + " 分钟一轮）"));
        enter(State.SCAN);
    }

    public void stop(String reason) {
        MinecraftClient mc = MinecraftClient.getInstance();
        state = State.IDLE;
        clearControllers();
        InputController.releaseAll(mc);
        if (mc.interactionManager != null) {
            mc.interactionManager.cancelBlockBreaking();
        }
        if (reason != null) {
            msg(reason);
        }
    }

    /** 断线等场景的强制复位（不发消息）。 */
    public void hardStop() {
        state = State.IDLE;
        clearControllers();
        InputController.clear();
    }

    private void clearControllers() {
        pathExec = null;
        seedTake = null;
        deposit = null;
        wateringCycle = false;
        waterQueue.clear();
    }

    private void enter(State s) {
        state = s;
        ticksInState = 0;
        graceTicks = 0;
        actionAttempts = 0;
        breakStarted = false;
        InputController.clear();
    }

    // ---------- 主循环 ----------

    public void tick(MinecraftClient mc) {
        if (state == State.IDLE) return;

        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            InputController.clear();
            return;
        }
        if (!player.isAlive()) {
            stop("§c玩家死亡，已停止");
            return;
        }

        tickCounter++;
        ticksInState++;
        FarmConfig cfg = FarmConfig.get();

        // 走路时玩家自己打开了界面（聊天/背包）→ 暂停
        boolean movementState = state == State.WALK || state == State.PICKUP;
        if (movementState && mc.currentScreen != null) {
            InputController.clear();
            InputController.apply(mc);
            return;
        }

        switch (state) {
            case SCAN -> tickScan(mc, player, cfg);
            case WALK -> tickWalk(mc, player);
            case HARVEST -> tickHarvest(mc, player, cfg);
            case PICKUP -> tickPickup(player);
            case PLANT -> tickPlant(mc, player, cfg);
            case TAKE_SEEDS -> tickTakeSeeds(mc, player, cfg);
            case DEPOSIT -> tickDeposit(mc, player, cfg);
            case FILL_BUCKET -> tickFillBucket(mc, player, cfg);
            case WATER -> tickWater(mc, player, cfg);
            default -> {}
        }

        InputController.apply(mc);
    }

    // ---------- SCAN：任务调度 ----------

    private void tickScan(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        if (scanCooldown > 0) {
            scanCooldown--;
            return;
        }

        // 1) 浇水优先（到点了 / 一轮还没浇完）
        if (wateringCycle || waterDue(cfg)) {
            if (stepWatering(mc, player, cfg)) return;
        }

        // 2) 背包快满且有可存的东西 → 存作物
        if (Bot.inventoryEmptySlots(player) <= cfg.depositWhenEmptySlots) {
            if (hasDepositable(player, cfg)) {
                if (cfg.cropChests.isEmpty()) {
                    stop("§c背包满了，但没有绑定作物箱（/farm bindcrop）");
                    return;
                }
                walkTo(mc, player, cfg.cropChests.get(cropChestIdx).toBlockPos(), 3.2, State.DEPOSIT);
                return;
            }
            warnOnce("inv-full-keep", "§e背包快满了，但都是种子/水桶等保留物品，不会存入作物箱");
        }

        // 3) 扫描地块
        List<FarmScanner.Plot> plots = FarmScanner.classify(mc.world, cfg, farmland(mc, cfg));
        plots.removeIf(p -> isBlacklisted(p.farmland()));
        plots.sort(Comparator.comparingDouble(p ->
                p.farmland().getSquaredDistance(player.getX(), player.getY(), player.getZ())));

        // 3a) 最近的成熟作物 → 收
        for (FarmScanner.Plot p : plots) {
            if (p.state() == FarmScanner.PlotState.MATURE) {
                beginPlot(mc, player, p, State.HARVEST);
                return;
            }
        }

        // 3b) 最近的空地 → 种（没种子先去种子箱拿）
        for (FarmScanner.Plot p : plots) {
            if (p.state() != FarmScanner.PlotState.EMPTY) continue;
            FarmConfig.Crop crop = cfg.crops.get(p.cropName());
            if (crop == null || crop.seedItemId == null || crop.seedName == null) {
                warnOnce("crop-" + p.cropName(),
                        "§e小区域作物「" + p.cropName() + "」还没定义种子（手持种子执行 /farm crop add "
                                + p.cropName() + "），先跳过");
                continue;
            }
            if (FarmItems.countSeeds(player, crop) > 0) {
                beginPlot(mc, player, p, State.PLANT);
                return;
            }
            if (crop.seedChest != null
                    && System.currentTimeMillis() >= noSeedsUntilMs.getOrDefault(p.cropName(), 0L)) {
                takeCropName = p.cropName();
                walkTo(mc, player, crop.seedChest.toBlockPos(), 3.2, State.TAKE_SEEDS);
                return;
            }
            if (crop.seedChest == null) {
                warnOnce("seedchest-" + p.cropName(),
                        "§e「" + p.cropName() + "」没绑种子箱（/farm bindseed " + p.cropName()
                                + "），背包里也没种子，先跳过");
            }
        }

        // 提示：没学过成熟特征的作物只种不收
        for (Map.Entry<String, FarmConfig.Crop> e : cfg.crops.entrySet()) {
            if (e.getValue().mature.isEmpty()) {
                warnOnce("mature-" + e.getKey(),
                        "§e作物「" + e.getKey() + "」还没学习成熟特征（对准成熟作物执行 /farm learn "
                                + e.getKey() + "，或用 /farm info 把各阶段信息发给开发者），暂时只种不收");
            }
        }

        // 4) 没事做 → 5 秒后再扫
        scanCooldown = 100;
    }

    private void beginPlot(MinecraftClient mc, ClientPlayerEntity player,
                           FarmScanner.Plot p, State action) {
        plotFarmland = p.farmland();
        plotCrop = p.crop();
        plotCropName = p.cropName();
        walkTo(mc, player, plotCrop, 3.0, action);
    }

    // ---------- 走路 ----------

    private void walkTo(MinecraftClient mc, ClientPlayerEntity player,
                        BlockPos target, double reach, State next) {
        walkGoal = target;
        walkReach = reach;
        afterWalk = next;
        pathRetries = 0;
        List<BlockPos> path = Pathfinder.findNear(mc.world, player.getBlockPos(), target, reach, PATH_MAX_NODES);
        if (path == null) {
            walkFailed(next);
            return;
        }
        pathExec = new PathExecutor(path);
        enter(State.WALK);
    }

    private void tickWalk(MinecraftClient mc, ClientPlayerEntity player) {
        if (pathExec == null) {
            enter(State.SCAN);
            return;
        }
        pathExec.tick(player);
        if (pathExec.isDone()) {
            pathExec = null;
            enter(afterWalk);
            return;
        }
        if (pathExec.isStuck() || ticksInState > 20 * 40) {
            pathRetries++;
            if (pathRetries > 3) {
                pathExec = null;
                InputController.clear();
                walkFailed(afterWalk);
                return;
            }
            List<BlockPos> path = Pathfinder.findNear(mc.world, player.getBlockPos(),
                    walkGoal, walkReach, PATH_MAX_NODES);
            if (path == null) {
                pathExec = null;
                InputController.clear();
                walkFailed(afterWalk);
                return;
            }
            pathExec = new PathExecutor(path);
            ticksInState = 0;
        }
    }

    private void walkFailed(State next) {
        switch (next) {
            case HARVEST, PLANT -> blacklistPlot("走不过去");
            case TAKE_SEEDS -> {
                noSeeds(takeCropName, "走不到种子箱");
                enter(State.SCAN);
            }
            case DEPOSIT -> nextCropChestOrStop("作物箱走不到");
            case WATER -> {
                waterQueue.poll();
                bucketsLeftForWaterer = 0;
                msg("§e走不到浇水器，跳过这个");
                enter(State.SCAN);
            }
            case FILL_BUCKET -> {
                msg("§e走不到水源，本轮浇水取消");
                abortWatering();
                enter(State.SCAN);
            }
            default -> enter(State.SCAN);
        }
    }

    // ---------- 收获 / 捡取 ----------

    private void tickHarvest(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        FarmConfig.Crop crop = cfg.crops.get(plotCropName);
        BlockState st = mc.world.getBlockState(plotCrop);
        boolean stillMature = crop != null && FarmScanner.matchesMature(crop, st);
        if (!stillMature) {
            // 挖掉了 / 右键收获后状态变了 → 去捡掉落物
            mc.interactionManager.cancelBlockBreaking();
            InputController.clear();
            enter(State.PICKUP);
            return;
        }

        faceBlock(player, Vec3d.ofCenter(plotCrop));

        if ("use".equals(cfg.harvestMode)) {
            if (graceTicks > 0) {
                graceTicks--;
                return;
            }
            if (actionAttempts >= 4) {
                blacklistPlot("右键收获无效（确认 /farm harvestmode 是否该用 break）");
                return;
            }
            actionAttempts++;
            BlockHitResult hit = new BlockHitResult(
                    Vec3d.ofCenter(plotCrop), Direction.UP, plotCrop, false);
            mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
            player.swingHand(Hand.MAIN_HAND);
            graceTicks = 12;
        } else {
            if (!breakStarted) {
                mc.interactionManager.attackBlock(plotCrop, Direction.UP);
                breakStarted = true;
            } else {
                mc.interactionManager.updateBlockBreakingProgress(plotCrop, Direction.UP);
            }
            player.swingHand(Hand.MAIN_HAND);
            if (ticksInState > 100) {
                mc.interactionManager.cancelBlockBreaking();
                blacklistPlot("挖不动（确认 /farm harvestmode 是否该用 use）");
            }
        }
    }

    private void tickPickup(ClientPlayerEntity player) {
        double dx = plotCrop.getX() + 0.5 - player.getX();
        double dz = plotCrop.getZ() + 0.5 - player.getZ();
        if (ticksInState > 30 || dx * dx + dz * dz < 0.8 * 0.8) {
            InputController.clear();
            enter(State.SCAN);
            return;
        }
        player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setPitch(15.0f);
        InputController.forward = true;
    }

    // ---------- 种植 ----------

    private void tickPlant(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        // 种上了（或别人种了）→ 下一个任务
        if (!mc.world.getBlockState(plotCrop).isAir()) {
            enter(State.SCAN);
            return;
        }
        if (graceTicks > 0) {
            graceTicks--;
            return;
        }
        if (!mc.world.getBlockState(plotFarmland).isOf(Blocks.FARMLAND)) {
            blacklistPlot("耕地没了");
            return;
        }
        if (actionAttempts >= 3) {
            blacklistPlot("种不上去");
            return;
        }
        FarmConfig.Crop crop = cfg.crops.get(plotCropName);
        if (crop == null) {
            enter(State.SCAN);
            return;
        }
        // 把种子换到主手（换手后等 2 tick 再右键，等背包同步）
        if (!FarmItems.isSeedOf(crop, player.getMainHandStack())) {
            if (!FarmItems.selectMatching(mc, player, s -> FarmItems.isSeedOf(crop, s))) {
                enter(State.SCAN); // 种子用完了，下轮 SCAN 会去种子箱拿
                return;
            }
            graceTicks = 2;
            return;
        }

        actionAttempts++;
        faceBlock(player, new Vec3d(plotFarmland.getX() + 0.5, plotFarmland.getY() + 1.0,
                plotFarmland.getZ() + 0.5));
        BlockHitResult hit = new BlockHitResult(
                new Vec3d(plotFarmland.getX() + 0.5, plotFarmland.getY() + 1.0, plotFarmland.getZ() + 0.5),
                Direction.UP, plotFarmland, false);
        mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        player.swingHand(Hand.MAIN_HAND);
        graceTicks = 8;
    }

    // ---------- 种子箱 / 作物箱 ----------

    private void tickTakeSeeds(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        FarmConfig.Crop crop = cfg.crops.get(takeCropName);
        if (crop == null || crop.seedChest == null) {
            enter(State.SCAN);
            return;
        }
        if (seedTake == null) {
            seedTake = new ChestTakeController(crop.seedChest.toBlockPos(),
                    s -> FarmItems.isSeedOf(crop, s),
                    "种子箱[" + takeCropName + "]", cfg.seedStacksPerTrip);
        }
        switch (seedTake.tick(mc, player)) {
            case WORKING -> {}
            case DONE -> {
                seedTake = null;
                msg("§7已补充种子: §e" + takeCropName);
                enter(State.SCAN);
            }
            case FAILED -> {
                String why = seedTake.getFailReason();
                seedTake = null;
                noSeeds(takeCropName, why);
                enter(State.SCAN);
            }
        }
    }

    private void tickDeposit(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        if (cfg.cropChests.isEmpty()) {
            stop("§c没有作物箱可用");
            return;
        }
        if (deposit == null) {
            deposit = new FarmDepositController(cfg.cropChests.get(cropChestIdx).toBlockPos());
        }
        switch (deposit.tick(mc, player)) {
            case WORKING -> {}
            case DONE -> {
                deposit = null;
                cropChestIdx = 0;
                enter(State.SCAN);
            }
            case CHEST_FULL -> {
                deposit = null;
                nextCropChestOrStop("作物箱满了");
            }
            case FAILED -> {
                deposit = null;
                nextCropChestOrStop("作物箱打不开");
            }
        }
    }

    /** 换下一个作物箱；没有了就停机。 */
    private void nextCropChestOrStop(String why) {
        FarmConfig cfg = FarmConfig.get();
        cropChestIdx++;
        if (cropChestIdx >= cfg.cropChests.size()) {
            stop("§c" + why + "，且没有其他作物箱了，已停止");
            return;
        }
        msg("§e" + why + "，换下一个作物箱 #" + (cropChestIdx + 1));
        enter(State.SCAN); // SCAN 会因背包仍满而走向下一个箱子
    }

    // ---------- 浇水 ----------

    private boolean waterDue(FarmConfig cfg) {
        if (cfg.waterers.isEmpty()) return false;
        return lastWaterMs == 0
                || System.currentTimeMillis() - lastWaterMs >= cfg.waterIntervalMinutes * 60_000L;
    }

    /**
     * 推进浇水流程一步（在 SCAN 里调用）。
     * 返回 true = 已安排走路/动作；false = 浇水流程结束/不可行，继续其他任务。
     */
    private boolean stepWatering(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        if (!wateringCycle) {
            wateringCycle = true;
            waterQueue.clear();
            cfg.waterers.forEach(p -> waterQueue.add(p.toBlockPos()));
            bucketsLeftForWaterer = 0;
            msg("§7开始浇水巡回（" + waterQueue.size() + " 个浇水器，每个 "
                    + Math.max(1, cfg.bucketsPerWaterer) + " 桶）");
        }
        if (waterQueue.isEmpty()) {
            wateringCycle = false;
            lastWaterMs = System.currentTimeMillis();
            msg("§a浇水完成，" + cfg.waterIntervalMinutes + " 分钟后再来一轮");
            return false;
        }
        if (bucketsLeftForWaterer <= 0) {
            bucketsLeftForWaterer = Math.max(1, cfg.bucketsPerWaterer);
        }

        BlockPos waterer = waterQueue.peek();
        if (FarmItems.hasWaterBucket(player)) {
            walkTo(mc, player, waterer, 3.0, State.WATER);
            return true;
        }
        if (FarmItems.hasEmptyBucket(player)) {
            if (cfg.waterSource == null) {
                msg("§c没绑水源（对着水执行 /farm bindwater），本轮浇水跳过");
                abortWatering();
                return false;
            }
            walkTo(mc, player, cfg.waterSource.toBlockPos(), 3.5, State.FILL_BUCKET);
            return true;
        }
        msg("§c背包里没有水桶，本轮浇水跳过（请在背包放至少一个桶）");
        abortWatering();
        return false;
    }

    /** 本轮浇水放弃：计时器照常重置，避免每次扫描都重试刷屏。 */
    private void abortWatering() {
        wateringCycle = false;
        waterQueue.clear();
        bucketsLeftForWaterer = 0;
        lastWaterMs = System.currentTimeMillis();
    }

    private void tickFillBucket(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        if (FarmItems.hasWaterBucket(player)) { // 装到水了
            enter(State.SCAN); // SCAN → stepWatering 继续去浇水器
            return;
        }
        if (graceTicks > 0) {
            graceTicks--;
            return;
        }
        if (actionAttempts >= 6) {
            msg("§c在水源装不到水（确认 /farm bindwater 指向的是水方块）");
            abortWatering();
            enter(State.SCAN);
            return;
        }
        if (cfg.waterSource == null) {
            abortWatering();
            enter(State.SCAN);
            return;
        }
        // 空桶换到主手（换手后等 2 tick）
        if (!FarmItems.isBucketInHand(player, FarmItems.BUCKET)) {
            if (!FarmItems.selectMatching(mc, player,
                    s -> !s.isEmpty() && FarmItems.idOf(s).equals(FarmItems.BUCKET))) {
                msg("§c背包里没有空桶了");
                abortWatering();
                enter(State.SCAN);
                return;
            }
            graceTicks = 2;
            return;
        }

        actionAttempts++;
        faceBlock(player, Vec3d.ofCenter(cfg.waterSource.toBlockPos()));
        // 装水走"使用物品"（服务器按视线自己做射线，包含流体）
        mc.interactionManager.interactItem(player, Hand.MAIN_HAND);
        player.swingHand(Hand.MAIN_HAND);
        graceTicks = 10;
    }

    private void tickWater(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        BlockPos waterer = waterQueue.peek();
        if (waterer == null) {
            enter(State.SCAN);
            return;
        }

        if (graceTicks > 0) {
            graceTicks--;
            // 水桶被消耗 = 加水成功
            if (FarmItems.waterBucketCount(player) < preUseWaterBuckets) {
                bucketsLeftForWaterer--;
                if (bucketsLeftForWaterer <= 0) {
                    waterQueue.poll();
                }
                enter(State.SCAN); // SCAN → stepWatering 继续（补桶或下一个浇水器）
            }
            return;
        }

        // 插件可能弹容器 GUI，关掉继续（不动玩家自己开的聊天等界面）
        if (player.currentScreenHandler != null
                && player.currentScreenHandler != player.playerScreenHandler) {
            player.closeHandledScreen();
        }

        if (actionAttempts >= 5) {
            msg("§e浇水器没消耗水桶，跳过这个（位置 " + waterer.toShortString() + "）");
            waterQueue.poll();
            bucketsLeftForWaterer = 0;
            enter(State.SCAN);
            return;
        }
        // 水桶换到主手
        if (!FarmItems.isBucketInHand(player, FarmItems.WATER_BUCKET)) {
            if (!FarmItems.selectMatching(mc, player,
                    s -> !s.isEmpty() && FarmItems.idOf(s).equals(FarmItems.WATER_BUCKET))) {
                enter(State.SCAN); // 没水桶了 → SCAN 会安排去装水
                return;
            }
            graceTicks = 2;
            return;
        }

        actionAttempts++;
        preUseWaterBuckets = FarmItems.waterBucketCount(player);
        faceBlock(player, Vec3d.ofCenter(waterer));
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(waterer), Direction.UP, waterer, false);
        mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        player.swingHand(Hand.MAIN_HAND);
        graceTicks = 10;
    }

    // ---------- 工具 ----------

    /** 背包里是否有可以存进作物箱的东西（种子/水桶/保留格除外）。 */
    private static boolean hasDepositable(ClientPlayerEntity player, FarmConfig cfg) {
        for (int i = 0; i < 36; i++) {
            if (i == com.autominer.bot.DepositController.RESERVED_SLOT) continue;
            var stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && !FarmDepositController.shouldKeep(cfg, stack)) {
                return true;
            }
        }
        return false;
    }

    private List<BlockPos> farmland(MinecraftClient mc, FarmConfig cfg) {
        if (farmlandCache == null || farmlandCache.isEmpty()
                || tickCounter - farmlandCacheAt > 1200) {
            farmlandCache = FarmScanner.findFarmland(mc.world, cfg);
            farmlandCacheAt = tickCounter;
        }
        return farmlandCache;
    }

    private boolean isBlacklisted(BlockPos farmland) {
        Long until = blacklist.get(farmland.asLong());
        return until != null && tickCounter < until;
    }

    private void blacklistPlot(String reason) {
        if (plotFarmland != null) {
            blacklist.put(plotFarmland.asLong(), tickCounter + BLACKLIST_TICKS);
            msg("§e跳过地块 " + plotFarmland.toShortString() + "（" + reason + "，1 分钟后重试）");
        }
        enter(State.SCAN);
    }

    private void noSeeds(String cropName, String why) {
        if (cropName == null) return;
        noSeedsUntilMs.put(cropName, System.currentTimeMillis() + NO_SEEDS_RETRY_MS);
        msg("§e「" + cropName + "」拿不到种子（" + why + "），5 分钟后再试");
    }

    private void warnOnce(String key, String text) {
        if (warnedOnce.add(key)) {
            msg(text);
        }
    }

    private static void faceBlock(ClientPlayerEntity player, Vec3d target) {
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setPitch((float) -Math.toDegrees(Math.atan2(dy, horiz)));
    }

    public static void msg(String s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("§2[AutoFarm]§r " + s), false);
        }
    }

    public String statusText() {
        FarmConfig cfg = FarmConfig.get();
        StringBuilder sb = new StringBuilder();
        sb.append("§2==== AutoFarm 状态 ====§r\n");
        sb.append("运行状态: ").append(isRunning() ? "§a" + state : "§7空闲").append("§r\n");
        sb.append("农场区域: ").append(cfg.region != null ? cfg.region : "§c未设置").append("§r");
        if (farmlandCache != null) sb.append(" §7(耕地 ").append(farmlandCache.size()).append(" 块)");
        sb.append("\n");
        sb.append("维度: ").append(cfg.dim != null ? cfg.dim : "§c-").append("§r\n");
        sb.append("小区域(").append(cfg.zones.size()).append("):\n");
        for (int i = 0; i < cfg.zones.size(); i++) {
            sb.append("  §e#").append(i + 1).append(" ").append(cfg.zones.get(i)).append("§r\n");
        }
        sb.append("作物(").append(cfg.crops.size()).append("):\n");
        for (Map.Entry<String, FarmConfig.Crop> e : cfg.crops.entrySet()) {
            FarmConfig.Crop c = e.getValue();
            sb.append("  §e").append(e.getKey()).append("§r 种子=").append(c.seedName)
                    .append("(").append(c.seedItemId).append(")")
                    .append(" 种子箱=").append(c.seedChest != null ? c.seedChest : "§c未绑§r")
                    .append(" 成熟特征=").append(c.mature.size()).append("条")
                    .append(c.mature.isEmpty() ? "§c(只种不收)§r" : "").append("\n");
        }
        sb.append("作物箱(").append(cfg.cropChests.size()).append("): ");
        for (int i = 0; i < cfg.cropChests.size(); i++) {
            sb.append("#").append(i + 1).append(cfg.cropChests.get(i)).append(" ");
        }
        if (cfg.cropChests.isEmpty()) sb.append("§c未绑定");
        sb.append("§r\n");
        sb.append("浇水器(").append(cfg.waterers.size()).append("): ");
        for (int i = 0; i < cfg.waterers.size(); i++) {
            sb.append("#").append(i + 1).append(cfg.waterers.get(i)).append(" ");
        }
        if (cfg.waterers.isEmpty()) sb.append("§7未绑定(不浇水)");
        sb.append("§r\n");
        sb.append("水源: ").append(cfg.waterSource != null ? cfg.waterSource : "§c未绑定").append("§r\n");
        sb.append("浇水间隔: ").append(cfg.waterIntervalMinutes).append(" 分钟，每个浇水器 ")
                .append(Math.max(1, cfg.bucketsPerWaterer)).append(" 桶");
        if (isRunning() && lastWaterMs > 0 && !cfg.waterers.isEmpty()) {
            long nextIn = cfg.waterIntervalMinutes * 60_000L - (System.currentTimeMillis() - lastWaterMs);
            sb.append("§7（下一轮约 ").append(Math.max(0, nextIn / 60_000)).append(" 分钟后）");
        }
        sb.append("§r\n");
        sb.append("收获方式: ").append(cfg.harvestMode).append("§r");
        return sb.toString();
    }
}
