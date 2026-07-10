package com.autominer.farm;

import com.autominer.bot.DepositController;
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

import java.util.function.Predicate;

/**
 * 从指定箱子取出若干组"符合条件"的物品（按 Predicate 匹配，
 * 用于按显示名取插件种子）。
 */
public class ChestTakeController {
    public enum Result {
        WORKING,
        DONE,
        FAILED
    }

    private final BlockPos chestPos;
    private final Predicate<ItemStack> matcher;
    private final String label;
    private final int maxStacks;

    private int openAttempts = 0;
    private int cooldown = 0;
    private int takeAttempts = 0;
    private int stacksTaken = 0;
    private String failReason = null;

    public ChestTakeController(BlockPos chestPos, Predicate<ItemStack> matcher,
                               String label, int maxStacks) {
        this.chestPos = chestPos;
        this.matcher = matcher;
        this.label = label;
        this.maxStacks = maxStacks;
    }

    public String getFailReason() {
        return failReason;
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
                failReason = "打不开" + label;
                return Result.FAILED;
            }
            if (!FarmLookController.smoothFace(player, Vec3d.ofCenter(chestPos))) {
                return Result.WORKING;
            }
            openAttempts++;
            BlockHitResult hit = new BlockHitResult(
                    Vec3d.ofCenter(chestPos), Direction.UP, chestPos, false);
            mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
            player.swingHand(Hand.MAIN_HAND);
            cooldown = 15;
            return Result.WORKING;
        }

        // 拿够了 → 收工
        if (stacksTaken >= maxStacks && hasMatching(player)) {
            player.closeHandledScreen();
            return Result.DONE;
        }

        if (takeAttempts >= maxStacks + 4) {
            player.closeHandledScreen();
            if (hasMatching(player)) {
                return Result.DONE; // 背包放不下更多了，但至少拿到了
            }
            failReason = "从" + label + "取物失败（背包可能没有空位）";
            return Result.FAILED;
        }

        // 取箱子里匹配的物品
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || !matcher.test(stack)) continue;

            takeAttempts++;
            stacksTaken++;
            mc.interactionManager.clickSlot(handler.syncId, slot.id, 0,
                    SlotActionType.QUICK_MOVE, player);
            cooldown = 5;
            return Result.WORKING;
        }

        // 箱子里没有（更多）匹配物品了
        player.closeHandledScreen();
        if (hasMatching(player)) {
            return Result.DONE;
        }
        failReason = label + "里没有匹配的物品";
        return Result.FAILED;
    }

    private boolean hasMatching(ClientPlayerEntity player) {
        for (int i = 0; i < 36; i++) {
            if (i == DepositController.RESERVED_SLOT) continue;
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && matcher.test(stack)) return true;
        }
        return false;
    }

}
