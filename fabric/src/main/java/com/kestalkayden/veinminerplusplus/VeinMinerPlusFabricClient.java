package com.kestalkayden.veinminerplusplus;

import org.lwjgl.glfw.GLFW;

import com.kestalkayden.veinminerplusplus.client.ShapeGuideRenderer;
import com.kestalkayden.veinminerplusplus.core.ClientShapeState;
import com.kestalkayden.veinminerplusplus.core.ClientState;
import com.kestalkayden.veinminerplusplus.core.ShapeState;
import com.kestalkayden.veinminerplusplus.core.VeinMinerConfig;
import com.kestalkayden.veinminerplusplus.network.ActivationHeldPayload;
import com.kestalkayden.veinminerplusplus.network.ShapeSelectPayload;
import com.kestalkayden.veinminerplusplus.network.ToggleEnabledPayload;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Fabric client entrypoint — runs only on the client dist.
 *
 * <p>Responsibilities (client-only; the serverbound payload + receiver live in the main initializer):
 * <ol>
 *   <li>Register the two keybind categories and mappings via {@link KeyMappingHelper}.
 *   <li>Poll the keys each client tick, cycle the local shape, write to {@link ClientShapeState},
 *       display an action-bar message, and send a {@link ShapeSelectPayload} to the server.
 *   <li>Register the {@link LevelRenderEvents#AFTER_TRANSLUCENT_FEATURES} callback that delegates
 *       to {@link ShapeGuideRenderer} for the xray-esque cuboid outline.
 * </ol>
 */
public class VeinMinerPlusFabricClient implements ClientModInitializer {

    // -------------------------------------------------------------------------
    // Keybind category
    // -------------------------------------------------------------------------

    /** Keybind category identifier — shown as "Veinminer++" in Controls. */
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(VeinMinerPlus.MOD_ID, "main"));

    // -------------------------------------------------------------------------
    // Key mappings
    // -------------------------------------------------------------------------

    /** Cycle to the previous shape. Default: [ */
    public static final KeyMapping KEY_PREV_SHAPE = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                    "key.veinminerplusplus.shape_prev",
                    GLFW.GLFW_KEY_LEFT_BRACKET,
                    CATEGORY));

    /** Cycle to the next shape. Default: ] */
    public static final KeyMapping KEY_NEXT_SHAPE = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                    "key.veinminerplusplus.shape_next",
                    GLFW.GLFW_KEY_RIGHT_BRACKET,
                    CATEGORY));

    /** Rebindable vein-mine activation (hold while breaking). Default: unbound. */
    public static final KeyMapping KEY_ACTIVATE = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                    "key.veinminerplusplus.activate",
                    GLFW.GLFW_KEY_UNKNOWN,
                    CATEGORY));

    /** Toggle vein-mining on/off for this client. Default: unbound. */
    public static final KeyMapping KEY_TOGGLE = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                    "key.veinminerplusplus.toggle",
                    GLFW.GLFW_KEY_UNKNOWN,
                    CATEGORY));

    // -------------------------------------------------------------------------
    // ClientModInitializer
    // -------------------------------------------------------------------------

    @Override
    public void onInitializeClient() {
        // The serverbound payload type + receiver are registered in the main initializer
        // (VeinMinerPlusFabric) so a dedicated server has them too; the client only sends.

        // Poll both cycle keys at the end of each client tick.
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        // Register the world-render callback for the shape guide outline.
        // AFTER_TRANSLUCENT_FEATURES fires after translucent geometry is submitted — the correct
        // stage for a translucent-blended overlay that must appear in front of the world.
        // In fabric-api 0.152.1+26.2 the LevelRenderContext exposes submitNodeCollector() and
        // poseStack() directly (the old consumers()/MultiBufferSource accessor is gone).
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(ctx ->
                ShapeGuideRenderer.render(ctx.submitNodeCollector(), ctx.poseStack()));
    }

    // -------------------------------------------------------------------------
    // Tick handler
    // -------------------------------------------------------------------------

    private void onClientTick(Minecraft client) {
        // (Re)sync per-connection client state the moment we connect, so a long-running dedicated
        // server never keeps a stale toggle from a previous session.
        boolean connected = client.player != null && client.getConnection() != null;
        if (connected && !ClientState.wasConnected) {
            send(new ToggleEnabledPayload(ClientState.enabled));
            ClientState.activationHeldSent = false;   // force an activation re-sync below
        }
        ClientState.wasConnected = connected;

        // consumeClick() returns true once per queued press, so holding the key for
        // multiple ticks will cycle the shape only as many times as presses were queued.
        boolean changed = false;
        while (KEY_PREV_SHAPE.consumeClick()) {
            ClientShapeState.current = ShapeState.cycle(
                    ClientShapeState.current, -1, VeinMinerConfig.enableSpread, VeinMinerConfig.enableExtraShapes);
            changed = true;
        }
        while (KEY_NEXT_SHAPE.consumeClick()) {
            ClientShapeState.current = ShapeState.cycle(
                    ClientShapeState.current, +1, VeinMinerConfig.enableSpread, VeinMinerConfig.enableExtraShapes);
            changed = true;
        }

        if (client.player == null) return;

        if (changed) {
            // Stamp the cycle time so the guide knows to show briefly.
            if (client.level != null) {
                ClientShapeState.lastCycleGameTime = client.level.getGameTime();
            }

            // Show the active shape name on the action bar (the hotbar-area overlay line).
            client.player.sendOverlayMessage(
                    Component.translatable("veinminerplusplus.shape", ClientShapeState.current.label));

            // Tell the server what we picked.
            send(new ShapeSelectPayload(ClientShapeState.current.ordinal()));
        }

        // On/off toggle — one flip per press, with a client-only chat line.
        while (KEY_TOGGLE.consumeClick()) {
            ClientState.enabled = !ClientState.enabled;
            // Client-only chat line (LocalPlayer#sendSystemMessage routes to the chat HUD, never
            // the server) so only this player sees the on/off feedback.
            client.player.sendSystemMessage(
                    Component.translatable(ClientState.enabled
                            ? "veinminerplusplus.toggle.enabled"
                            : "veinminerplusplus.toggle.disabled"));
            send(new ToggleEnabledPayload(ClientState.enabled));
        }

        // Rebindable activation key — report held-state changes (edge-triggered).
        boolean held = KEY_ACTIVATE.isDown();
        if (held != ClientState.activationHeldSent) {
            ClientState.activationHeldSent = held;
            send(new ActivationHeldPayload(held));
        }
    }

    /** Send a C2S payload over the active play connection. */
    private static void send(CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }
}
