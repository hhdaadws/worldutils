package com.autominer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 持久化配置。保存为 config/autominer.json
 */
public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ModConfig instance;

    // 绑定的箱子（支持多个，须在同一维度）
    public List<Pos> chests = new ArrayList<>();
    public String chestDim = null;

    // 挖矿目标 y 值（自动向下挖到该高度再向前挖）
    public Integer targetY = null;

    // 挖矿朝向（第一次开始挖矿时自动记录，可用 /miner face 重设）
    public String facing = null;

    // 传送操作：命令 + 依次点击的菜单格子（支持多级菜单；纯指令则 clicks 为空）
    public MenuSeq tpHome = null;
    public MenuSeq tpMine = null;

    // 急迫药水箱（可选）：急迫效果消失后回家取药喝
    public Pos potionChest = null;
    // 药水的物品 id（第一次从药水箱取出时自动记录）
    public String potionItemId = null;

    // 镐子箱（可选）：坏镐放进去、新镐从这里拿；不绑定则用普通存物箱
    public Pos pickChest = null;

    // 食物箱（可选）：食物吃完自动来取，保证饥饿值支持一直疾跑
    public Pos foodChest = null;
    // 食物的物品 id（第一次从食物箱取出时自动记录）
    public String foodItemId = null;

    // 保留物品（物品 id 包含以下任一子串则不存入箱子）
    public List<String> keepIds = new ArrayList<>(List.of("pickaxe"));

    // 镐子剩余耐久低于该值时回家换镐 / 存入箱子
    public int minPickaxeDurability = 20;

    // 剩余空格 <= 此值时回家存物（0 = 完全满了才回）
    public int returnWhenEmptySlots = 0;

    public static class Pos {
        public int x, y, z;

        public Pos() {}

        public Pos(BlockPos p) {
            this.x = p.getX();
            this.y = p.getY();
            this.z = p.getZ();
        }

        public BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }

        @Override
        public String toString() {
            return "(" + x + ", " + y + ", " + z + ")";
        }
    }

    /** 一次传送操作：输入命令 → 依次点击各级菜单中的格子。 */
    public static class MenuSeq {
        public String command;
        public List<Integer> clicks = new ArrayList<>();

        public MenuSeq() {}

        public MenuSeq(String command, List<Integer> clicks) {
            this.command = command;
            this.clicks = clicks;
        }

        @Override
        public String toString() {
            return "/" + command + (clicks == null || clicks.isEmpty()
                    ? "" : " → 点击格子 " + clicks);
        }
    }

    public Direction getFacing() {
        if (facing == null) return null;
        try {
            return Direction.valueOf(facing.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static ModConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("autominer.json");
    }

    public static void load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                String json = Files.readString(f, StandardCharsets.UTF_8);
                instance = GSON.fromJson(json, ModConfig.class);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (instance == null) {
            instance = new ModConfig();
        }
        if (instance.keepIds == null) {
            instance.keepIds = new ArrayList<>(List.of("pickaxe"));
        }
        if (instance.chests == null) {
            instance.chests = new ArrayList<>();
        }
    }

    public static void save() {
        try {
            Files.writeString(file(), GSON.toJson(get()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** 返回缺失的配置项名称列表；为空表示配置完整。 */
    public List<String> missing() {
        List<String> m = new ArrayList<>();
        if (chests.isEmpty() || chestDim == null) m.add("绑定箱子(/miner bind)");
        if (targetY == null) m.add("挖矿高度(/miner sety <y>)");
        if (tpHome == null || tpHome.command == null) m.add("回家操作(/miner tphome <指令> 或 /miner record home <指令>)");
        if (tpMine == null || tpMine.command == null) m.add("去矿点操作(/miner tpmine <指令> 或 /miner record mine <指令>)");
        return m;
    }
}
