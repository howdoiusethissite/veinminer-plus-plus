package com.kestalkayden.veinminerplusplus.core;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Loader-agnostic vein-mining core, shared by both loaders.
 *
 *  <p>A loader block-break hook calls {@link #onBlockBroken} on the server. The player's selected
 *  {@link MineShape} decides the gather (connectivity flood for Vein/Spread, oriented box for
 *  3x3x3). When a tree is felled, its isolated leaf canopy is gathered too. Everything is queued
 *  as {@link Target}s and drained a few per server tick by {@link #tick} (the lenient auto-stagger):
 *  logs/ores/etc. are mined with the held tool (drops, XP, durability) via
 *  {@link ServerPlayerGameMode#destroyBlock}; leaves are removed with decay-equivalent drops and no
 *  durability via {@link net.minecraft.world.level.Level#destroyBlock(BlockPos, boolean)}. A
 *  {@link #breaking} flag stops the hook re-triggering on our own breaks. */
public final class VeinMiner {

    /** A queued block to break. {@code leaf} chooses the break path (free decay-drop vs tool mine). */
    private record Target(BlockPos pos, boolean leaf) {}

    private static final Map<UUID, Deque<Target>> JOBS = new HashMap<>();

    /** Per-player fractional durability debt (a remainder &lt; 1.0 carried between blocks). The
     *  durability multiplier costs a fraction of a point per block (e.g. 0.40); accumulating that
     *  here and spending whole points as the debt crosses 1.0 keeps the percentage honest instead of
     *  rounding every block to 0 or a full point. */
    private static final Map<UUID, Double> DURABILITY_DEBT = new HashMap<>();

    /** Set while we break queued blocks so the loader's break hook ignores those breaks. */
    private static boolean breaking = false;

    private VeinMiner() {}

    public static boolean isBreaking() {
        return breaking;
    }

    /** Entry from the loader block-break hook. {@code originState} is the block the player broke;
     *  vanilla handles the origin itself, so it is never queued — except that when voiding is on and
     *  the origin is a void block, the drops vanilla just spawned for it are cleared so voiding stays
     *  consistent (no stray drop from the block you swing at). */
    public static void onBlockBroken(ServerPlayer player, ServerLevel level, BlockPos origin, BlockState originState) {
        if (breaking) return;                            // re-entrancy from our own breaks
        if (originState.isAir()) return;

        UUID uuid = player.getUUID();
        if (!PlayerState.isEnabled(uuid)) return;        // per-player master on/off toggle
        // Activation: Sneak (when the config lets it) and/or the rebindable activation keybind.
        boolean sneakActivate = VeinMinerConfig.sneakActivates && player.isShiftKeyDown();
        if (!sneakActivate && !PlayerState.isActivationHeld(uuid)) return;
        if (JOBS.containsKey(uuid)) return;              // a vein is already draining for this player

        ItemStack tool = player.getMainHandItem();
        if (!canMine(originState, tool)) return;         // the player couldn't collect this anyway

        // Voiding also covers the block the player breaks directly. This is a post-break hook, so
        // vanilla has already spawned the origin's drops — clear the items that just appeared in its
        // space so "void basic materials" doesn't leave one stray drop per swing.
        if (VeinMinerConfig.voidBasicMaterials && VeinMinerConfig.voidBlocks.contains(originState.getBlock())) {
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(origin))) {
                item.discard();
            }
        }

        MineShape shape = ShapeState.get(player.getUUID());
        Deque<Target> queue = new ArrayDeque<>();

        if (shape.isBox()) {
            Direction depthDir = MineShape.directionInto(player.getEyePosition(), player.getLookAngle(), origin);
            Set<BlockPos> box = new HashSet<>();
            collectBox(level, origin, shape, depthDir, tool, box);
            box.remove(origin.immutable());
            for (BlockPos pos : box) queue.add(new Target(pos, false));
        } else {
            // Logs use a dedicated, larger cap so tall giant trees fully fell.
            boolean log = originState.is(BlockTags.LOGS);
            int cap = log ? VeinMinerConfig.treeMax
                          : (shape == MineShape.SPREAD ? VeinMinerConfig.spreadMax : VeinMinerConfig.veinMax);
            Set<BlockPos> gathered = new HashSet<>();
            flood(level, List.of(origin), BlockMatcher.forOrigin(originState), tool, gathered, cap);
            gathered.remove(origin.immutable());

            // Smart-tree safety net: only fell connected logs when leaves are attached, so a
            // player-built log/wood structure (no leaves) is left intact — just the one block.
            if (log && VeinMinerConfig.smartTrees && !hasLeavesNearby(level, origin, gathered)) {
                gathered.clear();
            }
            for (BlockPos pos : gathered) queue.add(new Target(pos, false));

            // Once an actual tree is felled, clear its leaves too (decay-equivalent drops) — but
            // only when the canopy is isolated, so we never strip a neighbouring tree's leaves.
            if (log && VeinMinerConfig.clearLeaves && !gathered.isEmpty()) {
                Set<BlockPos> treeLogs = new HashSet<>(gathered);
                treeLogs.add(origin.immutable());
                addIsolatedCanopy(level, treeLogs, queue);
            }
        }

        if (!queue.isEmpty()) {
            JOBS.put(player.getUUID(), queue);
        }
    }

    /** Whether the player can mine {@code state} for drops with {@code tool}, per the tool policy.
     *  Floor: a block that requires a correct tool is only mined with the correct tool (so an iron
     *  pickaxe never wastes obsidian). Soft blocks (no tool requirement) drop with anything, but
     *  when {@code requireTool} is on they still need a tool in hand — unless toolless is allowed. */
    private static boolean canMine(BlockState state, ItemStack tool) {
        if (state.requiresCorrectToolForDrops()) {
            return tool.isCorrectToolForDrops(state);
        }
        if (VeinMinerConfig.requireTool) {
            return !tool.isEmpty() && tool.has(DataComponents.TOOL);
        }
        return true;
    }

    /** Oriented box: every mineable block in the cuboid. No cap — a box breaks its full volume
     *  (3x3x3=27, 5x5x5=125, 9x9x3=243); the shape is its own bound and the per-tick drain
     *  ({@code blocksPerTick}) staggers the work so even a big box never spikes the server. */
    private static void collectBox(ServerLevel level, BlockPos origin, MineShape shape, Direction depthDir,
                                   ItemStack tool, Set<BlockPos> out) {
        for (BlockPos raw : shape.positions(origin, depthDir)) {
            BlockPos pos = raw.immutable();
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            if (!canMine(state, tool)) continue;
            out.add(pos);
        }
    }

    /** True if a leaf block is face-adjacent to the origin log or any connected log — the signal
     *  that distinguishes a natural tree from a built log/wood structure. Early-exits on first leaf. */
    private static boolean hasLeavesNearby(ServerLevel level, BlockPos origin, Set<BlockPos> logs) {
        if (hasAdjacentLeaf(level, origin)) return true;
        for (BlockPos log : logs) {
            if (hasAdjacentLeaf(level, log)) return true;
        }
        return false;
    }

    private static boolean hasAdjacentLeaf(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (level.getBlockState(pos.relative(d)).is(BlockTags.LEAVES)) {
                return true;
            }
        }
        return false;
    }

    /** Flood the leaf canopy connected to a felled tree and, if it is isolated (touches no log
     *  outside the felled set and isn't an oversized merged canopy), queue the leaves for removal
     *  with decay-equivalent drops. Otherwise queue nothing and let vanilla decay handle them. */
    private static void addIsolatedCanopy(ServerLevel level, Set<BlockPos> treeLogs, Deque<Target> queue) {
        int cap = Math.max(VeinMinerConfig.treeMax * 2, 256);
        Set<BlockPos> leaves = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>(treeLogs);   // never revisit the tree's own logs
        Deque<BlockPos> frontier = new ArrayDeque<>();

        // Seed from leaves touching the tree's logs.
        for (BlockPos log : treeLogs) {
            for (Direction d : Direction.values()) {
                BlockPos n = log.relative(d).immutable();
                if (visited.add(n) && level.getBlockState(n).is(BlockTags.LEAVES)) {
                    leaves.add(n);
                    frontier.add(n);
                }
            }
        }

        boolean isolated = true;
        canopy:
        while (!frontier.isEmpty() && leaves.size() < cap) {
            BlockPos current = frontier.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos next = current.offset(dx, dy, dz).immutable();
                        if (!visited.add(next)) continue;
                        BlockState state = level.getBlockState(next);
                        if (state.is(BlockTags.LOGS)) {
                            isolated = false;   // a log outside the felled tree supports this canopy
                            break canopy;       // verdict sealed — nothing can flip it back to isolated
                        } else if (state.is(BlockTags.LEAVES)) {
                            leaves.add(next);
                            frontier.add(next);
                        }
                    }
                }
            }
        }

        if (leaves.size() >= cap) {
            isolated = false;   // huge/merged canopy — too risky to claim as one isolated tree
        }
        if (isolated) {
            for (BlockPos leaf : leaves) {
                queue.add(new Target(leaf, true));
            }
        }
    }

    /** Bounded 26-connected flood (diagonals included). Adds matching, mineable blocks reachable
     *  from {@code seeds} into {@code out}, never exceeding {@code cap}. */
    private static void flood(ServerLevel level, Collection<BlockPos> seeds, BlockMatcher matcher,
                              ItemStack tool, Set<BlockPos> out, int cap) {
        Set<BlockPos> visited = new HashSet<>(out);
        Deque<BlockPos> frontier = new ArrayDeque<>();
        for (BlockPos seed : seeds) {
            BlockPos immutable = seed.immutable();
            visited.add(immutable);
            frontier.add(immutable);
        }

        while (!frontier.isEmpty() && out.size() < cap) {
            BlockPos current = frontier.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos next = current.offset(dx, dy, dz).immutable();
                        if (!visited.add(next)) continue;
                        BlockState state = level.getBlockState(next);
                        if (!matcher.matches(state)) continue;
                        if (!canMine(state, tool)) continue;
                        out.add(next);
                        frontier.add(next);
                        if (out.size() >= cap) {
                            return;
                        }
                    }
                }
            }
        }
    }

    /** Called once per server tick by each loader. Drains up to {@code blocksPerTick} targets per
     *  active job so large veins/trees spread across ticks rather than freezing the server. */
    public static void tick(MinecraftServer server) {
        if (JOBS.isEmpty()) return;

        Iterator<Map.Entry<UUID, Deque<Target>>> it = JOBS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Deque<Target>> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {                 // logged off mid-vein — drop the job
                it.remove();
                DURABILITY_DEBT.remove(entry.getKey());
                continue;
            }

            Deque<Target> queue = entry.getValue();
            ServerLevel level = player.level();
            breaking = true;
            try {
                int budget = VeinMinerConfig.blocksPerTick;
                while (budget-- > 0 && !queue.isEmpty()) {
                    Target target = queue.poll();
                    BlockState state = level.getBlockState(target.pos());
                    if (state.isAir()) continue; // changed/decayed since queued
                    if (target.leaf()) {
                        // Decay-equivalent drops (no tool, no fortune) and no durability cost.
                        level.destroyBlock(target.pos(), true);
                    } else if (VeinMinerConfig.voidBasicMaterials
                            && VeinMinerConfig.voidBlocks.contains(state.getBlock())) {
                        // Void: delete with no drops/XP, but still charge durability per the % setting.
                        ItemStack tool = player.getMainHandItem();
                        if (tool.isDamageableItem() && tool.getDamageValue() >= tool.getMaxDamage() - 1) {
                            continue;
                        }
                        level.destroyBlock(target.pos(), false);
                        chargeVoidDurability(player);
                    } else {
                        ItemStack tool = player.getMainHandItem();
                        // Stop felling one hit before the tool would break; leaves (free) still finish.
                        if (tool.isDamageableItem() && tool.getDamageValue() >= tool.getMaxDamage() - 1) {
                            continue;
                        }
                        int damageBefore = tool.getDamageValue();
                        player.gameMode.destroyBlock(target.pos());
                        applyDurabilityMultiplier(player, damageBefore);
                    }
                }
            } finally {
                breaking = false;
            }

            if (queue.isEmpty()) {
                it.remove();
            }
        }
    }

    /** Re-charge a vein-mined block's durability at the configured multiplier. Vanilla has already
     *  applied its raw cost (post-Unbreaking) as {@code vanillaDelta}; we undo that and feed
     *  {@code vanillaDelta * mult} through the fractional accumulator, so a sub-1.0 per-block cost
     *  accumulates rather than rounding to 0 (or up to a full point) every block. mult &lt; 1.0
     *  reduces wear (0.0 = free); mult &gt; 1.0 charges extra; Unbreaking is preserved because we
     *  scale the delta it already reduced. */
    private static void applyDurabilityMultiplier(ServerPlayer player, int damageBefore) {
        double mult = VeinMinerConfig.durabilityMultiplier;
        if (mult == 1.0) return;                            // vanilla cost is already correct
        ItemStack tool = player.getMainHandItem();
        if (tool.isEmpty() || !tool.isDamageableItem()) return;
        int vanillaDelta = tool.getDamageValue() - damageBefore;
        if (vanillaDelta <= 0) return;                      // Unbreaking absorbed this hit
        tool.setDamageValue(damageBefore);                  // undo vanilla's raw charge...
        chargeScaled(player, tool, vanillaDelta * mult);    // ...re-apply the scaled cost via the debt
    }

    /** Charge a voided block's durability. Voiding deletes the block with no vanilla break to scale,
     *  so each voided block costs one block's worth ({@code 1.0 * mult}) through the same accumulator.
     *  Unbreaking is not applied here (there is no vanilla damage event), so voiding wears the tool a
     *  touch more than mining the same block normally. */
    private static void chargeVoidDurability(ServerPlayer player) {
        ItemStack tool = player.getMainHandItem();
        if (tool.isEmpty() || !tool.isDamageableItem()) return;
        chargeScaled(player, tool, VeinMinerConfig.durabilityMultiplier);
    }

    /** Add {@code cost} durability points to the player's running debt and spend the whole-number
     *  part now, carrying the fractional remainder to the next block. Clamps so the tool always
     *  keeps at least one point (it never breaks from vein-mining). */
    private static void chargeScaled(ServerPlayer player, ItemStack tool, double cost) {
        double debt = DURABILITY_DEBT.getOrDefault(player.getUUID(), 0.0) + cost;
        int whole = (int) Math.floor(debt);
        DURABILITY_DEBT.put(player.getUUID(), debt - whole);
        if (whole <= 0) return;
        int newDamage = Math.min(tool.getMaxDamage() - 1, tool.getDamageValue() + whole);
        tool.setDamageValue(newDamage);
    }
}
