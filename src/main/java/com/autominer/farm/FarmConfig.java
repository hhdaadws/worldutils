package com.autominer.farm;

import com.autominer.config.ModConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自动种地配置。保存为 config/autofarm.json
 */
public class FarmConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static FarmConfig instance;

    /** 农场所在维度（第一次设置区域/绑定时记录，所有点必须同维度）。 */
    public String dim = null;

    /** 大区域：区域内所有耕地(farmland)参与循环种植。 */
    public Region region = null;

    /** 小区域：不同小区域种不同作物；耕地不在任何小区域内则忽略。 */
    public List<Zone> zones = new ArrayList<>();

    /** 作物定义：名称 → 种子/成熟特征。 */
    public Map<String, Crop> crops = new LinkedHashMap<>();

    /** 作物存放箱（收获物存这里，可多个轮换）。 */
    public List<ModConfig.Pos> cropChests = new ArrayList<>();

    /** 浇水器位置（可多个）。 */
    public List<ModConfig.Pos> waterers = new ArrayList<>();

    /** 装水点（对着水右键装桶的位置，指向水方块本身）。 */
    public ModConfig.Pos waterSource = null;

    /** 浇水间隔（分钟）。 */
    public int waterIntervalMinutes = 20;

    /** 每个浇水器每轮需要倒几桶水。 */
    public int bucketsPerWaterer = 1;

    /** 收获方式：break=左键挖掉，use=右键收获（插件常见）。 */
    public String harvestMode = "break";

    /** 每次去种子箱拿几组种子。 */
    public int seedStacksPerTrip = 2;

    /** 背包剩余空格 <= 此值时去存作物。 */
    public int depositWhenEmptySlots = 2;

    // ---------- 数据结构 ----------

    /** 立方体区域（两角点，自动归一化）。 */
    public static class Region {
        public int x1, y1, z1, x2, y2, z2;

        public Region() {}

        public Region(BlockPos a, BlockPos b) {
            this.x1 = a.getX(); this.y1 = a.getY(); this.z1 = a.getZ();
            this.x2 = b.getX(); this.y2 = b.getY(); this.z2 = b.getZ();
        }

        public int minX() { return Math.min(x1, x2); }
        public int minY() { return Math.min(y1, y2); }
        public int minZ() { return Math.min(z1, z2); }
        public int maxX() { return Math.max(x1, x2); }
        public int maxY() { return Math.max(y1, y2); }
        public int maxZ() { return Math.max(z1, z2); }

        /** yPad：y 方向上下各放宽几格（小区域一般只框地面一层）。 */
        public boolean contains(BlockPos p, int yPad) {
            return p.getX() >= minX() && p.getX() <= maxX()
                    && p.getZ() >= minZ() && p.getZ() <= maxZ()
                    && p.getY() >= minY() - yPad && p.getY() <= maxY() + yPad;
        }

        public long volume() {
            return (long) (maxX() - minX() + 1) * (maxY() - minY() + 1) * (maxZ() - minZ() + 1);
        }

        @Override
        public String toString() {
            return "(" + minX() + "," + minY() + "," + minZ() + ")~(" + maxX() + "," + maxY() + "," + maxZ() + ")";
        }
    }

    /** 小区域：这一片种什么作物。 */
    public static class Zone {
        public String crop;
        public Region box;

        public Zone() {}

        public Zone(String crop, Region box) {
            this.crop = crop;
            this.box = box;
        }

        @Override
        public String toString() {
            return crop + " @ " + box;
        }
    }

    /**
     * 作物定义。种子不是原版种子而是插件物品：
     * 实际物品 id（如 minecraft:sugar）+ 自定义显示名（如 蚕豆种子），按名字子串匹配。
     */
    public static class Crop {
        public String seedItemId;              // 种子实际物品 id
        public String seedName;                // 种子显示名（子串匹配）
        public ModConfig.Pos seedChest = null; // 该作物的种子箱
        /** 成熟方块特征（可多条，任一匹配即视为成熟）。用 /farm learn <作物> 学习。 */
        public List<StateSig> mature = new ArrayList<>();
    }

    /** 方块状态签名：方块 id + 全部状态属性。 */
    public static class StateSig {
        public String blockId;
        public Map<String, String> props = new LinkedHashMap<>();

        @Override
        public String toString() {
            return blockId + (props.isEmpty() ? "" : props.toString());
        }
    }

    // ---------- 持久化 ----------

    public static FarmConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("autofarm.json");
    }

    public static void load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                String json = Files.readString(f, StandardCharsets.UTF_8);
                instance = GSON.fromJson(json, FarmConfig.class);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (instance == null) instance = new FarmConfig();
        if (instance.zones == null) instance.zones = new ArrayList<>();
        if (instance.crops == null) instance.crops = new LinkedHashMap<>();
        if (instance.cropChests == null) instance.cropChests = new ArrayList<>();
        if (instance.waterers == null) instance.waterers = new ArrayList<>();
        for (Crop c : instance.crops.values()) {
            if (c.mature == null) c.mature = new ArrayList<>();
        }
    }

    public static void save() {
        try {
            Files.writeString(file(), GSON.toJson(get()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** 返回缺失的必要配置；为空表示可以启动。 */
    public List<String> missing() {
        List<String> m = new ArrayList<>();
        if (region == null) m.add("农场区域(/farm pos1、/farm pos2 后 /farm region)");
        if (zones.isEmpty()) m.add("小区域(/farm zone add <作物>)");
        for (Zone z : zones) {
            Crop c = crops.get(z.crop);
            if (c == null || c.seedItemId == null || c.seedName == null) {
                m.add("作物「" + z.crop + "」种子定义(手持种子执行 /farm crop add " + z.crop + ")");
            }
        }
        return m;
    }
}
