package com.worldutils.bot;

import com.worldutils.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * 主状态机：
 * TP_MINE（回放去矿点菜单操作）→ WAIT_MINE → MINING（下挖到目标y后直线前挖，锁定疾跑）
 *   → 背包满 / 镐子耐久不足 / 没药没吃的 → TP_HOME → WAIT_HOME
 *   → PATH_TO_CHEST → DEPOSIT（存物/换镐）→ [药水|食物 按需取用] → TP_MINE → ... 循环
 */
public class Bot {
    public static final Bot INSTANCE = new Bot();

    public enum State {
        IDLE,
        TP_MINE,
        WAIT_MINE,
        MINING,
        TP_HOME,
        WAIT_HOME,
        PATH_TO_CHEST,
        DEPOSIT,
        PATH_TO_PICK,
        SWAP_PICK,
        PATH_TO_POTION,
        TAKE_POTION,
        DRINK,
        PATH_TO_FOOD,
        TAKE_FOOD,
        EAT,
    }

    private State state = State.IDLE;
    private int ticksInState = 0;
    private int tpRetries = 0;
    private int pathRetries = 0;
    private int settleTicks = 0;

    private MenuNavigator nav;
    private MiningController mining;
    private DepositController deposit;
    private PathExecutor pathExecutor;
    private ItemTakeController itemTake;
    private PickaxeSwapController pickSwap;
    private DrinkController drink;
    private EatController eat;
    private boolean inPlace = false; // 在矿点原地喝药/吃东西（不用回家）

    private int chestIdx = 0;
    private int chestStartIdx = 0; // 记住满了的箱子，下次直接从没满的开始（省时间）
    private Vec3d refPos = null;   // 传送前参考位置（检测位置突变用）
    private String refDim = null;
    private boolean potionSkip = false; // 药水没了：跳过急迫逻辑继续挖（下次回家再试）

    private long pausedUntilMs = 0; // 出错暂停到期时间（永不停止，只暂停重试）
    private int deadTicks = 0;      // 死亡计时（用于节流重生请求）

    private static final int TP_WAIT_TIMEOUT = 20 * 60; // 等传送最多 60 秒
    private static final int MAX_TP_RETRIES = 3;
    private static final int ARRIVE_RADIUS = 64;
    private static final long HALT_RETRY_MS = 30_000; // 出错后 30 秒自动重试
    private static final long DAMAGE_PAUSE_MS = 10_000; // 受伤后暂停 10 秒

    private Bot() {}

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

    public void start() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        if (!com.worldutils.license.LicenseManager.isAuthorized()) {
            msg("§c未授权，无法使用。请向作者索取验证码后执行 §e/miner license <6位码>");
            return;
        }
        if (com.worldutils.farm.FarmBot.INSTANCE.isRunning()) {
            msg("§c自动种地运行中，请先 /farm stop");
            return;
        }
        if (Recorder.isActive()) {
            msg("§c正在录制中，请先完成或取消录制");
            return;
        }

        ModConfig cfg = ModConfig.get();
        List<String> missing = cfg.missing();
        if (!missing.isEmpty()) {
            msg("§c配置不完整，还需要: §e" + String.join("§7, §e", missing));
            return;
        }

        tpRetries = 0;
        pathRetries = 0;
        chestIdx = 0;
        chestStartIdx = 0; // 手动开始时重新检查所有箱子（可能被清空过）
        deadTicks = 0;
        pausedUntilMs = 0;
        com.worldutils.util.DamageWatch.reset(mc.player);

        boolean needHome = inventoryEmptySlots(mc.player) <= cfg.returnWhenEmptySlots
                || !PickaxeUtil.hasUsable(mc.player, cfg.minPickaxeDurability)
                || (cfg.potionChest != null
                    && !mc.player.hasStatusEffect(StatusEffects.HASTE)
                    && !ItemTakeController.hasItem(mc.player, cfg.potionItemId))
                || (cfg.foodChest != null
                    && mc.player.getHungerManager().getFoodLevel() < EatController.SPRINT_MIN
                    && !ItemTakeController.hasItem(mc.player, cfg.foodItemId));
        if (needHome) {
            InputController.disablePauseOnLostFocus(mc);
            msg("需要先回家处理（存物/换镐/拿药/拿食物）…");
            goHome(mc);
        } else {
            InputController.disablePauseOnLostFocus(mc);
            msg("传送去矿点…");
            goMine();
        }
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
     * 出错时不停止：打印原因、暂停 30 秒后自动重建循环。
     * 只有按 J 键或 /miner stop 才真正停止。
     */
    private void softHalt(String reason) {
        MinecraftClient mc = MinecraftClient.getInstance();
        msg("§e" + reason + "§7（不会停止，" + (HALT_RETRY_MS / 1000)
                + " 秒后自动重试；按 J 或 /miner stop 可手动停止）");
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
        nav = null;
        mining = null;
        deposit = null;
        pathExecutor = null;
        itemTake = null;
        pickSwap = null;
        drink = null;
        eat = null;
        inPlace = false;
    }

    private void enter(State s) {
        state = s;
        ticksInState = 0;
        settleTicks = 0;
        InputController.clear();
    }

    private void goMine() {
        nav = new MenuNavigator(ModConfig.get().tpMine);
        enter(State.TP_MINE);
    }

    private void goHome(MinecraftClient mc) {
        if (mc.interactionManager != null) {
            mc.interactionManager.cancelBlockBreaking();
        }
        mining = null;
        chestIdx = 0;
        potionSkip = false; // 每次回家重新尝试药水箱（可能已补货）
        nav = new MenuNavigator(ModConfig.get().tpHome);
        enter(State.TP_HOME);
    }

    public void tick(MinecraftClient mc) {
        Recorder.tick(mc);
        if (state == State.IDLE) return;
        if (!com.worldutils.license.LicenseManager.isAuthorized()) {
            stop("§c授权已到期，已停止。请用 /miner license <码> 重新激活");
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
            sendPublicChat(mc, "?");
            msg("§e受到伤害，暂停 " + (DAMAGE_PAUSE_MS / 1000) + " 秒后继续");
            pausedUntilMs = System.currentTimeMillis() + DAMAGE_PAUSE_MS;
            clearControllers();
            InputController.releaseAll(mc);
            if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
            return;
        }
        // 出错后的暂停期：不停止，等冷却结束自动重建循环
        if (pausedUntilMs > 0) {
            if (System.currentTimeMillis() < pausedUntilMs) {
                InputController.clear();
                InputController.apply(mc);
                return;
            }
            pausedUntilMs = 0;
            msg("§7自动重试…");
            start();
            return;
        }

        ticksInState++;
        ModConfig cfg = ModConfig.get();

        switch (state) {
            case TP_MINE -> tickNav(mc, player, State.WAIT_MINE);
            case WAIT_MINE -> tickWaitMine(mc, player, cfg);
            case MINING -> tickMining(mc, player, cfg);
            case TP_HOME -> tickNav(mc, player, State.WAIT_HOME);
            case WAIT_HOME -> tickWaitHome(mc, player, cfg);
            case PATH_TO_CHEST -> tickPathToChest(mc, player, cfg);
            case DEPOSIT -> tickDeposit(mc, player, cfg);
            case PATH_TO_PICK -> tickPathToPick(mc, player, cfg);
            case SWAP_PICK -> tickSwapPick(mc, player, cfg);
            case PATH_TO_POTION -> tickPathToItem(mc, player, cfg, true);
            case TAKE_POTION -> tickTakeItem(mc, player, cfg, true);
            case DRINK -> tickDrink(mc, player, cfg);
            case PATH_TO_FOOD -> tickPathToItem(mc, player, cfg, false);
            case TAKE_FOOD -> tickTakeItem(mc, player, cfg, false);
            case EAT -> tickEat(mc, player, cfg);
            default -> {}
        }

        InputController.apply(mc);
    }

    // ---------- 菜单传送 ----------

    private void tickNav(MinecraftClient mc, ClientPlayerEntity player, State next) {
        if (nav == null) {
            nav = new MenuNavigator(next == State.WAIT_MINE
                    ? ModConfig.get().tpMine : ModConfig.get().tpHome);
        }
        MenuNavigator.Result r = nav.tick(mc, player);
        switch (r) {
            case WORKING -> {}
            case DONE -> {
                nav = null;
                refPos = player.getPos();
                refDim = dim(mc);
                enter(next);
            }
            case FAILED -> {
                msg("§e菜单操作失败: " + nav.getFailReason());
                nav = null;
                player.closeHandledScreen();
                retryTp(next == State.WAIT_MINE ? State.TP_MINE : State.TP_HOME);
            }
        }
    }

    private void retryTp(State tpState) {
        tpRetries++;
        if (tpRetries >= MAX_TP_RETRIES) {
            softHalt("§c传送反复失败（已试 " + MAX_TP_RETRIES + " 次），请检查传送设置");
            return;
        }
        msg("§e重试传送（第 " + (tpRetries + 1) + " 次）…");
        nav = new MenuNavigator(tpState == State.TP_MINE
                ? ModConfig.get().tpMine : ModConfig.get().tpHome);
        enter(tpState);
    }

    /** 等到达矿点：检测位置突变/维度变化（矿点坐标未知）。 */
    private void tickWaitMine(MinecraftClient mc, ClientPlayerEntity player, ModConfig cfg) {
        boolean jumped = !dim(mc).equals(refDim)
                || player.getPos().squaredDistanceTo(refPos) > 8 * 8;

        if (jumped) {
            settleTicks++;
            if (settleTicks >= 30) {
                tpRetries = 0;
                beginMining(mc, player, cfg);
            }
            return;
        }
        settleTicks = 0;

        // 兜底：45 秒没有位置突变，且不在家（不挨着任何绑定箱子）→ 认为本来就在矿点
        if (ticksInState > 20 * 45) {
            if (!nearAnyChest(mc, player, 16)) {
                msg("§e未检测到传送跳变，假定已在矿点，直接开挖");
                tpRetries = 0;
                beginMining(mc, player, cfg);
            } else {
                retryTp(State.TP_MINE);
            }
        }
    }

    /** 等到达家：目标维度 + 靠近某个绑定箱子。 */
    private void tickWaitHome(MinecraftClient mc, ClientPlayerEntity player, ModConfig cfg) {
        boolean jumped = !dim(mc).equals(refDim)
                || player.getPos().squaredDistanceTo(refPos) > 8 * 8;
        boolean nearHome = dim(mc).equals(cfg.chestDim) && nearAnyChest(mc, player, ARRIVE_RADIUS);

        if (nearHome && (jumped || ticksInState > 20 * 3)) {
            settleTicks++;
            if (settleTicks >= 20) {
                tpRetries = 0;
                pathRetries = 0;
                // 从上次没满的箱子直接开始；都记满了就从头再试一轮（可能被清空过）
                if (chestStartIdx >= cfg.chests.size()) {
                    chestStartIdx = 0;
                }
                chestIdx = chestStartIdx;
                msg("§a到家了，寻路去箱子 #" + (chestIdx + 1) + "…");
                enter(State.PATH_TO_CHEST);
            }
            return;
        }
        settleTicks = 0;

        if (ticksInState > TP_WAIT_TIMEOUT) {
            retryTp(State.TP_HOME);
        }
    }

    private void beginMining(MinecraftClient mc, ClientPlayerEntity player, ModConfig cfg) {
        // 朝向：第一次自动记录当前视角朝向，之后固定使用
        if (cfg.getFacing() == null) {
            cfg.facing = player.getHorizontalFacing().name();
            ModConfig.save();
            msg("§a已自动记录挖矿朝向: §e" + cfg.facing + "§7（/miner face 可重设）");
        }
        mining = new MiningController(cfg.targetY, cfg.getFacing(), cfg.minPickaxeDurability);
        msg("§a开始挖矿: 目标 y=" + cfg.targetY + "，朝向 " + cfg.facing);
        enter(State.MINING);
    }

    // ---------- 挖矿 ----------

    private void tickMining(MinecraftClient mc, ClientPlayerEntity player, ModConfig cfg) {
        if (inventoryEmptySlots(player) <= cfg.returnWhenEmptySlots) {
            msg("背包满了，回家存物…");
            goHome(mc);
            return;
        }
        if (!PickaxeUtil.hasUsable(player, cfg.minPickaxeDurability)) {
            msg("镐子耐久不足（<" + cfg.minPickaxeDurability + "），回家换镐…");
            goHome(mc);
            return;
        }

        // 急迫效果检查（绑定了药水箱才启用；药水耗尽则跳过，无急迫继续挖）
        if (cfg.potionChest != null && !potionSkip && !player.hasStatusEffect(StatusEffects.HASTE)) {
            if (ItemTakeController.hasItem(player, cfg.potionItemId)) {
                msg("急迫效果没了，原地喝一瓶…");
                inPlace = true;
                drink = new DrinkController();
                enter(State.DRINK);
            } else {
                msg("急迫效果没了且没有药水，回家清背包+拿药…");
                goHome(mc);
            }
            return;
        }

        // 饥饿检查（绑定了食物箱才启用）：保证能一直疾跑
        if (cfg.foodChest != null) {
            int food = player.getHungerManager().getFoodLevel();
            boolean hasFood = ItemTakeController.hasItem(player, cfg.foodItemId);
            if (food <= EatController.EAT_BELOW && hasFood) {
                msg("饿了，原地吃点东西…");
                inPlace = true;
                eat = new EatController();
                enter(State.EAT);
                return;
            }
            if (food < EatController.SPRINT_MIN && !hasFood) {
                msg("没吃的了且饿得跑不动，回家拿食物…");
                goHome(mc);
                return;
            }
        }

        if (mining == null) {
            beginMining(mc, player, cfg);
            return;
        }

        MiningController.Result r = mining.tick(mc, player);
        if (r == MiningController.Result.HAZARD_STOP) {
            softHalt("§c" + mining.getHazardMessage());
        }
    }

    // ---------- 寻路到存物箱 ----------

    private void tickPathToChest(MinecraftClient mc, ClientPlayerEntity player, ModConfig cfg) {
        BlockPos chest = cfg.chests.get(chestIdx).toBlockPos();

        if (pathExecutor == null) {
            List<BlockPos> path = Pathfinder.findToChest(mc.world, player.getBlockPos(), chest, 20000);
            if (path == null) {
                msg("§e走不到箱子 #" + (chestIdx + 1) + " " + cfg.chests.get(chestIdx));
                if (!nextChest(cfg)) {
                    softHalt("§c所有绑定箱子都无法到达");
                }
                return;
            }
            pathExecutor = new PathExecutor(path);
            return;
        }

        pathExecutor.tick(player);

        if (pathExecutor.isDone()) {
            pathExecutor = null;
            pathRetries = 0;
            // 绑定了专用镐子箱就不从普通箱子取镐
            boolean needPickaxe = cfg.pickChest == null
                    && !PickaxeUtil.hasUsable(player, cfg.minPickaxeDurability);
            deposit = new DepositController(chest, needPickaxe, cfg.minPickaxeDurability);
            msg("到达箱子 #" + (chestIdx + 1) + "，开始存放…");
            enter(State.DEPOSIT);
            return;
        }

        if (pathExecutor.isStuck() || ticksInState > 20 * 60) {
            pathExecutor = null;
            pathRetries++;
            if (pathRetries >= 4) {
                softHalt("§c寻路反复卡住");
            }
        }
    }

    /** 切到下一个绑定箱子；没有了返回 false。 */
    private boolean nextChest(ModConfig cfg) {
        chestIdx++;
        pathExecutor = null;
        deposit = null;
        if (chestIdx >= cfg.chests.size()) {
            return false;
        }
        msg("§7试下一个箱子 #" + (chestIdx + 1) + " " + cfg.chests.get(chestIdx));
        enter(State.PATH_TO_CHEST);
        return true;
    }

    // ---------- 存物 / 换镐 ----------

    private void tickDeposit(MinecraftClient mc, ClientPlayerEntity player, ModConfig cfg) {
        if (deposit == null) {
            boolean needPickaxe = cfg.pickChest == null
                    && !PickaxeUtil.hasUsable(player, cfg.minPickaxeDurability);
            deposit = new DepositController(cfg.chests.get(chestIdx).toBlockPos(),
                    needPickaxe, cfg.minPickaxeDurability);
        }

        DepositController.Result r = deposit.tick(mc, player);
        if (r == DepositController.Result.WORKING) return;

        deposit = null;
        if (r == DepositController.Result.FAILED) {
            msg("§e打不开箱子 #" + (chestIdx + 1));
            if (!nextChest(cfg)) {
                softHalt("§c所有箱子都打不开/用不了");
            }
            return;
        }

        // DONE 或 CHEST_FULL：检查还差什么
        // 满了的箱子记下来，之后的行程直接跳过，不再浪费时间
        if (r == DepositController.Result.CHEST_FULL && chestIdx == chestStartIdx) {
            chestStartIdx = chestIdx + 1;
            if (chestStartIdx < cfg.chests.size()) {
                msg("§7箱子 #" + (chestIdx + 1) + " 满了，以后直接从 #" + (chestStartIdx + 1) + " 开始存");
            }
        }
        boolean itemsRemain = hasNonKeepItems(player, cfg.minPickaxeDurability);
        // 有专用镐子箱时，镐的事交给镐子箱环节处理
        boolean pickaxeOk = cfg.pickChest != null
                || PickaxeUtil.hasUsable(player, cfg.minPickaxeDurability);

        if (!itemsRemain && pickaxeOk) {
            int empty = inventoryEmptySlots(player);
            if (empty <= cfg.returnWhenEmptySlots) {
                softHalt("§c背包被保留物品占满，无法继续");
                return;
            }
            afterHomeTasks(mc, player, cfg);
            return;
        }

        // 还有物品没存完 / 还没拿到镐 → 下一个箱子
        if (!nextChest(cfg)) {
            if (itemsRemain) {
                softHalt("§c所有绑定箱子都满了，物品存不完");
            } else {
                softHalt("§c所有箱子里都没有耐久充足的镐子");
            }
        }
    }

    /**
     * 存物/换镐完成后的"回家任务链"：按需拿药喝药 → 按需拿食物吃 → 返回矿点。
     * 每完成一步会重新进入这里检查下一步。
     */
    private void afterHomeTasks(MinecraftClient mc, ClientPlayerEntity player, ModConfig cfg) {
        // 镐子箱：没可用镐要去拿；背包里攒着旧镐也顺路放掉
        if (cfg.pickChest != null
                && (!PickaxeUtil.hasUsable(player, cfg.minPickaxeDurability)
                    || hasWornPickaxes(player, cfg.minPickaxeDurability))) {
            msg("去镐子箱换镐…");
            pathRetries = 0;
            enter(State.PATH_TO_PICK);
            return;
        }
        // 药水
        if (cfg.potionChest != null && !potionSkip && !player.hasStatusEffect(StatusEffects.HASTE)) {
            if (ItemTakeController.hasItem(player, cfg.potionItemId)) {
                msg("先喝一瓶急迫药水…");
                inPlace = false;
                drink = new DrinkController();
                enter(State.DRINK);
            } else {
                msg("去药水箱拿药…");
                pathRetries = 0;
                enter(State.PATH_TO_POTION);
            }
            return;
        }
        // 食物
        if (cfg.foodChest != null) {
            boolean hasFood = ItemTakeController.hasItem(player, cfg.foodItemId);
            if (!hasFood) {
                msg("去食物箱拿食物…");
                pathRetries = 0;
                enter(State.PATH_TO_FOOD);
                return;
            }
            if (player.getHungerManager().getFoodLevel() <= EatController.EAT_BELOW) {
                msg("先吃点东西…");
                inPlace = false;
                eat = new EatController();
                enter(State.EAT);
                return;
            }
        }
        msg("§a补给完成，返回矿点…");
        goMine();
    }

    // ---------- 镐子箱：寻路 / 换镐 ----------

    private void tickPathToPick(MinecraftClient mc, ClientPlayerEntity player, ModConfig cfg) {
        BlockPos chest = cfg.pickChest.toBlockPos();

        if (pathExecutor == null) {
            List<BlockPos> path = Pathfinder.findToChest(mc.world, player.getBlockPos(), chest, 20000);
            if (path == null) {
                softHalt("§c走不到镐子箱 " + cfg.pickChest);
                return;
            }
            pathExecutor = new PathExecutor(path);
            return;
        }

        pathExecutor.tick(player);

        if (pathExecutor.isDone()) {
            pathExecutor = null;
            pathRetries = 0;
            pickSwap = new PickaxeSwapController(chest, cfg.minPickaxeDurability);
            msg("到达镐子箱，换镐…");
            enter(State.SWAP_PICK);
            return;
        }

        if (pathExecutor.isStuck() || ticksInState > 20 * 60) {
            pathExecutor = null;
            pathRetries++;
            if (pathRetries >= 4) {
                softHalt("§c去镐子箱的路反复卡住");
            }
        }
    }

    private void tickSwapPick(MinecraftClient mc, ClientPlayerEntity player, ModConfig cfg) {
        if (pickSwap == null) {
            pickSwap = new PickaxeSwapController(cfg.pickChest.toBlockPos(), cfg.minPickaxeDurability);
        }
        PickaxeSwapController.Result r = pickSwap.tick(mc, player);
        if (r == PickaxeSwapController.Result.WORKING) return;

        String reason = pickSwap.getFailReason();
        pickSwap = null;
        if (r == PickaxeSwapController.Result.FAILED) {
            // 背包里还有能用的镐就继续，否则只能停（没镐挖不了）
            if (PickaxeUtil.hasUsable(player, cfg.minPickaxeDurability)) {
                msg("§e" + reason + "，先用背包里的镐继续");
                afterHomeTasks(mc, player, cfg);
            } else {
                softHalt("§c" + reason);
            }
            return;
        }
        msg("§a镐子已备好");
        afterHomeTasks(mc, player, cfg);
    }

    // ---------- 药水/食物：寻路 / 取物 ----------

    private void tickPathToItem(MinecraftClient mc, ClientPlayerEntity player, ModConfig cfg,
                                boolean potion) {
        ModConfig.Pos target = potion ? cfg.potionChest : cfg.foodChest;
        String label = potion ? "药水箱" : "食物箱";
        BlockPos chest = target.toBlockPos();

        if (pathExecutor == null) {
            List<BlockPos> path = Pathfinder.findToChest(mc.world, player.getBlockPos(), chest, 20000);
            if (path == null) {
                if (potion) {
                    msg("§e走不到药水箱，先不用药水继续挖");
                    potionSkip = true;
                    afterHomeTasks(mc, player, cfg);
                } else {
                    softHalt("§c走不到" + label + " " + target);
                }
                return;
            }
            pathExecutor = new PathExecutor(path);
            return;
        }

        pathExecutor.tick(player);

        if (pathExecutor.isDone()) {
            pathExecutor = null;
            pathRetries = 0;
            if (potion) {
                itemTake = new ItemTakeController(chest,
                        () -> ModConfig.get().potionItemId,
                        id -> { ModConfig.get().potionItemId = id; ModConfig.save(); },
                        "药水箱", 4);
                msg("到达药水箱，取药…");
                enter(State.TAKE_POTION);
            } else {
                itemTake = new ItemTakeController(chest,
                        () -> ModConfig.get().foodItemId,
                        id -> { ModConfig.get().foodItemId = id; ModConfig.save(); },
                        "食物箱", 1);
                msg("到达食物箱，拿食物…");
                enter(State.TAKE_FOOD);
            }
            return;
        }

        if (pathExecutor.isStuck() || ticksInState > 20 * 60) {
            pathExecutor = null;
            pathRetries++;
            if (pathRetries >= 4) {
                if (potion) {
                    msg("§e去药水箱的路反复卡住，先不用药水继续挖");
                    potionSkip = true;
                    afterHomeTasks(mc, player, cfg);
                } else {
                    softHalt("§c去" + label + "的路反复卡住");
                }
            }
        }
    }

    private void tickTakeItem(MinecraftClient mc, ClientPlayerEntity player, ModConfig cfg,
                              boolean potion) {
        if (itemTake == null) {
            afterHomeTasks(mc, player, cfg);
            return;
        }
        ItemTakeController.Result r = itemTake.tick(mc, player);
        if (r == ItemTakeController.Result.WORKING) return;

        String reason = itemTake.getFailReason();
        itemTake = null;
        if (r == ItemTakeController.Result.FAILED) {
            if (potion) {
                // 药水没了不停止：跳过急迫，继续挖矿（下次回家再看看有没有补货）
                msg("§e" + reason + "，先不用药水继续挖");
                potionSkip = true;
                afterHomeTasks(mc, player, cfg);
            } else {
                softHalt("§c" + reason);
            }
            return;
        }
        if (potion) {
            inPlace = false;
            drink = new DrinkController();
            msg("拿到药水，喝一瓶…");
            enter(State.DRINK);
        } else {
            // 拿到食物后重走任务链（饿了会自动进入吃东西）
            afterHomeTasks(mc, player, cfg);
        }
    }

    // ---------- 喝药 / 吃东西 ----------

    private void tickDrink(MinecraftClient mc, ClientPlayerEntity player, ModConfig cfg) {
        if (drink == null) {
            drink = new DrinkController();
        }
        DrinkController.Result r = drink.tick(mc, player);
        if (r == DrinkController.Result.WORKING) return;

        String reason = drink.getFailReason();
        drink = null;
        InputController.clear();
        if (r == DrinkController.Result.FAILED) {
            // 喝药失败不停止：跳过急迫继续挖
            msg("§e" + reason + "，先不用药水继续挖");
            potionSkip = true;
            if (inPlace) {
                inPlace = false;
                enter(State.MINING);
            } else {
                afterHomeTasks(mc, player, cfg);
            }
            return;
        }
        msg("§a已获得急迫效果");
        if (inPlace) {
            inPlace = false;
            enter(State.MINING); // 矿点原地喝完直接继续挖
        } else {
            afterHomeTasks(mc, player, cfg);
        }
    }

    private void tickEat(MinecraftClient mc, ClientPlayerEntity player, ModConfig cfg) {
        if (eat == null) {
            eat = new EatController();
        }
        EatController.Result r = eat.tick(mc, player);
        if (r == EatController.Result.WORKING) return;

        String reason = eat.getFailReason();
        eat = null;
        InputController.clear();
        if (r == EatController.Result.FAILED) {
            softHalt("§c" + reason);
            return;
        }
        msg("§a吃饱了（饥饿值 " + player.getHungerManager().getFoodLevel() + "）");
        if (inPlace) {
            inPlace = false;
            enter(State.MINING);
        } else {
            afterHomeTasks(mc, player, cfg);
        }
    }

    // ---------- 工具 ----------

    public static int inventoryEmptySlots(ClientPlayerEntity player) {
        int empty = 0;
        for (int i = 0; i < 36; i++) {
            if (player.getInventory().getStack(i).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    /** 背包里是否还有需要存入箱子的物品（保留格除外）。 */
    private static boolean hasNonKeepItems(ClientPlayerEntity player, int threshold) {
        for (int i = 0; i < 36; i++) {
            if (i == DepositController.RESERVED_SLOT) continue;
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && !DepositController.shouldKeep(stack, threshold)) {
                return true;
            }
        }
        return false;
    }

    /** 背包里是否有耐久不足的旧镐（该送去镐子箱）。 */
    private static boolean hasWornPickaxes(ClientPlayerEntity player, int threshold) {
        for (int i = 0; i < 36; i++) {
            if (i == DepositController.RESERVED_SLOT) continue;
            if (PickaxeUtil.isWorn(player.getInventory().getStack(i), threshold)) {
                return true;
            }
        }
        return false;
    }

    private boolean nearAnyChest(MinecraftClient mc, ClientPlayerEntity player, double radius) {
        ModConfig cfg = ModConfig.get();
        if (!dim(mc).equals(cfg.chestDim)) return false;
        for (ModConfig.Pos p : cfg.chests) {
            if (player.getBlockPos().isWithinDistance(p.toBlockPos(), radius)) {
                return true;
            }
        }
        return false;
    }

    private static String dim(MinecraftClient mc) {
        return mc.world.getRegistryKey().getValue().toString();
    }

    public static void msg(String s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("§6[挖矿]§r " + s), false);
        }
    }

    /** 发一条真正的公屏聊天（会发送到服务器，其他玩家可见）。 */
    public static void sendPublicChat(MinecraftClient mc, String text) {
        if (mc.player != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendChatMessage(text);
        }
    }

    public String statusText() {
        ModConfig cfg = ModConfig.get();
        StringBuilder sb = new StringBuilder();
        sb.append("§6==== 挖矿状态 ====§r\n");
        sb.append("运行状态: ").append(isRunning() ? "§a" + state : "§7空闲").append("§r\n");
        sb.append("箱子(").append(cfg.chests.size()).append("个): ");
        for (int i = 0; i < cfg.chests.size(); i++) {
            sb.append("#").append(i + 1).append(cfg.chests.get(i)).append(" ");
        }
        if (cfg.chests.isEmpty()) sb.append("§c未绑定");
        sb.append("§r\n");
        sb.append("箱子维度: ").append(cfg.chestDim != null ? cfg.chestDim : "§c-").append("§r\n");
        sb.append("挖矿高度 y: ").append(cfg.targetY != null ? cfg.targetY : "§c未设置").append("§r\n");
        sb.append("挖矿朝向: ").append(cfg.facing != null ? cfg.facing : "§7自动(开挖时记录)").append("§r\n");
        sb.append("回家操作: ").append(cfg.tpHome != null ? cfg.tpHome : "§c未设置").append("§r\n");
        sb.append("去矿操作: ").append(cfg.tpMine != null ? cfg.tpMine : "§c未设置").append("§r\n");
        sb.append("镐子耐久阈值: ").append(cfg.minPickaxeDurability).append("§r\n");
        sb.append("镐子箱: ").append(cfg.pickChest != null
                ? cfg.pickChest.toString() : "§7未绑定(用普通箱子换镐)").append("§r\n");
        sb.append("药水箱: ").append(cfg.potionChest != null
                ? cfg.potionChest + (cfg.potionItemId != null ? " (" + cfg.potionItemId + ")" : "")
                : "§7未绑定(急迫功能关闭)").append("§r\n");
        sb.append("食物箱: ").append(cfg.foodChest != null
                ? cfg.foodChest + (cfg.foodItemId != null ? " (" + cfg.foodItemId + ")" : "")
                : "§7未绑定(自动进食关闭)").append("§r\n");
        sb.append("保留物品: ").append(String.join(", ", cfg.keepIds));
        return sb.toString();
    }
}
