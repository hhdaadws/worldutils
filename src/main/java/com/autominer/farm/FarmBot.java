package com.autominer.farm;

import com.autominer.bot.Bot;
import com.autominer.bot.InputController;
import com.autominer.bot.PathExecutor;
import com.autominer.bot.Pathfinder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
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
 * SCAN（优先级：维持饱食 → 浇水到点 → 背包满存物 → 按 zone 补种 → 按 zone 收获并拾取 → 缺种补货）
 *   → WALK（A* 走过去）→ HARVEST/PICKUP_ZONE | PLANT | TAKE_SEEDS | DEPOSIT | FILL_CAN/WATER
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
        PICKUP_ZONE, // 完成一个小区域的收获/种植后，只清扫该区域掉落物
        PLANT,       // 右键种植
        TAKE_SEEDS,  // 从种子箱取种子
        TAKE_FOOD,   // 从食物箱取食物
        EAT,         // 自动进食维持疾跑
        DEPOSIT,     // 存作物
        FILL_CAN,    // 拿插件洒水壶在水源装水
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
    private final Set<Integer> harvestBatchZones = new HashSet<>();
    private int activeHarvestZone = -1;
    private int activePlantZone = -1;
    private ZoneTraversal zoneTraversal;
    private boolean zoneTraversalFallback = false;
    private int pickupZoneIndex = -1;
    private final Set<Integer> ignoredZoneDrops = new HashSet<>();
    private final Map<Integer, Integer> zonePickupRetries = new HashMap<>();
    private int zonePickupTargetId = -1;
    private int pickupApproachTicks = 0;
    private int pickupWaitTicks = 0;
    private float pickupApproachYaw = 0.0f;
    private boolean pickupScanAnnounced = false;
    private int zonePickupEmptyConfirmations = 0;
    private int zonePickupConfirmWaitTicks = 0;
    private int zonePickupPass = 1;

    // 浇水
    private long waterRetryAfterMs = 0;
    private boolean wateringCycle = false;
    private final ArrayDeque<FarmConfig.WatererTarget> waterQueue = new ArrayDeque<>();
    private int canUsesLeftForWaterer = 0;
    private boolean canLoaded = false;
    private boolean wateringActionSent = false;
    private boolean waterSneakReady = false;
    private boolean wateredAnyThisCycle = false;

    // 箱子
    private ChestTakeController seedTake;
    private ChestTakeController foodTake;
    private FarmEatController eat;
    private FarmDepositController deposit;
    private int cropChestIdx = 0;
    private String takeCropName;

    // 缓存与黑名单
    private List<BlockPos> farmlandCache = null;
    private long farmlandCacheAt = -1;
    private final Map<Long, Long> blacklist = new HashMap<>(); // 耕地 → 解禁 tick
    private final Set<String> warnedOnce = new HashSet<>();
    private final Map<String, Long> noSeedsUntilMs = new HashMap<>();
    private long noFoodUntilMs = 0;

    private static final int BLACKLIST_TICKS = 20 * 60;      // 失败地块 1 分钟后重试
    private static final long NO_SEEDS_RETRY_MS = 5 * 60_000; // 种子箱空 5 分钟后重试
    private static final int PATH_MAX_NODES = 20000;

    /** zone 内固定蛇形顺序；进入区域时确定，处理期间不随玩家位置重新排序。 */
    private record ZoneTraversal(int zoneIndex, boolean alongX,
                                 boolean reverseRows, boolean reverseFirstRow) {}

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
        harvestBatchZones.clear();
        activeHarvestZone = -1;
        activePlantZone = -1;
        zoneTraversal = null;
        zoneTraversalFallback = false;
        pickupZoneIndex = -1;
        ignoredZoneDrops.clear();
        zonePickupRetries.clear();
        zonePickupTargetId = -1;
        pickupApproachTicks = 0;
        pickupWaitTicks = 0;
        pickupScanAnnounced = false;
        zonePickupEmptyConfirmations = 0;
        zonePickupConfirmWaitTicks = 0;
        zonePickupPass = 1;
        waterRetryAfterMs = 0;
        noFoodUntilMs = 0;
        wateringCycle = false;
        waterQueue.clear();
        canLoaded = false;
        wateredAnyThisCycle = false;
        farmlandCache = null;
        clearControllers();
        scanCooldown = 0;

        msg("§a自动种地已启动"
                + (cfg.waterers.isEmpty() ? "" : "（浇水按上次成功时间计算，每 "
                + cfg.waterIntervalMinutes + " 分钟一轮）"));
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
        foodTake = null;
        eat = null;
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
        wateringActionSent = false;
        waterSneakReady = false;
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
        boolean movementState = state == State.WALK;
        if (movementState && mc.currentScreen != null) {
            InputController.clear();
            InputController.apply(mc);
            return;
        }

        switch (state) {
            case SCAN -> tickScan(mc, player, cfg);
            case WALK -> tickWalk(mc, player);
            case HARVEST -> tickHarvest(mc, player, cfg);
            case PICKUP_ZONE -> tickZonePickup(mc, player, cfg);
            case PLANT -> tickPlant(mc, player, cfg);
            case TAKE_SEEDS -> tickTakeSeeds(mc, player, cfg);
            case TAKE_FOOD -> tickTakeFood(mc, player, cfg);
            case EAT -> tickEat(mc, player, cfg);
            case DEPOSIT -> tickDeposit(mc, player, cfg);
            case FILL_CAN -> tickFillCan(mc, player, cfg);
            case WATER -> tickWater(mc, player, cfg);
            default -> {}
        }

        InputController.apply(mc);
    }

    // ---------- SCAN：任务调度 ----------

    private void tickScan(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        // 0) 先保证饥饿值支持疾跑；低于阈值优先吃，背包没食物则去绑定箱补充。
        int hunger = player.getHungerManager().getFoodLevel();
        if (hunger < FarmEatController.EAT_BELOW) {
            if (FarmItems.hasFood(player, cfg)) {
                eat = new FarmEatController();
                enter(State.EAT);
                return;
            }
            if (cfg.food != null && cfg.foodChest != null
                    && System.currentTimeMillis() >= noFoodUntilMs) {
                walkTo(mc, player, cfg.foodChest.toBlockPos(), 3.2, State.TAKE_FOOD);
                return;
            }
            if (hunger < FarmEatController.SPRINT_MIN) {
                stop("§c饥饿值不足以疾跑，且拿不到食物；请检查 /farm bindfood 和 /farm bindfoodchest");
                return;
            }
            warnOnce("farm-food-missing", "§e饥饿值正在下降，但没有可用食物/食物箱，暂时继续运行");
        }

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
            warnOnce("inv-full-keep", "§e背包快满了，但都是种子/洒水壶/食物等保留物品，不会存入作物箱");
        }

        // 3) 扫描地块
        List<FarmScanner.Plot> allPlots = FarmScanner.classify(mc.world, cfg, farmland(mc, cfg));
        List<FarmScanner.Plot> plots = new java.util.ArrayList<>(allPlots);
        plots.removeIf(p -> isBlacklisted(p.farmland()));
        plots.sort(Comparator.comparingDouble(p ->
                p.farmland().getSquaredDistance(player.getX(), player.getY(), player.getZ())));

        // 3a) 已经开始种植某个 zone：只做完这个 zone，随后立刻清扫该 zone 的掉落物。
        if (activePlantZone >= 0) {
            List<FarmScanner.Plot> zonePlots = orderedZonePlots(plots, activePlantZone, cfg);
            for (FarmScanner.Plot p : zonePlots) {
                if (p.state() != FarmScanner.PlotState.EMPTY) continue;
                FarmConfig.Crop crop = cfg.crops.get(p.cropName());
                if (crop != null && crop.seedItemId != null && crop.seedName != null
                        && FarmItems.countSeeds(player, crop) > 0) {
                    beginPlot(mc, player, p, State.PLANT);
                    return;
                }
            }
            for (FarmScanner.Plot p : zonePlots) {
                if (p.state() != FarmScanner.PlotState.EMPTY) continue;
                FarmConfig.Crop crop = cfg.crops.get(p.cropName());
                if (crop != null && crop.seedChest != null
                        && System.currentTimeMillis() >= noSeedsUntilMs.getOrDefault(p.cropName(), 0L)) {
                    takeCropName = p.cropName();
                    walkTo(mc, player, crop.seedChest.toBlockPos(), 3.2, State.TAKE_SEEDS);
                    return;
                }
            }
            if (switchToZoneFallback(activePlantZone, cfg)) return;
            beginZonePickup(activePlantZone, "种植");
            return;
        }

        // 3b) 已经开始收获某个 zone：补种优先，然后继续收获；完成后只清扫该 zone。
        if (activeHarvestZone >= 0) {
            List<FarmScanner.Plot> zonePlots = orderedZonePlots(plots, activeHarvestZone, cfg);
            for (FarmScanner.Plot p : zonePlots) {
                if (p.state() != FarmScanner.PlotState.EMPTY) continue;
                FarmConfig.Crop crop = cfg.crops.get(p.cropName());
                if (crop != null && FarmItems.countSeeds(player, crop) > 0) {
                    beginPlot(mc, player, p, State.PLANT);
                    return;
                }
            }

            if ("use".equals(cfg.harvestMode)) {
                for (FarmScanner.Plot p : zonePlots) {
                    if (p.state() != FarmScanner.PlotState.MATURE) continue;
                    FarmConfig.Crop crop = cfg.crops.get(p.cropName());
                    if (crop == null || FarmItems.countSeeds(player, crop) > 0) continue;
                    if (crop.seedChest != null
                            && System.currentTimeMillis() >= noSeedsUntilMs.getOrDefault(p.cropName(), 0L)) {
                        takeCropName = p.cropName();
                        msg("§7zone #" + (activeHarvestZone + 1) + " 收获前补充种子: §e" + p.cropName());
                        walkTo(mc, player, crop.seedChest.toBlockPos(), 3.2, State.TAKE_SEEDS);
                        return;
                    }
                }
            }

            for (FarmScanner.Plot p : zonePlots) {
                if (p.state() == FarmScanner.PlotState.MATURE) {
                    beginPlot(mc, player, p, State.HARVEST);
                    return;
                }
            }

            // 成熟作物已经收完，再尽量补齐该 zone 的空地；拿不到种子也不能阻塞拾取。
            for (FarmScanner.Plot p : zonePlots) {
                if (p.state() != FarmScanner.PlotState.EMPTY) continue;
                FarmConfig.Crop crop = cfg.crops.get(p.cropName());
                if (crop != null && crop.seedChest != null
                        && System.currentTimeMillis() >= noSeedsUntilMs.getOrDefault(p.cropName(), 0L)) {
                    takeCropName = p.cropName();
                    walkTo(mc, player, crop.seedChest.toBlockPos(), 3.2, State.TAKE_SEEDS);
                    return;
                }
            }
            if (switchToZoneFallback(activeHarvestZone, cfg)) return;
            beginZonePickup(activeHarvestZone, "收获/种植");
            return;
        }

        // 3c) 种植优先：选中一个 zone 后，直到该 zone 完成并清扫掉落物前不切换区域。
        for (FarmScanner.Plot p : plots) {
            if (p.state() != FarmScanner.PlotState.EMPTY) continue;
            FarmConfig.Crop crop = cfg.crops.get(p.cropName());
            if (crop == null || crop.seedItemId == null || crop.seedName == null) {
                warnOnce("crop-" + p.cropName(),
                        "§e小区域作物「" + p.cropName() + "」还没定义种子（手持种子执行 /farm crop add "
                                + p.cropName() + "），先跳过");
                continue;
            }
            if (FarmItems.countSeeds(player, crop) > 0
                    || (crop.seedChest != null
                    && System.currentTimeMillis() >= noSeedsUntilMs.getOrDefault(p.cropName(), 0L))) {
                activePlantZone = p.zoneIndex();
                beginZoneTraversal(activePlantZone, player, cfg);
                msg("§7开始处理种植 zone #§e" + (activePlantZone + 1));
                return;
            }
        }

        // 3d) 建立收获批次，但实际严格按 zone 逐个处理、逐个清扫。
        if (harvestBatchZones.isEmpty()) {
            int[] cropCounts = new int[cfg.zones.size()];
            int[] matureCounts = new int[cfg.zones.size()];
            int[] actionableMatureCounts = new int[cfg.zones.size()];
            for (FarmScanner.Plot p : allPlots) {
                if (p.zoneIndex() < 0 || p.zoneIndex() >= cfg.zones.size()) continue;
                if (p.state() != FarmScanner.PlotState.EMPTY) cropCounts[p.zoneIndex()]++;
                if (p.state() == FarmScanner.PlotState.MATURE) matureCounts[p.zoneIndex()]++;
            }
            for (FarmScanner.Plot p : plots) {
                if (p.zoneIndex() >= 0 && p.zoneIndex() < cfg.zones.size()
                        && p.state() == FarmScanner.PlotState.MATURE) {
                    actionableMatureCounts[p.zoneIndex()]++;
                }
            }
            for (int i = 0; i < cfg.zones.size(); i++) {
                if (actionableMatureCounts[i] > 0 && matureCounts[i] * 2 >= cropCounts[i]) {
                    harvestBatchZones.add(i);
                }
            }
            if (!harvestBatchZones.isEmpty()) {
                msg("§7有 §e" + harvestBatchZones.size()
                        + " §7个 zone 达到 50% 成熟，将逐个收获并逐区拾取掉落物");
            }
        }

        if (!harvestBatchZones.isEmpty()) {
            for (FarmScanner.Plot p : plots) {
                if (p.state() == FarmScanner.PlotState.MATURE
                        && harvestBatchZones.contains(p.zoneIndex())) {
                    activeHarvestZone = p.zoneIndex();
                    beginZoneTraversal(activeHarvestZone, player, cfg);
                    msg("§7开始收获 zone #§e" + (activeHarvestZone + 1)
                            + "§7；本区完成后立即拾取本区掉落物");
                    return;
                }
            }
            // 达标 zone 中只剩被临时拉黑的地块，也要逐区结束，不能重新建立同一批次死循环。
            activeHarvestZone = harvestBatchZones.iterator().next();
            beginZoneTraversal(activeHarvestZone, player, cfg);
            return;
        }

        // 没有可立即处理的空地时给出配置提示。
        for (FarmScanner.Plot p : plots) {
            if (p.state() != FarmScanner.PlotState.EMPTY) continue;
            FarmConfig.Crop crop = cfg.crops.get(p.cropName());
            if (crop == null || crop.seedItemId == null || crop.seedName == null) continue;
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

    private void beginZoneTraversal(int zoneIndex, ClientPlayerEntity player, FarmConfig cfg) {
        if (zoneIndex < 0 || zoneIndex >= cfg.zones.size() || cfg.zones.get(zoneIndex).box == null) {
            zoneTraversal = null;
            zoneTraversalFallback = false;
            return;
        }
        FarmConfig.Region r = cfg.zones.get(zoneIndex).box;
        int width = r.maxX() - r.minX() + 1;
        int depth = r.maxZ() - r.minZ() + 1;
        boolean alongX = width >= depth;
        // 行从离玩家较近的一侧开始，第一行也从离玩家较近的一端开始。
        boolean reverseRows = alongX
                ? player.getZ() > (r.minZ() + r.maxZ()) * 0.5
                : player.getX() > (r.minX() + r.maxX()) * 0.5;
        boolean reverseFirstRow = alongX
                ? player.getX() > (r.minX() + r.maxX()) * 0.5
                : player.getZ() > (r.minZ() + r.maxZ()) * 0.5;
        zoneTraversal = new ZoneTraversal(zoneIndex, alongX, reverseRows, reverseFirstRow);
        zoneTraversalFallback = false;
    }

    /** 蛇形阶段固定排序；兜底阶段保留调用方现有的“离玩家最近”顺序。 */
    private List<FarmScanner.Plot> orderedZonePlots(List<FarmScanner.Plot> distanceSortedPlots,
                                                     int zoneIndex, FarmConfig cfg) {
        List<FarmScanner.Plot> result = new java.util.ArrayList<>();
        for (FarmScanner.Plot p : distanceSortedPlots) {
            if (p.zoneIndex() == zoneIndex) result.add(p);
        }
        if (zoneTraversalFallback || zoneTraversal == null
                || zoneTraversal.zoneIndex() != zoneIndex
                || zoneIndex < 0 || zoneIndex >= cfg.zones.size()) {
            return result;
        }
        FarmConfig.Region r = cfg.zones.get(zoneIndex).box;
        result.sort(Comparator.comparingLong(p -> snakeOrder(p.farmland(), r, zoneTraversal)));
        return result;
    }

    private static long snakeOrder(BlockPos pos, FarmConfig.Region r, ZoneTraversal traversal) {
        if (traversal.alongX()) {
            int width = r.maxX() - r.minX() + 1;
            int row = traversal.reverseRows() ? r.maxZ() - pos.getZ() : pos.getZ() - r.minZ();
            int rawColumn = pos.getX() - r.minX();
            boolean reverseColumn = traversal.reverseFirstRow() ^ ((row & 1) != 0);
            int column = reverseColumn ? width - 1 - rawColumn : rawColumn;
            return (long) row * width + column;
        }
        int depth = r.maxZ() - r.minZ() + 1;
        int row = traversal.reverseRows() ? r.maxX() - pos.getX() : pos.getX() - r.minX();
        int rawColumn = pos.getZ() - r.minZ();
        boolean reverseColumn = traversal.reverseFirstRow() ^ ((row & 1) != 0);
        int column = reverseColumn ? depth - 1 - rawColumn : rawColumn;
        return (long) row * depth + column;
    }

    /** 蛇形结束后刷新一次扫描，并用旧的最近目标策略复查；同时给失败地块一次重试机会。 */
    private boolean switchToZoneFallback(int zoneIndex, FarmConfig cfg) {
        if (zoneTraversalFallback) return false;
        zoneTraversalFallback = true;
        farmlandCache = null;
        if (zoneIndex >= 0 && zoneIndex < cfg.zones.size() && cfg.zones.get(zoneIndex).box != null) {
            FarmConfig.Region r = cfg.zones.get(zoneIndex).box;
            blacklist.keySet().removeIf(packed -> r.contains(BlockPos.fromLong(packed), 1));
        }
        msg("§7zone #" + (zoneIndex + 1) + " 蛇形处理完成，切换最近目标策略进行遗漏复查");
        return true;
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
            walkFailed(next, player);
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
                walkFailed(afterWalk, player);
                return;
            }
            msg("§e寻路连续 3 秒没有水平位移，正在重新规划（" + pathRetries + "/3）");
            List<BlockPos> path = Pathfinder.findNear(mc.world, player.getBlockPos(),
                    walkGoal, walkReach, PATH_MAX_NODES);
            if (path == null) {
                pathExec = null;
                InputController.clear();
                walkFailed(afterWalk, player);
                return;
            }
            pathExec = new PathExecutor(path);
            ticksInState = 0;
        }
    }

    private void walkFailed(State next, ClientPlayerEntity player) {
        switch (next) {
            case HARVEST, PLANT -> blacklistPlot("走不过去");
            case TAKE_SEEDS -> {
                noSeeds(takeCropName, "走不到种子箱");
                enter(State.SCAN);
            }
            case TAKE_FOOD -> {
                foodUnavailable(player, "走不到食物箱");
                if (isRunning()) enter(State.SCAN);
            }
            case DEPOSIT -> nextCropChestOrStop("作物箱走不到");
            case PICKUP_ZONE -> {
                if (zonePickupTargetId >= 0) {
                    int retries = zonePickupRetries.merge(zonePickupTargetId, 1, Integer::sum);
                    if (retries >= 3) ignoredZoneDrops.add(zonePickupTargetId);
                }
                zonePickupTargetId = -1;
                pickupApproachTicks = 0;
                pickupWaitTicks = 0;
                enter(State.PICKUP_ZONE);
                graceTicks = 2;
            }
            case WATER -> {
                waterQueue.poll();
                canUsesLeftForWaterer = 0;
                canLoaded = false;
                msg("§e走不到浇水器，跳过这个");
                enter(State.SCAN);
            }
            case FILL_CAN -> {
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
            // 挖掉了 / 右键收获后状态变了：继续全局批次，不逐株检查掉落物。
            mc.interactionManager.cancelBlockBreaking();
            InputController.clear();
            enter(State.SCAN);
            return;
        }

        if (!FarmLookController.smoothFace(player, Vec3d.ofCenter(plotCrop))) return;

        if ("use".equals(cfg.harvestMode)) {
            if (graceTicks > 0) {
                graceTicks--;
                return;
            }
            if (crop != null && actionAttempts < 4) {
                // 有种子就优先走插件的“右键收获 + 原地复种”。
                if (!FarmItems.isSeedOf(crop, player.getMainHandStack())) {
                    if (FarmItems.selectMatching(mc, player, s -> FarmItems.isSeedOf(crop, s))) {
                        graceTicks = 2; // 等背包/快捷栏同步后再交互
                        return;
                    }
                    // 种子耗尽不能阻塞收获：本地块立刻退回左键，空地稍后再统一补种。
                    warnOnce("harvest-fallback-" + plotCropName,
                            "§e「" + plotCropName + "」种子不足，先左键收获，之后拿到种子再补种");
                    tickBreakHarvest(mc, player);
                    return;
                }
                actionAttempts++;
                BlockHitResult hit = new BlockHitResult(
                        Vec3d.ofCenter(plotCrop), Direction.UP, plotCrop, false);
                mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
                player.swingHand(Hand.MAIN_HAND);
                graceTicks = 12;
                return;
            }
            warnOnce("harvest-use-failed-" + plotCropName,
                    "§e「" + plotCropName + "」拿种子右键没有响应，当前地块改用左键收获");
            tickBreakHarvest(mc, player);
        } else {
            tickBreakHarvest(mc, player);
        }
    }

    private void tickBreakHarvest(MinecraftClient mc, ClientPlayerEntity player) {
        if (!breakStarted) {
            mc.interactionManager.attackBlock(plotCrop, Direction.UP);
            breakStarted = true;
        } else {
            mc.interactionManager.updateBlockBreakingProgress(plotCrop, Direction.UP);
        }
        player.swingHand(Hand.MAIN_HAND);
        if (ticksInState > 100) {
            mc.interactionManager.cancelBlockBreaking();
            blacklistPlot("挖不动");
        }
    }

    private void beginZonePickup(int zoneIndex, String completedWork) {
        pickupZoneIndex = zoneIndex;
        ignoredZoneDrops.clear();
        zonePickupRetries.clear();
        zonePickupTargetId = -1;
        pickupApproachTicks = 0;
        pickupWaitTicks = 0;
        pickupScanAnnounced = false;
        zonePickupEmptyConfirmations = 0;
        zonePickupConfirmWaitTicks = 0;
        zonePickupPass = 1;
        msg("§a" + completedWork + " zone #" + (zoneIndex + 1) + " 完成，开始拾取本区掉落物");
        enter(State.PICKUP_ZONE);
        graceTicks = 8;
    }

    /** 完成一个 zone 后，只逐个寻路捡取该 zone 内的掉落物。 */
    private void tickZonePickup(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        if (graceTicks > 0) {
            graceTicks--;
            return;
        }

        if (!pickupScanAnnounced) {
            int detected = zoneDrops(mc, cfg).size();
            msg("§7zone #" + (pickupZoneIndex + 1) + " 掉落物拾取：客户端检测到 §e"
                    + detected + " §7个 ItemEntity");
            pickupScanAnnounced = true;
        }

        List<ItemEntity> allVisibleDrops = zoneDrops(mc, cfg, false);
        if (!allVisibleDrops.isEmpty()) {
            zonePickupEmptyConfirmations = 0;
            zonePickupConfirmWaitTicks = 0;
        }

        // 已选中的目标只要还存在就持续处理，不因走过后别的物品变近而切换。
        ItemEntity drop = currentZoneDrop(mc);
        if (drop == null) drop = nearestZoneDrop(mc, player, cfg);
        if (drop == null) {
            InputController.clear();

            // 三次接近失败只代表本轮暂时跳过。结束前看到它仍存活，就清除跳过表强制再捡一轮。
            if (!allVisibleDrops.isEmpty()) {
                ignoredZoneDrops.clear();
                zonePickupRetries.clear();
                zonePickupPass++;
                msg("§e本区复查仍发现 " + allVisibleDrops.size()
                        + " 个掉落物，开始第 " + zonePickupPass + " 轮拾取，不会直接跳过");
                graceTicks = 10;
                return;
            }

            // 掉落实体可能延迟生成或延迟同步，必须间隔复扫连续 4 次为空才确认完成。
            if (zonePickupConfirmWaitTicks > 0) {
                zonePickupConfirmWaitTicks--;
                return;
            }
            zonePickupEmptyConfirmations++;
            if (zonePickupEmptyConfirmations >= 4) {
                msg("§a zone #" + (pickupZoneIndex + 1) + " 已连续复查 4 次无掉落物，确认拾取完成");
                finishZonePickup();
                return;
            }
            zonePickupConfirmWaitTicks = 10;
            return;
        }
        if (zonePickupTargetId != drop.getId()) {
            zonePickupTargetId = drop.getId();
            pickupApproachTicks = 0;
            pickupWaitTicks = 0;
        }
        double dx = drop.getX() - player.getX();
        double dz = drop.getZ() - player.getZ();
        double horizontalSq = dx * dx + dz * dz;

        if (horizontalSq > 1.2 * 1.2) {
            // 远距离先精确寻路到物品附近，比普通交互目标使用更小的到达半径。
            pickupApproachTicks = 0;
            pickupWaitTicks = 0;
            walkTo(mc, player, drop.getBlockPos(), 1.25, State.PICKUP_ZONE);
            return;
        }

        // 锁定第一次接近方向，直线穿过物品位置；走过后绝不立刻反向追踪，避免原地绕圈。
        InputController.sprint = false;
        InputController.jump = false;
        if (pickupApproachTicks == 0) {
            pickupApproachYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        }
        if (pickupApproachTicks < 14) {
            float yawError = FarmLookController.smoothYaw(player, pickupApproachYaw, 8.0f);
            InputController.forward = Math.abs(yawError) < 24.0f;
            if (InputController.forward) pickupApproachTicks++;
            return;
        }

        InputController.forward = false;
        pickupWaitTicks++;
        // 穿过目标后等待服务器确认；实体仍存在才重试，最多三轮后忽略。
        if (pickupWaitTicks > 20) {
            int retries = zonePickupRetries.merge(drop.getId(), 1, Integer::sum);
            if (retries >= 3) ignoredZoneDrops.add(drop.getId());
            zonePickupTargetId = -1;
            pickupApproachTicks = 0;
            pickupWaitTicks = 0;
            enter(State.PICKUP_ZONE);
            graceTicks = 2;
        }
    }

    private ItemEntity currentZoneDrop(MinecraftClient mc) {
        if (zonePickupTargetId < 0 || ignoredZoneDrops.contains(zonePickupTargetId)) return null;
        Entity entity = mc.world.getEntityById(zonePickupTargetId);
        return entity instanceof ItemEntity item && item.isAlive() ? item : null;
    }

    private ItemEntity nearestZoneDrop(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        return zoneDrops(mc, cfg, true).stream()
                .min(Comparator.comparingDouble(player::squaredDistanceTo))
                .orElse(null);
    }

    private List<ItemEntity> zoneDrops(MinecraftClient mc, FarmConfig cfg) {
        return zoneDrops(mc, cfg, true);
    }

    private List<ItemEntity> zoneDrops(MinecraftClient mc, FarmConfig cfg, boolean excludeTemporarilyIgnored) {
        List<ItemEntity> drops = new java.util.ArrayList<>();
        if (pickupZoneIndex < 0 || pickupZoneIndex >= cfg.zones.size()) return drops;
        FarmConfig.Zone zone = cfg.zones.get(pickupZoneIndex);
        if (zone == null || zone.box == null) return drops;
        FarmConfig.Region r = zone.box;
        // Region 的 max 坐标是包含端点的，而 Box 的 max 是排他的；max + 2 才能完整包含外扩一格。
        Box area = new Box(r.minX() - 1.0, r.minY() - 1.0, r.minZ() - 1.0,
                r.maxX() + 2.0, r.maxY() + 2.0, r.maxZ() + 2.0);
        for (ItemEntity entity : mc.world.getEntitiesByClass(ItemEntity.class, area,
                item -> item.isAlive()
                        && (!excludeTemporarilyIgnored || !ignoredZoneDrops.contains(item.getId())))) {
            drops.add(entity);
        }
        return drops;
    }

    private void finishZonePickup() {
        InputController.clear();
        if (pickupZoneIndex == activeHarvestZone) {
            harvestBatchZones.remove(activeHarvestZone);
            activeHarvestZone = -1;
        }
        if (pickupZoneIndex == activePlantZone) {
            activePlantZone = -1;
        }
        zoneTraversal = null;
        zoneTraversalFallback = false;
        pickupZoneIndex = -1;
        zonePickupTargetId = -1;
        pickupApproachTicks = 0;
        pickupWaitTicks = 0;
        pickupScanAnnounced = false;
        zonePickupEmptyConfirmations = 0;
        zonePickupConfirmWaitTicks = 0;
        zonePickupPass = 1;
        ignoredZoneDrops.clear();
        zonePickupRetries.clear();
        enter(State.SCAN);
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

        Vec3d plantTarget = new Vec3d(plotFarmland.getX() + 0.5, plotFarmland.getY() + 1.0,
                plotFarmland.getZ() + 0.5);
        if (!FarmLookController.smoothFace(player, plantTarget)) return;
        actionAttempts++;
        BlockHitResult hit = new BlockHitResult(
                plantTarget,
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

    private void tickTakeFood(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        if (cfg.food == null || cfg.foodChest == null) {
            enter(State.SCAN);
            return;
        }
        if (foodTake == null) {
            foodTake = new ChestTakeController(cfg.foodChest.toBlockPos(),
                    stack -> FarmItems.isFood(cfg, stack), "食物箱", Math.max(1, cfg.foodStacksPerTrip));
        }
        switch (foodTake.tick(mc, player)) {
            case WORKING -> {}
            case DONE -> {
                foodTake = null;
                noFoodUntilMs = 0;
                eat = new FarmEatController();
                enter(State.EAT);
            }
            case FAILED -> {
                String why = foodTake.getFailReason();
                foodTake = null;
                foodUnavailable(player, why);
                if (isRunning()) enter(State.SCAN);
            }
        }
    }

    private void tickEat(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        if (eat == null) eat = new FarmEatController();
        switch (eat.tick(mc, player, cfg)) {
            case WORKING -> {}
            case DONE -> {
                eat = null;
                enter(State.SCAN);
            }
            case FAILED -> {
                String why = eat.getFailReason();
                eat = null;
                foodUnavailable(player, why);
                if (isRunning()) enter(State.SCAN);
            }
        }
    }

    private void foodUnavailable(ClientPlayerEntity player, String why) {
        noFoodUntilMs = System.currentTimeMillis() + 5 * 60_000L;
        if (player.getHungerManager().getFoodLevel() < FarmEatController.SPRINT_MIN) {
            stop("§c" + why + "，饥饿值已不足以疾跑，自动种地停止");
        } else {
            msg("§e拿不到食物（" + why + "），5 分钟后重试");
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
        long now = System.currentTimeMillis();
        if (now < waterRetryAfterMs) return false;
        return cfg.lastWaterTimeMs <= 0
                || now - cfg.lastWaterTimeMs >= cfg.waterIntervalMinutes * 60_000L;
    }

    /**
     * 推进浇水流程一步（在 SCAN 里调用）。
     * 返回 true = 已安排走路/动作；false = 浇水流程结束/不可行，继续其他任务。
     */
    private boolean stepWatering(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        if (!wateringCycle) {
            wateringCycle = true;
            waterQueue.clear();
            waterQueue.addAll(cfg.waterers);
            canUsesLeftForWaterer = 0;
            canLoaded = false;
            wateredAnyThisCycle = false;
            msg("§7开始浇水巡回（" + waterQueue.size() + " 个浇水器，每个 "
                    + Math.max(1, cfg.canUsesPerWaterer) + " 壶）");
        }
        if (waterQueue.isEmpty()) {
            if (!wateredAnyThisCycle) {
                msg("§e本轮没有任何浇水器确认执行成功，5 分钟后重试");
                abortWatering();
                return false;
            }
            wateringCycle = false;
            canLoaded = false;
            cfg.lastWaterTimeMs = System.currentTimeMillis();
            FarmConfig.save();
            msg("§a浇水完成，" + cfg.waterIntervalMinutes + " 分钟后再来一轮");
            return false;
        }
        if (canUsesLeftForWaterer <= 0) {
            canUsesLeftForWaterer = Math.max(1, cfg.canUsesPerWaterer);
        }

        FarmConfig.WatererTarget waterer = waterQueue.peek();
        if (canLoaded) {
            walkTo(mc, player, waterer.pathPos(), 3.2, State.WATER);
            return true;
        }
        if (!FarmItems.hasWateringCan(player, cfg)) {
            msg("§c背包里没有已绑定的洒水壶，本轮浇水跳过（手持洒水壶执行 /farm bindcan）");
            abortWatering();
            return false;
        }
        if (cfg.waterSource == null) {
            msg("§c没绑水源（对着水执行 /farm bindwater），本轮浇水跳过");
            abortWatering();
            return false;
        }
        walkTo(mc, player, cfg.waterSource.toBlockPos(), 3.5, State.FILL_CAN);
        return true;
    }

    /** 本轮浇水放弃：不伪造成功时间，仅在本次运行中延迟 5 分钟重试。 */
    private void abortWatering() {
        wateringCycle = false;
        waterQueue.clear();
        canUsesLeftForWaterer = 0;
        canLoaded = false;
        wateredAnyThisCycle = false;
        waterRetryAfterMs = System.currentTimeMillis() + 5 * 60_000L;
    }

    private void tickFillCan(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        if (wateringActionSent) {
            if (graceTicks > 0) {
                graceTicks--;
                return;
            }
            // 插件壶可能改名/改模型，也可能只在服务端记录水量；点击发出后均继续。
            FarmItems.rememberWateringCanState(cfg, player.getMainHandStack());
            canLoaded = true;
            enter(State.SCAN);
            return;
        }
        if (graceTicks > 0) {
            graceTicks--;
            return;
        }
        if (actionAttempts >= 6) {
            msg("§c洒水壶在水源无法执行装水（确认已 /farm bindcan，且 /farm bindwater 指向水）");
            abortWatering();
            enter(State.SCAN);
            return;
        }
        if (cfg.waterSource == null) {
            abortWatering();
            enter(State.SCAN);
            return;
        }
        // 插件洒水壶换到主手（换手后等 2 tick）
        if (!FarmItems.isWateringCanInHand(player, cfg)) {
            if (!FarmItems.selectMatching(mc, player, s -> FarmItems.isWateringCan(cfg, s))) {
                msg("§c背包里没有已绑定的洒水壶");
                abortWatering();
                enter(State.SCAN);
                return;
            }
            graceTicks = 2;
            return;
        }

        if (!FarmLookController.smoothFace(player, Vec3d.ofCenter(cfg.waterSource.toBlockPos()))) return;
        actionAttempts++;
        // 插件按玩家视线处理洒水壶装水，因此发送“使用物品”而不是原版桶逻辑。
        mc.interactionManager.interactItem(player, Hand.MAIN_HAND);
        player.swingHand(Hand.MAIN_HAND);
        wateringActionSent = true;
        graceTicks = 10;
    }

    private void tickWater(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        FarmConfig.WatererTarget waterer = waterQueue.peek();
        if (waterer == null) {
            enter(State.SCAN);
            return;
        }

        if (wateringActionSent) {
            InputController.sneak = true;
            if (graceTicks > 0) {
                graceTicks--;
                return;
            }
            FarmItems.rememberWateringCanState(cfg, player.getMainHandStack());
            wateredAnyThisCycle = true;
            canLoaded = false;
            canUsesLeftForWaterer--;
            if (canUsesLeftForWaterer <= 0) waterQueue.poll();
            enter(State.SCAN); // 下一次先回水源装壶，再继续当前/下一个浇水器
            return;
        }
        if (graceTicks > 0) {
            graceTicks--;
            return;
        }

        // 插件可能弹容器 GUI，关掉继续（不动玩家自己开的聊天等界面）
        if (player.currentScreenHandler != null
                && player.currentScreenHandler != player.playerScreenHandler) {
            player.closeHandledScreen();
        }

        if (actionAttempts >= 5) {
            msg("§e无法右键虚拟浇水器，跳过这个（目标 " + waterer + "）");
            waterQueue.poll();
            canUsesLeftForWaterer = 0;
            canLoaded = false;
            enter(State.SCAN);
            return;
        }
        if (!canLoaded) {
            enter(State.SCAN);
            return;
        }
        // 装好水的插件洒水壶换到主手
        if (!FarmItems.isWateringCanInHand(player, cfg)) {
            if (!FarmItems.selectMatching(mc, player, s -> FarmItems.isWateringCan(cfg, s))) {
                canLoaded = false;
                enter(State.SCAN);
                return;
            }
            graceTicks = 2;
            return;
        }

        // 插件要求 Shift+右键加水：先按住潜行 2 tick，让服务器收到姿态变化后再交互。
        if (!waterSneakReady) {
            InputController.sneak = true;
            waterSneakReady = true;
            graceTicks = 2;
            return;
        }
        InputController.sneak = true;

        if (!FarmLookController.smoothFace(player, waterer.targetPos())) return;
        actionAttempts++;
        Entity entity = findWatererEntity(mc, player, waterer);
        if (entity != null) {
            mc.interactionManager.interactEntity(player, entity, Hand.MAIN_HAND);
        } else {
            // 某些插件的悬浮物没有客户端可交互实体，由服务器按视线自行检测。
            mc.interactionManager.interactItem(player, Hand.MAIN_HAND);
        }
        player.swingHand(Hand.MAIN_HAND);
        wateringActionSent = true;
        graceTicks = 10;
    }

    private static Entity findWatererEntity(MinecraftClient mc, ClientPlayerEntity player,
                                             FarmConfig.WatererTarget target) {
        if (!target.entityTarget) return null;
        Vec3d center = target.targetPos();
        Box search = new Box(center, center).expand(2.5);
        return mc.world.getOtherEntities(player, search, entity -> {
                    if (target.entityType != null
                            && !target.entityType.equals(Registries.ENTITY_TYPE.getId(entity.getType()).toString())) {
                        return false;
                    }
                    return target.entityCustomName == null
                            || (entity.getCustomName() != null
                            && target.entityCustomName.equals(entity.getCustomName().getString()));
                }).stream()
                .min(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(center)))
                .orElse(null);
    }

    // ---------- 工具 ----------

    /** 背包里是否有可以存进作物箱的东西（种子/洒水壶/食物/保留格除外）。 */
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
        sb.append("扫描范围: §e所有小区域并集§r");
        if (farmlandCache != null) sb.append(" §7(耕地 ").append(farmlandCache.size()).append(" 块)");
        sb.append("\n");
        sb.append("维度: ").append(cfg.dim != null ? cfg.dim : "§c-").append("§r\n");
        if (!harvestBatchZones.isEmpty()) {
            sb.append("待处理收获区域: §a").append(harvestBatchZones.size()).append(" 个 zone§r\n");
        }
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
        sb.append("洒水壶: ").append(cfg.wateringCan != null ? cfg.wateringCan : "§c未绑定").append("§r\n");
        sb.append("水源: ").append(cfg.waterSource != null ? cfg.waterSource : "§c未绑定").append("§r\n");
        sb.append("浇水间隔: ").append(cfg.waterIntervalMinutes).append(" 分钟，每个浇水器 ")
                .append(Math.max(1, cfg.canUsesPerWaterer)).append(" 壶");
        if (cfg.lastWaterTimeMs > 0 && !cfg.waterers.isEmpty()) {
            long nextIn = cfg.waterIntervalMinutes * 60_000L
                    - (System.currentTimeMillis() - cfg.lastWaterTimeMs);
            sb.append("§7（下一轮约 ").append(Math.max(0, nextIn / 60_000)).append(" 分钟后）");
        }
        sb.append("§r\n");
        sb.append("食物: ").append(cfg.food != null ? cfg.food : "§c未绑定")
                .append(" 食物箱=").append(cfg.foodChest != null ? cfg.foodChest : "§c未绑定")
                .append("§r\n");
        sb.append("收获方式: ").append(cfg.harvestMode).append("§r");
        return sb.toString();
    }
}
