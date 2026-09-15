# WorldUtils — Minecraft 1.21.8 Fabric 客户端自动化 mod（挖矿 + 种地）

## 项目概述

纯客户端 mod（不改服务器），两套互斥的机器人。**对外伪装名 WorldUtils**（mod id `worldutils`、产物 `worldutils-1.0.0.jar`、聊天前缀 `[挖矿]`/`[种地]`、按键分类 "WorldUtils 实用工具"）——不要在任何用户可见字符串里出现 AutoMiner/AutoFarm 字样；Java 包名是 `com.worldutils`（入口 `WorldUtilsClient`），配置文件仍是 `config/autominer.json`/`autofarm.json`（本地文件，服务器看不到，改名会丢用户配置）。

**挖矿（/miner，J 键）**——全自动挖矿循环：

```
菜单/指令传送去矿点 → 自动下挖到设定 y → 沿固定方向直线挖 1×2 隧道（W+左键+疾跑全程锁定）
  → 背包满 / 镐子耐久不足 / 没药没食物 → 传送回家（支持"站立等待30秒"式传送）
  → A* 寻路到存物箱存物（多箱轮换，记住满箱下次跳过）
  → 镐子箱换镐 → 药水箱拿急迫药水喝 → 食物箱拿食物吃（均按需）
  → 传送回矿点继续，无限循环
```

**种地（/farm，K 键）**——区域循环种地（插件服，种子是改名的 sugar 等）：

```
SCAN 调度（维持饱食 → 浇水 → 存物 → 背包现有种子补种 → 批次种子预取+成熟率达门槛(可配,默认50%)全局收获 → 其他缺种补货）
  → A* 走到目标 → 收获(左键挖或右键)+捡掉落 / 右键种植 / 箱子交互 / 插件洒水壶装水+右键悬浮虚拟浇水器
  → 回 SCAN 无限循环；成熟判定靠 /farm learn 学的方块状态签名（插件作物无原版 age 语义）
```

## 构建

- 环境：本机 JDK 25（`C:\Program Files\Java\jdk-25.0.2`）+ 本地 Gradle `./.tools/gradle-9.6.1/bin/gradle`（wrapper 也可用，超时已调到 120s）
- 命令：`./.tools/gradle-9.6.1/bin/gradle build --no-daemon -q`
- 产物：`build/libs/worldutils-1.0.0.jar`（与 Fabric API 一起放 mods）
- 版本组合：MC 1.21.8 / yarn `1.21.8+build.1` / loader 0.19.3 / Fabric API `0.136.1+1.21.8` / Loom 1.17.13 / Gradle 9.6.1 / Java target 21

## 代码结构（src/main/java/com/worldutils/）

| 文件 | 职责 |
|---|---|
| `WorldUtilsClient` | 入口：命令注册（/miner ...）、J 键开关、tick 事件、断线复位 |
| `config/ModConfig` | JSON 配置（config/autominer.json）：各箱子坐标、targetY、朝向、传送操作、保留物品、耐久阈值 |
| `bot/Bot` | 主状态机（单例）：TP_MINE/WAIT_MINE/MINING/TP_HOME/WAIT_HOME/PATH_TO_CHEST/DEPOSIT/PATH_TO_PICK/SWAP_PICK/PATH_TO_POTION/TAKE_POTION/DRINK/PATH_TO_FOOD/TAKE_FOOD/EAT |
| `bot/MiningController` | 下挖到 y + 直线前挖 + 搭路/封液体 + 防卡转向 + 换镐到手 |
| `bot/Pathfinder` | 客户端 A*（平走/跳1格/落3格 + 同高度对角线，斜向两侧脚部/头部都可通行才走防切角）：起点高度归一化、少转弯代价、平面安全路径压缩；搜索半径(≥160)与节点预算(≤15万)随目标距离自适应，启发式×1.02 破对角线等代价平台——否则远目标被误判"走不过去" |
| `bot/PathExecutor` | 沿压缩路径按角差比例平滑转向（yaw≤12°/tick）、轻量拐点预瞄；行走俯仰角 `withPitch()` 可配（默认 0 平视，收菜移动 30°）——**pitch 按剩余路程渐变**：距终点 >8 格平视赶路、8→2 格线性压向 walkPitch、2 格内全值（≤8°/tick 平滑），不再全程斜视地面走长途；朝准后前进，直线疾跑、转弯/终点减速；"走过头"判定（路径点在身后且够近直接视为到达，防止回头踩点绕圈；**刚跳上台阶且已站上目标高度时放宽半径加大到 1.6**——疾跑+跳跃惯性常越点 1 格以上，0.9 会掉头回去踩点绕台阶转圈，高度判定留 0.2 余量容 farmland 矮顶面）；双卡住检测：无位移 60tick + 距路径点最近距离 100tick 无缩小（抓绕圈，绕圈时一直在动，仅位移检测抓不到）；`notePause()` 供上层刻意停步时豁免卡住检测 |
| `bot/MenuNavigator` | 回放传送操作：发指令 → 等菜单 syncId 变化 → 依次点格子（多级菜单） |
| `bot/Recorder` | 录制传送操作：发指令后捕获玩家点击（HandledScreenMixin），位置突变自动保存 |
| `bot/DepositController` | 存物箱：QUICK_MOVE 存入非保留物品；可选从中取镐（未绑镐子箱时） |
| `bot/PickaxeSwapController` | 镐子箱：放旧镐（耐久<阈值）+ 取新镐 |
| `bot/ItemTakeController` | 通用取物（药水箱/食物箱共用），物品 id 首次取出时自动记录 |
| `bot/DrinkController` / `EatController` | 按住右键喝药/吃饭，含"消耗后效果延迟生效"宽限期 |
| `bot/InputController` | 按键模拟（KeyBinding.setPressed）：W/跳/疾跑/左键/右键，无 mixin |
| `bot/PickaxeUtil` | 镐识别（id 以 `_pickaxe` 结尾）、耐久计算 |
| `mixin/HandledScreenMixin` | 唯一 mixin：onMouseClick HEAD 注入，仅用于录制菜单点击 |

## 授权机制（src/main/java/com/worldutils/license/）

| 文件 | 职责 |
|---|---|
| `TotpAuth` | RFC 6238 TOTP（Google Authenticator 兼容）：HMAC-SHA1/6位/30秒，Base32 密钥，±window 周期容差 |
| `LicenseManager` | `/miner license <码>`、`/farm license <码>` 激活；成功写 `config/worldutils-license.dat`（到期毫秒:HMAC-SHA256签名，防手改延期）；`isAuthorized()` 内存判到期可每 tick 调；密钥常量 `SECRET`（**上线前必须替换**，默认是公开示例值）；`activateWithExpiry(long)` 供账号登录按账号到期日直接授权 |
| `AccountAuth` | 账号密码授权（与 TOTP 并存）：`/miner login <账号> <密码>`、`/farm login ...`；后台线程 GET `http://104.62.94.44:10000/auth/<SHA256(账号)>.dat`（nginx 静态文件，内容 `salt:SHA256(salt:密码):到期毫秒`），本地比对哈希+判到期，通过则 activateWithExpiry 写同一份授权文件；密码不上网（URL 只含账号哈希）；`mc.execute()` 切回主线程发聊天反馈 |

- **账号服务器管理**：`tools/authserver/worldutils-auth.sh`（本机 Git Bash 跑，SSH 到 root@104.62.94.44）：`setup` 一键装 nginx+建目录+写 conf（端口 10000，try_files 精确匹配防目录列表）；`add <账号> <天数> [密码]`（缺省随机密码）/`renew`/`passwd`/`del`/`list`；账号索引存服务器 `/var/lib/worldutils-auth/index.tsv`（不对外）。改端口/IP 要同步改 `AccountAuth.BASE_URL`。续期后用户须重新 login 才能取到新到期时间

- **授权闸门**：Bot.start / FarmBot.start 入口检查 isAuthorized；两个 tick 循环每帧复检，到期中途自动 stop；`load()` 在 WorldUtilsClient 初始化时调用一次缓存到期时间
- **配置**：`SECRET`（Base32，只有作者知道，同步录入作者手机的验证器 App）、`WINDOW=2`（±150秒容作者读码→用户输入）、`GRANT_DAYS=1`（每次激活授予天数=激活后24小时；一天一授权）
- **安全边界（诚实记录）**：纯客户端方案，密钥在 jar 内可被反编译提取、校验可被移除；授权文件 HMAC 签名只能防普通用户改文件延期，防不住有技术者。要强防护需服务端下发/校验

## 种地代码结构（src/main/java/com/worldutils/farm/）

| 文件 | 职责 |
|---|---|
| `FarmConfig` | JSON 配置：区域/作物/箱子、插件洒水壶/虚拟浇水器/水源、持久化浇水时间、食物/食物箱、收获方式 |
| `FarmBot` | 状态机：SCAN/WALK/HARVEST/PICKUP_ZONE/PLANT/TAKE_SEEDS/TAKE_FOOD/EAT/DEPOSIT/FILL_CAN/WATER；逐 zone 收获/种植与区域掉落拾取、自动进食、持久浇水计时 |
| `FarmScanner` | 扫区域内 farmland（缓存60s刷新）、按 zone 归类、成熟判定=方块id+全部状态属性精确匹配学习的签名（任一条命中） |
| `FarmItems` | 插件种子/洒水壶/食物识别、selectMatching 把物品换到主手（快捷栏直接选、主背包 SWAP） |
| `FarmEatController` | 饥饿值低于14时吃到18；食物同步宽限；低于7无法疾跑时报告失败 |
| `FarmLookController` | 种植/收获/水源/虚拟浇水器/箱子交互的渐进 yaw+pitch 对准，避免瞬间锁头 |
| `ChestTakeController` | 按 Predicate 从箱子取物（取种子用，QUICK_MOVE） |
| `FarmDepositController` | 存作物箱：QUICK_MOVE 除插件洒水壶/所有已定义种子/保留格外全部 |
| `BlockInfoDumper` | /farm info：方块或准星虚拟实体详细信息 → 聊天+config/autofarm-info.log |
| `FarmCommands` | /farm 指令注册（pos1/pos2 选区是内存态；作物名参数全用 greedyString 因中文无法过 string() 的 unquoted 校验） |

另有独立网页工具 `tools/farmmap.html`（不依赖 MC）：浏览器打开后拖入/选择 autofarm.json，SVG 渲染 zone 色块（按作物着色、停用灰色）+ 浇水点十字标 + 区块网格 + 悬停世界坐标；Chrome/Edge 下用 File System Access API 记住文件句柄支持一键刷新。曾做过游戏内 Screen 版会闪退（整合包渲染管线冲突），已删除——别再走游戏内 GUI 路线。

## 定时存物（src/main/java/com/worldutils/stash/，/stash，前缀 [存物]）

独立于两个 bot 的第三个小状态机：WAITING（计时）→ PATH_TO_CHEST → DEPOSIT（多箱轮换）→ PATH_BACK（走回出发点）→ RESTORE_FACING（平滑转回出发朝向 yaw≤12°/pitch≤8° 每 tick，60t 兜底）→ WAITING 循环；每轮收尾（finishRun）统一切回 1 号快捷栏（索引 0）。绑定食物箱后附带自动进食（WAITING 里饥饿<14 优先于存物计时触发：包里有食物就地 EAT，没有则 PATH_TO_FOOD → TAKE_FOOD → EAT → PATH_BACK，同样恢复朝向）。配置 `config/autostash.json`（`StashConfig`：chests/chestDim/intervalMinutes 默认 30/foodChest/foodItemId）。

| 文件 | 职责 |
|---|---|
| `StashBot` | 状态机 + 计时；复用 Pathfinder/PathExecutor/InputController + 挖矿的 ItemTakeController/EatController（均已参数化：取物消息出口、食物 id 来源可注入）；出错 30 秒重试（softHalt 同约定，进食失败走独立的 nextFoodMs 冷却不影响存物计时），死亡自动重生续跑，授权到期停止 |
| `StashDepositController` | 打开箱子 QUICK_MOVE 背包索引 **10..35**（快捷栏 0..8 和保留格 9 永不动）；开箱前先切到快捷栏索引 8（第九格）防主手物品被右键使用（食物箱同规则）；满箱返回 CHEST_FULL 轮换下一箱 |
| `StashCommands` | /stash bind/unbind/chests/interval/bindfood/unbindfood/now/start/stop/status |

- **互斥规则（单向让位）**：挖矿/种地运行时 WAITING 到点只推迟不触发；存物途中对方启动 → `abortRun(true)` 立即让位且**不碰输入、不 restorePauseOnLostFocus**（对方 enter() 已清输入；此时恢复焦点原值会把还在跑的对方坑回失焦暂停 bug，恢复由对方 stop 负责）。Bot/FarmBot.start 无需感知 stash
- WAITING 阶段绝不 InputController.apply（否则会跟玩家手动操作打架），只有 isBusy 三个状态才 apply；触发条件含：玩家没开着容器、在存物箱维度、背包 10..35 有东西（没东西静默跳过整轮）
- 存完走回出发位置（returnPos，findNear reach 1.6）；回不去就地结束不影响下轮

## 关键设计决策与注意点

### 1.21.8 API 陷阱（改代码前先看）
- `PlayerInventory` 选中槽用 `getSelectedSlot()/setSelectedSlot(int)`（1.21.5+ 字段已封装）
- `KeyBinding` 构造器第 4 参还是 String category（`KeyBinding.Category` 是 1.21.9 才有，别提前用）
- 挖方块：不手动调 `updateBlockBreakingProgress`，而是 `attackKey.setPressed(true)` + 准星对准目标，交给原版连续挖掘逻辑（效率最高、行为最像真人）
- 背包主区 = 索引 0..35（0-8 快捷栏，9-35 主背包）；**索引 9（按 E 背包最上排第一格）是保留格，任何存取/交换逻辑都必须跳过**
- 玩家背包 ScreenHandler 槽位映射：9..35 = 背包 9..35，36..44 = 快捷栏；SWAP 的 button 参数是快捷栏下标
- 容器判断：`handler.syncId != 0 && handler != player.playerScreenHandler`；区分箱子侧/玩家侧用 `slot.inventory instanceof PlayerInventory`

### 行为约定
- **永不自动停止（用户强需求）**：两个 bot 只有按 J/K 键或 `/miner stop`、`/farm stop` 才真正 stop 进 IDLE。所有原来会 `stop("§c...")` 的错误分支改为 `softHalt(reason)`——打印一次原因、暂停 30 秒（`HALT_RETRY_MS`）后自动重建循环（miner 调 start()、farm 回 SCAN）；`pausedUntilMs` 在 tick 顶部拦截。**例外只有授权到期**（license 是刻意保留的停止，见授权机制）
- **死亡不停止**：`!isAlive()` 不再 stop，而是每秒 `requestRespawn()` + 关死亡屏，重生后继续（位置可能变，靠状态机自行重新寻路/传送恢复）
- **不因界面/失焦暂停**：删除了所有 `currentScreen != null` 的暂停分支——玩家开背包/聊天、鼠标移出游戏窗口时 bot 继续运行（KeyBinding.setPressed 每 tick 强制重置，界面打开也生效）。交互状态自身会关掉插件弹出的容器 GUI。**运行期间接管 `pauseOnLostFocus=false`（停止时恢复原值）**——否则失焦自动弹暂停菜单，currentScreen!=null 时 handleInputEvents 停转，按住右键吃东西/喝药的启动逻辑卡死（移动不受影响，症状就是"只有吃卡住"）
- **两个 bot 互斥**：Bot.start 检查 FarmBot.isRunning，反之亦然；InputController 是共享静态状态，同时跑会打架
- **挖矿瞄准**：前方 4 格内找最近未挖穿方块瞄准（远距挖 → 掉落物落身前，疾跑顺路捡）；液体列 5 格预扫描，≤2 格才封堵
- **不停机原则**：悬崖/洞穴 → 自动搭路（BUILD_BLOCKS 白名单：圆石/深板岩圆石/石头等，精确匹配 id path，防止误放矿物）；水/岩浆 → 放方块封堵；药水耗尽 → 跳过急迫继续挖（potionSkip，回家时重试）；5 秒坐标不动 → 顺时针转向并保存
- **会停止的边界**：所有存物箱满、镐子全部耗尽（含镐子箱无镐）、食物箱空、走不到必需箱子、玩家死亡、掉到目标高度以下、搭路方块用完
- **传送等待**：回家最长 60s（兼容"站立30秒"），到达判定 = 维度匹配 + 靠近绑定箱 64 格 + 位置突变；去矿点靠位置突变判定（矿点坐标未知），45s 兜底
- **消耗品竞态**：喝药/吃饭后物品先消失、效果/饥饿包晚几 tick 到 —— 一定要留宽限期再判失败（已修过一次 bug）
- **保留物品**：keepIds 子串匹配 + 药水 id + 食物 id + （绑定镐子箱时）旧镐；满箱跳过记录 chestStartIdx 仅会话内有效

### 种地专有注意点
- **成熟判定是核心未知项**：插件作物的方块 id/状态未知，只能靠用户 /farm learn 现学或 /farm info 提供各阶段 dump 后在代码里完善；签名匹配是"全部属性精确相等"，同作物多种成熟外观要学多条
- **插件洒水壶**：手持执行 `/farm bindcan` 记录 id+显示名；装水前后改名会自动补充已知名字；存箱时保留
- **悬浮虚拟浇水器**：`/farm bindwaterer` 优先记录准星命中的实体类型/位置；运行时优先 `interactEntity`，实体不可见时朝记录点 `interactItem` 让服务器按视线检测
- **水下 shift 右键**：阶段二物理按住 shift（与手动一致，客户端真实潜行、原版自行同步；水中 shift=下沉正好钉底），对准 -90° 后保持按住 ≥2 tick 再点击；每次点击前必须 `isOnGround`（漂起来按下潜沉回）；WATER 及走向 WATER 的 WALK 从 shouldCrouch 排除；点击时输出诊断消息（命中方块/实体/落空 + 潜行状态）。洒水器本体是 `minecraft:interaction` 实体；**实体交互必须先 interactEntityAtLocation(INTERACT_AT) 再按需补 interactEntity(INTERACT)**——插件监听 PlayerInteractAtEntityEvent，只发 INTERACT 无效；纯发包模拟潜行插件不认，对空 interactItem 收不到——都别再试
- **右键必须走原版流程 vanillaRightClick**（重要教训）：按 mc.crosshairTarget 实际命中发包——方块→interactBlock、实体→interactEntity、落空才 interactItem。裸发 interactItem 是"对空使用"（RIGHT_CLICK_AIR），插件的取水/加水只监听 RIGHT_CLICK_BLOCK/实体交互，收不到=右键无效（手动能成功正是因为手动会命中方块）。浇水到点判定 reach=1.3（只覆盖绑定格本身，必须站上去才开始序列）
- **浇水（新版序列）**：`/farm bindwaterer` 绑一个站立坐标（含流体射线，可绑水下）；到点后固定序列 = 拿洒水壶低头 80° 右键 ×3（间隔 8 tick）→ 抬头到正上方 -90°（洒水器在站立点头顶）shift 稳 2 tick 后右键 ×1 → 下一个点；无装水步骤（FILL_CAN 状态已删，bindwater/canuses 命令保留但不再参与流程）；间隔默认 180 分钟，load() 把旧默认 20 自动迁移成 180；30 秒序列超时跳过该点；`lastWaterTimeMs` 仍只在整轮至少一个点成功后持久化，失败会话内 5 分钟重试；`/farm water` 手动触发一轮（waterNowRequested 无视间隔/冷却，巡回开始时消费）
- **水中寻路**：Pathfinder 支持 ≤3 格深的水（waterFloor=脚部水+水下实心地面+pos.up(3) 非水）：水底行走 2.5、**下潜进相邻更深水柱**（沿 swimPassable 列扫 ≤3 格，洒水器等无碰撞装饰方块也放行）、水中上台阶 3.0、岸上跳水入底 2.0+0.4/格、从水柱爬出到岸上**或更浅水层** 3.0+rise（竖井底→水面层）；水中节点不做普通跳跃/下落判定；normalizeStart 接受水底起点；更深需要真游泳（未实现）
- **装水机制已删除**：/farm bindwater 命令、FILL_CAN 状态、waterSource 检查全部移除（config 字段保留兼容旧文件）；浇水只需洒水壶在包里
- **食物**：`/farm bindfood` 记录 id+显示名，`/farm bindfoodchest` 绑定箱；低于14优先 EAT/TAKE_FOOD，低于7且拿不到食物时 softHalt；**吃饭朝脚下（80° 俯视，≤12°/tick 平滑压头）**，pitch 没压到位或准星指着容器/实体时绝不按右键（视角下压路径会扫过面前的箱子把它点开——拿完食物人就站在箱子跟前，必中），不安全时右转 20°/tick 避开；误开的容器界面立刻关掉
- 收获默认 use 模式：有对应种子时换到主手后 `interactBlock`，让插件一次完成收获+复种；种子不足或右键连续失败时当前地块立即退回 break，不得阻塞整个 zone
- break 路径首 tick `attackBlock` + 后续 `updateBlockBreakingProgress`；产生的空地之后由 PLANT/TAKE_SEEDS 正常补种
- zone 收获门槛按 `MATURE / (MATURE + GROWING) >= harvestMaturePercent%`（`/farm ratio <1-100>` 可配，默认 50，load() 出界值收回 50；整数运算 `mature*100 >= total*percent` 避免浮点），EMPTY 不计分母；所有达标 zone 加入 `harvestBatchZones`，但严格逐 zone 处理
- zone 遍历策略 `harvestStrategy`（/farm strategy，默认 route）：**route** = 进区时对当前实际目标（MATURE+EMPTY）做贪心最近邻+2-opt 规划一条最短路线，按耕地坐标存序号——收获和补种是同一批格子共用一条路线，路线外新目标排在路线之后；**random** = 每次进区随机选 SNAKE/DIAGONAL/SPIRAL/SPLIT（最短边<3 退回蛇形），按最近角定起始方向。两种策略处理期间都不随玩家位置重排；遍历无目标后刷新 farmland 缓存、清除本区临时黑名单，并用旧的最近目标顺序兜底一次
- **速度模式**（`FarmConfig.speedMode`：normal/medium/fast，/farm speed 设置，/farm fast 保留为 normal↔fast 互切；旧 fastMode 布尔 load() 自动迁移并保持同步）：medium/fast 共享 `isSpeedy()` 行为——zone 内收菜不停步（WALK(afterWalk=HARVEST) 途中 `tickFastHarvest` 每 tick 对眼睛 3.0 格内的本区成熟作物出手，use=有种子才换手右键 synthetic hit / break=attackBlock）、shouldCrouch 恒 false、beginPlot 贴身 reach 1.65 放宽 2.5、`fastRetarget` 不 enter() 换目标脚步不断、`fastFiredAt` 15 tick 防重复点同一株。**区别在指针**：fast 不做视角对准直接出手（最快最机械）、HARVEST 兜底不等 smoothFace；medium 出手前用 `smoothFace(quick=true)`（yaw≤40°/pitch≤24° 每 tick，约普通两倍速）把指针对准到目标（<3°）才出手，`mediumAimCrop` 粘住当前目标防止指针在多株间来回甩，HARVEST/PLANT 状态同样用 quick 对准；**medium 对准/出手期间必须独占指针并刻意停步**（tickFastHarvest 返回 true 时该 tick 跳过 pathExec.tick、清 forward、`pathExec.notePause()` 豁免卡住检测）——若与行走转向同 tick 各转各的（行走 ≤12°/tick 拉回路径、对准按剩余角差 ~50% 拉向作物），稳态角差收敛在 ~12° 永远到不了 3° 出手阈值，人原地僵持被误报"寻路卡住/无进展"、3 次重试烧光后地块还被冤枉拉黑。fast 只加速收获；medium 的 quick 对准同时作用于 PLANT；PICKUP/浇水流程两者都不变
- 锁定收获 zone 后先收完该区全部成熟作物，再统一补种空地；左键收获产生的 EMPTY 不得中途打断蛇形收割；完成后进入 PICKUP_ZONE
- 当前 zone 开收前按 cropName 去种子箱预取；单个箱失败后该作物进入 noSeedsUntilMs 并用 break 回退，不能阻塞本区收获和拾取
- PICKUP_ZONE 扫描框按 zone 六方向各外扩一格；首次报告本区检测数量；**掉落物寻路目标必须归一化到"可站立脚部格"（`ofFloored(x, y+0.5, z)`）**——物品躺在 farmland 顶面时 `getBlockPos()` 落在耕地方块内部，直接当 A* 目标会因 reach 永远不满足而"原地刷屏不去捡"；2 格内锁定一次 approachYaw 直走（时长按距离 14~40tick），寻路失败且物品在 5 格内改直线接近不计失败；直走/停步等确认期间准星跟在物品本体上（物品在锁定方向前方 <20° 时 yaw 跟踪物品、否则回退锁定方向防回头绕圈，pitch 用 `naturalPickupPitch` **按水平距离渐进低头**：≥5 格上限 15° 近乎平视、5→1.2 格线性放开到 72° 封顶——真人不会隔几格就死盯地面也不会 85° 盯脚尖，转动比例 0.35 更柔和；停步等待同样用 naturalPickupPitch + smoothYaw 自然瞥向脚边，不再 smoothFace 精确锁死）
- 单实体3次失败只加入当前轮临时忽略；结束前用不排除忽略项的原始实体列表复查，仍存活就清空忽略表再捡；连续4次间隔空扫描才确认本区完成；**复查轮数上限 5 轮**，仍捡不到（掉水里/卡方块）放弃本区拾取防止无限刷屏
- 换手（主背包 SWAP 到快捷栏）后必须等 2 tick 再交互，等背包同步；PLANT/HARVEST/PICKUP_ZONE 在玩家自己开界面（聊天/背包，无容器 handler）时暂停——否则 selectMatching 的 clickSlot(syncId=0) 会发到错误 handler 造成背包错乱；意外弹出的容器 GUI 主动关闭
- PLANT 有 200 tick、HARVEST 有 300 tick 总超时兜底（换手 desync/GUI/丢包都不允许永久卡死）；种植 hit 点用 farmland 真实顶面 y+0.9375（y+1.0 在方块外，严格反作弊拒包）；种植重试间隔 12 tick
- 去种子箱前必须 seedDefined（seedItemId+seedName 均已定义），否则 matcher 恒 false 白跑一趟还触发 5 分钟冷却；取种成功清除该作物 noSeedsUntilMs
- 一个 zone 连续 5 块地寻路失败 → 整区熔断 1 分钟并提示检查入口（门/梯子/矮顶；A* 只会平走/跳1格/降3格），防止逐块拉黑刷上百条消息、每块白烧 2 万节点搜索（实测蘑菇区曾刷 123 条）
- WALK 只有"真卡住/绕圈"才计入 3 次重试；走满 60 秒只做例行重规划不计次（远目标正常赶路不能算失败），总时长 4 分钟兜底
- 背包快满但全是保留物品（种子/洒水壶/食物）时不去存箱，只提示一次——否则 SCAN↔DEPOSIT 死循环
- **作物专箱分拣**：`Crop.depositChests`（/farm bindcrop <作物> 绑定，可多个）；存物优先遍历各作物专箱——背包内"非保留且显示名包含作物名"的产出分拣过去（FarmDepositController 带 Predicate filter 只 QUICK_MOVE 匹配项）；没绑专箱/专箱全满的产出走全局 cropChests 兜底（isGlobalDepositable=没有任何有空位的专箱能收它）。满箱/临时跳过改按坐标记（fullDepositChests/skipDepositChestsThisTrip，Set<Long> asLong），全满 softHalt 不停机、30 秒重试时清满箱记录；**DONE 后主动 startDepositTask 续跑直到全部存完**——不能依赖 SCAN 的背包阈值续跑（存完一种后空位回升就不满足阈值，其余作物会一直留包里）
- 种地/箱子寻路按剩余角差比例平滑 yaw，并轻量预瞄拐点；基本朝准后才前进，仅稳定直线段疾跑，降低绕圈、过冲和踩坏 farmland 的风险
- **未加载区块 ≠ 没有作物**：服务器只同步视距内区块，远处方块客户端读成空气。FarmScanner 对未加载区块的地块标 UNKNOWN（不计入成熟率分母、不当空地），耕地缓存刷新时未加载区块中的旧记录原样保留；SCAN 没有其他任务且存在 UNKNOWN（或某 zone 从没扫到过耕地且有未加载区块）→ 侦察：走过去让区块加载（>40 格分段跳点逼近，未加载区块内无法寻路），失败冷却 30 秒
- **开关**：`Crop.enabled`（/farm toggle <作物>，收获+种植一个总开关）——停用作物在 SCAN 的 plots 过滤和 planRoute 目标里直接剔除（不收/不种/不取种，GROWING 照常不动）；`wateringEnabled`（/farm toggle water）只挡定时触发，`/farm water` 手动强制仍执行（waterNowRequested 检查在开关之前）。旧配置缺字段时 Gson 保留字段初始值 true 不会误关
- **蹲下规则按"位置"而非状态**（shouldCrouch）：正在处理某 zone 且玩家身处该 zone 范围内（水平外扩1格、y±2）→ 持续蹲住（含 SCAN/WALK 间隙，杜绝一蹲一站闪烁）；站立例外（优先于位置判据，哪怕人还在 zone 里）：PICKUP_ZONE 捡掉落物、TAKE_SEEDS 拿种子、DEPOSIT 存作物、EAT/TAKE_FOOD 吃饭拿食物及各自的 WALK 路上；人在 zone 外（跨 zone 移动、初次走向 zone）也站立。tick 末尾 apply 前统一强制覆盖；WATER 状态自身的蹲下逻辑独立不受影响
- 农场方块、洒水器和箱子交互必须先由 FarmLookController 平滑对准到误差阈值内再点击，不能直接 setYaw/setPitch 瞬间锁头；对准速度 yaw≤20°/tick、pitch≤12°/tick（比例 0.5/0.45，阈值 3°）；目标近乎正上/正下方（horiz≤0.5）时 yaw 不动只调 pitch（否则 atan2 抖动导致一直偏头对不准）
- **地块交互贴身优先**：beginPlot 先用 reach=1.65 寻路（数学上只覆盖目标格本身/同排四邻格，斜邻 1.73 被排除）→ 站上垄沟沿收割轨迹推进、视线朝前下方；被围死（篱笆/固体成熟作物）才退回 reach=3.0 远距交互。3.0 直连会停在侧面 2~3 格外"平移+扭头"，一眼人机
- 提速约定：PLANT/HARVEST 的对准与换手并行（进状态第一 tick 就开始转头）；break 收获开挖后不因微小视角偏差中断；walkTo 起点已在交互距离内（单点路径）时跳过 WALK 直接进动作状态
- farmland 顶面不足一格，不能用 `targetY > player.getBlockPos().getY()` 判断跳跃；必须比较路径脚部 Y 与 `player.getY()` 的实际高度差
- 上升路径进入 2.2 格范围后持续按住跳跃直到越上目标高度，不依赖瞬时 `isOnGround`；`horizontalCollision` 作为漏判保险；起跳时显式 `setSprinting(false)`

### 用户使用流程（README.md 有完整版）
1. `/miner bind`（可多个）、`/miner bindpick`、`/miner bindpotion`、`/miner bindfood`（后三个可选）
2. `/miner sety -59`
3. `/miner record mine <打开菜单的指令>` 手动点完菜单传送即自动保存；回家纯指令 `/miner tphome home`
4. `/miner start`（J 键切换）；朝向首次开挖自动记录，`/miner face` 重设

### 测试提醒
- 只做过编译验证，**没进过游戏实测**；改动后先在单人存档跑完整循环
- 用户环境：Windows（Git Bash），工作目录 E:\mc\mod
