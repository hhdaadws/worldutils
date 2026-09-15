package com.worldutils.farm;

import com.worldutils.bot.Bot;
import com.worldutils.bot.InputController;
import com.worldutils.bot.PathExecutor;
import com.worldutils.bot.Pathfinder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
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
 *   → WALK（A* 走过去）→ HARVEST/PICKUP_ZONE | PLANT | TAKE_SEEDS | DEPOSIT | WATER
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
        WATER,       // 在绑定浇水点执行浇水序列（低头3次右键 + 抬头shift右键1次）
    }

    private State state = State.IDLE;
    private int ticksInState = 0;
    private int scanCooldown = 0;
    private long tickCounter = 0;

    // 走路
    private PathExecutor pathExec;
    private State afterWalk = State.SCAN;
    private BlockPos walkGoal;   // 最终目标（可能在未加载区块里）
    private double walkReach;
    private float walkPitch = 0.0f; // 本次行走保持的俯仰角（收菜移动=30°，其余=0 平视）
    private int pathRetries = 0;
    private int walkTotalTicks = 0; // 跨重规划累计的总行走时间
    private boolean walkApproaching = false; // 正在朝未加载/超远目标分段逼近
    private int approachStalls = 0;          // 逼近时连续无法再前进的次数
    private boolean walkApproachAnnounced = false; // 逼近提示只发一次

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
    /** 距物品多近改为锁 yaw 直走（默认 2.0，寻路失败时对当前目标临时放大）。 */
    private double pickupDirectRange = 2.0;
    private int pickupApproachTicksNeeded = 14;
    private boolean pickupScanAnnounced = false;
    private int zonePickupEmptyConfirmations = 0;
    private int zonePickupConfirmWaitTicks = 0;
    private int zonePickupPass = 1;

    // 浇水
    private long waterRetryAfterMs = 0;
    private boolean wateringCycle = false;
    private final ArrayDeque<FarmConfig.WatererTarget> waterQueue = new ArrayDeque<>();
    private int waterClicksDone = 0;        // 本浇水点已完成的"低头右键"次数
    private int waterClicksTarget = 3;      // 本浇水点计划的低头右键次数（每点随机 0~3）
    private boolean waterSneakReady = false; // 抬头 shift 姿态已保持够 2 tick
    private boolean wateredAnyThisCycle = false;
    private boolean waterNowRequested = false; // /farm water 手动触发：下次 SCAN 立即浇一轮

    // 箱子
    private ChestTakeController seedTake;
    private ChestTakeController foodTake;
    private FarmEatController eat;
    private FarmDepositController deposit;
    // 当前存物目标：作物名（null=全局箱，按名分拣的兜底）与箱子坐标
    private String depositCropName = null;
    private BlockPos depositChestPos = null;
    // 已知装满的箱（按坐标，会话内记住，之后存物直接跳过；暂停重试/手动启动时重置）
    private final Set<Long> fullDepositChests = new HashSet<>();
    // 本次存物行程内走不到/打不开而临时跳过的箱（存物成功后清空）
    private final Set<Long> skipDepositChestsThisTrip = new HashSet<>();
    private String takeCropName;

    // 缓存与黑名单
    private List<BlockPos> farmlandCache = null;
    private long farmlandCacheAt = -1;
    private final Map<Long, Long> blacklist = new HashMap<>(); // 耕地 → 解禁 tick
    private final Map<Integer, Long> zoneBlockedUntil = new HashMap<>(); // 整区走不过去 → 解禁 tick
    private int consecutiveWalkFails = 0;
    // 侦察未加载区块
    private long scoutCooldownUntil = 0;
    private BlockPos announcedScoutTarget = null;
    private final Set<String> warnedOnce = new HashSet<>();
    private final Map<String, Long> noSeedsUntilMs = new HashMap<>();
    private long noFoodUntilMs = 0;
    private long pausedUntilMs = 0; // 出错暂停到期时间（永不停止，只暂停重试）
    private int deadTicks = 0;      // 死亡计时（节流重生请求）

    // fast 模式（收菜不停步）
    private final Map<Long, Long> fastFiredAt = new HashMap<>(); // 已出手作物 → 出手 tick（等服务器确认，防连点同一株）
    private BlockPos mediumAimCrop = null; // medium 模式当前正在对准的目标作物（粘性，防止指针在多株间来回甩）
    private int fastActionGrace = 0; // 出手/换手后的间隔 tick

    private static final int BLACKLIST_TICKS = 20 * 60;      // 失败地块 1 分钟后重试
    private static final long NO_SEEDS_RETRY_MS = 5 * 60_000; // 种子箱空 5 分钟后重试
    private static final int PATH_MAX_NODES = 20000;
    private static final long HALT_RETRY_MS = 30_000;         // 出错后 30 秒自动重试
    private static final long DAMAGE_PAUSE_MS = 10_000;        // 受伤后暂停 10 秒
    private static final double FAST_REACH = 3.0;              // fast 模式出手距离（眼睛到作物中心）
    private static final int FAST_FIRE_COOLDOWN_TICKS = 15;   // fast 模式同一株的重试间隔

    /** zone 内遍历方式：进入区域时选定，处理期间不随玩家位置重新排序。 */
    private enum TraversalMode {
        SNAKE,     // 蛇形逐行
        DIAGONAL,  // 斜向条带扫（45°）
        SPIRAL,    // 由外圈向内圈
        SPLIT,     // 隔行跳扫：先奇数行再偶数行（看起来像人挑着收）
        ROUTE      // 按当前实际目标规划的贪心最短路线（harvestStrategy=route）
    }

    private record ZoneTraversal(int zoneIndex, TraversalMode mode, boolean alongX,
                                 boolean reverseRows, boolean reverseFirstRow, boolean diagFlip) {}

    /** ROUTE 模式：耕地坐标 → 路线序号（进区时规划一次，收获与补种共用）。 */
    private Map<Long, Integer> routeOrder = null;

    private final java.util.Random traversalRandom = new java.util.Random();

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
        if (!com.worldutils.license.LicenseManager.isAuthorized()) {
            msg("§c未授权，无法使用。请向作者索取验证码后执行 §e/farm license <6位码>");
            return;
        }
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
        zoneBlockedUntil.clear();
        consecutiveWalkFails = 0;
        scoutCooldownUntil = 0;
        announcedScoutTarget = null;
        warnedOnce.clear();
        noSeedsUntilMs.clear();
        depositCropName = null;
        depositChestPos = null;
        fullDepositChests.clear();       // 手动启动时重新检查所有箱（可能已被清空）
        skipDepositChestsThisTrip.clear();
        harvestBatchZones.clear();
        activeHarvestZone = -1;
        activePlantZone = -1;
        zoneTraversal = null;
        routeOrder = null;
        zoneTraversalFallback = false;
        pickupZoneIndex = -1;
        ignoredZoneDrops.clear();
        zonePickupRetries.clear();
        zonePickupTargetId = -1;
        pickupApproachTicks = 0;
        pickupWaitTicks = 0;
        pickupDirectRange = 2.0;
        pickupApproachTicksNeeded = 14;
        pickupScanAnnounced = false;
        zonePickupEmptyConfirmations = 0;
        zonePickupConfirmWaitTicks = 0;
        zonePickupPass = 1;
        waterRetryAfterMs = 0;
        noFoodUntilMs = 0;
        pausedUntilMs = 0;
        deadTicks = 0;
        fastFiredAt.clear();
        fastActionGrace = 0;
        mediumAimCrop = null;
        com.worldutils.util.DamageWatch.reset(mc.player);
        wateringCycle = false;
        waterQueue.clear();
        waterClicksDone = 0;
        wateredAnyThisCycle = false;
        farmlandCache = null;
        clearControllers();
        scanCooldown = 0;
        InputController.disablePauseOnLostFocus(mc);

        msg("§a自动种地已启动"
                + (cfg.waterers.isEmpty() ? "" : "（浇水按上次成功时间计算，每 "
                + cfg.waterIntervalMinutes + " 分钟一轮）"));
        enter(State.SCAN);
    }

    public void stop(String reason) {
        MinecraftClient mc = MinecraftClient.getInstance();
        state = State.IDLE;
        pausedUntilMs = 0;
        clearControllers();
        InputController.releaseAll(mc);
        InputController.restorePauseOnLostFocus(mc);
        if (mc.interactionManager != null) {
            mc.interactionManager.cancelBlockBreaking();
        }
        if (reason != null) {
            msg(reason);
        }
    }

    /**
     * 出错时不停止：打印原因、暂停 30 秒后自动回 SCAN 重试。
     * 只有按 K 键或 /farm stop 才真正停止。
     */
    private void softHalt(String reason) {
        MinecraftClient mc = MinecraftClient.getInstance();
        msg("§e" + reason + "§7（不会停止，" + (HALT_RETRY_MS / 1000)
                + " 秒后自动重试；按 K 或 /farm stop 可手动停止）");
        pausedUntilMs = System.currentTimeMillis() + HALT_RETRY_MS;
        clearControllers();
        InputController.releaseAll(mc);
        if (mc.interactionManager != null) {
            mc.interactionManager.cancelBlockBreaking();
        }
    }

    /** 断线等场景的强制复位（不发消息）。 */
    public void hardStop() {
        state = State.IDLE;
        pausedUntilMs = 0;
        clearControllers();
        InputController.clear();
        InputController.restorePauseOnLostFocus(MinecraftClient.getInstance());
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
        waterClicksDone = 0;
        waterSneakReady = false;
        InputController.clear();
    }

    // ---------- 主循环 ----------

    public void tick(MinecraftClient mc) {
        if (state == State.IDLE) return;
        if (!com.worldutils.license.LicenseManager.isAuthorized()) {
            stop("§c授权已到期，已停止。请用 /farm license <码> 重新激活");
            return;
        }

        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            InputController.clear();
            return;
        }
        // 死亡不停止：自动重生后继续（每秒请求一次重生）
        if (!player.isAlive()) {
            InputController.clear();
            InputController.apply(mc);
            if (deadTicks++ % 20 == 0) {
                player.requestRespawn();
                if (mc.currentScreen != null) mc.setScreen(null);
            }
            return;
        }
        deadTicks = 0;
        // 受伤：公屏发“?”，暂停 10 秒再继续（每 tick 检测以跟踪血量）
        if (com.worldutils.util.DamageWatch.checkDamaged(player) && pausedUntilMs == 0) {
            Bot.sendPublicChat(mc, "?");
            msg("§e受到伤害，暂停 " + (DAMAGE_PAUSE_MS / 1000) + " 秒后继续");
            pausedUntilMs = System.currentTimeMillis() + DAMAGE_PAUSE_MS;
            clearControllers();
            InputController.releaseAll(mc);
            if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
            return;
        }
        // 出错后的暂停期：不停止，等冷却结束自动回到 SCAN 继续
        if (pausedUntilMs > 0) {
            if (System.currentTimeMillis() < pausedUntilMs) {
                InputController.clear();
                InputController.apply(mc);
                return;
            }
            pausedUntilMs = 0;
            // 暂停多为"所有箱满"等待清空导致：重试时给满箱记录一次翻身机会，重新检查
            fullDepositChests.clear();
            skipDepositChestsThisTrip.clear();
            msg("§7自动重试…");
            enter(State.SCAN);
        }

        tickCounter++;
        ticksInState++;
        FarmConfig cfg = FarmConfig.get();

        // 注意：不再因玩家打开界面/鼠标离开游戏而暂停——按需求全程运行，
        // 只有按 K 键或 /farm stop 才停止。各交互状态自身会关掉插件弹出的容器 GUI。

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
            case WATER -> tickWater(mc, player, cfg);
            default -> {}
        }

        // 蹲下规则按"位置"而非状态：人在正在处理的 zone 内 → 全程蹲住（含 SCAN/WALK 的
        // 间隙，不会一蹲一站闪烁）；人在 zone 外（跨 zone 移动、拿种子往返）→ 站立。
        // 捡掉落物(PICKUP_ZONE)按需求始终站立。
        if (shouldCrouch(player, cfg)) {
            InputController.sneak = true;
            InputController.sprint = false; // 潜行与疾跑互斥
        }
        InputController.apply(mc);
    }

    /** 是否应保持蹲下：正在处理某 zone 且玩家身处该 zone 范围内（外扩 1 格），拾取除外。 */
    private boolean shouldCrouch(ClientPlayerEntity player, FarmConfig cfg) {
        if (cfg.isSpeedy()) return false; // medium/fast 模式：种植/收获全程取消下蹲
        int zoneIdx = activeHarvestZone >= 0 ? activeHarvestZone : activePlantZone;
        if (zoneIdx < 0 || zoneIdx >= cfg.zones.size()) return false;
        // 捡掉落物及走向捡取点：站立
        if (state == State.PICKUP_ZONE
                || (state == State.WALK && afterWalk == State.PICKUP_ZONE)) return false;
        // 拿种子及往返路上：站立
        if (state == State.TAKE_SEEDS
                || (state == State.WALK && afterWalk == State.TAKE_SEEDS)) return false;
        // 浇水序列及去浇水点路上：shift 由浇水逻辑自己控制，蹲下规则不得干扰
        if (state == State.WATER
                || (state == State.WALK && afterWalk == State.WATER)) return false;
        // 背包满去存作物及路上：站立（包括还没走出 zone 的那几步）
        if (state == State.DEPOSIT
                || (state == State.WALK && afterWalk == State.DEPOSIT)) return false;
        // 吃饭/拿食物及路上：站立
        if (state == State.EAT || state == State.TAKE_FOOD
                || (state == State.WALK && afterWalk == State.TAKE_FOOD)) return false;
        FarmConfig.Region box = cfg.zones.get(zoneIdx).box;
        if (box == null) return false;
        BlockPos p = player.getBlockPos();
        // 水平外扩 1 格（站在边缘地块外侧也算在区内），y 放宽 2 格
        return p.getX() >= box.minX() - 1 && p.getX() <= box.maxX() + 1
                && p.getZ() >= box.minZ() - 1 && p.getZ() <= box.maxZ() + 1
                && p.getY() >= box.minY() - 2 && p.getY() <= box.maxY() + 2;
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
                softHalt("§c饥饿值不足以疾跑，且拿不到食物；请检查 /farm bindfood 和 /farm bindfoodchest");
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

        // 2) 背包快满且有可存的东西 → 存作物（优先按作物名分拣到专箱，剩余走全局箱）
        if (Bot.inventoryEmptySlots(player) <= cfg.depositWhenEmptySlots) {
            if (hasDepositable(player, cfg)) {
                if (startDepositTask(mc, player, cfg)) return;
                softHalt("§c背包满了，但没有可用的作物箱（未绑定或全满）；绑定/清空后自动重试");
                return;
            }
            warnOnce("inv-full-keep", "§e背包快满了，但都是种子/洒水壶/食物等保留物品，不会存入作物箱");
        }

        // 3) 扫描地块
        List<FarmScanner.Plot> allPlots = FarmScanner.classify(mc.world, cfg, farmland(mc, cfg));
        List<FarmScanner.Plot> plots = new java.util.ArrayList<>(allPlots);
        // 停用的作物（/farm toggle <作物>）直接从可处理列表剔除：不收、不种、不取种
        plots.removeIf(p -> isBlacklisted(p.farmland()) || isZoneBlocked(p.zoneIndex())
                || !cropEnabled(cfg, p.cropName()));
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
                if (seedDefined(crop) && crop.seedChest != null
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

        // 3b) 已经开始收获某个 zone：先收完全部成熟作物，再统一补种，最后清扫该 zone。
        if (activeHarvestZone >= 0) {
            List<FarmScanner.Plot> zonePlots = orderedZonePlots(plots, activeHarvestZone, cfg);
            if ("use".equals(cfg.harvestMode)) {
                for (FarmScanner.Plot p : zonePlots) {
                    if (p.state() != FarmScanner.PlotState.MATURE) continue;
                    FarmConfig.Crop crop = cfg.crops.get(p.cropName());
                    if (!seedDefined(crop) || FarmItems.countSeeds(player, crop) > 0) continue;
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

            // 左键收获产生的空地不能立刻打断蛇形收割；确认本区没有成熟目标后才进入统一补种阶段。
            for (FarmScanner.Plot p : zonePlots) {
                if (p.state() != FarmScanner.PlotState.EMPTY) continue;
                FarmConfig.Crop crop = cfg.crops.get(p.cropName());
                if (crop != null && FarmItems.countSeeds(player, crop) > 0) {
                    beginPlot(mc, player, p, State.PLANT);
                    return;
                }
            }

            // 成熟作物已经收完，再尽量补齐该 zone 的空地；拿不到种子也不能阻塞拾取。
            for (FarmScanner.Plot p : zonePlots) {
                if (p.state() != FarmScanner.PlotState.EMPTY) continue;
                FarmConfig.Crop crop = cfg.crops.get(p.cropName());
                if (seedDefined(crop) && crop.seedChest != null
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
                // UNKNOWN（未加载）不计入分母，避免拉低成熟率让远处 zone 永远不达标
                if (p.state() == FarmScanner.PlotState.MATURE
                        || p.state() == FarmScanner.PlotState.GROWING) cropCounts[p.zoneIndex()]++;
                if (p.state() == FarmScanner.PlotState.MATURE) matureCounts[p.zoneIndex()]++;
            }
            for (FarmScanner.Plot p : plots) {
                if (p.zoneIndex() >= 0 && p.zoneIndex() < cfg.zones.size()
                        && p.state() == FarmScanner.PlotState.MATURE) {
                    actionableMatureCounts[p.zoneIndex()]++;
                }
            }
            for (int i = 0; i < cfg.zones.size(); i++) {
                // 成熟率门槛可配置（/farm ratio，默认 50%）；整数运算避免浮点误差
                if (actionableMatureCounts[i] > 0
                        && matureCounts[i] * 100 >= cropCounts[i] * cfg.harvestMaturePercent) {
                    harvestBatchZones.add(i);
                }
            }
            if (!harvestBatchZones.isEmpty()) {
                msg("§7有 §e" + harvestBatchZones.size()
                        + " §7个 zone 达到 " + cfg.harvestMaturePercent
                        + "% 成熟，将逐个收获并逐区拾取掉落物");
            }
        }

        harvestBatchZones.removeIf(this::isZoneBlocked); // 熔断中的区不参与批次
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

        // 4) 有耕地/zone 在未加载区块里 → 走过去让区块加载后再判断成熟
        //    （服务器只同步视距内的区块，远处方块客户端一律读成空气，不侦察就永远"没菜可收"）
        if (tickCounter >= scoutCooldownUntil) {
            BlockPos scout = pickScoutTarget(plots, player, cfg);
            if (scout != null) {
                beginScout(mc, player, scout);
                return;
            }
        }

        // 5) 没事做 → 5 秒后再扫
        scanCooldown = 100;
    }

    private void beginPlot(MinecraftClient mc, ClientPlayerEntity player,
                           FarmScanner.Plot p, State action) {
        plotFarmland = p.farmland();
        plotCrop = p.crop();
        plotCropName = p.cropName();
        // 跟着收割轨迹走：优先站上目标格本身或同排贴邻格（半径 1.65 只覆盖这些），
        // 视线自然朝前下方、沿垄推进。用 3.0 半径会停在侧面 2~3 格外平移+扭头，太像机器人。
        // medium/fast 模式不贴身：走到 2.5 内即可，出手由 3.0 的顺路收获覆盖，路程更短。
        walkGoal = plotCrop;
        afterWalk = action;
        pathRetries = 0;
        walkTotalTicks = 0;
        walkReach = FarmConfig.get().isSpeedy() ? 2.5 : 1.65;
        walkPitch = 30.0f; // 收菜/种菜移动：视线斜下方 30°
        List<BlockPos> path = Pathfinder.findNear(mc.world, player.getBlockPos(), plotCrop,
                walkReach, 4000);
        if (path == null) {
            // 贴身站位被围死（篱笆/固体成熟作物挡路）→ 退回远距离交互
            walkReach = 3.0;
            path = Pathfinder.findNear(mc.world, player.getBlockPos(), plotCrop,
                    walkReach, PATH_MAX_NODES);
        }
        if (path == null) {
            walkFailed(action, player);
            return;
        }
        if (path.size() <= 1) {
            pathExec = null;
            consecutiveWalkFails = 0;
            enter(action);
            return;
        }
        // 收菜/种菜在地块间移动时，视线保持斜下方 30°（俯视垄面），不平视。
        pathExec = new PathExecutor(path).withPitch(walkPitch);
        enter(State.WALK);
    }

    private void beginZoneTraversal(int zoneIndex, ClientPlayerEntity player, FarmConfig cfg) {
        routeOrder = null;
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

        if ("route".equals(cfg.harvestStrategy)) {
            // 最优策略：只看当前实际要处理的目标（成熟+空地），规划一条贪心+2-opt 短路线。
            zoneTraversal = new ZoneTraversal(zoneIndex, TraversalMode.ROUTE,
                    alongX, reverseRows, reverseFirstRow, false);
            routeOrder = planRoute(zoneIndex, player, cfg);
            zoneTraversalFallback = false;
            return;
        }

        // 随机策略：每次进区随机选一种遍历方式，打破"永远蛇形"的机械感。
        TraversalMode[] modes = {TraversalMode.SNAKE, TraversalMode.DIAGONAL,
                TraversalMode.SPIRAL, TraversalMode.SPLIT};
        TraversalMode mode = modes[traversalRandom.nextInt(modes.length)];
        // 太小的区斜向/螺旋没意义还容易退化，退回蛇形。
        if (Math.min(width, depth) < 3) mode = TraversalMode.SNAKE;
        zoneTraversal = new ZoneTraversal(zoneIndex, mode, alongX, reverseRows, reverseFirstRow,
                traversalRandom.nextBoolean());
        zoneTraversalFallback = false;
    }

    /**
     * ROUTE 模式路线规划：对本 zone 当前的目标地块（成熟作物 + 空耕地）
     * 从玩家位置贪心取最近邻建初始路线，再用 2-opt 消除交叉，返回 farmland→序号。
     * 只在进区时规划一次；期间新增目标（收获出的空地）由 orderedZonePlots 排在路线之后。
     */
    private Map<Long, Integer> planRoute(int zoneIndex, ClientPlayerEntity player, FarmConfig cfg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        List<FarmScanner.Plot> targets = new java.util.ArrayList<>();
        for (FarmScanner.Plot p : FarmScanner.classify(mc.world, cfg, farmland(mc, cfg))) {
            if (p.zoneIndex() != zoneIndex) continue;
            if (!cropEnabled(cfg, p.cropName())) continue; // 停用作物不进路线
            if (p.state() == FarmScanner.PlotState.MATURE
                    || p.state() == FarmScanner.PlotState.EMPTY) {
                targets.add(p);
            }
        }
        Map<Long, Integer> order = new HashMap<>();
        if (targets.isEmpty()) return order;

        int n = targets.size();
        // 贪心最近邻（平面距离即可，zone 内基本同高）。
        int[] route = new int[n];
        boolean[] used = new boolean[n];
        double curX = player.getX(), curZ = player.getZ();
        for (int step = 0; step < n; step++) {
            int bestIdx = -1;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (used[i]) continue;
                BlockPos f = targets.get(i).farmland();
                double dx = f.getX() + 0.5 - curX;
                double dz = f.getZ() + 0.5 - curZ;
                double d = dx * dx + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    bestIdx = i;
                }
            }
            route[step] = bestIdx;
            used[bestIdx] = true;
            BlockPos f = targets.get(bestIdx).farmland();
            curX = f.getX() + 0.5;
            curZ = f.getZ() + 0.5;
        }

        // 2-opt 改进：消除路线交叉（上限控制耗时，400 块地约几毫秒）。
        int maxPasses = n > 400 ? 1 : 3;
        for (int pass = 0; pass < maxPasses; pass++) {
            boolean improved = false;
            for (int i = 0; i < n - 2; i++) {
                for (int j = i + 2; j < n - (i == 0 ? 1 : 0); j++) {
                    double before = dist(targets, route[i], route[i + 1])
                            + dist(targets, route[j], route[(j + 1) % n]);
                    double after = dist(targets, route[i], route[j])
                            + dist(targets, route[i + 1], route[(j + 1) % n]);
                    if (after + 1e-6 < before) {
                        // 反转 i+1..j 段
                        for (int a = i + 1, b = j; a < b; a++, b--) {
                            int tmp = route[a];
                            route[a] = route[b];
                            route[b] = tmp;
                        }
                        improved = true;
                    }
                }
            }
            if (!improved) break;
        }

        for (int step = 0; step < n; step++) {
            order.put(targets.get(route[step]).farmland().asLong(), step);
        }
        return order;
    }

    private static double dist(List<FarmScanner.Plot> targets, int a, int b) {
        BlockPos p1 = targets.get(a).farmland();
        BlockPos p2 = targets.get(b).farmland();
        double dx = p1.getX() - p2.getX();
        double dz = p1.getZ() - p2.getZ();
        return Math.sqrt(dx * dx + dz * dz);
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
        if (zoneTraversal.mode() == TraversalMode.ROUTE) {
            // 路线内按规划序号；不在路线上的新目标（收获后出现的空地等）保持
            // 距离序排在整条路线之后，等路线走完再统一处理。
            Map<Long, Integer> order = routeOrder != null ? routeOrder : Map.of();
            List<FarmScanner.Plot> onRoute = new java.util.ArrayList<>();
            List<FarmScanner.Plot> offRoute = new java.util.ArrayList<>();
            for (FarmScanner.Plot p : result) {
                if (order.containsKey(p.farmland().asLong())) onRoute.add(p);
                else offRoute.add(p);
            }
            onRoute.sort(Comparator.comparingInt(p -> order.get(p.farmland().asLong())));
            onRoute.addAll(offRoute);
            return onRoute;
        }
        FarmConfig.Region r = cfg.zones.get(zoneIndex).box;
        result.sort(Comparator.comparingLong(p -> traversalOrder(p.farmland(), r, zoneTraversal)));
        return result;
    }

    /** 按选定遍历方式给地块编序（越小越先处理）。 */
    private static long traversalOrder(BlockPos pos, FarmConfig.Region r, ZoneTraversal t) {
        int width = r.maxX() - r.minX() + 1;
        int depth = r.maxZ() - r.minZ() + 1;
        // 归一到行/列坐标（含玩家起始侧翻转），后续各模式共用。
        int row, column, rows, columns;
        if (t.alongX()) {
            rows = depth;
            columns = width;
            row = t.reverseRows() ? r.maxZ() - pos.getZ() : pos.getZ() - r.minZ();
            column = t.reverseFirstRow() ? r.maxX() - pos.getX() : pos.getX() - r.minX();
        } else {
            rows = width;
            columns = depth;
            row = t.reverseRows() ? r.maxX() - pos.getX() : pos.getX() - r.minX();
            column = t.reverseFirstRow() ? r.maxZ() - pos.getZ() : pos.getZ() - r.minZ();
        }
        switch (t.mode()) {
            case DIAGONAL -> {
                // 斜向条带：按 row+column 分带，同带内蛇形往返；diagFlip 换另一条对角线。
                int band = t.diagFlip() ? row + (columns - 1 - column) : row + column;
                int within = (band & 1) == 0 ? row : rows - 1 - row;
                return (long) band * (rows + columns) + within;
            }
            case SPIRAL -> {
                // 由外圈到内圈：圈号 = 到区域边缘的最小距离；同圈内按周向角排序。
                int ring = Math.min(Math.min(row, rows - 1 - row), Math.min(column, columns - 1 - column));
                double cx = (rows - 1) / 2.0, cy = (columns - 1) / 2.0;
                double angle = Math.atan2(column - cy, row - cx) + Math.PI; // 0..2π
                long angleKey = (long) (angle * 10000);
                return (long) ring * 4_000_000L + angleKey;
            }
            case SPLIT -> {
                // 隔行跳扫：先处理偶数行（蛇形），再回头处理奇数行（蛇形）。
                int pass = row & 1;
                int normalizedRow = row >> 1;
                boolean reverseColumn = (normalizedRow & 1) != 0;
                int c = reverseColumn ? columns - 1 - column : column;
                return (long) pass * 4_000_000L + (long) normalizedRow * columns + c;
            }
            default -> {
                // SNAKE：逐行往返。
                boolean reverseColumn = (row & 1) != 0;
                int c = reverseColumn ? columns - 1 - column : column;
                return (long) row * columns + c;
            }
        }
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
        walkPitch = 0.0f; // 常规寻路（去箱子/水源/侦察）平视
        pathRetries = 0;
        walkTotalTicks = 0;
        walkApproaching = false;
        approachStalls = 0;
        List<BlockPos> path = Pathfinder.findNear(mc.world, player.getBlockPos(), target, reach, PATH_MAX_NODES);
        if (path == null) {
            // 直达寻路失败：目标坐标已知，但很可能在未加载区块里（客户端没有那里的方块数据，
            // A* 沿途读到的全是空气/无地面）。不直接判失败，而是朝目标方向分段逼近，
            // 走近后目标区块加载出来即可正常寻路。
            if (tryApproach(mc, player)) return;
            walkFailed(next, player);
            return;
        }
        // 起点已在交互距离内（单点路径）：跳过 WALK，直接进入动作状态，省 2~3 tick/块
        if (path.size() <= 1) {
            pathExec = null;
            consecutiveWalkFails = 0;
            enter(next);
            return;
        }
        pathExec = new PathExecutor(path).withPitch(walkPitch);
        enter(State.WALK);
    }

    /**
     * 朝 walkGoal 方向分段逼近：目标（或沿途）在未加载区块里时，
     * 取"玩家到目标连线上、当前已加载范围内最远的可站立点"作为跳点走过去，
     * 到达后 tickWalk 会重新规划——如此一段段推进，直到目标区块加载、能直接寻到路。
     * 返回 true 表示已安排逼近行走；false 表示连一步都迈不出去（真正走不到）。
     */
    private boolean tryApproach(MinecraftClient mc, ClientPlayerEntity player) {
        BlockPos hop = findApproachHop(mc, player, walkGoal);
        if (hop == null) return false;
        walkApproaching = true;
        pathRetries = 0;
        List<BlockPos> path = Pathfinder.findNear(mc.world, player.getBlockPos(), hop, 1.0, PATH_MAX_NODES);
        if (path == null || path.size() <= 1) {
            approachStalls++;
            return false;
        }
        if (!walkApproachAnnounced) {
            walkApproachAnnounced = true;
            msg("§7目标区域未加载，正朝 §e" + walkGoal.toShortString() + " §7方向靠近…");
        }
        pathExec = new PathExecutor(path).withPitch(walkPitch);
        enter(State.WALK);
        return true;
    }

    /** 在玩家→目标的连线上，找当前已加载区块内最远的可站立跳点（从远到近扫）。 */
    private BlockPos findApproachHop(MinecraftClient mc, ClientPlayerEntity player, BlockPos goal) {
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        double dx = goal.getX() + 0.5 - px;
        double dz = goal.getZ() + 0.5 - pz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0) return null;
        double ux = dx / dist, uz = dz / dist;
        // 从尽量远处往回找第一个"已加载且可站立"的点（步长 2 格，最远探到目标）。
        for (double d = Math.min(dist, 48.0); d >= 3.0; d -= 2.0) {
            double x = px + ux * d;
            double z = pz + uz * d;
            BlockPos col = BlockPos.ofFloored(x, py, z);
            if (!FarmScanner.isLoaded(mc.world, col)) continue;
            BlockPos stand = findStandableNear(mc, col);
            if (stand != null) return stand;
        }
        return null;
    }

    /** 在给定水平位置附近上下找一个可站立的脚部格（应对地形起伏）。 */
    private BlockPos findStandableNear(MinecraftClient mc, BlockPos col) {
        for (int dy = 0; dy <= 4; dy++) {
            for (int s : (dy == 0 ? new int[]{0} : new int[]{-dy, dy})) {
                BlockPos p = col.up(s);
                if (Pathfinder.standable(mc.world, p)) return p;
            }
        }
        return null;
    }

    private void tickWalk(MinecraftClient mc, ClientPlayerEntity player) {
        if (pathExec == null) {
            enter(State.SCAN);
            return;
        }
        FarmConfig cfg = FarmConfig.get();
        // medium/fast 模式：收菜不停步——走路途中对 reach 内的成熟作物出手
        // （fast 直接 synthetic hit，medium 指针加速对准后才出手）；
        // 当前走路目标已被顺路收掉时，不 enter() 打断行走输入，直接换路线上的下一株。
        if (cfg.isSpeedy() && afterWalk == State.HARVEST && activeHarvestZone >= 0) {
            FarmConfig.Crop targetCrop = cfg.crops.get(plotCropName);
            if (plotCrop != null && FarmScanner.isLoaded(mc.world, plotCrop)
                    && (targetCrop == null
                    || !FarmScanner.matchesMature(targetCrop, mc.world.getBlockState(plotCrop)))) {
                fastRetarget(mc, player, cfg);
                if (state != State.WALK || pathExec == null) return;
            }
        }
        walkTotalTicks++;
        // 正常赶路也走不完的硬上限（4 分钟），防止极端情况下无限游走。
        if (walkTotalTicks > 20 * 240) {
            pathExec = null;
            InputController.clear();
            walkFailed(afterWalk, player);
            return;
        }
        // 顺路出手：fast 不碰指针，可与行走转向并行；medium 出手前要把指针对准到 <3°，
        // 若与 pathExec.tick 同 tick 各转各的（行走 ≤12°/tick 拉回路径方向、对准按剩余
        // 角差 ~50% 拉向作物），稳态角差收敛在 ~12° 永远到不了阈值——人原地僵持，
        // 还被卡住检测误报"寻路卡住/无进展"，3 次重试烧光后整块地被冤枉拉黑。
        // 所以 medium 对准/出手期间独占指针并刻意停步，该 tick 跳过 pathExec。
        boolean mediumEngaging = false;
        if (cfg.isSpeedy() && afterWalk == State.HARVEST && activeHarvestZone >= 0) {
            mediumEngaging = tickFastHarvest(mc, player, cfg) && cfg.isMedium();
        }
        if (mediumEngaging) {
            InputController.forward = false;
            InputController.sprint = false;
            InputController.jump = false;
            pathExec.notePause(); // 刻意停步：不计入卡住/无进展，恢复行走后重新累计
            return;
        }
        pathExec.tick(player);
        if (pathExec.isDone()) {
            pathExec = null;
            if (walkApproaching) {
                // 到达一个逼近跳点：先试直达最终目标，仍不行就再逼近一段。
                List<BlockPos> direct = Pathfinder.findNear(mc.world, player.getBlockPos(),
                        walkGoal, walkReach, PATH_MAX_NODES);
                if (direct != null) {
                    walkApproaching = false;
                    walkApproachAnnounced = false;
                    approachStalls = 0;
                    consecutiveWalkFails = 0;
                    if (direct.size() <= 1) {
                        enter(afterWalk);
                        return;
                    }
                    pathExec = new PathExecutor(direct).withPitch(walkPitch);
                    ticksInState = 0;
                    return;
                }
                if (tryApproach(mc, player)) {
                    ticksInState = 0;
                    return;
                }
                // 连续多段都迈不动 = 真的走不到
                if (approachStalls >= 3) {
                    walkApproaching = false;
                    walkApproachAnnounced = false;
                    walkFailed(afterWalk, player);
                    return;
                }
                scanCooldown = 20; // 稍等地形/区块同步后由 SCAN 再次尝试
                enter(State.SCAN);
                return;
            }
            consecutiveWalkFails = 0; // 成功走到目标，重置整区不可达计数
            enter(afterWalk);
            return;
        }
        boolean stuck = pathExec.isStuck();
        // 只有“真卡住/绕圈”才计入重试次数；长途路走满 60 秒只做例行重规划，
        // 否则远但能走到的目标会被 3 次“重试”误判成走不过去。
        if (stuck || ticksInState > 20 * 60) {
            if (stuck) {
                pathRetries++;
                if (pathRetries > 3) {
                    pathExec = null;
                    InputController.clear();
                    walkFailed(afterWalk, player);
                    return;
                }
                msg("§e寻路卡住/无进展，正在重新规划（" + pathRetries + "/3）");
            }
            List<BlockPos> path = Pathfinder.findNear(mc.world, player.getBlockPos(),
                    walkGoal, walkReach, PATH_MAX_NODES);
            if (path == null) {
                pathExec = null;
                InputController.clear();
                // 重规划失败（目标仍在未加载区块里）→ 继续分段逼近，别直接判走不到
                if (tryApproach(mc, player)) {
                    ticksInState = 0;
                    return;
                }
                walkFailed(afterWalk, player);
                return;
            }
            pathExec = new PathExecutor(path).withPitch(walkPitch);
            ticksInState = 0;
        }
    }

    // ---------- fast 模式（收菜不停步） ----------

    /**
     * fast 模式：当前走路目标已被顺路收掉——不 enter()（不清行走输入），
     * 直接把本区路线上的下一株成熟作物设为新目标并重规划路径，脚步不停。
     */
    private void fastRetarget(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        List<FarmScanner.Plot> plots = new java.util.ArrayList<>(
                FarmScanner.classify(mc.world, cfg, farmland(mc, cfg)));
        plots.removeIf(p -> isBlacklisted(p.farmland()) || isZoneBlocked(p.zoneIndex())
                || !cropEnabled(cfg, p.cropName()));
        plots.sort(Comparator.comparingDouble(p ->
                p.farmland().getSquaredDistance(player.getX(), player.getY(), player.getZ())));
        for (FarmScanner.Plot p : orderedZonePlots(plots, activeHarvestZone, cfg)) {
            if (p.state() != FarmScanner.PlotState.MATURE) continue;
            FarmConfig.Crop crop = cfg.crops.get(p.cropName());
            // use 模式种子用完且有种子箱 → 交回 SCAN 决定去补货，不在这里硬收
            if ("use".equals(cfg.harvestMode) && seedDefined(crop) && crop.seedChest != null
                    && FarmItems.countSeeds(player, crop) <= 0) break;
            List<BlockPos> path = Pathfinder.findNear(mc.world, player.getBlockPos(), p.crop(),
                    2.5, 4000);
            double reach = 2.5;
            if (path == null) {
                path = Pathfinder.findNear(mc.world, player.getBlockPos(), p.crop(),
                        3.0, PATH_MAX_NODES);
                reach = 3.0;
            }
            if (path == null) continue; // 这株走不到就试路线上的下一株，不打断行走
            plotFarmland = p.farmland();
            plotCrop = p.crop();
            plotCropName = p.cropName();
            walkGoal = plotCrop;
            walkReach = reach;
            walkPitch = 30.0f;
            pathRetries = 0;
            walkTotalTicks = 0;
            ticksInState = 0;
            consecutiveWalkFails = 0;
            if (path.size() <= 1) {
                // 已在交互范围内：直接进 HARVEST（fast 不等对准立即出手，medium 加速对准后出手）
                pathExec = null;
                enter(afterWalk);
                return;
            }
            pathExec = new PathExecutor(path).withPitch(walkPitch);
            return; // 保持 WALK 状态，行走输入不断
        }
        // 本区没有可直接收的成熟目标了（收完/需补种子）→ 交回 SCAN 正常调度
        pathExec = null;
        enter(State.SCAN);
    }

    /**
     * medium/fast 模式核心：行走中每 tick 检查眼前 reach 内是否有本区成熟作物，边走边出手。
     * use=有种子时换手右键（插件收获+复种），break=左键（插件作物通常瞬间挖掉）。
     * fast：不做视角对准（synthetic hit 指向作物中心），用人机特征换速度；
     * medium：先把指针加速平滑对准目标（yaw≤40°/pitch≤24° 每 tick），指针到位才出手。
     * 返回 true = 本 tick 正在对准/换手/出手（medium 据此停步并豁免卡住检测）。
     */
    private boolean tickFastHarvest(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        if (fastActionGrace > 0) {
            fastActionGrace--;
            // medium：换手等待期间也继续转头对准，两件事并行不浪费 tick
            if (cfg.isMedium() && mediumAimCrop != null) {
                FarmLookController.smoothFace(player, Vec3d.ofCenter(mediumAimCrop), true);
            }
            return true;
        }
        // 玩家开着界面（聊天/背包/容器）时不出手：换手 clickSlot 会发到错误 handler
        if (mc.currentScreen != null
                || player.currentScreenHandler != player.playerScreenHandler) return false;
        if (farmlandCache == null) return false;
        Vec3d eyes = player.getEyePos();
        List<BlockPos> nearby = new java.util.ArrayList<>();
        for (BlockPos f : farmlandCache) {
            if (eyes.squaredDistanceTo(f.getX() + 0.5, f.getY() + 1.5, f.getZ() + 0.5)
                    <= FAST_REACH * FAST_REACH) {
                nearby.add(f);
            }
        }
        if (nearby.isEmpty()) {
            mediumAimCrop = null;
            return false;
        }
        // 选目标：medium 对上一 tick 的目标有粘性——对准中途不换目标，指针不来回甩
        FarmScanner.Plot chosen = null;
        for (FarmScanner.Plot p : FarmScanner.classify(mc.world, cfg, nearby)) {
            if (p.state() != FarmScanner.PlotState.MATURE) continue;
            if (p.zoneIndex() != activeHarvestZone) continue; // 只收本区，掉落物才归本区拾取
            if (isBlacklisted(p.farmland()) || !cropEnabled(cfg, p.cropName())) continue;
            Long fired = fastFiredAt.get(p.crop().asLong());
            if (fired != null && tickCounter - fired < FAST_FIRE_COOLDOWN_TICKS) continue;
            if ("use".equals(cfg.harvestMode)) {
                FarmConfig.Crop crop = cfg.crops.get(p.cropName());
                if (crop == null) continue;
                // 顺路收获只在包里有种子时出手；没种子留给正常流程（补货/空手兜底）
                if (!FarmItems.isSeedOf(crop, player.getMainHandStack())
                        && FarmItems.countSeeds(player, crop) <= 0) continue;
            }
            if (p.crop().equals(mediumAimCrop)) {
                chosen = p;
                break;
            }
            if (chosen == null) chosen = p;
        }
        if (chosen == null) {
            mediumAimCrop = null;
            return false;
        }
        FarmConfig.Crop crop = cfg.crops.get(chosen.cropName());
        boolean useMode = "use".equals(cfg.harvestMode);
        if (cfg.isMedium()) {
            mediumAimCrop = chosen.crop();
        }
        if (useMode && !FarmItems.isSeedOf(crop, player.getMainHandStack())) {
            if (FarmItems.selectMatching(mc, player, s -> FarmItems.isSeedOf(crop, s))) {
                fastActionGrace = 2; // 等背包同步后再右键
            }
            // 换手的同时 medium 就开始转头
            if (cfg.isMedium()) {
                FarmLookController.smoothFace(player, Vec3d.ofCenter(chosen.crop()), true);
            }
            return true;
        }
        if (cfg.isMedium()
                && !FarmLookController.smoothFace(player, Vec3d.ofCenter(chosen.crop()), true)) {
            return true; // 指针还没到位：本 tick 只转头，出手留给后续 tick
        }
        if (useMode) {
            BlockHitResult hit = new BlockHitResult(
                    Vec3d.ofCenter(chosen.crop()), Direction.UP, chosen.crop(), false);
            mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        } else {
            mc.interactionManager.attackBlock(chosen.crop(), Direction.UP);
        }
        player.swingHand(Hand.MAIN_HAND);
        fastFiredAt.put(chosen.crop().asLong(), tickCounter);
        fastActionGrace = 2; // 一 tick 最多出手一株，稍作分散
        mediumAimCrop = null;
        return true;
    }

    private void walkFailed(State next, ClientPlayerEntity player) {
        walkApproaching = false;
        walkApproachAnnounced = false;
        approachStalls = 0;
        switch (next) {
            case HARVEST, PLANT -> {
                // 一个 zone 连续多块地都寻路失败 = 整区被隔断（门/梯子/矮顶/围栏），
                // 逐块拉黑会刷上百条消息、每块还白烧一次全量搜索 → 整区熔断 1 分钟。
                consecutiveWalkFails++;
                if (consecutiveWalkFails >= 5) {
                    int zone = activeHarvestZone >= 0 ? activeHarvestZone : activePlantZone;
                    consecutiveWalkFails = 0;
                    if (zone >= 0) {
                        zoneBlockedUntil.put(zone, tickCounter + BLACKLIST_TICKS);
                        harvestBatchZones.remove(zone);
                        if (zone == activeHarvestZone) activeHarvestZone = -1;
                        if (zone == activePlantZone) activePlantZone = -1;
                        zoneTraversal = null;
                        routeOrder = null;
                        zoneTraversalFallback = false;
                        msg("§ezone #" + (zone + 1) + " 连续多块地都走不过去，整区暂停 1 分钟。"
                                + "§7寻路只会平走/跳1格/降3格，不会开门、爬梯子、游泳——请检查该区入口");
                        enter(State.SCAN);
                        return;
                    }
                }
                blacklistPlot("走不过去");
            }
            case TAKE_SEEDS -> {
                noSeeds(takeCropName, "走不到种子箱");
                enter(State.SCAN);
            }
            case TAKE_FOOD -> {
                foodUnavailable(player, "走不到食物箱");
                if (isRunning()) enter(State.SCAN);
            }
            case DEPOSIT -> {
                // 走不到当前箱（可能地形/未加载）：本行程临时跳过它，换下一个
                if (depositChestPos != null) {
                    skipDepositChestsThisTrip.add(depositChestPos.asLong());
                }
                continueDepositOrHalt(MinecraftClient.getInstance(), player,
                        FarmConfig.get(), "作物箱走不到");
            }
            case PICKUP_ZONE -> {
                // 寻路失败但物品就在附近的开阔农田上：升级为直线接近，不计失败次数。
                // 否则物品逐个进忽略表后，只会“复查发现→清空→再失败”原地刷屏，永远不动。
                MinecraftClient mc = MinecraftClient.getInstance();
                ItemEntity target = currentZoneDrop(mc);
                if (target != null) {
                    double dx = target.getX() - player.getX();
                    double dz = target.getZ() - player.getZ();
                    double dy = Math.abs(target.getY() - player.getY());
                    double horizontalSq = dx * dx + dz * dz;
                    if (horizontalSq <= 5.0 * 5.0 && dy <= 1.5) {
                        pickupDirectRange = Math.max(pickupDirectRange, Math.sqrt(horizontalSq) + 0.5);
                        pickupApproachTicks = 0;
                        pickupWaitTicks = 0;
                        enter(State.PICKUP_ZONE);
                        graceTicks = 1;
                        return;
                    }
                }
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
                msg("§e走不到浇水点，跳过这个");
                enter(State.SCAN);
            }
            default -> {
                // 侦察等无主任务的走路失败：冷却 15 秒，避免立刻重试同一条失败路线
                scoutCooldownUntil = tickCounter + 20 * 15;
                enter(State.SCAN);
            }
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

        // 总超时保险：任何停滞（对准、GUI、发包被吞）都不允许永久卡在 HARVEST。
        if (ticksInState > 300) {
            mc.interactionManager.cancelBlockBreaking();
            blacklistPlot("收获超时");
            return;
        }
        // use 模式右键可能弹插件容器 GUI；不是自己开的就关掉再继续。
        if (player.currentScreenHandler != null
                && player.currentScreenHandler != player.playerScreenHandler) {
            player.closeHandledScreen();
            return;
        }

        // 对准与换手/等待并行；break 模式开挖后不因微小视角偏差中断挖掘。
        // medium 用加速对准（指针到位才出手）；fast 不等对准，立即出手。
        boolean aligned = FarmLookController.smoothFace(player, Vec3d.ofCenter(plotCrop), cfg.isMedium());
        if (cfg.isFast()) aligned = true;

        if ("use".equals(cfg.harvestMode)) {
            if (graceTicks > 0) {
                graceTicks--;
                return;
            }
            if (crop != null && actionAttempts < 4) {
                // 有种子就优先走插件的“右键收获 + 原地复种”。
                if (!FarmItems.isSeedOf(crop, player.getMainHandStack())) {
                    if (FarmItems.selectMatching(mc, player, s -> FarmItems.isSeedOf(crop, s))) {
                        graceTicks = 2; // 等背包/快捷栏同步后再交互（转头同时进行）
                        return;
                    }
                    // 种子耗尽不阻塞收获：改为【空手右键】收获（不复种），空地稍后统一补种。
                    warnOnce("harvest-fallback-" + plotCropName,
                            "§e「" + plotCropName + "」种子不足，先空手右键收获，之后拿到种子再补种");
                    if (!FarmItems.selectEmptyHand(mc, player)) {
                        graceTicks = 2; // 等腾空主手同步
                        return;
                    }
                    if (!aligned) return;
                    actionAttempts++;
                    BlockHitResult h2 = new BlockHitResult(
                            Vec3d.ofCenter(plotCrop), Direction.UP, plotCrop, false);
                    mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, h2);
                    player.swingHand(Hand.MAIN_HAND);
                    graceTicks = 12;
                    return;
                }
                if (!aligned) return;
                actionAttempts++;
                BlockHitResult hit = new BlockHitResult(
                        Vec3d.ofCenter(plotCrop), Direction.UP, plotCrop, false);
                mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
                player.swingHand(Hand.MAIN_HAND);
                graceTicks = 12;
                return;
            }
            warnOnce("harvest-use-failed-" + plotCropName,
                    "§e「" + plotCropName + "」右键收获多次没有响应，先跳过该地块，稍后重试");
            blacklistPlot("右键收获无响应");
        } else {
            if (!aligned && !breakStarted) return;
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
        pickupDirectRange = 2.0;
        pickupApproachTicksNeeded = 14;
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
                // 多轮仍捡不到的（掉进水里/卡进方块）不能无限重试刷屏，放弃本区拾取。
                if (zonePickupPass >= 5) {
                    msg("§e本区仍有 " + allVisibleDrops.size()
                            + " 个掉落物经 " + zonePickupPass + " 轮尝试无法拾取（可能卡在水/方块里），放弃并结束本区拾取");
                    finishZonePickup();
                    return;
                }
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
            pickupDirectRange = 2.0; // 新目标恢复默认直走范围
        }
        double dx = drop.getX() - player.getX();
        double dz = drop.getZ() - player.getZ();
        double horizontalSq = dx * dx + dz * dz;

        if (horizontalSq > pickupDirectRange * pickupDirectRange) {
            // 掉落物大多躺在 farmland 顶面（高 15/16），getBlockPos() 会落进耕地方块内部：
            // 拿它当 A* 目标时“眼睛到目标格中心”恒为 2 格，reach=1.25 永远无法满足，
            // 每次寻路都瞬间失败 → 全部物品进忽略表 → 原地刷屏不动。
            // 必须归一化到物品实际所在的“可站立脚部格”（上取半格再取整）。
            BlockPos footCell = BlockPos.ofFloored(drop.getX(), drop.getY() + 0.5, drop.getZ());
            pickupApproachTicks = 0;
            pickupWaitTicks = 0;
            walkTo(mc, player, footCell, 1.25, State.PICKUP_ZONE);
            return;
        }

        // 锁定第一次接近方向，直线穿过物品位置；走过后绝不立刻反向追踪，避免原地绕圈。
        InputController.sprint = false;
        InputController.jump = false;
        Vec3d dropCenter = drop.getBoundingBox().getCenter();
        if (pickupApproachTicks == 0) {
            pickupApproachYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            // 直走时长按起步距离估算：穿过物品约 1 格后停下等服务器确认。
            pickupApproachTicksNeeded = MathHelper.clamp(
                    (int) Math.ceil((Math.sqrt(horizontalSq) + 1.0) / 0.2), 14, 40);
        }
        if (pickupApproachTicks < pickupApproachTicksNeeded) {
            // 准星对着要捡的物品：物品仍基本在锁定方向前方时 yaw 直接跟踪物品本体，
            // 偏差变大（正从身旁/身后经过）则回退锁定方向防回头绕圈；pitch 用“自然低头”——
            // 远处近乎平视、靠近才渐渐压低且封顶 ~72°，不再隔好几格就死盯地面（一眼人机）。
            float yawToDrop = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float trackYaw = Math.abs(MathHelper.wrapDegrees(yawToDrop - pickupApproachYaw)) < 20.0f
                    ? yawToDrop : pickupApproachYaw;
            float yawError = FarmLookController.smoothYaw(player, trackYaw, 8.0f);
            FarmLookController.naturalPickupPitch(player, dropCenter, 8.0f);
            InputController.forward = Math.abs(yawError) < 24.0f;
            if (InputController.forward) pickupApproachTicks++;
            return;
        }

        InputController.forward = false;
        // 停步等服务器确认期间自然地瞥向物品：yaw 转回去、pitch 同样按距离渐进压低，
        // 不像 smoothFace 那样精确锁死在物品上（真人回头看脚边不会盯到 3° 以内）。
        float yawBack = (float) Math.toDegrees(Math.atan2(-dx, dz));
        FarmLookController.smoothYaw(player, yawBack, 10.0f);
        FarmLookController.naturalPickupPitch(player, dropCenter, 8.0f);
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
        routeOrder = null;
        zoneTraversalFallback = false;
        pickupZoneIndex = -1;
        zonePickupTargetId = -1;
        pickupApproachTicks = 0;
        pickupWaitTicks = 0;
        pickupDirectRange = 2.0;
        pickupApproachTicksNeeded = 14;
        pickupScanAnnounced = false;
        zonePickupEmptyConfirmations = 0;
        zonePickupConfirmWaitTicks = 0;
        zonePickupPass = 1;
        ignoredZoneDrops.clear();
        zonePickupRetries.clear();
        fastFiredAt.clear(); // 本区处理完毕，fast 模式的出手记录不再有意义
        mediumAimCrop = null;
        enter(State.SCAN);
    }

    // ---------- 种植 ----------

    private void tickPlant(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        // 种上了（或别人种了）→ 下一个任务
        if (!mc.world.getBlockState(plotCrop).isAir()) {
            enter(State.SCAN);
            return;
        }
        // 总超时保险：换手 desync、插件 GUI 等任何停滞都不允许永久卡在 PLANT。
        if (ticksInState > 200) {
            blacklistPlot("种植超时");
            return;
        }
        // 插件可能对右键弹容器 GUI；不是自己开的就关掉再继续。
        if (player.currentScreenHandler != null
                && player.currentScreenHandler != player.playerScreenHandler) {
            player.closeHandledScreen();
            return;
        }
        // farmland 顶面在 y+0.9375（不足一格）；hit 点必须落在方块边界内，
        // 否则严格反作弊会以“命中点在方块外”拒包，表现为种不上去。
        // 对准从进入状态第一 tick 就开始，与换手/等待并行，不串行浪费时间。
        Vec3d plantTarget = new Vec3d(plotFarmland.getX() + 0.5, plotFarmland.getY() + 0.9375,
                plotFarmland.getZ() + 0.5);
        boolean aligned = FarmLookController.smoothFace(player, plantTarget,
                FarmConfig.get().isMedium());
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

        if (!aligned) return;
        actionAttempts++;
        BlockHitResult hit = new BlockHitResult(
                plantTarget,
                Direction.UP, plotFarmland, false);
        mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        player.swingHand(Hand.MAIN_HAND);
        // 插件处理 + 网络延迟可能超过 8 tick，间隔太短会在成功前重复右键烧掉重试次数。
        graceTicks = 12;
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
                noSeedsUntilMs.remove(takeCropName); // 拿到了就清掉旧的失败冷却
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
            softHalt("§c" + why + "，饥饿值已不足以疾跑");
        } else {
            msg("§e拿不到食物（" + why + "），5 分钟后重试");
        }
    }

    private void tickDeposit(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        if (depositChestPos == null) {
            enter(State.SCAN);
            return;
        }
        if (deposit == null) {
            String cropName = depositCropName;
            java.util.function.Predicate<net.minecraft.item.ItemStack> filter =
                    cropName == null ? s -> isGlobalDepositable(cfg, s)
                            : s -> isProduceOf(cfg, cropName, s);
            deposit = new FarmDepositController(depositChestPos, filter);
        }
        switch (deposit.tick(mc, player)) {
            case WORKING -> {}
            case DONE -> {
                deposit = null;
                skipDepositChestsThisTrip.clear(); // 存物成功，清掉本行程的临时跳过
                depositChestPos = null;
                depositCropName = null;
                // 一次存物行程必须把背包里所有能存的都存完：
                // 不能依赖 SCAN 的"背包快满"阈值续跑——存完一种后空位回升就不满足阈值了，
                // 剩下的作物会一直留在包里。主动继续找下一个能存的箱子（下种作物专箱→全局箱）。
                if (hasDepositable(player, cfg) && startDepositTask(mc, player, cfg)) {
                    return;
                }
                enter(State.SCAN);
            }
            case CHEST_FULL -> {
                deposit = null;
                // 记住这个箱满了，之后的存物行程直接跳过它
                fullDepositChests.add(depositChestPos.asLong());
                msg("§7作物箱 " + depositChestPos.toShortString() + " 已满，之后存物会跳过它");
                continueDepositOrHalt(mc, player, cfg, "作物箱满了");
            }
            case FAILED -> {
                deposit = null;
                skipDepositChestsThisTrip.add(depositChestPos.asLong()); // 打不开：本行程跳过
                continueDepositOrHalt(mc, player, cfg, "作物箱打不开");
            }
        }
    }

    /** 当前箱不可用：换下一个可用箱继续；都没有则 softHalt（不停止，等清空后自动重试）。 */
    private void continueDepositOrHalt(MinecraftClient mc, ClientPlayerEntity player,
                                       FarmConfig cfg, String why) {
        depositChestPos = null;
        depositCropName = null;
        if (startDepositTask(mc, player, cfg)) {
            msg("§e" + why + "，换下一个箱子");
            return;
        }
        softHalt("§c" + why + "，且没有其他可用的作物箱了");
    }

    /**
     * 安排一次存物行程：优先各作物专箱（产出显示名包含作物名即分拣过去），
     * 剩余/没有专箱的产出走全局 cropChests 兜底。返回 false = 没有可用箱。
     */
    private boolean startDepositTask(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        // 1) 作物专箱：背包里有该作物产出且专箱有空位
        for (Map.Entry<String, FarmConfig.Crop> e : cfg.crops.entrySet()) {
            String cropName = e.getKey();
            List<com.worldutils.config.ModConfig.Pos> chests = e.getValue().depositChests;
            if (chests == null || chests.isEmpty()) continue;
            if (FarmItems.countMatching(player, s -> isProduceOf(cfg, cropName, s)) <= 0) continue;
            BlockPos chest = firstNonFullChest(chests);
            if (chest == null) continue; // 专箱全满：这些产出稍后由全局箱兜底
            depositCropName = cropName;
            depositChestPos = chest;
            walkTo(mc, player, chest, 3.2, State.DEPOSIT);
            return true;
        }
        // 2) 全局箱兜底：还有不属于任何"可用专箱"的可存物
        if (!cfg.cropChests.isEmpty()
                && FarmItems.countMatching(player, s -> isGlobalDepositable(cfg, s)) > 0) {
            BlockPos chest = firstNonFullChest(cfg.cropChests);
            if (chest != null) {
                depositCropName = null;
                depositChestPos = chest;
                walkTo(mc, player, chest, 3.2, State.DEPOSIT);
                return true;
            }
        }
        return false;
    }

    /** 是否某作物的产出：非保留物品且显示名包含作物名（茄子产出名都含"茄子"）。 */
    private static boolean isProduceOf(FarmConfig cfg, String cropName,
                                       net.minecraft.item.ItemStack stack) {
        if (stack.isEmpty() || FarmDepositController.shouldKeep(cfg, stack)) return false;
        return stack.getName().getString().contains(cropName);
    }

    /** 应走全局箱的物品：可存、且没有任何"有空位的专箱"能收它。 */
    private boolean isGlobalDepositable(FarmConfig cfg, net.minecraft.item.ItemStack stack) {
        if (stack.isEmpty() || FarmDepositController.shouldKeep(cfg, stack)) return false;
        String name = stack.getName().getString();
        for (Map.Entry<String, FarmConfig.Crop> e : cfg.crops.entrySet()) {
            List<com.worldutils.config.ModConfig.Pos> chests = e.getValue().depositChests;
            if (chests == null || chests.isEmpty()) continue;
            if (name.contains(e.getKey()) && firstNonFullChest(chests) != null) {
                return false; // 有专箱能收，不占全局箱
            }
        }
        return true;
    }

    /** 列表中第一个未满、且本行程未临时跳过的箱；没有返回 null。 */
    private BlockPos firstNonFullChest(List<com.worldutils.config.ModConfig.Pos> chests) {
        for (com.worldutils.config.ModConfig.Pos p : chests) {
            long key = p.toBlockPos().asLong();
            if (!fullDepositChests.contains(key) && !skipDepositChestsThisTrip.contains(key)) {
                return p.toBlockPos();
            }
        }
        return null;
    }

    // ---------- 浇水 ----------

    private boolean waterDue(FarmConfig cfg) {
        if (cfg.waterers.isEmpty()) return false;
        if (waterNowRequested) return true; // 手动触发无视间隔/冷却/开关
        if (!cfg.wateringEnabled) return false; // 洒水总开关（/farm toggle water）
        long now = System.currentTimeMillis();
        if (now < waterRetryAfterMs) return false;
        return cfg.lastWaterTimeMs <= 0
                || now - cfg.lastWaterTimeMs >= cfg.waterIntervalMinutes * 60_000L;
    }

    /** /farm water：手动触发立即浇水一轮（机器人运行中下一次调度即执行）。 */
    public void waterNow() {
        FarmConfig cfg = FarmConfig.get();
        if (cfg.waterers.isEmpty()) {
            msg("§c没有绑定任何浇水点（/farm bindwaterer）");
            return;
        }
        waterNowRequested = true;
        waterRetryAfterMs = 0; // 清掉失败冷却
        if (isRunning()) {
            msg("§a收到，马上开始一轮浇水（" + cfg.waterers.size() + " 个点）");
        } else {
            msg("§e已登记立即浇水，/farm start 启动后第一时间执行");
        }
    }

    /**
     * 推进浇水流程一步（在 SCAN 里调用）。
     * 返回 true = 已安排走路/动作；false = 浇水流程结束/不可行，继续其他任务。
     */
    private boolean stepWatering(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        if (!wateringCycle) {
            wateringCycle = true;
            waterNowRequested = false; // 手动触发的请求已被消费
            waterQueue.clear();
            waterQueue.addAll(cfg.waterers);
            wateredAnyThisCycle = false;
            msg("§7开始浇水巡回（" + waterQueue.size() + " 个浇水点）");
        }
        if (waterQueue.isEmpty()) {
            if (!wateredAnyThisCycle) {
                msg("§e本轮没有任何浇水点确认执行成功，5 分钟后重试");
                abortWatering();
                return false;
            }
            wateringCycle = false;
            cfg.lastWaterTimeMs = System.currentTimeMillis();
            FarmConfig.save();
            msg("§a浇水完成，" + cfg.waterIntervalMinutes + " 分钟后再来一轮");
            return false;
        }
        if (!FarmItems.hasWateringCan(player, cfg)) {
            msg("§c背包里没有已绑定的洒水壶，本轮浇水跳过（手持洒水壶执行 /farm bindcan）");
            abortWatering();
            return false;
        }
        // 直接走到绑定坐标（可在水下 ≤3 格，寻路支持沉底行走）；
        // reach 1.3 只覆盖绑定格本身——必须真正站到点上才开始浇水序列。
        // 低头右键次数每个点随机 0~3 次，避免固定节奏特征。
        waterClicksTarget = traversalRandom.nextInt(4);
        walkTo(mc, player, waterQueue.peek().pathPos(), 1.3, State.WATER);
        return true;
    }

    /** 本轮浇水放弃：不伪造成功时间，仅在本次运行中延迟 5 分钟重试。 */
    private void abortWatering() {
        wateringCycle = false;
        waterQueue.clear();
        waterClicksDone = 0;
        waterSneakReady = false;
        wateredAnyThisCycle = false;
        waterRetryAfterMs = System.currentTimeMillis() + 5 * 60_000L;
    }

    /**
     * 浇水序列（已站在绑定坐标处）：
     * 拿洒水壶 → 低头 80° 右键 3 次（每次间隔 8 tick）→ 抬头 -35°、按住 shift 右键 1 次 → 下一个点。
     */
    private void tickWater(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        FarmConfig.WatererTarget waterer = waterQueue.peek();
        if (waterer == null) {
            enter(State.SCAN);
            return;
        }

        // 总超时保险：30 秒完不成就跳过这个点
        if (ticksInState > 20 * 30) {
            msg("§e浇水点 " + waterer + " 操作超时，跳过");
            waterQueue.poll();
            enter(State.SCAN);
            return;
        }
        // 插件可能弹容器 GUI，关掉继续
        if (player.currentScreenHandler != null
                && player.currentScreenHandler != player.playerScreenHandler) {
            player.closeHandledScreen();
            return;
        }
        // 洒水壶换到主手（换手后等 2 tick）
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
        if (graceTicks > 0) {
            graceTicks--;
            return;
        }

        // 必须钉在水底再操作：漂起来了就按下潜（水中 shift=下沉）沉回去，落底松开
        if (!player.isOnGround()) {
            InputController.sneak = true;
            return;
        }
        InputController.sneak = false;

        if (waterClicksDone < waterClicksTarget) {
            // 阶段一：低头 80°，到位后右键（本点随机 0~3 次，不带 shift）
            float pitchDelta = 80.0f - player.getPitch();
            player.setPitch(player.getPitch()
                    + Math.max(-12.0f, Math.min(12.0f, pitchDelta)));
            if (Math.abs(80.0f - player.getPitch()) > 5.0f) return;
            vanillaRightClick(mc, player);
            waterClicksDone++;
            FarmItems.rememberWateringCanState(cfg, player.getMainHandStack());
            graceTicks = 8;
            return;
        }

        // 阶段二：抬头到正上方（-90°）。物理按住 shift——和手动操作完全一致
        // （客户端真实潜行、原版自行同步状态包）；水中 shift=下沉，正好帮忙钉底。
        InputController.sneak = true;
        float pitchDelta = -90.0f - player.getPitch();
        player.setPitch(Math.max(-90.0f, player.getPitch()
                + Math.max(-12.0f, Math.min(12.0f, pitchDelta))));
        if (Math.abs(-90.0f - player.getPitch()) > 3.0f) return;
        if (!waterSneakReady) {
            waterSneakReady = true; // 等潜行状态同步到服务器（2 tick）再点击
            graceTicks = 2;
            return;
        }
        // 诊断：报告这一下实际命中了什么，方便核对插件为何不响应
        msg("§7shift右键浇水器: 命中=" + describeHit(mc.crosshairTarget)
                + " 潜行=" + player.isSneaking());
        vanillaRightClick(mc, player);
        InputController.sneak = false;
        FarmItems.rememberWateringCanState(cfg, player.getMainHandStack());
        wateredAnyThisCycle = true;
        waterQueue.poll();
        enter(State.SCAN); // SCAN → stepWatering 继续下一个浇水点
    }

    /** 诊断用：描述准星命中的目标。 */
    private static String describeHit(HitResult hit) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            return "方块 " + net.minecraft.registry.Registries.BLOCK.getId(
                    mc.world.getBlockState(bhr.getBlockPos()).getBlock())
                    + " @" + bhr.getBlockPos().toShortString();
        }
        if (hit instanceof EntityHitResult ehr && hit.getType() == HitResult.Type.ENTITY) {
            return "实体 " + net.minecraft.registry.Registries.ENTITY_TYPE.getId(ehr.getEntity().getType());
        }
        return "落空(对空使用)";
    }

    /**
     * 完全模拟原版右键：按客户端准星实际命中的目标发包——
     * 命中方块发 interactBlock（插件的 RIGHT_CLICK_BLOCK 事件才会触发）；
     * 命中实体先发 INTERACT_AT（带命中点坐标，PlayerInteractAtEntityEvent 靠它触发，
     * interaction 实体的插件基本都监听这个），未被接受再补普通 INTERACT——顺序与原版一致；
     * 落空才发对空 interactItem。
     */
    private static void vanillaRightClick(MinecraftClient mc, ClientPlayerEntity player) {
        HitResult hit = mc.crosshairTarget;
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, bhr);
        } else if (hit instanceof EntityHitResult ehr && hit.getType() == HitResult.Type.ENTITY) {
            ActionResult r = mc.interactionManager.interactEntityAtLocation(
                    player, ehr.getEntity(), ehr, Hand.MAIN_HAND);
            if (!r.isAccepted()) {
                mc.interactionManager.interactEntity(player, ehr.getEntity(), Hand.MAIN_HAND);
            }
        } else {
            mc.interactionManager.interactItem(player, Hand.MAIN_HAND);
        }
        player.swingHand(Hand.MAIN_HAND);
    }

    // ---------- 工具 ----------

    /** 作物总开关：未定义的作物按开启处理（由其他检查负责提示）。 */
    private static boolean cropEnabled(FarmConfig cfg, String cropName) {
        FarmConfig.Crop crop = cfg.crops.get(cropName);
        return crop == null || crop.enabled;
    }

    /** 该作物的种子物品是否已定义；未定义时种子箱 matcher 恒 false，去了也白跑还触发 5 分钟冷却。 */
    private static boolean seedDefined(FarmConfig.Crop crop) {
        return crop != null && crop.seedItemId != null && crop.seedName != null;
    }

    // ---------- 侦察未加载区块 ----------

    /** 选一个需要侦察的目标：优先缓存中处于未加载区块的耕地，其次从没扫到过耕地且有未加载区块的 zone 中心。 */
    private BlockPos pickScoutTarget(List<FarmScanner.Plot> plots, ClientPlayerEntity player, FarmConfig cfg) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (FarmScanner.Plot p : plots) {
            if (p.state() != FarmScanner.PlotState.UNKNOWN) continue;
            double d = p.farmland().getSquaredDistance(player.getX(), player.getY(), player.getZ());
            if (d < bestD) {
                bestD = d;
                best = p.farmland();
            }
        }
        if (best != null) return best;

        // 缓存里一块耕地都没有的 zone：可能从启动起区块就没加载过，去中心看一眼
        MinecraftClient mc = MinecraftClient.getInstance();
        for (int i = 0; i < cfg.zones.size(); i++) {
            if (isZoneBlocked(i)) continue;
            FarmConfig.Region r = cfg.zones.get(i).box;
            if (r == null) continue;
            boolean hasCached = false;
            if (farmlandCache != null) {
                for (BlockPos f : farmlandCache) {
                    if (r.contains(f, 1)) {
                        hasCached = true;
                        break;
                    }
                }
            }
            if (hasCached || !hasUnloadedChunk(mc, r)) continue;
            BlockPos center = new BlockPos((r.minX() + r.maxX()) / 2,
                    (r.minY() + r.maxY()) / 2, (r.minZ() + r.maxZ()) / 2);
            double d = center.getSquaredDistance(player.getX(), player.getY(), player.getZ());
            if (d < bestD) {
                bestD = d;
                best = center;
            }
        }
        return best;
    }

    private static boolean hasUnloadedChunk(MinecraftClient mc, FarmConfig.Region r) {
        for (int cx = r.minX() >> 4; cx <= r.maxX() >> 4; cx++) {
            for (int cz = r.minZ() >> 4; cz <= r.maxZ() >> 4; cz++) {
                if (!mc.world.getChunkManager().isChunkLoaded(cx, cz)) return true;
            }
        }
        return false;
    }

    /** 朝未加载目标走：超过 40 格取中间跳点分段逼近（未加载区块内无法寻路，只能走近让它加载）。 */
    private void beginScout(MinecraftClient mc, ClientPlayerEntity player, BlockPos target) {
        if (!target.equals(announcedScoutTarget)) {
            announcedScoutTarget = target;
            msg("§7目标区域未加载（超出视距），前往 §e" + target.toShortString() + " §7查看成熟情况");
        }
        double dx = target.getX() + 0.5 - player.getX();
        double dz = target.getZ() + 0.5 - player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        BlockPos hop = target;
        double reach = 12.0;
        if (dist > 40) {
            double f = 40 / dist;
            hop = BlockPos.ofFloored(player.getX() + dx * f, player.getY(), player.getZ() + dz * f);
            reach = 10.0;
        }
        walkGoal = hop;
        walkReach = reach;
        afterWalk = State.SCAN;
        walkPitch = 0.0f; // 侦察远处未加载区块，平视
        pathRetries = 0;
        walkTotalTicks = 0;
        List<BlockPos> path = Pathfinder.findNear(mc.world, player.getBlockPos(), hop, reach, PATH_MAX_NODES);
        if (path == null || path.size() <= 1) {
            // 走不过去，或已贴着目标但区块仍没加载（视距太小）→ 冷却 30 秒防死循环
            scoutCooldownUntil = tickCounter + 20 * 30;
            scanCooldown = 40;
            if (path == null) {
                msg("§e去未加载区域的路走不通，30 秒后再试");
            }
            return;
        }
        pathExec = new PathExecutor(path).withPitch(walkPitch);
        enter(State.WALK);
    }


    /** 背包里是否有可以存进作物箱的东西（种子/洒水壶/食物/保留格除外）。 */
    private static boolean hasDepositable(ClientPlayerEntity player, FarmConfig cfg) {
        for (int i = 0; i < 36; i++) {
            if (i == com.worldutils.bot.DepositController.RESERVED_SLOT) continue;
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
            farmlandCache = FarmScanner.findFarmland(mc.world, cfg, farmlandCache);
            farmlandCacheAt = tickCounter;
        }
        return farmlandCache;
    }

    private boolean isBlacklisted(BlockPos farmland) {
        Long until = blacklist.get(farmland.asLong());
        return until != null && tickCounter < until;
    }

    private boolean isZoneBlocked(int zoneIndex) {
        Long until = zoneBlockedUntil.get(zoneIndex);
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
            mc.player.sendMessage(Text.literal("§2[种地]§r " + s), false);
        }
    }

    public String statusText() {
        FarmConfig cfg = FarmConfig.get();
        StringBuilder sb = new StringBuilder();
        sb.append("§2==== 种地状态 ====§r\n");
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
            sb.append("  §e").append(e.getKey()).append("§r")
                    .append(c.enabled ? "" : " §c[已停用]§r")
                    .append(" 种子=").append(c.seedName)
                    .append("(").append(c.seedItemId).append(")")
                    .append(" 种子箱=").append(c.seedChest != null ? c.seedChest : "§c未绑§r")
                    .append(" 专箱=").append(c.depositChests == null || c.depositChests.isEmpty()
                            ? "§7无(走全局)§r" : c.depositChests.size() + "个")
                    .append(" 成熟特征=").append(c.mature.size()).append("条")
                    .append(c.mature.isEmpty() ? "§c(只种不收)§r" : "").append("\n");
        }
        sb.append("全局作物箱(").append(cfg.cropChests.size()).append("): ");
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
        sb.append("浇水间隔: ").append(cfg.waterIntervalMinutes).append(" 分钟")
                .append(cfg.wateringEnabled ? "" : " §c[洒水已关闭]§r")
                .append("（每点：低头右键×随机0~3 + 抬头shift右键×1）");
        if (cfg.lastWaterTimeMs > 0 && !cfg.waterers.isEmpty()) {
            long nextIn = cfg.waterIntervalMinutes * 60_000L
                    - (System.currentTimeMillis() - cfg.lastWaterTimeMs);
            sb.append("§7（下一轮约 ").append(Math.max(0, nextIn / 60_000)).append(" 分钟后）");
        }
        sb.append("§r\n");
        sb.append("食物: ").append(cfg.food != null ? cfg.food : "§c未绑定")
                .append(" 食物箱=").append(cfg.foodChest != null ? cfg.foodChest : "§c未绑定")
                .append("§r\n");
        sb.append("收获方式: ").append(cfg.harvestMode)
                .append("  遍历策略: ").append("route".equals(cfg.harvestStrategy)
                        ? "最短路线(route)" : "随机(random)")
                .append("  速度模式: ").append(cfg.isFast()
                        ? "§afast(不停步/不对准/不下蹲)" : cfg.isMedium()
                        ? "§amedium(不停步/加速对准/不下蹲)" : "§7normal").append("§r");
        return sb.toString();
    }
}
