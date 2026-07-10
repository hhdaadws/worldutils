package com.autominer.farm;

import com.autominer.bot.DepositController;
import com.autominer.bot.InputController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

/** 吃绑定食物，维持足够疾跑的饥饿值。 */
public class FarmEatController {
    public enum Result { WORKING, DONE, FAILED }

    public static final int EAT_UNTIL = 18;
    public static final int EAT_BELOW = 14;
    public static final int SPRINT_MIN = 7;

    private boolean everAte = false;
    private int graceTimer = 0;
    private int holdTimer = 0;
    private String failReason;

    public String getFailReason() {
        return failReason;
    }

    public Result tick(MinecraftClient mc, ClientPlayerEntity player, FarmConfig cfg) {
        int hunger = player.getHungerManager().getFoodLevel();
        if (hunger >= EAT_UNTIL) {
            InputController.use = false;
            return Result.DONE;
        }
        if (cfg.food == null || cfg.food.itemId == null) {
            failReason = "还没有绑定食物（手持食物执行 /farm bindfood）";
            return Result.FAILED;
        }

        int selected = player.getInventory().getSelectedSlot();
        if (FarmItems.isFood(cfg, player.getInventory().getStack(selected))) {
            player.setPitch(-45.0f);
            InputController.sneak = false;
            InputController.use = true;
            everAte = true;
            holdTimer++;
            if (holdTimer > 20 * 30) {
                InputController.use = false;
                if (hunger >= SPRINT_MIN) return Result.DONE;
                failReason = "持续进食但饥饿值没有上升，请检查绑定的物品是否能食用";
                return Result.FAILED;
            }
            return Result.WORKING;
        }

        InputController.use = false;
        holdTimer = 0;

        for (int i = 0; i < 9; i++) {
            if (FarmItems.isFood(cfg, player.getInventory().getStack(i))) {
                player.getInventory().setSelectedSlot(i);
                return Result.WORKING;
            }
        }
        for (int i = 10; i < 36; i++) {
            if (i == DepositController.RESERVED_SLOT) continue;
            ItemStack stack = player.getInventory().getStack(i);
            if (FarmItems.isFood(cfg, stack)) {
                mc.interactionManager.clickSlot(player.playerScreenHandler.syncId, i, selected,
                        SlotActionType.SWAP, player);
                return Result.WORKING;
            }
        }

        if (everAte) {
            graceTimer++;
            if (graceTimer <= 20 * 3) return Result.WORKING;
            if (player.getHungerManager().getFoodLevel() >= SPRINT_MIN) return Result.DONE;
            failReason = "食物吃完后饥饿值仍不足以疾跑";
            return Result.FAILED;
        }
        failReason = "背包里没有绑定的食物";
        return Result.FAILED;
    }
}
