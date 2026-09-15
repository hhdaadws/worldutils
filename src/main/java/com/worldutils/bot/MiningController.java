package com.worldutils.bot;

import com.worldutils.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Set;

/**
 * 挖矿：先垂直向下挖到目标 y，然后沿固定朝向挖 1宽×2高 隧道向前。
 * - 前挖阶段 W + 左键全程按住（连续挖矿不停顿）
 * - 悬崖/洞穴：自动用背包里的方块（圆石等）搭路通过
 * - 水/岩浆：自动放方块封堵后继续
 * - 5 秒坐标没变化：自动顺时针换一个方向继续挖
 */
public class MiningController {
    public enum Result {
        CONTINUE,
        HAZARD_STOP
    }

    /** 可用来搭路/封堵的方块（物品 id 的 path 精确匹配）。 */
    private static final Set<String> BUILD_BLOCKS = Set.of(
            "cobblestone", "mossy_cobblestone", "cobbled_deepslate", "stone", "dirt",
            "netherrack", "andesite", "diorite", "granite", "tuff", "blackstone",
            "basalt", "deepslate", "end_stone");

    private final int targetY;
    private Direction facing;
    private final int pickThreshold;
    private BlockPos anchor = null; // 到达目标 y 后锁定车道
    private String hazardMessage = null;

    private int placeCooldown = 0;
    private int sealGrace = 0;     // 刚封堵过液体，短暂禁止前进防止踩进去

    private Vec3d stuckRef = null; // 防卡死：5 秒坐标不变就换方向
    private int stuckTicks = 0;

    public MiningController(int targetY, Direction facing, int pickThreshold) {
        this.targetY = targetY;
        this.facing = facing;
        this.pickThreshold = pickThreshold;
    }

    public String getHazardMessage() {
        return hazardMessage;
    }

    public Result tick(MinecraftClient mc, ClientPlayerEntity player) {
        if (!ensureHotbarPickaxe(mc, player)) {
            hazardMessage = "背包里没有可用镐子，已停止";
            return Result.HAZARD_STOP;
        }
        if (placeCooldown > 0) placeCooldown--;
        if (sealGrace > 0) sealGrace--;

        BlockPos feet = player.getBlockPos();

        // 头顶被落沙/落石埋住 → 先挖头顶
        BlockPos head = feet.up();
        if (!Pathfinder.passable(mc.world, head)
                && mc.world.getBlockState(head).getFluidState().isEmpty()) {
            aimAndBreak(player, head);
            InputController.forward = false;
            return Result.CONTINUE;
        }

        if (feet.getY() > targetY) {
            return digDown(mc, player, feet);
        }
        if (feet.getY() < targetY - 1) {
            hazardMessage = "掉到了目标高度以下 (y=" + feet.getY() + " < " + targetY + ")，已停止";
            return Result.HAZARD_STOP;
        }
        return mineForward(mc, player, feet);
    }

    // ---------- 垂直下挖 ----------

    private Result digDown(MinecraftClient mc, ClientPlayerEntity player, BlockPos feet) {
        InputController.forward = false;
        InputController.jump = false;
        InputController.attack = false;

        if (!player.isOnGround()) {
            return Result.CONTINUE; // 正在下落
        }

        BlockPos below = feet.down();

        // 脚下不安全（液体 / 深洞）→ 沿挖矿方向横向绕过去（会自动搭路/封堵）
        boolean fluidBelow = !mc.world.getBlockState(below).getFluidState().isEmpty()
                || !mc.world.getBlockState(below.down()).getFluidState().isEmpty();
        boolean holeBelow = Pathfinder.passable(mc.world, below.down())
                && Pathfinder.passable(mc.world, below.down(2));

        if (fluidBelow || holeBelow) {
            return sidestep(mc, player, feet);
        }

        if (Pathfinder.passable(mc.world, below)) {
            centerOnBlock(player, feet);
            return Result.CONTINUE;
        }

        aimAndBreak(player, below);
        return Result.CONTINUE;
    }

    /** 下方有坑/液体时：在当前高度沿挖矿方向横向推进（液体封堵、断路搭桥）。 */
    private Result sidestep(MinecraftClient mc, ClientPlayerEntity player, BlockPos feet) {
        BlockPos frontFeet = feet.offset(facing);
        BlockPos frontHead = frontFeet.up();

        // 前方液体 → 放方块封堵
        BlockPos seal = firstFluid(mc, frontFeet, frontHead, frontHead.up());
        if (seal != null) {
            InputController.forward = false;
            InputController.attack = false;
            sealGrace = 30;
            return placeBlockAt(mc, player, seal) ? Result.CONTINUE : Result.HAZARD_STOP;
        }

        boolean headClear = Pathfinder.passable(mc.world, frontHead);
        boolean feetClear = Pathfinder.passable(mc.world, frontFeet);

        if (!headClear || !feetClear) {
            aimAndBreak(player, headClear ? frontFeet : frontHead);
            InputController.forward = sealGrace == 0;
            InputController.sprint = true;
            stuckCheck(player);
            return Result.CONTINUE;
        }

        // 前方落脚点缺失 → 搭桥
        if (!Pathfinder.solidGround(mc.world, frontFeet.down())) {
            InputController.forward = false;
            InputController.attack = false;
            return placeBlockAt(mc, player, frontFeet.down()) ? Result.CONTINUE : Result.HAZARD_STOP;
        }

        // 朝前方方块中心走一格
        double cx = frontFeet.getX() + 0.5;
        double cz = frontFeet.getZ() + 0.5;
        double dx = cx - player.getX();
        double dz = cz - player.getZ();
        player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setPitch(10.0f);
        InputController.attack = false;
        InputController.forward = sealGrace == 0;
        InputController.sprint = true;
        stuckCheck(player);
        return Result.CONTINUE;
    }

    private void centerOnBlock(ClientPlayerEntity player, BlockPos feet) {
        double cx = feet.getX() + 0.5;
        double cz = feet.getZ() + 0.5;
        double dx = cx - player.getX();
        double dz = cz - player.getZ();
        if (dx * dx + dz * dz > 0.02) {
            player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
            player.setPitch(30.0f);
            InputController.forward = true;
        }
    }

    // ---------- 水平前挖（W + 左键全程按住） ----------

    private Result mineForward(MinecraftClient mc, ClientPlayerEntity player, BlockPos feet) {
        if (anchor == null) {
            anchor = new BlockPos(feet.getX(), targetY, feet.getZ());
        }

        BlockPos base = new BlockPos(feet.getX(), targetY, feet.getZ());

        // 向前扫描 5 列，找最近的液体列（决定安全推进上限）
        int fluidLimit = 6;
        for (int d = 1; d <= 5; d++) {
            BlockPos ff = base.offset(facing, d);
            if (firstFluid(mc, ff, ff.up(), ff.up(2)) != null) {
                fluidLimit = d;
                break;
            }
        }

        // 液体已经很近 → 放方块封堵后继续（不停止）
        if (fluidLimit <= 2) {
            BlockPos ff = base.offset(facing, fluidLimit);
            BlockPos seal = firstFluid(mc, ff, ff.up(), ff.up(2));
            InputController.forward = false;
            InputController.attack = false;
            sealGrace = 30;
            return placeBlockAt(mc, player, seal) ? Result.CONTINUE : Result.HAZARD_STOP;
        }

        // 在 4 格触及范围内找最近的未挖穿方块：准星放远处挖，
        // 方块在身前几格就被破坏，掉落物落在身前，疾跑路过顺路捡起
        BlockPos target = null;
        int maxD = Math.min(4, fluidLimit - 1);
        for (int d = 1; d <= maxD; d++) {
            BlockPos ff = base.offset(facing, d);
            BlockPos fh = ff.up();
            if (!Pathfinder.passable(mc.world, fh)) {
                target = fh;
                break;
            }
            if (!Pathfinder.passable(mc.world, ff)) {
                target = ff;
                break;
            }
        }

        // W + 疾跑全程按住
        InputController.forward = sealGrace == 0;
        InputController.sprint = true;
        InputController.jump = false;

        if (target != null) {
            aimAndBreak(player, target);
            // 紧邻列的落脚点缺失时不能贴着走过去 → 搭路
            BlockPos front1 = base.offset(facing);
            if (Pathfinder.passable(mc.world, front1) && Pathfinder.passable(mc.world, front1.up())
                    && !Pathfinder.solidGround(mc.world, front1.down())) {
                InputController.forward = false;
                InputController.attack = false;
                return placeBlockAt(mc, player, front1.down()) ? Result.CONTINUE : Result.HAZARD_STOP;
            }
            stuckCheck(player);
            return Result.CONTINUE;
        }

        // 前方 4 格全通：落脚点缺失（悬崖/洞穴）→ 自动搭路（不停止）
        BlockPos front1 = base.offset(facing);
        if (!Pathfinder.solidGround(mc.world, front1.down())) {
            InputController.forward = false;
            InputController.attack = false;
            return placeBlockAt(mc, player, front1.down()) ? Result.CONTINUE : Result.HAZARD_STOP;
        }

        InputController.attack = false;
        walkSteer(player);
        stuckCheck(player);
        return Result.CONTINUE;
    }

    /** 返回第一个含液体的位置；没有返回 null。 */
    private BlockPos firstFluid(MinecraftClient mc, BlockPos... positions) {
        for (BlockPos p : positions) {
            if (!mc.world.getBlockState(p).getFluidState().isEmpty()) {
                return p;
            }
        }
        return null;
    }

    private void walkSteer(ClientPlayerEntity player) {
        double laneX = anchor.getX() + 0.5;
        double laneZ = anchor.getZ() + 0.5;

        double fx = facing.getOffsetX();
        double fz = facing.getOffsetZ();

        double ox = 0, oz = 0;
        if (fx != 0) {
            oz = laneZ - player.getZ();
        } else {
            ox = laneX - player.getX();
        }
        ox = Math.max(-0.6, Math.min(0.6, ox * 1.5));
        oz = Math.max(-0.6, Math.min(0.6, oz * 1.5));

        double dx = fx + ox;
        double dz = fz + oz;
        player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setPitch(20.0f);
    }

    /** 准星对准方块中心并按住左键，由原版逻辑连续挖掘。 */
    private void aimAndBreak(ClientPlayerEntity player, BlockPos target) {
        Vec3d eye = player.getEyePos();
        Vec3d c = Vec3d.ofCenter(target);
        double dx = c.x - eye.x;
        double dy = c.y - eye.y;
        double dz = c.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setPitch((float) -Math.toDegrees(Math.atan2(dy, horiz)));
        InputController.attack = true;
    }

    // ---------- 防卡死：5 秒坐标不变 → 顺时针换方向 ----------

    private void stuckCheck(ClientPlayerEntity player) {
        Vec3d now = player.getPos();
        if (stuckRef == null || now.squaredDistanceTo(stuckRef) > 0.25) {
            stuckRef = now;
            stuckTicks = 0;
            return;
        }
        stuckTicks++;
        if (stuckTicks >= 100) { // 5 秒
            facing = facing.rotateYClockwise();
            anchor = null; // 在新方向上重新锁定车道
            ModConfig.get().facing = facing.name();
            ModConfig.save();
            Bot.msg("§e5秒没动弹，换个方向挖: §a" + facing.name());
            stuckRef = null;
            stuckTicks = 0;
        }
    }

    // ---------- 放方块（搭路 / 封堵液体） ----------

    /**
     * 在 target 处放一个方块（找一个相邻实心面作为支撑点右键）。
     * 失败时设置 hazardMessage 并返回 false。
     */
    private boolean placeBlockAt(MinecraftClient mc, ClientPlayerEntity player, BlockPos target) {
        if (placeCooldown > 0) {
            return true; // 冷却中，等下一 tick
        }

        // 找支撑面
        for (Direction d : Direction.values()) {
            BlockPos support = target.offset(d);
            if (!Pathfinder.solidGround(mc.world, support)) continue;

            // 手里拿好搭路方块
            if (!selectBuildBlock(mc, player)) {
                hazardMessage = "背包里没有搭路用的方块（圆石/石头等），无法通过，已停止";
                return false;
            }

            Direction face = d.getOpposite();
            Vec3d hit = Vec3d.ofCenter(support).add(
                    face.getOffsetX() * 0.5,
                    face.getOffsetY() * 0.5,
                    face.getOffsetZ() * 0.5);

            // 看向放置点
            Vec3d eye = player.getEyePos();
            double dx = hit.x - eye.x;
            double dy = hit.y - eye.y;
            double dz = hit.z - eye.z;
            double horiz = Math.sqrt(dx * dx + dz * dz);
            player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
            player.setPitch((float) -Math.toDegrees(Math.atan2(dy, horiz)));

            BlockHitResult bhr = new BlockHitResult(hit, face, support, false);
            mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, bhr);
            player.swingHand(Hand.MAIN_HAND);
            placeCooldown = 4;
            return true;
        }

        hazardMessage = "找不到放方块的支撑面，无法搭路，已停止";
        return false;
    }

    private static boolean isBuildBlock(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) return false;
        return BUILD_BLOCKS.contains(Registries.ITEM.getId(stack.getItem()).getPath());
    }

    /** 把搭路方块拿到手上。 */
    private boolean selectBuildBlock(MinecraftClient mc, ClientPlayerEntity player) {
        int sel = player.getInventory().getSelectedSlot();
        if (isBuildBlock(player.getInventory().getStack(sel))) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            if (isBuildBlock(player.getInventory().getStack(i))) {
                player.getInventory().setSelectedSlot(i);
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (i == DepositController.RESERVED_SLOT) continue;
            if (isBuildBlock(player.getInventory().getStack(i))) {
                mc.interactionManager.clickSlot(player.playerScreenHandler.syncId, i, sel,
                        SlotActionType.SWAP, player);
                return true;
            }
        }
        return false;
    }

    /**
     * 确保当前手持可用镐：快捷栏没有就从主背包换一把上来。
     * 返回 false 表示整个背包都没有可用镐。
     */
    private boolean ensureHotbarPickaxe(MinecraftClient mc, ClientPlayerEntity player) {
        int sel = player.getInventory().getSelectedSlot();
        if (PickaxeUtil.isUsable(player.getInventory().getStack(sel), pickThreshold)) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            if (PickaxeUtil.isUsable(player.getInventory().getStack(i), pickThreshold)) {
                player.getInventory().setSelectedSlot(i);
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (i == DepositController.RESERVED_SLOT) continue;
            if (PickaxeUtil.isUsable(player.getInventory().getStack(i), pickThreshold)) {
                mc.interactionManager.clickSlot(player.playerScreenHandler.syncId, i, sel,
                        SlotActionType.SWAP, player);
                return true;
            }
        }
        return false;
    }
}
