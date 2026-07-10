package com.autominer.bot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 通用取物控制器：打开指定箱子，取出若干组指定物品
 * （物品 id 未记录时取第一个非空格子并记录）。药水箱/食物箱共用。
 */
public class ItemTakeController {
    public enum Result {
        WORKING,
        DONE,
        FAILED
    }

    private final BlockPos chestPos;
    private final Supplier<String> idGet;
    private final Consumer<String> idSet;
    private final String label;
    private final int maxStacks;

    private int openAttempts = 0;
    private int cooldown = 0;
    private int takeAttempts = 0;
    private int stacksTaken = 0;
    private String failReason = null;

    public ItemTakeController(BlockPos chestPos, Supplier<String> idGet, Consumer<String> idSet,
                              String label, int maxStacks) {
        this.chestPos = chestPos;
        this.idGet = idGet;
        this.idSet = idSet;
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
            openAttempts++;
            face(player);
            BlockHitResult hit = new BlockHitResult(
                    Vec3d.ofCenter(chestPos), Direction.UP, chestPos, false);
            mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
            player.swingHand(Hand.MAIN_HAND);
            cooldown = 15;
            return Result.WORKING;
        }

        // 拿够了 → 收工
        if (stacksTaken >= maxStacks && hasItem(player, idGet.get())) {
            player.closeHandledScreen();
            return Result.DONE;
        }

        if (takeAttempts >= maxStacks + 4) {
            player.closeHandledScreen();
            if (hasItem(player, idGet.get())) {
                return Result.DONE; // 背包放不下更多了，但至少拿到了
            }
            failReason = "从" + label + "取物失败（背包可能没有空位）";
            return Result.FAILED;
        }

        // 取箱子里匹配的物品
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;

            String id = Registries.ITEM.getId(stack.getItem()).toString();
            if (idGet.get() == null) {
                idSet.accept(id);
                Bot.msg("§7已记录" + label + "物品: §e" + id);
            } else if (!idGet.get().equals(id)) {
                continue; // 箱子里混了别的东西，跳过
            }
            takeAttempts++;
            stacksTaken++;
            mc.interactionManager.clickSlot(handler.syncId, slot.id, 0,
                    SlotActionType.QUICK_MOVE, player);
            cooldown = 5;
            return Result.WORKING;
        }

        // 箱子里没有（更多）目标物品了
        player.closeHandledScreen();
        if (hasItem(player, idGet.get())) {
            return Result.DONE;
        }
        failReason = label + "是空的";
        return Result.FAILED;
    }

    public static boolean hasItem(ClientPlayerEntity player, String itemId) {
        if (itemId == null) return false;
        for (int i = 0; i < 36; i++) {
            if (i == DepositController.RESERVED_SLOT) continue;
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty()
                    && Registries.ITEM.getId(stack.getItem()).toString().equals(itemId)) {
                return true;
            }
        }
        return false;
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
