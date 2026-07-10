package com.autominer.bot;

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
 * 镐子箱：把耐久不足的旧镐放进去，再取一把耐久充足的新镐。
 */
public class PickaxeSwapController {
    public enum Result {
        WORKING,
        DONE,
        FAILED
    }

    private final BlockPos chestPos;
    private final int threshold;

    private int openAttempts = 0;
    private int cooldown = 0;
    private final Map<Integer, Integer> attempts = new HashMap<>();
    private int takeAttempts = 0;
    private String failReason = null;

    public PickaxeSwapController(BlockPos chestPos, int threshold) {
        this.chestPos = chestPos;
        this.threshold = threshold;
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
                failReason = "打不开镐子箱";
                return Result.FAILED;
            }
            openAttempts++;
            face(player);
            BlockHitResult hit = new BlockHitResult(
                    Vec3d.ofCenter(chestPos), Direction.UP, chestPos, false);
            mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
            player.swingHand(Hand.MAIN_HAND);
            cooldown = 15;
            return Result.WORKING;
        }

        // 1) 把耐久不足的旧镐放进箱子
        for (Slot slot : handler.slots) {
            if (!(slot.inventory instanceof PlayerInventory)) continue;
            if (slot.getIndex() == DepositController.RESERVED_SLOT) continue;
            ItemStack stack = slot.getStack();
            if (!PickaxeUtil.isWorn(stack, threshold)) continue;

            int tried = attempts.getOrDefault(slot.id, 0);
            if (tried >= 2) continue; // 放不进去（箱子满）就带着吧，不影响继续
            attempts.put(slot.id, tried + 1);
            mc.interactionManager.clickSlot(handler.syncId, slot.id, 0,
                    SlotActionType.QUICK_MOVE, player);
            cooldown = 2;
            return Result.WORKING;
        }

        // 2) 没有可用镐就从箱子里取一把
        if (!PickaxeUtil.hasUsable(player, threshold)) {
            if (takeAttempts >= 4) {
                player.closeHandledScreen();
                failReason = "从镐子箱取镐失败（背包可能没有空位）";
                return Result.FAILED;
            }
            for (Slot slot : handler.slots) {
                if (slot.inventory instanceof PlayerInventory) continue;
                ItemStack stack = slot.getStack();
                if (PickaxeUtil.isUsable(stack, threshold)) {
                    takeAttempts++;
                    mc.interactionManager.clickSlot(handler.syncId, slot.id, 0,
                            SlotActionType.QUICK_MOVE, player);
                    cooldown = 5;
                    return Result.WORKING;
                }
            }
            player.closeHandledScreen();
            failReason = "镐子箱里没有耐久充足的镐子";
            return Result.FAILED;
        }

        player.closeHandledScreen();
        return Result.DONE;
    }

    private void face(ClientPlayerEntity player) {
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
