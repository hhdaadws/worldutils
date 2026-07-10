package com.autominer.bot;

import net.minecraft.client.MinecraftClient;

/**
 * 通过 KeyBinding.setPressed 控制按键（无需 mixin，版本兼容性好）。
 * 每 tick 由 Bot 调用 apply()；停止时调用 releaseAll()。
 */
public final class InputController {
    public static boolean forward = false;
    public static boolean jump = false;
    public static boolean sprint = false;
    public static boolean sneak = false;
    public static boolean attack = false; // 按住左键（连续挖矿）
    public static boolean use = false;    // 按住右键（喝药水）

    private InputController() {}

    public static void clear() {
        forward = false;
        jump = false;
        sprint = false;
        sneak = false;
        attack = false;
        use = false;
    }

    public static void apply(MinecraftClient mc) {
        mc.options.forwardKey.setPressed(forward);
        mc.options.jumpKey.setPressed(jump);
        mc.options.sprintKey.setPressed(sprint);
        mc.options.sneakKey.setPressed(sneak);
        mc.options.attackKey.setPressed(attack);
        mc.options.useKey.setPressed(use);
    }

    public static void releaseAll(MinecraftClient mc) {
        clear();
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.attackKey.setPressed(false);
        mc.options.useKey.setPressed(false);
    }
}
