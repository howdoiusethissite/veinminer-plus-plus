package com.kestalkayden.veinminerplusplus.core;

/** Client-only vein-mining UI state, mirrored to the server over the network.
 *
 *  <p>Plain static holder (like {@link ClientShapeState}) so both loaders' client tick handlers
 *  share it without importing loader code. Every field is in-memory only — the on/off toggle
 *  deliberately resets to enabled on each client launch. */
public final class ClientState {

    /** Master on/off toggle for this client. Flipped by the toggle keybind and mirrored to the
     *  server via {@code ToggleEnabledPayload}; also read by the shape-guide renderer. Resets to
     *  true each launch. */
    public static boolean enabled = true;

    /** Last activation-held value sent to the server, for edge-triggered network syncing. Doubles
     *  as the renderer's "is the activation key held" signal. */
    public static boolean activationHeldSent = false;

    /** Whether we were connected to a server on the previous client tick — used to (re)sync the
     *  toggle state to the server the moment a connection is (re)established. */
    public static boolean wasConnected = false;

    private ClientState() {}
}
