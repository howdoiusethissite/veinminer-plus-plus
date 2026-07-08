package com.kestalkayden.veinminerplusplus.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kestalkayden.veinminerplusplus.core.VeinMinerConfig;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Gson-backed config POJO for Veinminer++.
 *
 * <p>This class is loader-agnostic: it uses only vanilla Minecraft classes and the Gson library
 * (which ships with every MC installation). No Fabric or NeoForge imports are allowed here.
 *
 * <h3>Seam</h3>
 * <p>Each loader entrypoint calls {@link #setConfigDir(Path)} once — before any call to
 * {@link #load()} — passing its loader-specific config root:
 * <ul>
 *   <li>Fabric: {@code FabricLoader.getInstance().getConfigDir()}
 *   <li>NeoForge: {@code FMLPaths.CONFIGDIR.get()}
 * </ul>
 * Both files live in a {@code veinminerplusplus/} subfolder of that root —
 * {@code config/veinminerplusplus/config.json} and {@code .../void-blocks.txt}. Installs from
 * before 0.4.0 kept them flat in the config root ({@code veinminerplusplus.json} and
 * {@code veinminerplusplus-void-blocks.txt}); {@link #migrateLegacyFiles()} moves them into the
 * subfolder once, on first load.
 *
 * <h3>JSON shape</h3>
 * <p>Field names match the original Cloth AutoConfig POJO, plus {@code enableExtraShapes} and
 * {@code voidBasicMaterials} (added post-cloth; absent in old configs, so Gson leaves them at their
 * {@code false} default):
 * {@code veinMax}, {@code spreadMax}, {@code treeMax}, {@code smartTrees}, {@code clearLeaves},
 * {@code blocksPerTick}, {@code requireTool}, {@code enableSpread}, {@code enableExtraShapes},
 * {@code durabilityPercent}, {@code alwaysShowGuide}, {@code voidBasicMaterials}.
 *
 * <p>The void-block <em>list</em> is NOT in this JSON — it's a separate hand-edited file,
 * {@code void-blocks.txt} (loaded by {@link #loadVoidBlocks()}), so the config screen's auto-save
 * never clobbers it.
 */
public class VeinMinerPlusConfig {

    // -------------------------------------------------------------------------
    // Field definitions + defaults (same values as the original cloth POJO)
    // -------------------------------------------------------------------------

    /** Block cap for the everyday Vein mode. Range: 1-128. */
    public int veinMax = 32;

    /** Block cap for the larger, opt-in Spread mode. Range: 1-1024. */
    public int spreadMax = 256;

    /** Block cap when felling a tree -- large enough for tall 2x2 giant trees. Range: 1-512. */
    public int treeMax = 256;

    /** When on, logs only fell as a tree if leaves are attached -- protects log/wood houses. */
    public boolean smartTrees = true;

    /** When on, felling an isolated tree also clears its leaves with decay-equivalent drops. */
    public boolean clearLeaves = true;

    /** Blocks broken per server tick when draining the queue (the lenient stagger). Range: 1-64. */
    public int blocksPerTick = 16;

    /** When on, soft blocks (grass/dirt/sand) still need a tool in hand; when off, they can be
     *  vein-mined by hand or any item. Hard blocks always need the correct tool to drop. */
    public boolean requireTool = false;

    /** When on, holding Sneak while breaking a block activates vein-mining (the classic trigger).
     *  When off, only the rebindable "Vein-mine (hold)" keybind does. */
    public boolean sneakActivates = true;

    /** When on, the larger Spread mode is offered in the [ / ] cycle. */
    public boolean enableSpread = false;

    /** When on, the extra box shapes (5x5x5, 9x9x3) are offered in the [ / ] cycle. */
    public boolean enableExtraShapes = false;

    /** Durability cost as a percentage of vanilla per block: 100 = vanilla, 0 = free, 150 = 1.5x.
     *  Range: 0-150. */
    public int durabilityPercent = 40;

    /** When on, the 3x3x3 edge guide is shown continuously while sneaking, not just after cycling. */
    public boolean alwaysShowGuide = false;

    /** When on, vein-mined blocks listed in {@code void-blocks.txt} are deleted with no drops/XP
     *  (they still cost durability). That list lives in its own file — the config screen never
     *  touches it — and is edited via the "Open config folder" button. */
    public boolean voidBasicMaterials = false;

    // -------------------------------------------------------------------------
    // Static infrastructure
    // -------------------------------------------------------------------------

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Subfolder, under the loader's config root, that holds this mod's files. */
    private static final String SUBFOLDER = "veinminerplusplus";
    private static final String FILE_NAME = "config.json";

    /** Separate, hand-edited file holding the void-block list — never written by the config screen. */
    private static final String VOID_FILE_NAME = "void-blocks.txt";

    /** Pre-0.4.0 filenames, kept flat in the config root; migrated into {@link #SUBFOLDER} once. */
    private static final String LEGACY_FILE_NAME = "veinminerplusplus.json";
    private static final String LEGACY_VOID_FILE_NAME = "veinminerplusplus-void-blocks.txt";

    /** Default blocks voided when {@code voidBasicMaterials} is on. */
    private static final List<String> DEFAULT_VOID_BLOCKS = List.of(
            "minecraft:stone", "minecraft:cobblestone", "minecraft:deepslate", "minecraft:cobbled_deepslate",
            "minecraft:granite", "minecraft:diorite", "minecraft:andesite", "minecraft:tuff",
            "minecraft:dirt", "minecraft:grass_block", "minecraft:gravel", "minecraft:sand", "minecraft:netherrack");

    /** Set by each loader entrypoint before the first {@link #load()} call. */
    private static Path configDir;

    /** The live singleton instance, populated by {@link #load()}. */
    private static VeinMinerPlusConfig instance = new VeinMinerPlusConfig();

    // -------------------------------------------------------------------------
    // Seam -- called from loader entrypoints
    // -------------------------------------------------------------------------

    /**
     * Injects the loader-specific config root and resolves this mod's subfolder under it.
     * Must be called before {@link #load()}.
     *
     * @param dir the loader's config root (e.g. {@code .minecraft/config/}); files are stored in
     *            its {@code veinminerplusplus/} subfolder
     */
    public static void setConfigDir(Path dir) {
        configDir = dir.resolve(SUBFOLDER);
    }

    // -------------------------------------------------------------------------
    // Load / save
    // -------------------------------------------------------------------------

    /**
     * Load (or create) {@code config.json} from the config subfolder, then
     * {@link #apply()} the values into the shared static holder {@link VeinMinerConfig}.
     *
     * <p>If the file does not exist, the defaults are written so the file is self-documenting on
     * first run. If JSON parsing fails for any field the default is silently preserved by Gson.
     */
    public static void load() {
        migrateLegacyFiles();
        Path file = resolveFile();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file)) {
                VeinMinerPlusConfig loaded = GSON.fromJson(r, VeinMinerPlusConfig.class);
                if (loaded != null) {
                    instance = loaded;
                }
            } catch (IOException e) {
                // Keep defaults; broken file will be overwritten on next save.
            }
        }
        clamp(instance);
        instance.apply();
        // Write the file back to ensure it exists and reflects any new fields.
        writeFile(instance);
        loadVoidBlocks();
    }

    /**
     * Clamp all fields to their documented bounds, copy into {@link VeinMinerConfig}, and persist
     * to disk. Call this after mutating {@link #get()} from the config screen.
     */
    public static void save() {
        clamp(instance);
        instance.apply();
        writeFile(instance);
    }

    /** The live config instance. Mutate fields directly, then call {@link #save()}. */
    public static VeinMinerPlusConfig get() {
        return instance;
    }

    /** The config subfolder ({@code config/veinminerplusplus/}), for the "Open config folder" button. */
    public static Path getConfigDir() {
        return configDir;
    }

    // -------------------------------------------------------------------------
    // Apply into shared static holder
    // -------------------------------------------------------------------------

    /** Copy every field into the shared static holder the core reads. */
    public void apply() {
        VeinMinerConfig.veinMax              = veinMax;
        VeinMinerConfig.spreadMax            = spreadMax;
        VeinMinerConfig.treeMax              = treeMax;
        VeinMinerConfig.smartTrees           = smartTrees;
        VeinMinerConfig.clearLeaves          = clearLeaves;
        VeinMinerConfig.blocksPerTick        = blocksPerTick;
        VeinMinerConfig.requireTool          = requireTool;
        VeinMinerConfig.sneakActivates       = sneakActivates;
        VeinMinerConfig.enableSpread         = enableSpread;
        VeinMinerConfig.enableExtraShapes    = enableExtraShapes;
        VeinMinerConfig.durabilityMultiplier = durabilityPercent / 100.0;
        VeinMinerConfig.alwaysShowGuide      = alwaysShowGuide;
        VeinMinerConfig.voidBasicMaterials   = voidBasicMaterials;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Path resolveFile() {
        if (configDir == null) {
            throw new IllegalStateException(
                    "VeinMinerPlusConfig.setConfigDir() was not called before load()");
        }
        return configDir.resolve(FILE_NAME);
    }

    /** Clamp all bounded int fields to their documented min/max. */
    private static void clamp(VeinMinerPlusConfig c) {
        c.veinMax           = clampInt(c.veinMax,           1,   128);
        c.spreadMax         = clampInt(c.spreadMax,         1,  1024);
        c.treeMax           = clampInt(c.treeMax,           1,   512);
        c.blocksPerTick     = clampInt(c.blocksPerTick,     1,    64);
        c.durabilityPercent = clampInt(c.durabilityPercent, 0,   150);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void writeFile(VeinMinerPlusConfig cfg) {
        Path file = resolveFile();
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(cfg, w);
            }
        } catch (IOException e) {
            // Non-fatal: the in-memory state is already correct.
        }
    }

    /**
     * One-time migration of pre-0.4.0 flat config files into the {@link #SUBFOLDER} subfolder:
     * {@code config/veinminerplusplus.json} → {@code config/veinminerplusplus/config.json}, and the
     * void list likewise → {@code void-blocks.txt}. After the move the legacy files are gone, so the
     * two {@link Files#exists} checks short-circuit and this returns immediately (no I/O) on every
     * later launch. The move is a same-filesystem rename — no copy — and never overwrites a file
     * already present in the new location.
     */
    private static void migrateLegacyFiles() {
        if (configDir == null) return;                 // resolveFile() reports this clearly
        Path root = configDir.getParent();
        if (root == null) return;
        Path legacyJson = root.resolve(LEGACY_FILE_NAME);
        Path legacyVoid = root.resolve(LEGACY_VOID_FILE_NAME);
        if (!Files.exists(legacyJson) && !Files.exists(legacyVoid)) return;   // already migrated / fresh
        try {
            Files.createDirectories(configDir);
            moveIfAbsent(legacyJson, configDir.resolve(FILE_NAME));
            moveIfAbsent(legacyVoid, configDir.resolve(VOID_FILE_NAME));
        } catch (IOException e) {
            // Non-fatal: fresh defaults are written in the new location below.
        }
    }

    /** Rename {@code from} to {@code to} only when the source exists and the destination does not,
     *  so a partially-migrated state never clobbers a newer file. */
    private static void moveIfAbsent(Path from, Path to) throws IOException {
        if (Files.exists(from) && !Files.exists(to)) {
            Files.move(from, to);
        }
    }

    /**
     * Load the hand-edited void-block list ({@value #VOID_FILE_NAME}, one block id per line, {@code #}
     * comments) and resolve it into {@link VeinMinerConfig#voidBlocks}. Writes the default file on
     * first run. The list lives in its own file the config screen never rewrites, so hand-edits are
     * never clobbered; changes take effect on the next game launch.
     */
    public static void loadVoidBlocks() {
        Path file = configDir.resolve(VOID_FILE_NAME);
        List<String> ids = DEFAULT_VOID_BLOCKS;
        if (Files.exists(file)) {
            try {
                ids = Files.readAllLines(file).stream()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .toList();
            } catch (IOException e) {
                // Unreadable — keep the defaults in memory.
            }
        } else {
            writeVoidFile(file);
        }
        Set<Block> resolved = new HashSet<>();
        for (String id : ids) {
            Identifier rl = Identifier.tryParse(id);
            if (rl != null) {
                BuiltInRegistries.BLOCK.getOptional(rl).ifPresent(resolved::add);
            }
        }
        VeinMinerConfig.voidBlocks = resolved;
    }

    private static void writeVoidFile(Path file) {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("# Veinminer++ - blocks voided (deleted, no drops) when \"Void basic materials\" is on.\n");
            sb.append("# One block id per line; lines starting with # are comments. Takes effect on restart.\n\n");
            for (String id : DEFAULT_VOID_BLOCKS) {
                sb.append(id).append('\n');
            }
            Files.writeString(file, sb.toString());
        } catch (IOException e) {
            // Non-fatal: defaults are already in memory.
        }
    }
}
