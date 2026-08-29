package com.kestalkayden.veinminerplusplus.core;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** The selectable mining modes, cycled with the {@code [} / {@code ]} keys.
 *
 *  <ul>
 *    <li>{@link #VEIN} (default) and {@link #SPREAD} are connectivity floods — they follow
 *        connected matching blocks (same block, ore family, or tree). They differ only in their
 *        block cap (Vein is small/everyday, Spread is large) and Spread is gated behind config.
 *    <li>{@link #CUBE_3} is an oriented 3x3x3 box: the block the player broke sits on the near
 *        face and the box extends into the surface along the look direction. No vein extension.
 *    <li>{@link #CUBE_5} (5x5x5) and {@link #HALL} (9 wide x 3 tall x 9 deep) are larger oriented
 *        boxes, gated behind the "enable extra shapes" config toggle. Like {@link #CUBE_3} they
 *        break their full volume (no cap — the shape is its own bound), staggered by blocksPerTick.
 *  </ul> */
public enum MineShape {

    VEIN("Vein", false, Gate.NONE, 0, 0, 0),
    CUBE_3("3x3x3", true, Gate.NONE, 3, 3, 3),
    CUBE_5("5x5x5", true, Gate.EXTRA, 5, 5, 5),
    HALL("9x9x3", true, Gate.EXTRA, 9, 3, 9),
    SPREAD("Spread", false, Gate.SPREAD, 0, 0, 0),

    // ---- added shapes -------------------------------------------------------
    // Appended after SPREAD so existing ordinals (which ShapeSelectPayload puts on the wire)
    // keep their meaning. All gated behind the existing "extra shapes" toggle.
    // The three walkable ones anchor at the top: aim at the block level with your head and the
    // corridor is carved downward from there, so it lands on your head + feet rather than
    // head + ceiling.
    STRIP("Strip 1x2x10", true, Gate.EXTRA, 1, 2, 10, Anchor.TOP, 0),
    STAIRS_UP("Stairs up", true, Gate.EXTRA, 1, 2, 10, Anchor.TOP, +1),
    STAIRS_DOWN("Stairs down", true, Gate.EXTRA, 1, 2, 10, Anchor.TOP, -1),
    SHAFT("3x3x8", true, Gate.EXTRA, 3, 3, 8),
    WALL("5x5x1", true, Gate.EXTRA, 5, 5, 1),
    LAYER("7x1x7", true, Gate.EXTRA, 7, 1, 7),
    CUBE_7("7x7x7", true, Gate.EXTRA, 7, 7, 7);

    /** Which config toggle, if any, gates a shape's appearance in the [ / ] cycle. */
    public enum Gate { NONE, SPREAD, EXTRA }

    /** Where the origin block sits in the box's height. {@code CENTERED} keeps the classic
     *  behaviour (origin in the middle); {@code TOP} makes the origin the topmost layer and
     *  carves downward, which is what you want for anything you intend to walk through. */
    public enum Anchor { CENTERED, TOP }

    public final String label;
    /** True for the oriented cuboid; false for connectivity floods. */
    public final boolean box;
    /** Which config toggle gates this shape in the cycle ({@link Gate#NONE} = always shown). */
    public final Gate gate;
    public final int width;
    public final int height;
    public final int depth;
    /** Where the origin sits vertically within the box. */
    public final Anchor anchor;
    /** Vertical rise per step along the depth axis: +1 climbs, -1 descends, 0 stays level.
     *  Ignored when the player is looking straight up or down (a staircase has no meaning there,
     *  so the shape falls back to a straight box). */
    public final int stair;

    MineShape(String label, boolean box, Gate gate, int width, int height, int depth) {
        this(label, box, gate, width, height, depth, Anchor.CENTERED, 0);
    }

    MineShape(String label, boolean box, Gate gate, int width, int height, int depth,
              Anchor anchor, int stair) {
        this.label = label;
        this.box = box;
        this.gate = gate;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.anchor = anchor;
        this.stair = stair;
    }

    public boolean isBox() {
        return box;
    }

    /** The ordered cycle list. Spread appears only when {@code includeSpread}; the extra box
     *  shapes (5x5x5, 9x9x3) appear only when {@code includeExtra}. */
    public static List<MineShape> cycle(boolean includeSpread, boolean includeExtra) {
        List<MineShape> list = new ArrayList<>();
        for (MineShape shape : values()) {
            boolean show = switch (shape.gate) {
                case NONE -> true;
                case SPREAD -> includeSpread;
                case EXTRA -> includeExtra;
            };
            if (show) list.add(shape);
        }
        return list;
    }

    /** Positions of the oriented cuboid: the broken block ({@code origin}) is on the near face,
     *  and the box runs {@code depth} blocks along {@code depthDir} (the player's look direction),
     *  centered in width/height around the origin. Empty for non-box modes. The origin is included;
     *  the caller drops it (vanilla already broke it). */
    public List<BlockPos> positions(BlockPos origin, Direction depthDir) {
        if (!box) {
            return List.of();
        }
        Direction widthDir;
        Direction heightDir;
        switch (depthDir.getAxis()) {
            case Y -> { widthDir = Direction.EAST; heightDir = Direction.SOUTH; }  // looking up/down
            case X -> { widthDir = Direction.SOUTH; heightDir = Direction.UP; }     // looking east/west
            default -> { widthDir = Direction.EAST; heightDir = Direction.UP; }     // looking north/south
        }
        // Split low/high instead of one half-extent so even dimensions work: an even width or
        // height keeps the origin on the low side and grows one extra block toward +width/+height.
        int wLow = (width - 1) / 2, wHigh = width / 2;
        int hLow, hHigh;
        if (anchor == Anchor.TOP) {
            hLow = height - 1;   // origin is the ceiling layer; carve downward from it
            hHigh = 0;
        } else {
            hLow = (height - 1) / 2;
            hHigh = height / 2;
        }

        // A staircase only makes sense when you're looking along the ground. Aiming straight up
        // or down leaves the shape as a plain box.
        int rise = depthDir.getAxis().isVertical() ? 0 : stair;

        List<BlockPos> out = new ArrayList<>(width * height * depth);
        for (int d = 0; d < depth; d++) {
            BlockPos slice = origin.relative(depthDir, d);
            if (rise != 0) {
                slice = rise > 0 ? slice.above(d) : slice.below(d);
            }
            for (int w = -wLow; w <= wHigh; w++) {
                for (int h = -hLow; h <= hHigh; h++) {
                    out.add(slice.relative(widthDir, w).relative(heightDir, h));
                }
            }
        }
        return out;
    }

    /** AABB tightly enclosing the cuboid (world coords), for the edge guide. A single-block box at
     *  {@code origin} for non-box modes (the caller guards on {@link #isBox()}). */
    public AABB bounds(BlockPos origin, Direction depthDir) {
        if (!box) {
            return new AABB(origin);
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (BlockPos p : positions(origin, depthDir)) {
            if (p.getX() < minX) minX = p.getX();
            if (p.getY() < minY) minY = p.getY();
            if (p.getZ() < minZ) minZ = p.getZ();
            if (p.getX() + 1 > maxX) maxX = p.getX() + 1;
            if (p.getY() + 1 > maxY) maxY = p.getY() + 1;
            if (p.getZ() + 1 > maxZ) maxZ = p.getZ() + 1;
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** The cardinal direction the oriented box should extend — straight into {@code block} through
     *  the face the player's look ray enters. The guide and the miner both call this so they orient
     *  identically and intuitively: the box bores into the face you're aimed at, at any view angle.
     *
     *  <p>This replaces snapping to the look vector's dominant axis, which at steep angles tips the
     *  box into the floor/ceiling instead of the wall face you clicked. It's a ray/box slab test
     *  from {@code eye} along {@code look}: the entry face is the axis whose near plane the ray
     *  crosses last, and the box extends along {@code look}'s sign on that axis. */
    public static Direction directionInto(Vec3 eye, Vec3 look, BlockPos block) {
        double bestT = Double.NEGATIVE_INFINITY;
        Direction.Axis entryAxis = Direction.Axis.Z;
        for (Direction.Axis axis : Direction.Axis.values()) {
            double dir = axis.choose(look.x, look.y, look.z);
            if (dir == 0.0) continue;                       // ray parallel to this axis' faces
            double from = axis.choose(eye.x, eye.y, eye.z);
            double min  = axis.choose((double) block.getX(), (double) block.getY(), (double) block.getZ());
            double nearPlane = (dir > 0) ? min : min + 1.0; // the slab plane the ray enters from
            double t = (nearPlane - from) / dir;
            if (t > bestT) {
                bestT = t;
                entryAxis = axis;
            }
        }
        // The box extends along look's sign on the entry axis (i.e. into the block, away from you).
        double ex = entryAxis == Direction.Axis.X ? Math.signum(look.x) : 0.0;
        double ey = entryAxis == Direction.Axis.Y ? Math.signum(look.y) : 0.0;
        double ez = entryAxis == Direction.Axis.Z ? Math.signum(look.z) : 0.0;
        return Direction.getApproximateNearest(new Vec3(ex, ey, ez));
    }
}
