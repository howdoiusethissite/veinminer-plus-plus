package com.kestalkayden.veinminerplusplus;

/** Shared constants and loader-agnostic entry points, compiled into both the Fabric and
 *  NeoForge jars from the {@code common/} source directory.
 *
 *  <p>Everything under {@code com.kestalkayden.veinminerplusplus.*} here must use only vanilla
 *  Minecraft APIs (plus cross-loader libraries both loaders ship, e.g. Cloth Config) so it
 *  compiles identically against each loader's classpath. Loader-specific glue — the
 *  entrypoint, block-break hook, networking, keybinds, and render hooks — lives in the
 *  {@code fabric/} and {@code neoforge/} source trees and calls into this shared code. */
public final class VeinMinerPlus {

    public static final String MOD_ID = "veinminerplusplus";

    private VeinMinerPlus() {}
}
