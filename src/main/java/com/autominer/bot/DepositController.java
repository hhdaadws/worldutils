package com.autominer.bot;

import com.autominer.config.ModConfig;
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

import java.util.HashMap;
import java.util.Map;

/**
 * 打开箱子：存入物品（保留列表除外，但耐久不足的旧镐要存入）；
 * 需要换镐时从箱子里取一把耐久充足的镐。
 */
public class DepositController {
    /** 保留格：按 E 打开的背包最上排第一格（PlayerInventory 索引 9），永不存入箱子。 */
    public static final int RESERVED_SLOT = 9;

    public enum Result {
        WORKING,
        DONE,        // 本箱子操作完成（物品可能没存完/镐可能没取到，由 Bot 检查决定去下一个箱子）
        CHEST_FULL,  // 箱子满了，还有物品没存进去
        FAILED       // 打不开箱子
    }

    private final BlockPos chestPos;
    private final boolean needPickaxe;
    private final int threshold;

    private int openAttempts = 0;
    private int cooldown = 0;
    private final Map<Integer, Integer> attempts = new HashMap<>();
    private boolean triedTakePickaxe = false;

    public DepositController(BlockPos chestPos, boolean needPickaxe, int threshold) {
        this.chestPos = chestPos;
        this.needPickaxe = needPickaxe;
        this.threshold = threshold;
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
            faceChest(player);
            BlockHitResult hit = new BlockHitResult(
                    Vec3d.ofCenter(chestPos), Direction.UP, chestPos, false);
            mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
            player.swingHand(Hand.MAIN_HAND);
            cooldown = 15;
            return Result.WORKING;
        }

        // ---- 存物阶段 ----
        boolean anyPending = false;
        for (Slot slot : handler.slots) {
            if (!(slot.inventory instanceof PlayerInventory)) continue;
            // 跳过背包界面最上排第一格（背包索引 9），这一格永不存入箱子
            if (slot.getIndex() == RESERVED_SLOT) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || shouldKeep(stack, threshold)) continue;

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

        // ---- 取镐阶段 ----
        if (needPickaxe && !triedTakePickaxe
                && !PickaxeUtil.hasUsable(player, threshold)) {
            for (Slot slot : handler.slots) {
                if (slot.inventory instanceof PlayerInventory) continue;
                ItemStack stack = slot.getStack();
                if (PickaxeUtil.isUsable(stack, threshold)) {
                    mc.interactionManager.clickSlot(handler.syncId, slot.id, 0,
                            SlotActionType.QUICK_MOVE, player);
                    triedTakePickaxe = true;
                    cooldown = 5; // 等服务器同步，然后收尾
                    return Result.WORKING;
                }
            }
            triedTakePickaxe = true; // 这个箱子里没有可用镐
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

    /**
     * 是否保留在背包：耐久不足的旧镐一律存入；其余按保留列表匹配。
     */
    public static boolean shouldKeep(ItemStack stack, int threshold) {
        if (PickaxeUtil.isWorn(stack, threshold)) {
            // 绑定了专用镐子箱：旧镐留在背包，等会儿放去镐子箱；否则存进普通箱子
            return ModConfig.get().pickChest != null;
        }
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        // 急迫药水和食物永远保留
        if (id.equals(ModConfig.get().potionItemId) || id.equals(ModConfig.get().foodItemId)) {
            return true;
        }
        for (String keep : ModConfig.get().keepIds) {
            if (keep != null && !keep.isBlank() && id.contains(keep)) {
                return true;
            }
        }
        return false;
    }
}
