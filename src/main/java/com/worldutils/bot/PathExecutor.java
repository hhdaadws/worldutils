package com.worldutils.bot;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * 沿 Pathfinder 给出的路径点行走。
 */
public class PathExecutor {
    private static final float MAX_YAW_STEP = 12.0f;

    private final List<BlockPos> path;
    private int index = 0;

    private Vec3d lastPos = null;
    private int stuckTicks = 0;

    // 行走时的目标俯仰角（度）：默认平视 0°；收菜移动可设 30° 斜下方。
    private float walkPitch = 0.0f;

    // 无进展检测：绕圈时玩家一直在动，仅靠"无位移"的卡住检测永远抓不到。
    private int progressIndex = -1;
    private double bestDistSq = Double.MAX_VALUE;
    private int noProgressTicks = 0;

    public PathExecutor(List<BlockPos> path) {
        this.path = path;
    }

    /** 设置行走时保持的俯仰角（0=平视，正值向下看）。 */
    public PathExecutor withPitch(float pitch) {
        this.walkPitch = pitch;
        return this;
    }

    public boolean isDone() {
        return index >= path.size();
    }

    /** 卡住（无位移）或长时间没有接近当前路径点（绕圈），需要重新寻路。 */
    public boolean isStuck() {
        return stuckTicks > 60 || noProgressTicks > 100;
    }

    /**
     * 上层刻意暂停行走（如 medium 模式顺路收菜的对准/出手）时每 tick 调用：
     * 清空卡住/无进展计数，否则有意的停顿会被误判成"寻路卡住"触发无谓重规划。
     */
    public void notePause() {
        stuckTicks = 0;
        noProgressTicks = 0;
        bestDistSq = Double.MAX_VALUE;
        lastPos = null;
    }

    public void tick(ClientPlayerEntity player) {
        if (isDone()) {
            release();
            return;
        }

        // 中间拐点无需踩到正中心；稍早切到下一线段，转弯会更连贯。
        while (index < path.size() - 1 && closeTo(player, path.get(index + 1), 0.60)) {
            index++;
        }
        BlockPos target = path.get(index);
        double arriveRadius = index < path.size() - 1 ? 0.55 : 0.35;
        if (closeTo(player, target, arriveRadius)) {
            index++;
            if (isDone()) {
                release();
                return;
            }
            target = path.get(index);
        }

        Vec3d c = Vec3d.ofBottomCenter(target);
        double rawDx = c.x - player.getX();
        double rawDz = c.z - player.getZ();
        double rawDistSq = rawDx * rawDx + rawDz * rawDz;
        float rawDesiredYaw = (float) Math.toDegrees(Math.atan2(-rawDx, rawDz));
        float rawDelta = MathHelper.wrapDegrees(rawDesiredYaw - player.getYaw());

        // 走过头判定：路径点已在身后且足够近 → 直接视为到达。
        // 否则会掉头回去"踩点"，转身期间又走远，形成绕着路径点画圈。
        // 跳上台阶时疾跑+跳跃惯性常越过路径点 1 格以上：已站上该点高度（刚完成上跳）
        // 时把放宽半径加大，否则落点稍远就掉头回去踩点，绕着台阶转圈。
        // 高度判定留 0.2 余量：farmland/耕地顶面矮 1/16，站上去时脚底比路径格 Y 低。
        boolean ascended = index > 0 && target.getY() > path.get(index - 1).getY()
                && player.isOnGround() && player.getY() > target.getY() - 0.2;
        double passRadius = ascended ? 1.6 : (index < path.size() - 1 ? 0.9 : 0.5);
        if (rawDistSq < passRadius * passRadius && Math.abs(rawDelta) > 75.0f
                && Math.abs(target.getY() - player.getY()) < 1.2) {
            index++;
            if (isDone()) {
                release();
                return;
            }
            target = path.get(index);
            c = Vec3d.ofBottomCenter(target);
            rawDx = c.x - player.getX();
            rawDz = c.z - player.getZ();
            rawDistSq = rawDx * rawDx + rawDz * rawDz;
        }

        // 距当前路径点的最近距离长时间不再缩小 → 无进展（绕圈/被挡），交给上层重寻路。
        if (index != progressIndex) {
            progressIndex = index;
            bestDistSq = Double.MAX_VALUE;
            noProgressTicks = 0;
        }
        if (rawDistSq < bestDistSq - 0.01) {
            bestDistSq = rawDistSq;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }

        Vec3d steeringPoint = c;
        // 靠近拐点时少量朝下一段预瞄，让转弯形成圆滑过渡；只在同高度使用，避免切台阶。
        if (index + 1 < path.size() && path.get(index + 1).getY() == target.getY()) {
            Vec3d next = Vec3d.ofBottomCenter(path.get(index + 1));
            double toCorner = Math.sqrt(horizontalSquaredDistance(player.getPos(), c));
            if (toCorner < 1.8) {
                double blend = MathHelper.clamp((1.8 - toCorner) / 1.8 * 0.22, 0.0, 0.22);
                steeringPoint = c.lerp(next, blend);
            }
        }
        double dx = steeringPoint.x - player.getX();
        double dz = steeringPoint.z - player.getZ();
        double horizontalSq = dx * dx + dz * dz;

        Vec3d finalPoint = Vec3d.ofBottomCenter(path.get(path.size() - 1));
        double finalDistanceSq = horizontalSquaredDistance(player.getPos(), finalPoint);

        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float yawDelta = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
        // 按剩余角度比例转向：大角度较快、接近目标时自然减速，但不保留惯性，避免越过目标后绕圈。
        float yawStep = MathHelper.clamp(yawDelta * 0.30f, -MAX_YAW_STEP, MAX_YAW_STEP);
        if (Math.abs(yawStep) > Math.abs(yawDelta)) yawStep = yawDelta;
        player.setYaw(player.getYaw() + yawStep);

        // 行走俯仰角按剩余路程渐变：远处平视赶路，进入 8 格后逐渐压向 walkPitch，
        // 2 格内到全值——低头只出现在快到地块时，不再全程斜视地面走长途。
        double finalDist = Math.sqrt(finalDistanceSq);
        float pitchGoal = walkPitch
                * (float) MathHelper.clamp((8.0 - finalDist) / 6.0, 0.0, 1.0);
        float pitchDelta = pitchGoal - player.getPitch();
        float pitchStep = MathHelper.clamp(pitchDelta, -8.0f, 8.0f);
        player.setPitch(player.getPitch() + pitchStep);

        // 必须基本朝准路径再前进；近距离时带着 80° 偏角前进会围绕目标画圆。
        InputController.forward = Math.abs(yawDelta) < 42.0f;

        // 用玩家真实脚底高度判断台阶。farmland 顶面是 y+0.9375，不能按 getBlockPos() 判断，
        // 否则会把正常平走误认为"上升一格"，出现一路连跳。
        boolean needsJump = c.y - player.getY() > 0.45;
        // 上升节点进入 2.2 格范围后持续按住跳跃，直到真实高度已经越上目标台阶。
        // 不依赖 isOnGround：疾跑接近边缘、farmland 顶面和网络同步都可能让该值短暂为 false。
        boolean blockedAhead = player.horizontalCollision;
        InputController.jump = InputController.forward
                && ((needsJump && horizontalSq < 2.2 * 2.2) || blockedAhead);
        // 平地寻路使用疾跑；起跳期间同时释放按键并明确取消客户端疾跑状态。
        // 直线段才疾跑；明显转弯或接近交互终点时降为步行，减少急停和过冲。
        InputController.sprint = InputController.forward && !InputController.jump
                && Math.abs(yawDelta) < 18.0f && finalDistanceSq > 2.4 * 2.4;
        if (InputController.jump && player.isSprinting()) {
            player.setSprinting(false);
        }

        // 卡住检测：原地转向也必须累计"无水平位移"时间。
        Vec3d now = player.getPos();
        if (lastPos != null) {
            double movedX = now.x - lastPos.x;
            double movedZ = now.z - lastPos.z;
            if (movedX * movedX + movedZ * movedZ < 0.0004) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
            }
        }
        lastPos = now;
    }

    private void release() {
        InputController.forward = false;
        InputController.jump = false;
        InputController.sprint = false;
    }

    private boolean closeTo(ClientPlayerEntity player, BlockPos pos, double radius) {
        double dx = pos.getX() + 0.5 - player.getX();
        double dz = pos.getZ() + 0.5 - player.getZ();
        double dy = pos.getY() - player.getY();
        return dx * dx + dz * dz < radius * radius && Math.abs(dy) < 1.2;
    }

    private static double horizontalSquaredDistance(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }
}
