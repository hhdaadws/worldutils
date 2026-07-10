package com.autominer.bot;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * 沿 Pathfinder 给出的路径点行走。
 */
public class PathExecutor {
    private final List<BlockPos> path;
    private int index = 0;

    private Vec3d lastPos = null;
    private int stuckTicks = 0;

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
            return;
        }

        // 跳过已经很接近的后续路径点（避免来回震荡）
        while (index < path.size() - 1 && closeTo(player, path.get(index + 1))) {
            index++;
        }
        BlockPos target = path.get(index);
        if (closeTo(player, target)) {
            index++;
            if (isDone()) {
                InputController.forward = false;
                InputController.jump = false;
                return;
            }
            target = path.get(index);
        }

        Vec3d c = Vec3d.ofBottomCenter(target);
        double dx = c.x - player.getX();
        double dz = c.z - player.getZ();

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        player.setYaw(yaw);
        player.setPitch(12.0f);

        InputController.forward = true;
        InputController.jump = target.getY() > player.getBlockPos().getY() && player.isOnGround();

        // 卡住检测
        Vec3d now = player.getPos();
        if (lastPos != null && now.squaredDistanceTo(lastPos) < 0.0004) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastPos = now;
    }

    private boolean closeTo(ClientPlayerEntity player, BlockPos pos) {
        double dx = pos.getX() + 0.5 - player.getX();
        double dz = pos.getZ() + 0.5 - player.getZ();
        double dy = pos.getY() - player.getY();
        return dx * dx + dz * dz < 0.35 * 0.35 && Math.abs(dy) < 1.2;
    }
}
