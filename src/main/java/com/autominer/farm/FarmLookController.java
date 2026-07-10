package com.autominer.farm;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** 农场交互共用的渐进式视角控制，避免打开箱子、种植和收获时瞬间锁头。 */
final class FarmLookController {
    private FarmLookController() {}

    static boolean smoothFace(ClientPlayerEntity player, Vec3d target) {
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float desiredPitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        float yawError = smoothYaw(player, desiredYaw, 9.0f);
        float pitchError = desiredPitch - player.getPitch();
        float pitchStep = MathHelper.clamp(pitchError * 0.30f, -6.0f, 6.0f);
        if (Math.abs(pitchStep) > Math.abs(pitchError)) pitchStep = pitchError;
        player.setPitch(MathHelper.clamp(player.getPitch() + pitchStep, -90.0f, 90.0f));
        return Math.abs(yawError) < 2.5f && Math.abs(pitchError) < 2.5f;
    }

    /** 返回转动前的角度误差，调用方可据此决定是否开始移动。 */
    static float smoothYaw(ClientPlayerEntity player, float desiredYaw, float maxStep) {
        float yawError = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
        float yawStep = MathHelper.clamp(yawError * 0.28f, -maxStep, maxStep);
        if (Math.abs(yawStep) > Math.abs(yawError)) yawStep = yawError;
        player.setYaw(player.getYaw() + yawStep);
        return yawError;
    }
}
