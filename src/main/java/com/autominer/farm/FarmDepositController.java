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

import java.util.HashMap;
import java.util.Map;

/**
 * 把收获的作物存进作物箱：QUICK_MOVE 存入除洒水壶/食物/种子/保留格外的所有物品。
 */
public class FarmDepositController {
    public enum Result {
        WORKING,
        DONE,        // 都存完了
        CHEST_FULL,  // 箱子满了，还有东西没存进去
        FAILED       // 打不开箱子
    }

    private final BlockPos chestPos;

    private int openAttempts = 0;
    private int cooldown = 0;
    private final Map<Integer, Integer> attempts = new HashMap<>();

    public FarmDepositController(BlockPos chestPos) {
        this.chestPos = chestPos;
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

        FarmConfig cfg = FarmConfig.get();
        boolean anyPending = false;
        for (Slot slot : handler.slots) {
            if (!(slot.inventory instanceof PlayerInventory)) continue;
            if (slot.getIndex() == DepositController.RESERVED_SLOT) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || shouldKeep(cfg, stack)) continue;

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

    /** 插件洒水壶、食物和所有已定义作物种子留在背包，其余存入。 */
    public static boolean shouldKeep(FarmConfig cfg, ItemStack stack) {
        if (FarmItems.isWateringCan(cfg, stack)) return true;
        if (FarmItems.isFood(cfg, stack)) return true;
        return FarmItems.isAnySeed(cfg, stack);
    }

}
