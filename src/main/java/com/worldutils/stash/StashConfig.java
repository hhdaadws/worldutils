package com.worldutils.stash;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.worldutils.config.ModConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 定时存物配置。保存为 config/autostash.json
 */
public class StashConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static StashConfig instance;

    // 绑定的存物箱（支持多个轮换，须在同一维度）
    public List<ModConfig.Pos> chests = new ArrayList<>();
    public String chestDim = null;

    // 每隔多少分钟存一次
    public int intervalMinutes = 30;

    // 食物箱（可选）：饥饿值低于 14 自动吃，包里没食物自动来这里拿
    public ModConfig.Pos foodChest = null;
    // 食物的物品 id（第一次从食物箱取出时自动记录）
    public String foodItemId = null;

    public static StashConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("autostash.json");
    }

    public static void load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                String json = Files.readString(f, StandardCharsets.UTF_8);
                instance = GSON.fromJson(json, StashConfig.class);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (instance == null) {
            instance = new StashConfig();
        }
        if (instance.chests == null) {
            instance.chests = new ArrayList<>();
        }
        if (instance.intervalMinutes < 1) {
            instance.intervalMinutes = 30;
        }
    }

    public static void save() {
        try {
            Files.writeString(file(), GSON.toJson(get()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
