package com.autominer.bot;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * 沿 Pathfinder 给出的路径点行走。
 */
public class PathExecutor {
    private static final float MAX_YAW_STEP = 8.5f;
    private static final float YAW_ACCELERATION = 0.42f;
    private static final float YAW_DAMPING = 0.58f;

    private final List<BlockPos> path;
    private int index = 0;

    private Vec3d lastPos = null;
    private int stuckTicks = 0;
    private float yawVelocity = 0.0f;

    public PathExecutor(List<BlockPos> path) {
        this.path = path;
    }

    public boolean isDone() {
        return index >= path.size();
    }

    /** 卡住太久，需要重新寻路。 */
    public boolean isStuck() {
        return stuckTicks > 60;
    }

    public void tick(ClientPlayerEntity player) {
        if (isDone()) {
            InputController.forward = false;
            InputController.jump = false;
            InputController.sprint = false;
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
                InputController.forward = false;
                InputController.jump = false;
                InputController.sprint = false;
                return;
            }
            target = path.get(index);
        }

        Vec3d c = Vec3d.ofBottomCenter(target);
        Vec3d steeringPoint = c;
        // 靠近拐点时少量朝下一段预瞄，让转弯形成圆滑过渡；只在同高度使用，避免切台阶。
        if (index + 1 < path.size() && path.get(index + 1).getY() == target.getY()) {
            Vec3d next = Vec3d.ofBottomCenter(path.get(index + 1));
            double toCorner = Math.sqrt(horizontalSquaredDistance(player.getPos(), c));
            if (toCorner < 1.8) {
                double blend = MathHelper.clamp((1.8 - toCorner) / 1.8 * 0.32, 0.0, 0.32);
                steeringPoint = c.lerp(next, blend);
            }
        }
        double dx = steeringPoint.x - player.getX();
        double dz = steeringPoint.z - player.getZ();
        double horizontalSq = dx * dx + dz * dz;

        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float yawDelta = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
        // 阻尼式转向：先加速、接近目标角度时自然减速，避免每 tick 固定转 10° 的机械感。
        float requestedVelocity = MathHelper.clamp(yawDelta * 0.24f, -MAX_YAW_STEP, MAX_YAW_STEP);
        yawVelocity = yawVelocity * YAW_DAMPING + requestedVelocity * YAW_ACCELERATION;
        if (Math.abs(yawVelocity) > Math.abs(yawDelta)) yawVelocity = yawDelta;
        if (Math.abs(yawDelta) < 0.08f) yawVelocity = 0.0f;
        player.setYaw(player.getYaw() + yawVelocity);

        // 大转角先原地平滑转身，防止一边朝错误方向走一边强拉视角。
        InputController.forward = Math.abs(yawDelta) < 82.0f;

        // 用玩家真实脚底高度判断台阶。farmland 顶面是 y+0.9375，不能按 getBlockPos() 判断，
        // 否则会把正常平走误认为“上升一格”，出现一路连跳。
        boolean needsJump = c.y - player.getY() > 0.45;
        // 上升节点进入 2.2 格范围后持续按住跳跃，直到真实高度已经越上目标台阶。
        // 不依赖 isOnGround：疾跑接近边缘、farmland 顶面和网络同步都可能让该值短暂为 false。
        boolean blockedAhead = player.horizontalCollision;
        InputController.jump = InputController.forward
                && ((needsJump && horizontalSq < 2.2 * 2.2) || blockedAhead);
        // 平地寻路使用疾跑；起跳期间同时释放按键并明确取消客户端疾跑状态。
        Vec3d finalPoint = Vec3d.ofBottomCenter(path.get(path.size() - 1));
        double finalDistanceSq = horizontalSquaredDistance(player.getPos(), finalPoint);
        // 直线段才疾跑；明显转弯或接近交互终点时降为步行，减少急停和过冲。
        InputController.sprint = InputController.forward && !InputController.jump
                && Math.abs(yawDelta) < 32.0f && finalDistanceSq > 2.4 * 2.4;
        if (InputController.jump && player.isSprinting()) {
            player.setSprinting(false);
        }

        // 卡住检测：原地转向也必须累计“无水平位移”时间。
        // 旧逻辑在 forward=false（大角度转身）时不断清零，某些路径点会让视角来回摆动，
        // 最坏要等 FarmBot 的 40 秒总超时并重复多轮，看起来就像一直原地转圈。
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
