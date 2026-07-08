package com.kestalkayden.veinminerplusplus.client;

import com.kestalkayden.veinminerplusplus.core.ClientShapeState;
import com.kestalkayden.veinminerplusplus.core.ClientState;
import com.kestalkayden.veinminerplusplus.core.MineShape;
import com.kestalkayden.veinminerplusplus.core.VeinMinerConfig;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Renders an xray-esque (depth-test-disabled) outline of the currently selected cuboid shape
 * centred on the block the player is looking at.  Call {@link #render} from both loaders'
 * AFTER_TRANSLUCENT_FEATURES / SubmitCustomGeometryEvent hook.
 *
 * <h3>26.2 render pipeline</h3>
 *
 * <p>In 26.2, immediate-mode buffer sources ({@code MultiBufferSource}, {@code ShapeRenderer}) are
 * gone.  Geometry is submitted to the frame graph through a {@link SubmitNodeCollector} obtained
 * from the Fabric {@code LevelRenderContext} or the NeoForge {@code SubmitCustomGeometryEvent}.
 *
 * <p>We submit via {@link SubmitNodeCollector#submitShapeOutline}; the last {@code boolean} is the
 * render phase, not depth. The xray (depth-test disable) comes from the custom
 * {@link #PIPELINE_LINES_NO_DEPTH} pipeline ({@code ALWAYS_PASS}), which is registered and
 * (optionally) mapped to Iris's LINES program so it survives Sodium/Iris — see the pipeline field.
 *
 * <h3>Visibility rules (checked every frame in {@link #render})</h3>
 * <ol>
 *   <li>The local player must be in-world, vein-mining must be enabled for this client, and an
 *       activation must be engaged — Sneak (when "Sneak activates" is on) or the rebindable
 *       activation key.
 *   <li>The selected shape must be a cuboid (not {@link MineShape#isVein()}).
 *   <li>The crosshair must be aimed at a block ({@code hitResult instanceof BlockHitResult} with
 *       type {@code BLOCK}).
 *   <li>If {@code VeinMinerConfig.alwaysShowGuide} is {@code false}, the guide is only shown
 *       within {@value #GUIDE_TICKS} ticks of the last cycle key press.
 * </ol>
 */
public final class ShapeGuideRenderer {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /** Ticks after a cycle key press during which the guide remains visible when
     *  {@code VeinMinerConfig.alwaysShowGuide == false}. */
    private static final long GUIDE_TICKS = 40L;

    /**
     * Color of the guide outline — cyan with 50 % alpha.
     * Encoded as a packed {@code 0xAARRGGBB} int as accepted by
     * {@link SubmitNodeCollector#submitShapeOutline}.
     */
    private static final int GUIDE_COLOR_ARGB = 0x8030E0FF;  // α=0x80, r=0x30, g=0xE0, b=0xFF

    /** Line width passed to {@link SubmitNodeCollector#submitShapeOutline}. */
    private static final float LINE_WIDTH = 2.0f;

    /**
     * A lines pipeline with the depth test disabled ({@link CompareOp#ALWAYS_PASS}, no depth write)
     * so the outline shows through terrain — the xray effect from the 26.1 version.
     *
     * <p>26.2 removed every public depth-disabled lines RenderType and locked the builder pieces
     * ({@code RenderPipelines.LINES_SNIPPET} is private, {@code RenderType.create} is
     * package-private), so this rebuilds the custom pipeline with both reopened via the Fabric
     * access widener / NeoForge access transformer ({@code lines_no_depth}). Seeded from
     * {@code LINES_SNIPPET} so it inherits the vanilla lines shader + vertex format; only the
     * depth-stencil state is overridden.
     */
    private static final RenderPipeline PIPELINE_LINES_NO_DEPTH = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("veinminerplusplus", "lines_no_depth"))
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                    .build());
    // register() the pipeline: an UNregistered custom pipeline draws in vanilla but Sodium/Iris
    // silently drop it. register() (private; reopened via the access widener / transformer) adds it
    // to the shared list those mods scan, so the guide renders in-pack.

    /** The {@link RenderType} that wraps {@link #PIPELINE_LINES_NO_DEPTH} for submission. */
    private static final RenderType LINES_NO_DEPTH = RenderType.create(
            "veinminerplusplus:lines_no_depth",
            RenderSetup.builder(PIPELINE_LINES_NO_DEPTH).createRenderSetup());

    static {
        // Optional Iris support: route our pipeline through Iris's LINES program so the see-through
        // guide survives an active shaderpack (Iris draws only geometry tied to a program it knows).
        // No-op without Iris — see IrisCompat.
        IrisCompat.assignToLinesProgram(PIPELINE_LINES_NO_DEPTH);
    }

    // -------------------------------------------------------------------------
    // Private constructor — all methods are static
    // -------------------------------------------------------------------------

    private ShapeGuideRenderer() {}

    // -------------------------------------------------------------------------
    // Public entry point (called by both loaders' render hooks)
    // -------------------------------------------------------------------------

    /**
     * Evaluate visibility conditions and, if met, draw the cuboid outline.
     *
     * <p>The caller supplies the {@link SubmitNodeCollector} and the {@link PoseStack} obtained
     * from the render event.  The PoseStack is identity at the camera origin, so this method
     * translates it by {@code -cameraPos} to position the world-space {@link VoxelShape}.  The
     * final {@code submitShapeOutline} boolean is {@code afterTerrain} (render phase, not depth);
     * the see-through (depth-test disable) comes from the {@link #PIPELINE_LINES_NO_DEPTH} pipeline.
     *
     * @param collector  the node collector from the current frame's render event
     * @param poseStack  the frame PoseStack (camera-relative, identity at call time)
     */
    public static void render(SubmitNodeCollector collector, PoseStack poseStack) {
        Minecraft mc = Minecraft.getInstance();

        // ---- 1. Require a live in-world client player -----------------------------------
        if (mc.player == null || mc.level == null) return;

        // ---- 2. Master toggle + activation: show only while an activation is engaged --
        // Hidden when the player has toggled vein-mining off, otherwise shown while Sneak (when the
        // config lets Sneak activate) or the rebindable activation key is held — so the guide
        // tracks whatever actually triggers a vein-mine.
        if (!ClientState.enabled) return;
        boolean activating = (VeinMinerConfig.sneakActivates && mc.options.keyShift.isDown())
                || ClientState.activationHeldSent;
        if (!activating) return;

        // ---- 3. Selected shape must be a box (only the cuboid has an edge to preview) -
        MineShape shape = ClientShapeState.current;
        if (!shape.isBox()) return;

        // ---- 4. Crosshair must be aimed at a block ------------------------------------
        if (!(mc.hitResult instanceof BlockHitResult bhr)) return;
        if (bhr.getType() != HitResult.Type.BLOCK) return;

        // ---- 5. Time-based visibility when alwaysShowGuide is false -------------------
        if (!VeinMinerConfig.alwaysShowGuide) {
            long gameTime = mc.level.getGameTime();
            long elapsed  = gameTime - ClientShapeState.lastCycleGameTime;
            // lastCycleGameTime starts at -1, so elapsed is always huge until the first cycle.
            if (elapsed < 0 || elapsed > GUIDE_TICKS) return;
        }

        // ---- Compute the oriented bounding box ----------------------------------------
        BlockPos origin  = bhr.getBlockPos();
        // Orient by the FACE the look ray enters (MineShape.directionInto), not the look vector's
        // dominant axis — so the box always bores into the face you're aimed at, and the guide
        // matches VeinMiner (same helper) at any view angle.
        Direction depthDir = MineShape.directionInto(
                mc.player.getEyePosition(1.0f), mc.player.getViewVector(1.0f), origin);
        AABB bounds = shape.bounds(origin, depthDir);

        // ---- Submit the outline via the 26.2 node collector ---------------------------
        // The PoseStack from the render event is at the CAMERA origin, so a world-space shape must
        // be shifted by -cameraPos to land at the target block. submitShapeOutline transforms the
        // shape by the pose only — it does NOT subtract the camera itself (the 26.1 path did this
        // via ShapeRenderer's dx/dy/dz args, which the migration dropped → outline drawn off in
        // world space, invisible). The final submitShapeOutline arg is afterTerrain (render phase,
        // not depth); the see-through effect is PIPELINE_LINES_NO_DEPTH's ALWAYS_PASS, not this flag.
        VoxelShape voxelShape = Shapes.create(bounds);
        var camPos = mc.gameRenderer.mainCamera().position();
        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        collector.submitShapeOutline(poseStack, voxelShape, LINES_NO_DEPTH, GUIDE_COLOR_ARGB, LINE_WIDTH, true);
        poseStack.popPose();
    }
}
