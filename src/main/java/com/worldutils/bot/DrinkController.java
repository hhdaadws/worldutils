package com.worldutils.bot;

import com.worldutils.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;

/**
 * 喝急迫药水：把药水换到手上，按住右键喝，直到获得急迫效果。
 * 注意：药水喝完到效果生效之间有几 tick 延迟，需要宽限期，不能立刻判定失败。
 */
public class DrinkController {
    public enum Result {
        WORKING,
        DONE,
        FAILED
    }

    private boolean everDrank = false; // 是否已经喝下去过（物品被消耗）
    private int graceTimer = 0;        // 喝完等效果生效的宽限计时
    private int holdTimer = 0;
    private String failReason = null;

    public String getFailReason() {
        return failReason;
    }

    public Result tick(MinecraftClient mc, ClientPlayerEntity player) {
        if (player.hasStatusEffect(StatusEffects.HASTE)) {
            InputController.use = false;
            return Result.DONE;
        }

        String potionId = ModConfig.get().potionItemId;
        if (potionId == null) {
            failReason = "还不知道药水是什么物品（先绑定药水箱让我取一次）";
            return Result.FAILED;
        }

        int sel = player.getInventory().getSelectedSlot();
        boolean holdingPotion = isPotion(player.getInventory().getStack(sel), potionId);

        if (holdingPotion) {
            // 抬头看天避免右键点到方块，按住右键喝
            player.setPitch(-45.0f);
            InputController.use = true;
            everDrank = true; // 已开始喝（物品随后会被消耗）
            holdTimer++;
            if (holdTimer > 20 * 10) {
                failReason = "按住右键 10 秒药水没有被喝掉，请检查该物品是否可饮用";
                InputController.use = false;
                return Result.FAILED;
            }
            return Result.WORKING;
        }

        InputController.use = false;
        holdTimer = 0;

        // 手里没药：先找背包里的
        for (int i = 0; i < 9; i++) {
            if (isPotion(player.getInventory().getStack(i), potionId)) {
                player.getInventory().setSelectedSlot(i);
                return Result.WORKING;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (i == DepositController.RESERVED_SLOT) continue;
            if (isPotion(player.getInventory().getStack(i), potionId)) {
                mc.interactionManager.clickSlot(player.playerScreenHandler.syncId, i, sel,
                        SlotActionType.SWAP, player);
                return Result.WORKING;
            }
        }

        // 背包也没有药了
        if (everDrank) {
            // 刚喝完最后一瓶，效果包可能还没到 → 等最多 5 秒
            graceTimer++;
            if (graceTimer > 20 * 5) {
                failReason = "喝了药水但没有获得急迫效果，请检查药水箱里放的东西";
                return Result.FAILED;
            }
            return Result.WORKING;
        }
        failReason = "背包里没有药水了";
        return Result.FAILED;
    }

    private static boolean isPotion(ItemStack stack, String potionId) {
        return !stack.isEmpty()
                && Registries.ITEM.getId(stack.getItem()).toString().equals(potionId);
    }
}
