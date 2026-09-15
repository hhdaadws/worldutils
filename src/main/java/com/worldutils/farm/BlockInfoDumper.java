package com.worldutils.farm;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * /farm info：dump 准星指向方块的全部可见信息（方块 id、状态属性、
 * 方块实体 NBT、附近实体），输出到聊天并追加到 config/autofarm-info.log。
 * 用途：插件作物不是原版作物，把不同生长阶段的信息发给开发者/用 /farm learn 学习。
 */
public final class BlockInfoDumper {
    private BlockInfoDumper() {}

    /** 返回给玩家看的文本；没有指向方块返回 null。 */
    public static String dump(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return null;

        BlockPos pos = null;
        HitResult hit = mc.crosshairTarget;
        if (hit instanceof EntityHitResult ehr && hit.getType() == HitResult.Type.ENTITY) {
            StringBuilder entityInfo = new StringBuilder();
            entityInfo.append("§6==== 虚拟目标信息 ====§r\n");
            entityInfo.append("维度: ").append(mc.world.getRegistryKey().getValue()).append("\n");
            appendEntity(entityInfo, ehr.getEntity(), "准星目标");
            BlockPos below = BlockPos.ofFloored(ehr.getPos());
            appendBlock(entityInfo, mc, below, "目标位置方块");
            String text = entityInfo.toString();
            String logged = appendLog(text);
            return text + "§7(已保存到 " + logged + "，可整段复制)";
        }
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            pos = bhr.getBlockPos();
        } else {
            // 兜底：带流体的射线（方便指向水）
            HitResult fluidHit = mc.player.raycast(6.0, 0.0f, true);
            if (fluidHit instanceof BlockHitResult fbhr && fluidHit.getType() == HitResult.Type.BLOCK) {
                pos = fbhr.getBlockPos();
            }
        }
        if (pos == null) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("§6==== 方块信息 ====§r\n");
        sb.append("维度: ").append(mc.world.getRegistryKey().getValue()).append("\n");
        appendBlock(sb, mc, pos, "目标");
        appendBlock(sb, mc, pos.down(), "下方");
        appendBlock(sb, mc, pos.up(), "上方");

        // 附近实体（插件作物可能用展示实体/盔甲架实现）
        List<Entity> entities = mc.world.getOtherEntities(mc.player,
                new Box(pos).expand(2.0));
        sb.append("附近实体(").append(entities.size()).append("):\n");
        for (Entity e : entities) {
            appendEntity(sb, e, "  附近");
        }

        String text = sb.toString();
        String logged = appendLog(text);
        return text + "§7(已保存到 " + logged + "，可整段复制)";
    }

    private static void appendEntity(StringBuilder sb, Entity entity, String label) {
        sb.append(label).append(": §e").append(Registries.ENTITY_TYPE.getId(entity.getType()))
                .append("§r class=").append(entity.getClass().getSimpleName())
                .append(" id=").append(entity.getId())
                .append(" 名字=").append(entity.getName().getString());
        if (entity.getCustomName() != null) {
            sb.append(" 自定义名=").append(entity.getCustomName().getString());
        }
        sb.append(String.format(" @(%.3f, %.3f, %.3f)", entity.getX(), entity.getY(), entity.getZ()))
                .append(" box=").append(entity.getBoundingBox()).append("\n");
    }

    private static void appendBlock(StringBuilder sb, MinecraftClient mc, BlockPos pos, String label) {
        BlockState st = mc.world.getBlockState(pos);
        sb.append(label).append(" ").append(pos.toShortString()).append(": §e")
                .append(Registries.BLOCK.getId(st.getBlock())).append("§r\n");
        Map<String, String> props = FarmScanner.propsOf(st);
        if (!props.isEmpty()) {
            sb.append("  属性: ").append(props).append("\n");
        }
        if (!st.getFluidState().isEmpty()) {
            sb.append("  流体: ").append(Registries.FLUID.getId(st.getFluidState().getFluid())).append("\n");
        }
        BlockEntity be = mc.world.getBlockEntity(pos);
        if (be != null) {
            sb.append("  方块实体: ").append(Registries.BLOCK_ENTITY_TYPE.getId(be.getType())).append("\n");
            try {
                sb.append("  NBT: ").append(be.createNbtWithIdentifyingData(mc.world.getRegistryManager())).append("\n");
            } catch (Throwable t) {
                sb.append("  NBT: (客户端读取失败: ").append(t.getClass().getSimpleName()).append(")\n");
            }
        }
    }

    /** 追加写入日志文件，返回文件名。 */
    private static String appendLog(String text) {
        try {
            Path f = FabricLoader.getInstance().getConfigDir().resolve("autofarm-info.log");
            String entry = "\n===== " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    + " =====\n" + text.replaceAll("§.", "") + "\n";
            Files.writeString(f, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return "config/autofarm-info.log";
        } catch (Exception e) {
            return "(写日志失败)";
        }
    }
}
