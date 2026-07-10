package com.autominer.farm;

import com.autominer.config.ModConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

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

    /** 旧版大区域字段，仅为兼容已有 JSON 保留；运行逻辑不再使用。 */
    public Region region = null;

    /** 小区域：不同小区域种不同作物；耕地不在任何小区域内则忽略。 */
    public List<Zone> zones = new ArrayList<>();

    /** 作物定义：名称 → 种子/成熟特征。 */
    public Map<String, Crop> crops = new LinkedHashMap<>();

    /** 作物存放箱（收获物存这里，可多个轮换）。 */
    public List<ModConfig.Pos> cropChests = new ArrayList<>();

    /** 悬浮虚拟浇水器目标（可多个）。 */
    public List<WatererTarget> waterers = new ArrayList<>();

    /** 插件洒水壶物品；手持空洒水壶用 /farm bindcan 记录。 */
    public WateringCan wateringCan = null;

    /** 装水点（拿洒水壶对着水右键的位置，指向水方块本身）。 */
    public ModConfig.Pos waterSource = null;

    /** 上一次成功完成整轮浇水的真实时间；持久化后重启不会重复立即浇水。 */
    public long lastWaterTimeMs = 0L;

    /** 浇水间隔（分钟）。 */
    public int waterIntervalMinutes = 20;

    /** 每个浇水器每轮使用几次装满的洒水壶；兼容旧配置字段 bucketsPerWaterer。 */
    @SerializedName(value = "canUsesPerWaterer", alternate = {"bucketsPerWaterer"})
    public int canUsesPerWaterer = 1;

    /** 自动补充的食物及食物箱；用于让饥饿值始终支持疾跑。 */
    public FoodItem food = null;
    public ModConfig.Pos foodChest = null;
    public int foodStacksPerTrip = 2;

    /** 收获方式：use=拿对应种子右键，插件自动收获并复种；break=兼容其他服务器的左键挖掉。 */
    public String harvestMode = "use";

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

    /** 插件洒水壶物品签名。实际物品常是改名/改模型的普通物品。 */
    public static class WateringCan {
        public String itemId;
        /** 已观察到的显示名；装水前后名字变化时运行中会自动补充。 */
        public List<String> names = new ArrayList<>();

        public WateringCan() {}

        public WateringCan(String itemId, String name) {
            this.itemId = itemId;
            this.names.add(name);
        }

        @Override
        public String toString() {
            return (names.isEmpty() ? "?" : String.join("/", names)) + "(" + itemId + ")";
        }
    }

    /** 食物物品签名，支持插件改名食物。 */
    public static class FoodItem {
        public String itemId;
        public String itemName;

        public FoodItem() {}

        public FoodItem(String itemId, String itemName) {
            this.itemId = itemId;
            this.itemName = itemName;
        }

        @Override
        public String toString() {
            return itemName + "(" + itemId + ")";
        }
    }

    /**
     * 悬浮虚拟浇水器的交互目标。entityTarget=true 时优先找到并右键对应实体；
     * 找不到时仍会朝记录点发送“使用物品”，兼容服务器自行射线检测的虚拟物品。
     */
    public static class WatererTarget {
        public double x, y, z;
        public boolean entityTarget;
        public String entityType;
        public String entityCustomName;

        public WatererTarget() {}

        public WatererTarget(Vec3d pos, boolean entityTarget, String entityType, String entityCustomName) {
            this.x = pos.x;
            this.y = pos.y;
            this.z = pos.z;
            this.entityTarget = entityTarget;
            this.entityType = entityType;
            this.entityCustomName = entityCustomName;
        }

        public Vec3d targetPos() {
            return new Vec3d(x, y, z);
        }

        public BlockPos pathPos() {
            return BlockPos.ofFloored(x, y, z);
        }

        public double squaredDistanceTo(Vec3d pos) {
            return targetPos().squaredDistanceTo(pos);
        }

        @Override
        public String toString() {
            String pos = String.format("(%.1f,%.1f,%.1f)", x, y, z);
            return entityTarget ? pos + "[" + (entityType == null ? "实体" : entityType) + "]" : pos + "[射线]";
        }
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
        if (instance.wateringCan != null && instance.wateringCan.names == null) {
            instance.wateringCan.names = new ArrayList<>();
        }
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
        if (zones.isEmpty()) m.add("小区域(/farm zone add <作物>)");
        for (Zone z : zones) {
            Crop c = crops.get(z.crop);
            if (c == null || c.seedItemId == null || c.seedName == null) {
                m.add("作物「" + z.crop + "」种子定义(手持种子执行 /farm crop add " + z.crop + ")");
            }
        }
        if (!waterers.isEmpty()) {
            if (wateringCan == null || wateringCan.itemId == null) {
                m.add("洒水壶(手持空洒水壶执行 /farm bindcan)");
            }
            if (waterSource == null) {
                m.add("装水点(对准水面执行 /farm bindwater)");
            }
        }
        if (food != null && foodChest == null) {
            m.add("食物箱(对准箱子执行 /farm bindfoodchest)");
        }
        if (foodChest != null && (food == null || food.itemId == null)) {
            m.add("食物(手持食物执行 /farm bindfood)");
        }
        return m;
    }
}
