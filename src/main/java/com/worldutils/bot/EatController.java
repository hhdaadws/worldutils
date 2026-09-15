package com.worldutils.bot;

import com.worldutils.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;

import java.util.function.Supplier;

/**
 * 吃东西：把食物换到手上，按住右键吃，吃到饥饿值 >= 18（保证能一直疾跑）。
 * 默认吃挖矿配置的食物；也可传入自定义食物 id 来源（定时存物复用）。
 */
public class EatController {
    public enum Result {
        WORKING,
        DONE,
        FAILED
    }

    public static final int EAT_UNTIL = 18;   // 吃到这个饥饿值为止
    public static final int EAT_BELOW = 14;   // 低于这个值就该吃了
    public static final int SPRINT_MIN = 7;   // 疾跑至少需要 >6

    private final Supplier<String> foodIdGet;

    private boolean everAte = false;
    private int graceTimer = 0;
    private int holdTimer = 0;
    private String failReason = null;

    public EatController() {
        this(() -> ModConfig.get().foodItemId);
    }

    public EatController(Supplier<String> foodIdGet) {
        this.foodIdGet = foodIdGet;
    }

    public String getFailReason() {
        return failReason;
    }

    public Result tick(MinecraftClient mc, ClientPlayerEntity player) {
        int food = player.getHungerManager().getFoodLevel();
        if (food >= EAT_UNTIL) {
            InputController.use = false;
            return Result.DONE;
        }

        String foodId = foodIdGet.get();
        if (foodId == null) {
            failReason = "还不知道食物是什么物品（先绑定食物箱让我取一次）";
            return Result.FAILED;
        }

        int sel = player.getInventory().getSelectedSlot();
        boolean holdingFood = isFood(player.getInventory().getStack(sel), foodId);

        if (holdingFood) {
            player.setPitch(-45.0f); // 抬头避免右键点到方块
            InputController.use = true;
            everAte = true;
            holdTimer++;
            if (holdTimer > 20 * 30) { // 吃 30 秒还没到目标 → 有问题
                InputController.use = false;
                if (food >= SPRINT_MIN) return Result.DONE;
                failReason = "一直在吃但饥饿值没有上升，请检查食物箱里放的东西";
                return Result.FAILED;
            }
            return Result.WORKING;
        }

        InputController.use = false;
        holdTimer = 0;

        // 手里没食物：从背包找
        for (int i = 0; i < 9; i++) {
            if (isFood(player.getInventory().getStack(i), foodId)) {
                player.getInventory().setSelectedSlot(i);
                return Result.WORKING;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (i == DepositController.RESERVED_SLOT) continue;
            if (isFood(player.getInventory().getStack(i), foodId)) {
                mc.interactionManager.clickSlot(player.playerScreenHandler.syncId, i, sel,
                        SlotActionType.SWAP, player);
                return Result.WORKING;
            }
        }

        // 背包没食物了
        if (everAte) {
            graceTimer++;
            if (graceTimer > 20 * 3) {
                // 吃完了最后的存货：只要还能疾跑就算完成
                if (player.getHungerManager().getFoodLevel() >= SPRINT_MIN) return Result.DONE;
                failReason = "食物吃完了饥饿值还是太低";
                return Result.FAILED;
            }
            return Result.WORKING;
        }
        failReason = "背包里没有食物了";
        return Result.FAILED;
    }

    private static boolean isFood(ItemStack stack, String foodId) {
        return !stack.isEmpty()
                && Registries.ITEM.getId(stack.getItem()).toString().equals(foodId);
    }
}
