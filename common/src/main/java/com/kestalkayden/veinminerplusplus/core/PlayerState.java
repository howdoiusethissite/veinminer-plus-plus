package com.kestalkayden.veinminerplusplus.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-player vein-mining state held server-side, set from the client over the network.
 *
 *  <ul>
 *    <li>{@code enabled} — the master on/off toggle (default on). The client flips it with a keybind
 *        and re-sends it on connect; {@link VeinMiner} skips everything while it is off.
 *    <li>{@code activationHeld} — whether the client's rebindable activation key is held right now
 *        (default false). {@link VeinMiner} treats it as an activation alongside Sneak.
 *  </ul>
 *
 *  <p>Sibling of {@link ShapeState}: plain per-UUID maps, defaults applied on read so a player with
 *  no stored entry behaves as enabled / not-holding. */
public final class PlayerState {

    private static final Map<UUID, Boolean> ENABLED = new HashMap<>();
    private static final Map<UUID, Boolean> ACTIVATION_HELD = new HashMap<>();

    private PlayerState() {}

    public static boolean isEnabled(UUID player) {
        return ENABLED.getOrDefault(player, true);
    }

    public static void setEnabled(UUID player, boolean enabled) {
        ENABLED.put(player, enabled);
    }

    public static boolean isActivationHeld(UUID player) {
        return ACTIVATION_HELD.getOrDefault(player, false);
    }

    public static void setActivationHeld(UUID player, boolean held) {
        ACTIVATION_HELD.put(player, held);
    }

    public static void clear(UUID player) {
        ENABLED.remove(player);
        ACTIVATION_HELD.remove(player);
    }
}
