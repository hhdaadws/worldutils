package com.autominer.farm;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 扫描所有小区域的并集：找耕地(farmland)、按小区域归类作物、
 * 用学习到的"成熟特征签名"判断作物是否成熟。
 */
public final class FarmScanner {
    private FarmScanner() {}

    public enum PlotState {
        EMPTY,   // 空耕地，可以种
        GROWING, // 有作物但未匹配成熟特征
        MATURE   // 匹配了成熟特征，可以收
    }

    /** 一块地：耕地坐标、作物坐标(耕地上方)、所属作物名、状态。 */
    public record Plot(BlockPos farmland, BlockPos crop, String cropName, int zoneIndex, PlotState state) {}

    /** 扫描所有小 zone 内的耕地方块（重叠位置自动去重，只在开始/定期刷新缓存）。 */
    public static List<BlockPos> findFarmland(World world, FarmConfig cfg) {
        java.util.LinkedHashSet<BlockPos> found = new java.util.LinkedHashSet<>();
        for (FarmConfig.Zone zone : cfg.zones) {
            if (zone == null || zone.box == null) continue;
            FarmConfig.Region r = zone.box;
            for (BlockPos p : BlockPos.iterate(
                    r.minX(), r.minY(), r.minZ(), r.maxX(), r.maxY(), r.maxZ())) {
                if (world.getBlockState(p).isOf(Blocks.FARMLAND)) {
                    found.add(p.toImmutable());
                }
            }
        }
        return new ArrayList<>(found);
    }

    /** 对缓存的耕地列表做当前状态分类。不在任何小区域内的耕地忽略。 */
    public static List<Plot> classify(World world, FarmConfig cfg, List<BlockPos> farmland) {
        List<Plot> plots = new ArrayList<>();
        for (BlockPos f : farmland) {
            if (!world.getBlockState(f).isOf(Blocks.FARMLAND)) continue; // 可能被踩坏/改掉
            int zoneIndex = zoneIndexAt(cfg, f);
            if (zoneIndex < 0) continue;
            String cropName = cfg.zones.get(zoneIndex).crop;
            BlockPos cropPos = f.up();
            BlockState st = world.getBlockState(cropPos);
            PlotState ps;
            if (st.isAir()) {
                ps = PlotState.EMPTY;
            } else {
                FarmConfig.Crop crop = cfg.crops.get(cropName);
                ps = (crop != null && matchesMature(crop, st)) ? PlotState.MATURE : PlotState.GROWING;
            }
            plots.add(new Plot(f, cropPos, cropName, zoneIndex, ps));
        }
        return plots;
    }

    /** 该耕地属于哪个小区域（返回作物名，命中第一个；没有则 null）。 */
    public static String zoneCropAt(FarmConfig cfg, BlockPos farmland) {
        int index = zoneIndexAt(cfg, farmland);
        return index < 0 ? null : cfg.zones.get(index).crop;
    }

    /** 该耕地命中的第一个小区域编号；没有则 -1。 */
    public static int zoneIndexAt(FarmConfig cfg, BlockPos farmland) {
        for (int i = 0; i < cfg.zones.size(); i++) {
            FarmConfig.Zone z = cfg.zones.get(i);
            if (z.box != null && z.box.contains(farmland, 1)) return i;
        }
        return -1;
    }

    /** 方块状态是否匹配该作物的任一成熟特征。 */
    public static boolean matchesMature(FarmConfig.Crop crop, BlockState st) {
        if (crop.mature == null || crop.mature.isEmpty()) return false;
        String blockId = Registries.BLOCK.getId(st.getBlock()).toString();
        Map<String, String> props = null;
        for (FarmConfig.StateSig sig : crop.mature) {
            if (!blockId.equals(sig.blockId)) continue;
            if (props == null) props = propsOf(st);
            if (props.equals(sig.props)) return true;
        }
        return false;
    }

    /** 生成方块状态签名（学习成熟特征用）。 */
    public static FarmConfig.StateSig sigOf(BlockState st) {
        FarmConfig.StateSig sig = new FarmConfig.StateSig();
        sig.blockId = Registries.BLOCK.getId(st.getBlock()).toString();
        sig.props = propsOf(st);
        return sig;
    }

    /** 方块状态所有属性 → name=value 的有序 Map。 */
    public static Map<String, String> propsOf(BlockState st) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Property<?> p : st.getProperties()) {
            m.put(p.getName(), valueName(st, p));
        }
        return m;
    }

    private static <T extends Comparable<T>> String valueName(BlockState st, Property<T> p) {
        return p.name(st.get(p));
    }
}
