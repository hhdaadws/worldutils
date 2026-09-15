package com.worldutils.farm;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** 农场交互共用的渐进式视角控制，避免打开箱子、种植和收获时瞬间锁头。 */
final class FarmLookController {
    private FarmLookController() {}

    static boolean smoothFace(ClientPlayerEntity player, Vec3d target) {
        return smoothFace(player, target, false);
    }

    /** quick=true：medium 模式的加速对准（步长约两倍），仍是渐进转头不瞬移。 */
    static boolean smoothFace(ClientPlayerEntity player, Vec3d target, boolean quick) {
        float yawMax = quick ? 40.0f : 20.0f;
        float pitchMax = quick ? 24.0f : 12.0f;
        float pitchRatio = quick ? 0.7f : 0.45f;
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float desiredPitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));

        // 目标几乎在正上/正下方（比如站在要种/要收的那块地上）时 yaw 对命中没有意义，
        // 而且 atan2 对微小的 dx/dz 极端敏感（dx=dz=0 时恒指北），会让头一直往同一个
        // 方向偏还永远对不准。此时 yaw 保持不动，只调 pitch。
        boolean yawMatters = horiz > 0.5;
        float yawError = 0.0f;
        if (yawMatters) {
            float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            yawError = smoothYaw(player, desiredYaw, yawMax);
        }
        float pitchError = desiredPitch - player.getPitch();
        float pitchStep = MathHelper.clamp(pitchError * pitchRatio, -pitchMax, pitchMax);
        if (Math.abs(pitchStep) > Math.abs(pitchError)) pitchStep = pitchError;
        player.setPitch(MathHelper.clamp(player.getPitch() + pitchStep, -90.0f, 90.0f));
        return (!yawMatters || Math.abs(yawError) < 3.0f) && Math.abs(pitchError) < 3.0f;
    }

    /** 返回转动前的角度误差，调用方可据此决定是否开始移动。 */
    static float smoothYaw(ClientPlayerEntity player, float desiredYaw, float maxStep) {
        float yawError = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
        float yawStep = MathHelper.clamp(yawError * 0.50f, -maxStep, maxStep);
        if (Math.abs(yawStep) > Math.abs(yawError)) yawStep = yawError;
        player.setYaw(player.getYaw() + yawStep);
        return yawError;
    }

    /**
     * 捡掉落物用的“自然低头”：pitch 按水平距离渐进设上限——远处基本平视（真人走路
     * 不会隔 5 格就死盯地上的物品），越近头压得越低，但贴脚边也封顶 ~72°（真人弯腰
     * 捡东西靠余光，不会 85° 盯脚尖）。转动比例也放缓，头部动作更柔和。
     */
    static void naturalPickupPitch(ClientPlayerEntity player, Vec3d target, float maxStep) {
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float desiredPitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        // 距离 → pitch 上限：≥5 格 15°（正常赶路视线略偏下），5→1.2 格线性放开到 72°。
        float cap;
        if (horiz >= 5.0) cap = 15.0f;
        else if (horiz <= 1.2) cap = 72.0f;
        else cap = 15.0f + (float) ((5.0 - horiz) / 3.8) * 57.0f;
        desiredPitch = Math.min(desiredPitch, cap);
        float pitchError = desiredPitch - player.getPitch();
        float pitchStep = MathHelper.clamp(pitchError * 0.35f, -maxStep, maxStep);
        if (Math.abs(pitchStep) > Math.abs(pitchError)) pitchStep = pitchError;
        player.setPitch(MathHelper.clamp(player.getPitch() + pitchStep, -90.0f, 90.0f));
    }
}
