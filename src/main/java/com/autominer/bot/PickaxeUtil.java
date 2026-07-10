package com.autominer.bot;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

/**
 * 镐子相关工具。
 */
public final class PickaxeUtil {
    private PickaxeUtil() {}

    public static boolean isPickaxe(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return Registries.ITEM.getId(stack.getItem()).getPath().endsWith("_pickaxe");
    }

    /** 剩余耐久；不可损坏（不掉耐久）的镐视为无限。 */
    public static int remaining(ItemStack stack) {
        if (!stack.isDamageable()) return Integer.MAX_VALUE;
        return stack.getMaxDamage() - stack.getDamage();
    }

    /** 是剩余耐久 >= threshold 的镐。 */
    public static boolean isUsable(ItemStack stack, int threshold) {
        return isPickaxe(stack) && remaining(stack) >= threshold;
    }

    /** 是剩余耐久不足的旧镐（要放回箱子的那种）。 */
    public static boolean isWorn(ItemStack stack, int threshold) {
        return isPickaxe(stack) && remaining(stack) < threshold;
    }

    /** 背包（36 格）中是否有可用镐。 */
    public static boolean hasUsable(ClientPlayerEntity player, int threshold) {
        for (int i = 0; i < 36; i++) {
            if (isUsable(player.getInventory().getStack(i), threshold)) {
                return true;
            }
        }
        return false;
    }
}
