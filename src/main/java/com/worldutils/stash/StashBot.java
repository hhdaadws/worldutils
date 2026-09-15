package com.worldutils.stash;

import com.worldutils.bot.Bot;
import com.worldutils.bot.EatController;
import com.worldutils.bot.InputController;
import com.worldutils.bot.ItemTakeController;
import com.worldutils.bot.PathExecutor;
import com.worldutils.bot.Pathfinder;
import com.worldutils.farm.FarmBot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.List;

/**
 * 定时存物（独立于挖矿/种地）：每隔 intervalMinutes 分钟，把背包除快捷栏（0..8）
 * 和保留格（索引 9）之外的所有物品（索引 10..35）存进绑定的箱子，存完走回原位继续等。
 * 绑定食物箱后附带自动进食：饥饿值低于 14 先吃包里的，没食物走去食物箱拿。
 *
 * WAITING（计时）→ PATH_TO_CHEST → DEPOSIT（多箱轮换）→ PATH_BACK（回出发点）→ WAITING 循环
 * WAITING（饿了）→ EAT（包里有食物就地吃）或 PATH_TO_FOOD → TAKE_FOOD → EAT → PATH_BACK
 *
 * 与挖矿/种地互斥：它们运行时定时器只推迟不触发（它们各自有存物环节）；
 * 存物途中对方启动则立即让位（输入和 pauseOnLostFocus 交给对方接管/恢复）。
 */
public class StashBot {
    public static final StashBot INSTANCE = new StashBot();

    public enum State { IDLE, WAITING, PATH_TO_CHEST, DEPOSIT, PATH_TO_FOOD, TAKE_FOOD, EAT, PATH_BACK, RESTORE_FACING }

    private State state = State.IDLE;
    private int ticksInState = 0;
    private long nextRunMs = 0;
    private long nextFoodMs = 0; // 进食失败后的冷却（与存物计时互不影响）
    private int chestIdx = 0;
    private int pathRetries = 0;
    private boolean anyChestFull = false;
    private boolean deferMsgSent = false; // 维度不对的提示只发一次
    private boolean foodRun = false;      // 当前这轮是进食（而不是存物）
    private int deadTicks = 0;

    private PathExecutor pathExecutor;
    private StashDepositController deposit;
    private ItemTakeController itemTake;
    private EatController eat;
    private BlockPos returnPos; // 出发位置，存完/吃完走回来
    private Float returnYaw;    // 出发时的朝向，走回来后平滑转回去
    private Float returnPitch;

    private static final long RETRY_MS = 30_000;          // 出错后 30 秒重试
    private static final int PATH_TIMEOUT_TICKS = 20 * 60; // 单段寻路最长 60 秒
    private static final int DEPOSIT_TIMEOUT_TICKS = 600;  // 存物兜底超时（desync/丢包不许卡死）

    private StashBot() {}

    public boolean isRunning() {
        return state != State.IDLE;
    }

    /** 正在实际走路/存物/进食（占用输入），而不是纯等计时。 */
    public boolean isBusy() {
        return state != State.IDLE && state != State.WAITING;
    }

    public void start() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        if (isRunning()) {
            msg("§e定时存物已在运行（/stash status 查看，/stash stop 关闭）");
            return;
        }
        if (!com.worldutils.license.LicenseManager.isAuthorized()) {
            msg("§c未授权，无法使用。请向作者索取验证码后执行 §e/miner license <6位码>");
            return;
        }
        StashConfig cfg = StashConfig.get();
        if (cfg.chests.isEmpty()) {
            msg("§c还没有绑定存物箱：把准星对准箱子执行 §e/stash bind");
            return;
        }
        deferMsgSent = false;
        deadTicks = 0;
        nextRunMs = System.currentTimeMillis() + cfg.intervalMinutes * 60_000L;
        state = State.WAITING;
        msg("§a定时存物已开启：每 §e" + cfg.intervalMinutes + "§a 分钟存一次（快捷栏和背包第一格保留）"
                + "\n§7首轮 " + cfg.intervalMinutes + " 分钟后开始；/stash now 立即存一次，/stash stop 关闭");
    }

    public void stop(String reason) {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean wasBusy = isBusy();
        state = State.IDLE;
        clearRun();
        if (wasBusy) {
            InputController.releaseAll(mc);
            InputController.restorePauseOnLostFocus(mc);
        }
        if (reason != null) {
            msg(reason);
        }
    }

    /** 断线等场景的强制复位（不发消息）。 */
    public void hardStop() {
        state = State.IDLE;
        clearRun();
    }

    /** 立即触发一轮存物（无视计时）。 */
    public void triggerNow() {
        if (!isRunning()) {
            msg("§c定时存物未开启，请先 /stash start");
            return;
        }
        if (isBusy()) {
            msg("§e正在存物中");
            return;
        }
        nextRunMs = 0;
        msg("§a将立即执行一轮存物");
    }

    /** 修改间隔后重新计时（仅计时等待中生效）。 */
    public void rescheduleFromNow() {
        if (state == State.WAITING) {
            nextRunMs = System.currentTimeMillis() + StashConfig.get().intervalMinutes * 60_000L;
        }
    }

    public void tick(MinecraftClient mc) {
        if (state == State.IDLE) return;
        if (!com.worldutils.license.LicenseManager.isAuthorized()) {
            stop("§c授权已到期，定时存物已停止。请用 /miner license <码> 重新激活");
            return;
        }

        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            if (isBusy()) {
                abortRun(false);
            }
            return;
        }

        if (state == State.WAITING) {
            tickWaiting(mc, player);
            return;
        }

        // ---- 存物途中 ----
        // 挖矿/种地启动 → 立即让位（对方 enter() 会清输入并接管 pauseOnLostFocus 的恢复）
        if (Bot.INSTANCE.isRunning() || FarmBot.INSTANCE.isRunning()) {
            abortRun(true);
            return;
        }
        // 死亡不停止：自动重生后本轮从当前位置重新寻路
        if (!player.isAlive()) {
            InputController.clear();
            InputController.apply(mc);
            pathExecutor = null;
            deposit = null;
            itemTake = null;
            eat = null;
            if (deadTicks++ % 20 == 0) {
                player.requestRespawn();
                if (mc.currentScreen != null) mc.setScreen(null);
            }
            return;
        }
        deadTicks = 0;
        ticksInState++;

        switch (state) {
            case PATH_TO_CHEST -> tickPathToChest(mc, player);
            case DEPOSIT -> tickDeposit(mc, player);
            case PATH_TO_FOOD -> tickPathToFood(mc, player);
            case TAKE_FOOD -> tickTakeFood(mc, player);
            case EAT -> tickEat(mc, player);
            case PATH_BACK -> tickPathBack(mc, player);
            case RESTORE_FACING -> tickRestoreFacing(player);
            default -> {}
        }

        if (isBusy()) {
            InputController.apply(mc);
        }
    }

    // ---------- 计时等待 ----------

    private void tickWaiting(MinecraftClient mc, ClientPlayerEntity player) {
        // 挖矿/种地运行中不抢（它们自己有存物/进食环节），结束后到点自动触发
        if (Bot.INSTANCE.isRunning() || FarmBot.INSTANCE.isRunning()) return;
        if (!player.isAlive()) return;
        // 玩家自己开着容器时不抢界面，等关掉再触发
        if (player.currentScreenHandler != null && player.currentScreenHandler.syncId != 0) return;

        StashConfig cfg = StashConfig.get();
        String dim = mc.world.getRegistryKey().getValue().toString();
        boolean dimOk = cfg.chestDim == null || cfg.chestDim.equals(dim);

        // 饿了优先吃（与存物计时无关）：包里有食物就地吃，没有就走去食物箱拿
        if (cfg.foodChest != null && System.currentTimeMillis() >= nextFoodMs
                && player.getHungerManager().getFoodLevel() < EatController.EAT_BELOW) {
            if (ItemTakeController.hasItem(player, cfg.foodItemId)) {
                beginEat(mc, null);
                return;
            }
            if (dimOk) {
                beginFoodRun(mc, player);
                return;
            }
        }

        if (System.currentTimeMillis() < nextRunMs) return;

        if (cfg.chests.isEmpty()) {
            stop("§c存物箱已全部解绑，定时存物停止");
            return;
        }
        if (!dimOk) {
            if (!deferMsgSent) {
                msg("§e当前不在存物箱所在维度（" + cfg.chestDim + "），回去后自动继续");
                deferMsgSent = true;
            }
            nextRunMs = System.currentTimeMillis() + RETRY_MS;
            return;
        }
        deferMsgSent = false;
        if (!StashDepositController.hasDepositable(player)) {
            // 没东西可存，静默跳过本轮
            nextRunMs = System.currentTimeMillis() + cfg.intervalMinutes * 60_000L;
            return;
        }

        foodRun = false;
        returnPos = player.getBlockPos();
        returnYaw = player.getYaw();
        returnPitch = player.getPitch();
        chestIdx = 0;
        pathRetries = 0;
        anyChestFull = false;
        pathExecutor = null;
        deposit = null;
        InputController.disablePauseOnLostFocus(mc);
        msg("到点存物：走向箱子 #1 …");
        enter(State.PATH_TO_CHEST);
    }

    /** 就地吃（returnPos=null）或吃完走回出发点；吃完都转回原朝向。 */
    private void beginEat(MinecraftClient mc, BlockPos back) {
        foodRun = true;
        returnPos = back;
        if (mc.player != null) {
            returnYaw = mc.player.getYaw();
            returnPitch = mc.player.getPitch();
        }
        eat = null;
        InputController.disablePauseOnLostFocus(mc);
        enter(State.EAT);
    }

    private void beginFoodRun(MinecraftClient mc, ClientPlayerEntity player) {
        foodRun = true;
        returnPos = player.getBlockPos();
        returnYaw = player.getYaw();
        returnPitch = player.getPitch();
        pathRetries = 0;
        pathExecutor = null;
        itemTake = null;
        InputController.disablePauseOnLostFocus(mc);
        msg("饿了且包里没食物：走向食物箱…");
        enter(State.PATH_TO_FOOD);
    }

    // ---------- 寻路到箱子 ----------

    private void tickPathToChest(MinecraftClient mc, ClientPlayerEntity player) {
        StashConfig cfg = StashConfig.get();
        BlockPos chest = cfg.chests.get(chestIdx).toBlockPos();

        if (pathExecutor == null) {
            List<BlockPos> path = Pathfinder.findToChest(mc.world, player.getBlockPos(), chest, 20000);
            if (path == null) {
                msg("§e走不到箱子 #" + (chestIdx + 1) + " " + cfg.chests.get(chestIdx));
                if (!nextChest()) {
                    endRun("§c所有存物箱都无法到达");
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
            deposit = new StashDepositController(chest);
            enter(State.DEPOSIT);
            return;
        }

        if (pathExecutor.isStuck() || ticksInState > PATH_TIMEOUT_TICKS) {
            pathExecutor = null;
            pathRetries++;
            if (pathRetries >= 4) {
                endRun("§c寻路反复卡住");
            }
        }
    }

    /** 切到下一个绑定箱子；没有了返回 false。 */
    private boolean nextChest() {
        chestIdx++;
        pathExecutor = null;
        deposit = null;
        if (chestIdx >= StashConfig.get().chests.size()) {
            return false;
        }
        msg("§7试下一个箱子 #" + (chestIdx + 1));
        enter(State.PATH_TO_CHEST);
        return true;
    }

    // ---------- 存物 ----------

    private void tickDeposit(MinecraftClient mc, ClientPlayerEntity player) {
        if (deposit == null) {
            deposit = new StashDepositController(StashConfig.get().chests.get(chestIdx).toBlockPos());
        }
        if (ticksInState > DEPOSIT_TIMEOUT_TICKS) {
            player.closeHandledScreen();
            msg("§e箱子 #" + (chestIdx + 1) + " 存物超时");
            if (!nextChest()) {
                endRun("§c存物反复超时");
            }
            return;
        }

        StashDepositController.Result r = deposit.tick(mc, player);
        if (r == StashDepositController.Result.WORKING) return;

        deposit = null;
        if (r == StashDepositController.Result.FAILED) {
            msg("§e打不开箱子 #" + (chestIdx + 1));
            if (!nextChest()) {
                endRun("§c所有箱子都打不开");
            }
            return;
        }

        if (r == StashDepositController.Result.CHEST_FULL) {
            anyChestFull = true;
        }
        if (!StashDepositController.hasDepositable(player)) {
            goBack(player);
            return;
        }
        // 还有剩余 → 下一个箱子；全试过仍剩（都满了）→ 本轮结束，下轮再存
        if (!nextChest()) {
            msg("§e箱子放不下，剩余物品下轮再存");
            goBack(player);
        }
    }

    // ---------- 食物：寻路 / 取物 / 吃 ----------

    private void tickPathToFood(MinecraftClient mc, ClientPlayerEntity player) {
        StashConfig cfg = StashConfig.get();
        if (cfg.foodChest == null) {
            endRun("§c食物箱已解绑");
            return;
        }
        BlockPos chest = cfg.foodChest.toBlockPos();

        if (pathExecutor == null) {
            List<BlockPos> path = Pathfinder.findToChest(mc.world, player.getBlockPos(), chest, 20000);
            if (path == null) {
                endRun("§c走不到食物箱 " + cfg.foodChest);
                return;
            }
            pathExecutor = new PathExecutor(path);
            return;
        }

        pathExecutor.tick(player);

        if (pathExecutor.isDone()) {
            pathExecutor = null;
            pathRetries = 0;
            // 与存物箱同一约定：开箱前切到第 9 格快捷栏，避免主手物品被右键使用
            player.getInventory().setSelectedSlot(8);
            itemTake = new ItemTakeController(chest,
                    () -> StashConfig.get().foodItemId,
                    id -> { StashConfig.get().foodItemId = id; StashConfig.save(); },
                    "食物箱", 1, StashBot::msg);
            msg("到达食物箱，拿食物…");
            enter(State.TAKE_FOOD);
            return;
        }

        if (pathExecutor.isStuck() || ticksInState > PATH_TIMEOUT_TICKS) {
            pathExecutor = null;
            pathRetries++;
            if (pathRetries >= 4) {
                endRun("§c去食物箱的路反复卡住");
            }
        }
    }

    private void tickTakeFood(MinecraftClient mc, ClientPlayerEntity player) {
        if (itemTake == null) {
            itemTake = new ItemTakeController(StashConfig.get().foodChest.toBlockPos(),
                    () -> StashConfig.get().foodItemId,
                    id -> { StashConfig.get().foodItemId = id; StashConfig.save(); },
                    "食物箱", 1, StashBot::msg);
        }

        ItemTakeController.Result r = itemTake.tick(mc, player);
        if (r == ItemTakeController.Result.WORKING) return;

        String fail = itemTake.getFailReason();
        itemTake = null;
        if (r == ItemTakeController.Result.FAILED) {
            endRun("§c" + fail);
            return;
        }
        enter(State.EAT);
    }

    private void tickEat(MinecraftClient mc, ClientPlayerEntity player) {
        if (eat == null) {
            eat = new EatController(() -> StashConfig.get().foodItemId);
        }

        EatController.Result r = eat.tick(mc, player);
        if (r == EatController.Result.WORKING) return;

        String fail = eat.getFailReason();
        eat = null;
        InputController.use = false;
        if (r == EatController.Result.FAILED) {
            endRun("§c" + fail);
            return;
        }
        goBack(player);
    }

    // ---------- 走回出发点 / 恢复朝向 ----------

    private void goBack(ClientPlayerEntity player) {
        pathRetries = 0;
        pathExecutor = null;
        if (returnPos == null || player.getBlockPos().isWithinDistance(returnPos, 3.0)) {
            beginRestoreFacing();
            return;
        }
        enter(State.PATH_BACK);
    }

    private void tickPathBack(MinecraftClient mc, ClientPlayerEntity player) {
        if (pathExecutor == null) {
            List<BlockPos> path = Pathfinder.findNear(mc.world, player.getBlockPos(), returnPos, 1.6, 20000);
            if (path == null) {
                finishRun(); // 回不去就地结束，不影响下一轮
                return;
            }
            pathExecutor = new PathExecutor(path);
            return;
        }

        pathExecutor.tick(player);

        if (pathExecutor.isDone()) {
            beginRestoreFacing();
            return;
        }

        if (pathExecutor.isStuck() || ticksInState > PATH_TIMEOUT_TICKS) {
            pathExecutor = null;
            pathRetries++;
            if (pathRetries >= 3) {
                finishRun();
            }
        }
    }

    /** 走回出发点后把视角平滑转回出发时的朝向，再收尾。 */
    private void beginRestoreFacing() {
        pathExecutor = null;
        if (returnYaw == null || returnPitch == null) {
            finishRun();
            return;
        }
        enter(State.RESTORE_FACING);
    }

    private void tickRestoreFacing(ClientPlayerEntity player) {
        float yawDelta = MathHelper.wrapDegrees(returnYaw - player.getYaw());
        float pitchDelta = returnPitch - player.getPitch();
        player.setYaw(player.getYaw() + MathHelper.clamp(yawDelta, -12.0f, 12.0f));
        player.setPitch(player.getPitch() + MathHelper.clamp(pitchDelta, -8.0f, 8.0f));
        // 本 tick 步长已能走完剩余角差 → 已对齐；60 tick 兜底
        boolean aligned = Math.abs(yawDelta) <= 12.0f && Math.abs(pitchDelta) <= 8.0f;
        if (aligned || ticksInState > 60) {
            finishRun();
        }
    }

    // ---------- 结束一轮 ----------

    private void finishRun() {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean wasFood = foodRun;
        clearRun();
        InputController.releaseAll(mc);
        InputController.restorePauseOnLostFocus(mc);
        // 收尾统一切回 1 号快捷栏
        if (mc.player != null) {
            mc.player.getInventory().setSelectedSlot(0);
        }
        if (wasFood) {
            msg("§a吃饱了，继续待命");
        } else {
            StashConfig cfg = StashConfig.get();
            nextRunMs = System.currentTimeMillis() + cfg.intervalMinutes * 60_000L;
            msg("§a本轮存物完成，" + cfg.intervalMinutes + " 分钟后再存"
                    + (anyChestFull ? " §e（有箱子已满，记得清理）" : ""));
        }
        state = State.WAITING;
    }

    /** 出错收尾：不停止，30 秒后自动重试（与挖矿/种地的 softHalt 同一约定）。 */
    private void endRun(String reason) {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean wasFood = foodRun;
        clearRun();
        InputController.releaseAll(mc);
        InputController.restorePauseOnLostFocus(mc);
        if (wasFood) {
            nextFoodMs = System.currentTimeMillis() + RETRY_MS;
        } else {
            nextRunMs = System.currentTimeMillis() + RETRY_MS;
        }
        msg(reason + " §7（" + (RETRY_MS / 1000) + " 秒后重试；/stash stop 可关闭）");
        state = State.WAITING;
    }

    /**
     * 中途让位/异常复位。otherBotTookOver=true 时不碰输入和 pauseOnLostFocus：
     * 对方 enter() 已清输入，恢复原值由对方 stop 负责（此时恢复会把对方坑回失焦暂停 bug）。
     */
    private void abortRun(boolean otherBotTookOver) {
        clearRun();
        if (!otherBotTookOver) {
            MinecraftClient mc = MinecraftClient.getInstance();
            InputController.releaseAll(mc);
            InputController.restorePauseOnLostFocus(mc);
        }
        nextRunMs = System.currentTimeMillis() + RETRY_MS;
        state = State.WAITING;
    }

    private void clearRun() {
        pathExecutor = null;
        deposit = null;
        itemTake = null;
        eat = null;
        returnPos = null;
        returnYaw = null;
        returnPitch = null;
        foodRun = false;
        deadTicks = 0;
    }

    private void enter(State s) {
        state = s;
        ticksInState = 0;
        InputController.clear();
    }

    public String statusText() {
        StashConfig cfg = StashConfig.get();
        StringBuilder sb = new StringBuilder("§6[存物状态]§r\n");
        sb.append("运行: ").append(state == State.IDLE ? "§7关闭"
                : isBusy() ? "§a存物中(" + state + ")" : "§a等待计时").append("§r\n");
        sb.append("间隔: §e").append(cfg.intervalMinutes).append(" 分钟§r  箱子: §e")
                .append(cfg.chests.size()).append(" 个§r");
        sb.append("\n食物箱: ").append(cfg.foodChest == null ? "§7未绑定"
                : "§e" + cfg.foodChest + (cfg.foodItemId != null ? " §7(" + cfg.foodItemId + ")" : ""))
                .append("§r");
        if (state == State.WAITING) {
            long left = Math.max(0, nextRunMs - System.currentTimeMillis());
            sb.append("\n下次存物: §e").append(left / 60000).append(" 分 ")
                    .append(left % 60000 / 1000).append(" 秒后");
        }
        return sb.toString();
    }

    public static void msg(String s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("§6[存物]§r " + s), false);
        }
    }
}
