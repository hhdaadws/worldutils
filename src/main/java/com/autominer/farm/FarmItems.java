package com.autominer.farm;

import com.autominer.bot.DepositController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;

import java.util.function.Predicate;

/**
 * 农场物品工具：种子识别（实际物品 id + 自定义显示名子串匹配）、
 * 插件洒水壶识别、把目标物品换到主手。
 */
public final class FarmItems {
    private FarmItems() {}

    public static String idOf(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    /** 是否为某作物的种子：物品 id 相同且显示名包含种子名。 */
    public static boolean isSeedOf(FarmConfig.Crop crop, ItemStack stack) {
        if (stack.isEmpty() || crop == null || crop.seedItemId == null || crop.seedName == null) {
            return false;
        }
        if (!idOf(stack).equals(crop.seedItemId)) return false;
        return stack.getName().getString().contains(crop.seedName);
    }

    /** 是否为任意已定义作物的种子。 */
    public static boolean isAnySeed(FarmConfig cfg, ItemStack stack) {
        if (stack.isEmpty()) return false;
        for (FarmConfig.Crop crop : cfg.crops.values()) {
            if (isSeedOf(crop, stack)) return true;
        }
        return false;
    }

    /** 是否为绑定的食物：实际物品 id + 显示名精确匹配。 */
    public static boolean isFood(FarmConfig cfg, ItemStack stack) {
        if (stack.isEmpty() || cfg.food == null || cfg.food.itemId == null) return false;
        if (!idOf(stack).equals(cfg.food.itemId)) return false;
        return cfg.food.itemName == null || stack.getName().getString().equals(cfg.food.itemName);
    }

    public static boolean hasFood(ClientPlayerEntity player, FarmConfig cfg) {
        return countMatching(player, stack -> isFood(cfg, stack)) > 0;
    }

    /** 是否为已绑定的插件洒水壶。 */
    public static boolean isWateringCan(FarmConfig cfg, ItemStack stack) {
        if (stack.isEmpty() || cfg.wateringCan == null || cfg.wateringCan.itemId == null) return false;
        if (!idOf(stack).equals(cfg.wateringCan.itemId)) return false;
        if (cfg.wateringCan.names == null || cfg.wateringCan.names.isEmpty()) return true;
        String name = stack.getName().getString();
        return cfg.wateringCan.names.stream().anyMatch(name::equals);
    }

    public static int countSeeds(ClientPlayerEntity player, FarmConfig.Crop crop) {
        return countMatching(player, s -> isSeedOf(crop, s));
    }

    public static int countMatching(ClientPlayerEntity player, Predicate<ItemStack> pred) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            if (i == DepositController.RESERVED_SLOT) continue;
            ItemStack stack = player.getInventory().getStack(i);
            if (pred.test(stack)) n += stack.getCount();
        }
        return n;
    }

    public static boolean hasWateringCan(ClientPlayerEntity player, FarmConfig cfg) {
        return countMatching(player, s -> isWateringCan(cfg, s)) > 0;
    }

    public static boolean isWateringCanInHand(ClientPlayerEntity player, FarmConfig cfg) {
        return isWateringCan(cfg, player.getMainHandStack());
    }

    /** 装水前后显示名变化时把新名字加入签名，避免下一步找不到同一把壶。 */
    public static void rememberWateringCanState(FarmConfig cfg, ItemStack stack) {
        if (stack.isEmpty() || cfg.wateringCan == null) return;
        if (!idOf(stack).equals(cfg.wateringCan.itemId)) return;
        if (cfg.wateringCan.names == null) cfg.wateringCan.names = new java.util.ArrayList<>();
        String name = stack.getName().getString();
        if (!cfg.wateringCan.names.contains(name)) {
            cfg.wateringCan.names.add(name);
            FarmConfig.save();
        }
    }

    /**
     * 把符合条件的物品换到主手。已在主手返回 true；
     * 在快捷栏则切换选中格；在主背包则 SWAP 到快捷栏（换完下一 tick 再用更稳）。
     * 找不到返回 false。
     */
    public static boolean selectMatching(MinecraftClient mc, ClientPlayerEntity player,
                                         Predicate<ItemStack> pred) {
        PlayerInventory inv = player.getInventory();
        if (pred.test(player.getMainHandStack())) return true;

        // 快捷栏 0-8
        for (int i = 0; i < 9; i++) {
            if (pred.test(inv.getStack(i))) {
                inv.setSelectedSlot(i);
                return true;
            }
        }
        // 主背包 10-35（跳过保留格 9）→ SWAP 到快捷栏
        for (int i = 10; i < 36; i++) {
            if (i == DepositController.RESERVED_SLOT) continue;
            if (pred.test(inv.getStack(i))) {
                int hotbar = chooseHotbarSlot(player);
                // 玩家背包 ScreenHandler：槽位 9..35 = 背包 9..35；SWAP 的 button = 快捷栏下标
                mc.interactionManager.clickSlot(player.playerScreenHandler.syncId, i, hotbar,
                        SlotActionType.SWAP, player);
                inv.setSelectedSlot(hotbar);
                return true;
            }
        }
        return false;
    }

    /** 选一个快捷栏格子用来放换入的物品：优先空格，其次非洒水壶格，兜底当前选中格。 */
    private static int chooseHotbarSlot(ClientPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isEmpty()) return i;
        }
        FarmConfig cfg = FarmConfig.get();
        for (int i = 0; i < 9; i++) {
            if (!isWateringCan(cfg, inv.getStack(i))) return i;
        }
        return inv.getSelectedSlot();
    }
}
