package com.kestalkayden.veinminerplusplus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kestalkayden.veinminerplusplus.client.VeinMinerPlusNeoForgeClient;
import com.kestalkayden.veinminerplusplus.config.VeinMinerPlusConfig;
import com.kestalkayden.veinminerplusplus.core.MineShape;
import com.kestalkayden.veinminerplusplus.core.PlayerState;
import com.kestalkayden.veinminerplusplus.core.ShapeState;
import com.kestalkayden.veinminerplusplus.core.VeinMiner;
import com.kestalkayden.veinminerplusplus.network.ActivationHeldPayload;
import com.kestalkayden.veinminerplusplus.network.ShapeSelectPayload;
import com.kestalkayden.veinminerplusplus.network.ToggleEnabledPayload;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

@Mod(VeinMinerPlus.MOD_ID)
public class VeinMinerPlusNeoForge {
    public static final Logger LOGGER = LoggerFactory.getLogger(VeinMinerPlus.MOD_ID);

    public VeinMinerPlusNeoForge(IEventBus modBus, ModContainer modContainer) {
        LOGGER.info("Initializing Veinminer++ (NeoForge)");

        // Inject the NeoForge config directory and load the Gson-backed config. Must happen before
        // any event listeners that read VeinMinerConfig, so loaded values are in place from the
        // first server tick.
        VeinMinerPlusConfig.setConfigDir(FMLPaths.CONFIGDIR.get());
        VeinMinerPlusConfig.load();

        // Register the C2S payload on the mod event bus (fires before the world loads).
        modBus.addListener(VeinMinerPlusNeoForge::onRegisterPayloadHandlers);

        // Both block breaking and server ticking fire on the game event bus.
        NeoForge.EVENT_BUS.addListener(VeinMinerPlusNeoForge::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(VeinMinerPlusNeoForge::onServerTick);

        // Guard client-only code behind a dist check so the client class is never loaded on a
        // dedicated server (which would fail because client-only Minecraft classes are absent).
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            new VeinMinerPlusNeoForgeClient(modBus, modContainer);
        }
    }

    // -------------------------------------------------------------------------
    // Networking -- mod bus
    // -------------------------------------------------------------------------

    private static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        // Request a versioned registrar so incompatible clients/servers disconnect cleanly.
        PayloadRegistrar registrar = event.registrar(VeinMinerPlus.MOD_ID).versioned("1");

        // playToServer: C2S packet carrying the player's selected shape ordinal.
        registrar.playToServer(
                ShapeSelectPayload.TYPE,
                ShapeSelectPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    // context.player() is the ServerPlayer on the server side.
                    if (!(context.player() instanceof ServerPlayer player)) return;
                    MineShape[] shapes = MineShape.values();
                    int ordinal = Math.max(0, Math.min(payload.shapeOrdinal(), shapes.length - 1));
                    ShapeState.set(player.getUUID(), shapes[ordinal]);
                }));

        // C2S: per-player master on/off toggle.
        registrar.playToServer(
                ToggleEnabledPayload.TYPE,
                ToggleEnabledPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        PlayerState.setEnabled(player.getUUID(), payload.enabled());
                    }
                }));

        // C2S: rebindable activation key held-state.
        registrar.playToServer(
                ActivationHeldPayload.TYPE,
                ActivationHeldPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        PlayerState.setActivationHeld(player.getUUID(), payload.held());
                    }
                }));
    }

    // -------------------------------------------------------------------------
    // Game event handlers
    // -------------------------------------------------------------------------

    private static void onBlockBreak(BreakBlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        VeinMiner.onBlockBroken(player, level, event.getPos(), event.getState());
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        VeinMiner.tick(event.getServer());
    }
}
