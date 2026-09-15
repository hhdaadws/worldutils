package com.worldutils.farm;

import com.worldutils.bot.DepositController;
import com.worldutils.bot.InputController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

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
            // 吃饭误开的容器界面（准星扫过箱子时点开的）立刻关掉
            if (player.currentScreenHandler != player.playerScreenHandler) {
                InputController.use = false;
                player.closeHandledScreen();
                return Result.WORKING;
            }
            // 朝脚下吃：低头 80°，平滑过渡；没压到位前绝不按右键——
            // 否则视角下压路径会扫过面前的箱子，把箱子点开。
            float pitchDelta = 80.0f - player.getPitch();
            player.setPitch(player.getPitch() + Math.max(-12.0f, Math.min(12.0f, pitchDelta)));
            boolean pitchReady = Math.abs(80.0f - player.getPitch()) < 5.0f;
            // 准星仍指着容器（箱子/木桶）或实体时不按右键，右转视角避开再吃
            boolean aimSafe = true;
            if (mc.crosshairTarget != null) {
                if (mc.crosshairTarget.getType() == HitResult.Type.ENTITY) {
                    aimSafe = false;
                } else if (mc.crosshairTarget instanceof BlockHitResult bhr
                        && mc.crosshairTarget.getType() == HitResult.Type.BLOCK
                        && mc.world.getBlockEntity(bhr.getBlockPos()) instanceof Inventory) {
                    aimSafe = false;
                }
            }
            if (!pitchReady || !aimSafe) {
                InputController.use = false;
                if (!aimSafe) {
                    player.setYaw(player.getYaw() + 20.0f); // 转开避开容器/实体
                }
                return Result.WORKING;
            }
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
