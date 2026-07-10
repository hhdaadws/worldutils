package com.autominer.bot;

import com.autominer.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

/**
 * 回放录制的传送操作：发送命令 → 等菜单打开 → 依次点击各级菜单格子。
 */
public class MenuNavigator {
    public enum Result {
        WORKING,
        DONE,    // 命令已发送且所有点击完成（传送本身由外部等待确认）
        FAILED
    }

    private final ModConfig.MenuSeq seq;
    private int step = -1;      // -1 = 还没发命令；否则 = 已完成的点击数
    private int usedSyncId = 0; // 已点击过的菜单 syncId，防止重复点同一个菜单
    private int timer = 0;
    private int stabilize = 0;
    private String failReason = null;

    public MenuNavigator(ModConfig.MenuSeq seq) {
        this.seq = seq;
    }

    public String getFailReason() {
        return failReason;
    }

    public Result tick(MinecraftClient mc, ClientPlayerEntity player) {
        timer++;

        if (step == -1) {
            player.networkHandler.sendChatCommand(seq.command);
            step = 0;
            timer = 0;
            return seq.clicks == null || seq.clicks.isEmpty() ? Result.DONE : Result.WORKING;
        }

        if (seq.clicks == null || step >= seq.clicks.size()) {
            return Result.DONE;
        }

        ScreenHandler handler = player.currentScreenHandler;
        boolean menuOpen = handler != null && handler.syncId != 0
                && handler != player.playerScreenHandler
                && handler.syncId != usedSyncId;

        if (menuOpen) {
            // 等几 tick 让服务器把菜单物品同步过来
            stabilize++;
            if (stabilize >= 8) {
                int slot = seq.clicks.get(step);
                if (slot < 0 || slot >= handler.slots.size()) {
                    failReason = "录制的格子 " + slot + " 超出当前菜单大小 " + handler.slots.size();
                    return Result.FAILED;
                }
                mc.interactionManager.clickSlot(handler.syncId, slot, 0,
                        SlotActionType.PICKUP, player);
                usedSyncId = handler.syncId;
                step++;
                stabilize = 0;
                timer = 0;
            }
        } else {
            stabilize = 0;
        }

        if (timer > 20 * 15) {
            failReason = "等待菜单打开超时（第 " + (step + 1) + " 步）";
            return Result.FAILED;
        }
        return Result.WORKING;
    }
}
