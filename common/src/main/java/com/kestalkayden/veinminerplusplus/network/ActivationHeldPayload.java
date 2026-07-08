package com.kestalkayden.veinminerplusplus.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import com.kestalkayden.veinminerplusplus.VeinMinerPlus;

/**
 * C2S packet: the client reports whether the rebindable "Vein-mine (hold)" activation key is
 * currently held.
 *
 * <p>Sent edge-triggered — only when the held state flips — so it costs at most a couple of bytes
 * per press/release. The server stores it in {@link
 * com.kestalkayden.veinminerplusplus.core.PlayerState}; {@link
 * com.kestalkayden.veinminerplusplus.core.VeinMiner} treats it as an activation alongside Sneak.
 * Defined in common; registered per loader (see the Fabric and NeoForge entrypoints).
 */
public record ActivationHeldPayload(boolean held) implements CustomPacketPayload {

    /** Wire ID. */
    public static final CustomPacketPayload.Type<ActivationHeldPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(VeinMinerPlus.MOD_ID, "activation_held"));

    /** StreamCodec over {@link RegistryFriendlyByteBuf}; a single boolean. */
    public static final StreamCodec<RegistryFriendlyByteBuf, ActivationHeldPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeBoolean(payload.held()),
                    buf -> new ActivationHeldPayload(buf.readBoolean()));

    @Override
    public CustomPacketPayload.Type<ActivationHeldPayload> type() {
        return TYPE;
    }
}
