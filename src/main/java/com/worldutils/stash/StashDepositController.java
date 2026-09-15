package com.worldutils.stash;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * 定时存物的箱子交互：打开箱子后把背包索引 10..35 的物品全部 QUICK_MOVE 存入。
 * 快捷栏（0..8）和保留格（按 E 背包最上排第一格，索引 9）永不存入。
 */
public class StashDepositController {
    // 存物范围：主背包去掉保留格（索引 9）
    private static final int FIRST_SLOT = 10;
    private static final int LAST_SLOT = 35;

    public enum Result {
        WORKING,
        DONE,        // 本箱子操作完成，范围内物品已全部存入
        CHEST_FULL,  // 箱子满了，还有物品没存进去
        FAILED       // 打不开箱子
    }

    private final BlockPos chestPos;

    private int openAttempts = 0;
    private int cooldown = 0;
    private final Map<Integer, Integer> attempts = new HashMap<>();

    public StashDepositController(BlockPos chestPos) {
        this.chestPos = chestPos;
    }

    /** 该背包索引是否属于定时存物范围（10..35）。 */
    public static boolean shouldDeposit(int invIndex) {
        return invIndex >= FIRST_SLOT && invIndex <= LAST_SLOT;
    }

    /** 背包 10..35 范围内是否还有物品可存。 */
    public static boolean hasDepositable(ClientPlayerEntity player) {
        for (int i = FIRST_SLOT; i <= LAST_SLOT; i++) {
            if (!player.getInventory().getStack(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public Result tick(MinecraftClient mc, ClientPlayerEntity player) {
        if (cooldown > 0) {
            cooldown--;
            return Result.WORKING;
        }

        ScreenHandler handler = player.currentScreenHandler;
        boolean containerOpen = handler != null && handler.syncId != 0
                && handler != player.playerScreenHandler;

        if (!containerOpen) {
            if (openAttempts >= 8) {
                return Result.FAILED;
            }
            openAttempts++;
            // 开箱前切到第 9 格快捷栏（索引 8），避免主手物品被右键使用
            if (player.getInventory().getSelectedSlot() != 8) {
                player.getInventory().setSelectedSlot(8);
                cooldown = 2; // 等切换同步到服务器再开箱
                return Result.WORKING;
            }
            faceChest(player);
            BlockHitResult hit = new BlockHitResult(
                    Vec3d.ofCenter(chestPos), Direction.UP, chestPos, false);
            mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
            player.swingHand(Hand.MAIN_HAND);
            cooldown = 15;
            return Result.WORKING;
        }

        boolean anyPending = false;
        for (Slot slot : handler.slots) {
            if (!(slot.inventory instanceof PlayerInventory)) continue;
            if (!shouldDeposit(slot.getIndex())) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;

            int tried = attempts.getOrDefault(slot.id, 0);
            if (tried >= 2) {
                anyPending = true; // 移不进去（箱子放不下）
                continue;
            }
            attempts.put(slot.id, tried + 1);
            mc.interactionManager.clickSlot(handler.syncId, slot.id, 0,
                    SlotActionType.QUICK_MOVE, player);
            cooldown = 2;
            return Result.WORKING;
        }

        player.closeHandledScreen();
        return anyPending ? Result.CHEST_FULL : Result.DONE;
    }

    private void faceChest(ClientPlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Vec3d c = Vec3d.ofCenter(chestPos);
        double dx = c.x - eye.x;
        double dy = c.y - eye.y;
        double dz = c.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setPitch((float) -Math.toDegrees(Math.atan2(dy, horiz)));
    }
}
