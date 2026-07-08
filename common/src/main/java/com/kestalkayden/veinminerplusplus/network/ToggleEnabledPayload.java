package com.kestalkayden.veinminerplusplus.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import com.kestalkayden.veinminerplusplus.VeinMinerPlus;

/**
 * C2S packet: the client tells the server whether this player's vein-mining is currently enabled
 * (the on/off toggle keybind).
 *
 * <p>The server stores it in {@link com.kestalkayden.veinminerplusplus.core.PlayerState} and skips
 * vein-mining entirely while off. The client also re-sends its current value whenever it connects,
 * so a long-running dedicated server can never keep a stale value from a previous session. Defined
 * in common so it compiles against both loaders' classpaths; registered per loader (see the Fabric
 * and NeoForge entrypoints).
 */
public record ToggleEnabledPayload(boolean enabled) implements CustomPacketPayload {

    /** Wire ID. */
    public static final CustomPacketPayload.Type<ToggleEnabledPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(VeinMinerPlus.MOD_ID, "toggle_enabled"));

    /** StreamCodec over {@link RegistryFriendlyByteBuf}; a single boolean. */
    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleEnabledPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeBoolean(payload.enabled()),
                    buf -> new ToggleEnabledPayload(buf.readBoolean()));

    @Override
    public CustomPacketPayload.Type<ToggleEnabledPayload> type() {
        return TYPE;
    }
}
