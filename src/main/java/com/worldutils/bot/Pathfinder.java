package com.worldutils.bot;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * 简单的客户端 A* 寻路：支持平走、跳一格、下落最多 3 格。
 * 用于从传送落点走到绑定箱子旁边。
 */
public final class Pathfinder {
    private static final Direction[] HORIZONTALS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    /** 对角线邻居（成对：两条正交方向的组合）。 */
    private static final Direction[] DIAGONALS = {
            Direction.NORTH, Direction.EAST,
            Direction.NORTH, Direction.WEST,
            Direction.SOUTH, Direction.EAST,
            Direction.SOUTH, Direction.WEST,
    };

    private Pathfinder() {}

    private static class Node implements Comparable<Node> {
        final BlockPos pos;
        final Node parent;
        final double g;
        final double f;

        Node(BlockPos pos, Node parent, double g, double h) {
            this.pos = pos;
            this.parent = parent;
            this.g = g;
            this.f = g + h;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(this.f, o.f);
        }
    }

    /** 方块可通行（无碰撞箱且无流体）。 */
    public static boolean passable(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) return false;
        return state.getCollisionShape(world, pos).isEmpty();
    }

    /** 方块可作为地面（有碰撞箱且无流体）。 */
    public static boolean solidGround(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) return false;
        return !state.getCollisionShape(world, pos).isEmpty();
    }

    /** 玩家可以站立于 pos（脚下实心，脚部与头部可通行）。 */
    public static boolean standable(World world, BlockPos pos) {
        return passable(world, pos) && passable(world, pos.up()) && solidGround(world, pos.down());
    }

    /** 方块是水（无碰撞箱的流体，可穿过）。 */
    public static boolean isWater(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.getFluidState().isEmpty() && state.getCollisionShape(world, pos).isEmpty();
    }

    /**
     * 水底可站：脚部是水、水下有实心地面，水深 ≤3 格（头可以完全没入水中，沉底行走）。
     * 更深需要真正游泳，不支持。
     */
    public static boolean waterFloor(World world, BlockPos pos) {
        if (!isWater(world, pos)) return false;                     // 脚部必须是水
        BlockState ground = world.getBlockState(pos.down());
        if (!ground.getFluidState().isEmpty()) return false;        // 再往下还是水 = 不是水底
        if (ground.getCollisionShape(world, pos.down()).isEmpty()) return false;
        // 深度上限 3：pos 上方最多还有 2 格水，再上必须不是水（有水面可换气/爬出）
        return !isWater(world, pos.up(3));
    }

    /** 水中可上浮穿过：水或空气。 */
    private static boolean swimPassable(World world, BlockPos pos) {
        return passable(world, pos) || isWater(world, pos);
    }

    /**
     * A* 搜索。
     *
     * @param goalTest 目标判定
     * @param target   启发式的目标点
     * @return 从起点到目标的脚部坐标序列；找不到返回 null
     */
    public static List<BlockPos> find(World world, BlockPos start, Predicate<BlockPos> goalTest,
                                      BlockPos target, int maxNodes) {
        // 玩家站在 farmland/path 等不足一格高的方块上时，getBlockPos() 可能落在地面方块内。
        // 先归一化为真正的“脚部空气格”，否则第一步会被误判为跳上一格。
        start = normalizeStart(world, start);

        // 搜索范围与节点预算随目标距离自适应：固定 160 格/2 万节点会把
        // “远但完全能走到”的目标直接误判成走不过去。
        int targetDist = start.getManhattanDistance(target);
        int range = Math.max(160, targetDist * 2 + 32);
        int budget = Math.min(150_000, Math.max(maxNodes, targetDist * 300));

        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<Long, Double> best = new HashMap<>();

        Node startNode = new Node(start, null, 0, heuristic(start, target));
        open.add(startNode);
        best.put(start.asLong(), 0.0);

        int visited = 0;
        while (!open.isEmpty() && visited < budget) {
            Node cur = open.poll();
            visited++;

            if (goalTest.test(cur.pos)) {
                return reconstruct(world, cur);
            }

            // 限制搜索范围，防止爆炸式扩张
            if (cur.pos.getManhattanDistance(start) > range) continue;

            for (Direction dir : HORIZONTALS) {
                BlockPos side = cur.pos.offset(dir);

                // 1. 平走
                if (standable(world, side)) {
                    tryAdd(world, open, best, cur, side, 1.0, target);
                    continue;
                }

                // 1b. 水底行走 / 下潜进相邻更深的水柱（≤3 格深，头可没入水中）
                {
                    BlockPos b = side;
                    boolean added = false;
                    for (int k = 0; k <= 3; k++) {
                        if (waterFloor(world, b)) {
                            tryAdd(world, open, best, cur, b, 2.5 + k * 0.4, target);
                            added = true;
                            break;
                        }
                        if (!swimPassable(world, b)) break; // 一路必须是水/可穿过（洒水器等无碰撞装饰方块也放行）
                        b = b.down();
                    }
                    if (added) continue;
                }

                // 1c. 水中上一格台阶（按住跳跃游上去）
                if (isWater(world, cur.pos) && waterFloor(world, side.up())
                        && swimPassable(world, cur.pos.up(2))) {
                    tryAdd(world, open, best, cur, side.up(), 3.0, target);
                    continue;
                }

                // 1d. 从水里向上爬出：沿本格水柱游到上方，跨到高 1~3 格的岸上或浅水层
                if (isWater(world, cur.pos)) {
                    boolean columnOk = true;
                    for (int rise = 1; rise <= 3; rise++) {
                        if (!swimPassable(world, cur.pos.up(rise))) {
                            columnOk = false;
                        }
                        if (!columnOk) break;
                        BlockPos exit = side.up(rise);
                        // 出口可以是岸上，也可以是更浅的水层（比如从竖井底爬回水面层）
                        if (standable(world, exit) || waterFloor(world, exit)) {
                            tryAdd(world, open, best, cur, exit, 3.0 + rise, target);
                            break;
                        }
                    }
                    // 水中节点不做普通跳跃/下落判定
                    continue;
                }

                // 2. 跳上一格（需要当前头顶再往上一格可通行）
                BlockPos up = side.up();
                if (standable(world, up) && passable(world, cur.pos.up(2)) && passable(world, side.up(2))) {
                    tryAdd(world, open, best, cur, up, 1.8, target);
                    continue;
                }

                // 3. 走出去并下落 1~3 格（落点可以是水底：跳进水里沉底，无摔落伤害）
                if (passable(world, side) && passable(world, side.up())) {
                    BlockPos below = side.down();
                    for (int fall = 1; fall <= 3; fall++) {
                        if (standable(world, below)) {
                            tryAdd(world, open, best, cur, below, 1.0 + fall * 0.6, target);
                            break;
                        }
                        if (waterFloor(world, below)) {
                            tryAdd(world, open, best, cur, below, 2.0 + fall * 0.4, target);
                            break;
                        }
                        // 空气或水都允许继续下探；实心障碍则停
                        if (!passable(world, below) && !isWater(world, below)) break;
                        below = below.down();
                    }
                }
            }

            // 4. 平面对角线（斜向直线走）：两侧相邻格的脚部/头部都必须可通行，
            //    防止切墙角卡住；只在同高度走，斜向不跳台阶/下落。
            for (int i = 0; i < DIAGONALS.length; i += 2) {
                Direction d1 = DIAGONALS[i];
                Direction d2 = DIAGONALS[i + 1];
                BlockPos diag = cur.pos.offset(d1).offset(d2);
                if (!standable(world, diag)) continue;
                BlockPos s1 = cur.pos.offset(d1);
                BlockPos s2 = cur.pos.offset(d2);
                if (passable(world, s1) && passable(world, s1.up())
                        && passable(world, s2) && passable(world, s2.up())) {
                    tryAdd(world, open, best, cur, diag, 1.415, target);
                }
            }
        }
        return null;
    }

    private static void tryAdd(World world, PriorityQueue<Node> open, Map<Long, Double> best,
                               Node parent, BlockPos pos, double moveCost, BlockPos target) {
        double turnPenalty = 0.0;
        if (parent.parent != null) {
            int previousDx = Integer.signum(parent.pos.getX() - parent.parent.pos.getX());
            int previousDz = Integer.signum(parent.pos.getZ() - parent.parent.pos.getZ());
            int nextDx = Integer.signum(pos.getX() - parent.pos.getX());
            int nextDz = Integer.signum(pos.getZ() - parent.pos.getZ());
            if (previousDx != nextDx || previousDz != nextDz) {
                turnPenalty = 0.12; // 同等距离优先少转弯，减少逐格左右摆头
            }
        }
        double g = parent.g + moveCost + turnPenalty;
        Long key = pos.asLong();
        Double old = best.get(key);
        if (old != null && old <= g) return;
        best.put(key, g);
        open.add(new Node(pos, parent, g, heuristic(pos, target)));
    }

    private static double heuristic(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        // 轻微放大启发式（贪心倾向）：打破网格 A* 对角线的等代价平台，
        // 展开节点数从 O(距离²) 降到接近直线量级，长途寻路不再烧完预算。
        return Math.sqrt(dx * dx + dy * dy + dz * dz) * 1.02;
    }

    private static BlockPos normalizeStart(World world, BlockPos start) {
        if (standable(world, start) || waterFloor(world, start)) return start;
        if (standable(world, start.up())) return start.up();
        if (standable(world, start.down()) || waterFloor(world, start.down())) return start.down();
        return start;
    }

    private static List<BlockPos> reconstruct(World world, Node end) {
        List<BlockPos> path = new ArrayList<>();
        Node n = end;
        while (n != null) {
            path.add(n.pos);
            n = n.parent;
        }
        java.util.Collections.reverse(path);
        return smoothFlatSegments(world, path);
    }

    /**
     * 把平面上逐格的曼哈顿折线路径压缩成可直走的长线段。
     * 会检查玩家 0.6 格宽的四角、脚部/头部空间和连续地面，不跨高度、不切墙角或悬空。
     */
    private static List<BlockPos> smoothFlatSegments(World world, List<BlockPos> raw) {
        if (raw.size() <= 2) return raw;
        List<BlockPos> result = new ArrayList<>();
        int anchor = 0;
        result.add(raw.get(anchor));

        while (anchor < raw.size() - 1) {
            int farthest = anchor + 1;
            int y = raw.get(anchor).getY();
            for (int i = anchor + 2; i < raw.size(); i++) {
                if (raw.get(i).getY() != y) break; // 上下台阶必须保留原始节点
                if (!directlyWalkable(world, raw.get(anchor), raw.get(i))) break;
                farthest = i;
            }
            result.add(raw.get(farthest));
            anchor = farthest;
        }
        return result;
    }

    private static boolean directlyWalkable(World world, BlockPos from, BlockPos to) {
        double x1 = from.getX() + 0.5;
        double z1 = from.getZ() + 0.5;
        double x2 = to.getX() + 0.5;
        double z2 = to.getZ() + 0.5;
        double distance = Math.hypot(x2 - x1, z2 - z1);
        int samples = Math.max(1, (int) Math.ceil(distance * 4.0));
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            double x = x1 + (x2 - x1) * t;
            double z = z1 + (z2 - z1) * t;
            if (!footprintStandable(world, x, from.getY(), z)) return false;
        }
        return true;
    }

    private static boolean footprintStandable(World world, double x, int y, double z) {
        // 玩家宽 0.6 格，稍留余量，避免压线时碰墙。
        double[] offsets = {-0.31, 0.31};
        for (double ox : offsets) {
            for (double oz : offsets) {
                BlockPos foot = BlockPos.ofFloored(x + ox, y, z + oz);
                if (!passable(world, foot) || !passable(world, foot.up())
                        || !solidGround(world, foot.down())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 寻找目标方块旁可站立、且眼睛在 reach 距离内的路径（种地/浇水等通用）。 */
    public static List<BlockPos> findNear(World world, BlockPos start, BlockPos target,
                                          double reach, int maxNodes) {
        Predicate<BlockPos> goal = pos -> {
            double dx = pos.getX() + 0.5 - (target.getX() + 0.5);
            double dy = pos.getY() + 1.5 - (target.getY() + 0.5);
            double dz = pos.getZ() + 0.5 - (target.getZ() + 0.5);
            return dx * dx + dy * dy + dz * dz <= reach * reach;
        };
        if (goal.test(start)) {
            List<BlockPos> p = new ArrayList<>();
            p.add(start);
            return p;
        }
        return find(world, start, goal, target, maxNodes);
    }

    /** 寻找箱子旁可站立、且在交互距离内的路径。 */
    public static List<BlockPos> findToChest(World world, BlockPos start, BlockPos chest, int maxNodes) {
        Predicate<BlockPos> goal = pos -> {
            double dx = pos.getX() + 0.5 - (chest.getX() + 0.5);
            double dy = pos.getY() + 1.5 - (chest.getY() + 0.5); // 眼睛高度到箱子中心
            double dz = pos.getZ() + 0.5 - (chest.getZ() + 0.5);
            return dx * dx + dy * dy + dz * dz <= 3.2 * 3.2;
        };
        if (goal.test(start)) {
            List<BlockPos> p = new ArrayList<>();
            p.add(start);
            return p;
        }
        return find(world, start, goal, chest, maxNodes);
    }
}
