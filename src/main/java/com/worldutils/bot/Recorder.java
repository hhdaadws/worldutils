package com.worldutils.bot;

import com.worldutils.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 传送操作录制器：
 * /miner record mine <打开菜单的指令> → mod 发送指令 → 玩家手动点击各级菜单完成传送
 * → 检测到位置突变/维度变化后自动保存整个操作序列。
 */
public final class Recorder {
    private static String target = null; // "mine" / "home"
    private static String command = null;
    private static List<Integer> clicks = null;
    private static Vec3d startPos = null;
    private static String startDim = null;

    private Recorder() {}

    public static boolean isActive() {
        return target != null;
    }

    public static void start(String tgt, String cmd) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        if (Bot.INSTANCE.isRunning()) {
            Bot.msg("§c请先 /miner stop 再录制");
            return;
        }
        target = tgt;
        command = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        clicks = new ArrayList<>();
        startPos = mc.player.getPos();
        startDim = mc.world.getRegistryKey().getValue().toString();
        mc.player.networkHandler.sendChatCommand(command);
        Bot.msg("§a开始录制" + ("mine".equals(tgt) ? "去矿点" : "回家") + "操作，已发送 §e/" + command
                + "§a。请手动点完菜单完成传送，传送成功后自动保存（/miner record cancel 取消）");
    }

    /** 由 HandledScreenMixin 调用。 */
    public static void onSlotClick(int slotId) {
        if (!isActive() || clicks == null) return;
        clicks.add(slotId);
        Bot.msg("§7已录制点击: 格子 " + slotId + "（第 " + clicks.size() + " 步）");
    }

    /** 每 tick 检测传送是否完成。 */
    public static void tick(MinecraftClient mc) {
        if (!isActive()) return;
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return;

        String dim = mc.world.getRegistryKey().getValue().toString();
        boolean teleported = !dim.equals(startDim)
                || player.getPos().squaredDistanceTo(startPos) > 16 * 16;
        if (!teleported) return;

        ModConfig.MenuSeq seq = new ModConfig.MenuSeq(command, clicks);
        ModConfig cfg = ModConfig.get();
        if ("mine".equals(target)) {
            cfg.tpMine = seq;
        } else {
            cfg.tpHome = seq;
        }
        ModConfig.save();
        Bot.msg("§a录制完成并保存: §e" + seq);
        reset();
    }

    public static void cancel() {
        if (isActive()) {
            Bot.msg("已取消录制");
        }
        reset();
    }

    private static void reset() {
        target = null;
        command = null;
        clicks = null;
        startPos = null;
        startDim = null;
    }
}
