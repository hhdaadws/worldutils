package com.worldutils.util;

import net.minecraft.client.network.ClientPlayerEntity;

/**
 * 受伤检测：跟踪玩家血量，掉血时返回一次 true。
 * 两个 bot 互斥运行，共用一份静态状态即可。
 */
public final class DamageWatch {
    private static float lastHealth = Float.NaN;

    private DamageWatch() {}

    /** bot 启动时调用，以当前血量为基准，避免开局误报。 */
    public static void reset(ClientPlayerEntity player) {
        lastHealth = player != null ? player.getHealth() : Float.NaN;
    }

    /**
     * 每 tick 调用：血量比上次低（受到伤害）返回 true。
     * 回血、重生（NaN 基准）不触发。
     */
    public static boolean checkDamaged(ClientPlayerEntity player) {
        if (player == null) return false;
        float hp = player.getHealth();
        boolean damaged = !Float.isNaN(lastHealth) && hp < lastHealth - 0.01f;
        lastHealth = hp;
        return damaged;
    }
}
