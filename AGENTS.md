# AutoMiner — Minecraft 1.21.8 Fabric 客户端自动化 mod（挖矿 + 种地）

## 项目概述

纯客户端 mod（不改服务器），两套互斥的机器人：

**AutoMiner（/miner，J 键）**——全自动挖矿循环：

```
菜单/指令传送去矿点 → 自动下挖到设定 y → 沿固定方向直线挖 1×2 隧道（W+左键+疾跑全程锁定）
  → 背包满 / 镐子耐久不足 / 没药没食物 → 传送回家（支持"站立等待30秒"式传送）
  → A* 寻路到存物箱存物（多箱轮换，记住满箱下次跳过）
  → 镐子箱换镐 → 药水箱拿急迫药水喝 → 食物箱拿食物吃（均按需）
  → 传送回矿点继续，无限循环
```

**AutoFarm（/farm，K 键）**——区域循环种地（插件服，种子是改名的 sugar 等）：

```
SCAN 调度（优先级：浇水到点 → 背包满存作物箱 → 收成熟作物 → 补种空地/去种子箱拿种子）
  → A* 走到目标 → 收获(左键挖或右键)+捡掉落 / 右键种植 / 箱子交互 / 水源装桶+右键浇水器
  → 回 SCAN 无限循环；成熟判定靠 /farm learn 学的方块状态签名（插件作物无原版 age 语义）
```

## 构建

- 环境：本机 JDK 25（`C:\Program Files\Java\jdk-25.0.2`）+ 本地 Gradle `./.tools/gradle-9.6.1/bin/gradle`（wrapper 也可用，超时已调到 120s）
- 命令：`./.tools/gradle-9.6.1/bin/gradle build --no-daemon -q`
- 产物：`build/libs/autominer-1.0.0.jar`（与 Fabric API 一起放 mods）
- 版本组合：MC 1.21.8 / yarn `1.21.8+build.1` / loader 0.19.3 / Fabric API `0.136.1+1.21.8` / Loom 1.17.13 / Gradle 9.6.1 / Java target 21

## 代码结构（src/main/java/com/autominer/）

| 文件 | 职责 |
|---|---|
| `AutoMinerClient` | 入口：命令注册（/miner ...）、J 键开关、tick 事件、断线复位 |
| `config/ModConfig` | JSON 配置（config/autominer.json）：各箱子坐标、targetY、朝向、传送操作、保留物品、耐久阈值 |
| `bot/Bot` | 主状态机（单例）：TP_MINE/WAIT_MINE/MINING/TP_HOME/WAIT_HOME/PATH_TO_CHEST/DEPOSIT/PATH_TO_PICK/SWAP_PICK/PATH_TO_POTION/TAKE_POTION/DRINK/PATH_TO_FOOD/TAKE_FOOD/EAT |
| `bot/MiningController` | 下挖到 y + 直线前挖 + 搭路/封液体 + 防卡转向 + 换镐到手 |
| `bot/Pathfinder` | 客户端 A*（平走/跳1格/落3格），`passable`/`solidGround`/`standable` |
| `bot/PathExecutor` | 沿路径点行走 + 卡住检测 |
| `bot/MenuNavigator` | 回放传送操作：发指令 → 等菜单 syncId 变化 → 依次点格子（多级菜单） |
| `bot/Recorder` | 录制传送操作：发指令后捕获玩家点击（HandledScreenMixin），位置突变自动保存 |
| `bot/DepositController` | 存物箱：QUICK_MOVE 存入非保留物品；可选从中取镐（未绑镐子箱时） |
| `bot/PickaxeSwapController` | 镐子箱：放旧镐（耐久<阈值）+ 取新镐 |
| `bot/ItemTakeController` | 通用取物（药水箱/食物箱共用），物品 id 首次取出时自动记录 |
| `bot/DrinkController` / `EatController` | 按住右键喝药/吃饭，含"消耗后效果延迟生效"宽限期 |
| `bot/InputController` | 按键模拟（KeyBinding.setPressed）：W/跳/疾跑/左键/右键，无 mixin |
| `bot/PickaxeUtil` | 镐识别（id 以 `_pickaxe` 结尾）、耐久计算 |
| `mixin/HandledScreenMixin` | 唯一 mixin：onMouseClick HEAD 注入，仅用于录制菜单点击 |

## 种地代码结构（src/main/java/com/autominer/farm/）

| 文件 | 职责 |
|---|---|
| `FarmConfig` | JSON 配置（config/autofarm.json）：大区域 Region、小区域 Zone(crop+box)、作物 Crop(seedItemId+seedName+seedChest+mature签名)、作物箱/浇水器/水源、浇水间隔/桶数/收获方式 |
| `FarmBot` | 状态机（单例）：SCAN/WALK/HARVEST/PICKUP/PLANT/TAKE_SEEDS/DEPOSIT/FILL_BUCKET/WATER；黑名单(失败地块1分钟)、种子箱空5分钟重试、浇水计时（启动先浇一轮） |
| `FarmScanner` | 扫区域内 farmland（缓存60s刷新）、按 zone 归类、成熟判定=方块id+全部状态属性精确匹配学习的签名（任一条命中） |
| `FarmItems` | 插件种子识别（物品id相同+显示名子串包含 seedName）、水桶识别、selectMatching 把物品换到主手（快捷栏直接选、主背包 SWAP，换手后等2tick再用） |
| `ChestTakeController` | 按 Predicate 从箱子取物（取种子用，QUICK_MOVE） |
| `FarmDepositController` | 存作物箱：QUICK_MOVE 除水桶/所有已定义种子/保留格外全部 |
| `BlockInfoDumper` | /farm info：方块id+状态属性+方块实体NBT+附近实体 → 聊天+config/autofarm-info.log（用户复制发给开发者分析插件作物阶段） |
| `FarmCommands` | /farm 指令注册（pos1/pos2 选区是内存态；作物名参数全用 greedyString 因中文无法过 string() 的 unquoted 校验） |

## 关键设计决策与注意点

### 1.21.8 API 陷阱（改代码前先看）
- `PlayerInventory` 选中槽用 `getSelectedSlot()/setSelectedSlot(int)`（1.21.5+ 字段已封装）
- `KeyBinding` 构造器第 4 参还是 String category（`KeyBinding.Category` 是 1.21.9 才有，别提前用）
- 挖方块：不手动调 `updateBlockBreakingProgress`，而是 `attackKey.setPressed(true)` + 准星对准目标，交给原版连续挖掘逻辑（效率最高、行为最像真人）
- 背包主区 = 索引 0..35（0-8 快捷栏，9-35 主背包）；**索引 9（按 E 背包最上排第一格）是保留格，任何存取/交换逻辑都必须跳过**
- 玩家背包 ScreenHandler 槽位映射：9..35 = 背包 9..35，36..44 = 快捷栏；SWAP 的 button 参数是快捷栏下标
- 容器判断：`handler.syncId != 0 && handler != player.playerScreenHandler`；区分箱子侧/玩家侧用 `slot.inventory instanceof PlayerInventory`

### 行为约定
- **两个 bot 互斥**：Bot.start 检查 FarmBot.isRunning，反之亦然；InputController 是共享静态状态，同时跑会打架
- **挖矿瞄准**：前方 4 格内找最近未挖穿方块瞄准（远距挖 → 掉落物落身前，疾跑顺路捡）；液体列 5 格预扫描，≤2 格才封堵
- **不停机原则**：悬崖/洞穴 → 自动搭路（BUILD_BLOCKS 白名单：圆石/深板岩圆石/石头等，精确匹配 id path，防止误放矿物）；水/岩浆 → 放方块封堵；药水耗尽 → 跳过急迫继续挖（potionSkip，回家时重试）；5 秒坐标不动 → 顺时针转向并保存
- **会停止的边界**：所有存物箱满、镐子全部耗尽（含镐子箱无镐）、食物箱空、走不到必需箱子、玩家死亡、掉到目标高度以下、搭路方块用完
- **传送等待**：回家最长 60s（兼容"站立30秒"），到达判定 = 维度匹配 + 靠近绑定箱 64 格 + 位置突变；去矿点靠位置突变判定（矿点坐标未知），45s 兜底
- **消耗品竞态**：喝药/吃饭后物品先消失、效果/饥饿包晚几 tick 到 —— 一定要留宽限期再判失败（已修过一次 bug）
- **保留物品**：keepIds 子串匹配 + 药水 id + 食物 id + （绑定镐子箱时）旧镐；满箱跳过记录 chestStartIdx 仅会话内有效

### 种地专有注意点
- **成熟判定是核心未知项**：插件作物的方块 id/状态未知，只能靠用户 /farm learn 现学或 /farm info 提供各阶段 dump 后在代码里完善；签名匹配是"全部属性精确相等"，同作物多种成熟外观要学多条
- **浇水成功判定** = 水桶数量在右键后 10 tick 内减少；插件若不消耗桶会重试 5 次后跳过该浇水器并提示
- **装水**用 `interactItem`（服务器按视线自己做流体射线），**浇水/种植**用 `interactBlock` 指定坐标
- 收获 break 模式：首 tick `attackBlock` + 后续 `updateBlockBreakingProgress`（兼容瞬破和有硬度的方块），不依赖准星命中（幼苗碰撞箱太小）
- 换手（主背包 SWAP 到快捷栏）后必须等 2 tick 再交互，等背包同步
- 背包快满但全是保留物品（种子/桶）时不去存箱，只提示一次——否则 SCAN↔DEPOSIT 死循环
- 种地寻路不开疾跑：疾跑跳跃落在 farmland 上会踩坏耕地

### 用户使用流程（README.md 有完整版）
1. `/miner bind`（可多个）、`/miner bindpick`、`/miner bindpotion`、`/miner bindfood`（后三个可选）
2. `/miner sety -59`
3. `/miner record mine <打开菜单的指令>` 手动点完菜单传送即自动保存；回家纯指令 `/miner tphome home`
4. `/miner start`（J 键切换）；朝向首次开挖自动记录，`/miner face` 重设

### 测试提醒
- 只做过编译验证，**没进过游戏实测**；改动后先在单人存档跑完整循环
- 用户环境：Windows（Git Bash），工作目录 E:\mc\mod
