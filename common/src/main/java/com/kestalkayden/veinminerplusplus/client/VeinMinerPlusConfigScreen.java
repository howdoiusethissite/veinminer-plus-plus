package com.kestalkayden.veinminerplusplus.client;

import java.util.Collections;
import java.util.List;

import com.kestalkayden.veinminerplusplus.config.VeinMinerPlusConfig;

import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Hand-built vanilla config screen for Veinminer++ — single-column, full-width layout.
 *
 * <p>Replaces the previous {@link net.minecraft.client.gui.screens.options.OptionsSubScreen} +
 * {@link net.minecraft.client.gui.components.OptionsList#addSmall} two-column grid with a custom
 * {@link ContainerObjectSelectionList} that places each option on its own full-width row, grouped
 * under four centred section headers.
 *
 * <h3>Layout (top to bottom)</h3>
 * <pre>
 *              Veinminer++ Settings        (title, in HeaderAndFooterLayout header)
 *   ─────────────── Limits ───────────────  (HeaderEntry)
 *     Vein cap (blocks)          [slider]   (OptionEntry)
 *     Spread cap (blocks)        [slider]   (OptionEntry)
 *     Tree cap (blocks)          [slider]   (OptionEntry)
 *     Blocks per tick            [slider]   (OptionEntry)
 *   ──────────── Tree felling ────────────  (HeaderEntry)
 *     Smart tree detection       [on/off]   (OptionEntry)
 *     Clear felled-tree leaves   [on/off]   (OptionEntry)
 *   ────────────── Tooling ───────────────  (HeaderEntry)
 *     Sneak activates            [on/off]   (OptionEntry)
 *     Require a tool             [on/off]   (OptionEntry)
 *     Durability cost            [slider]   (OptionEntry)
 *   ────────── Modes & display ───────────  (HeaderEntry)
 *     Enable Spread mode         [on/off]   (OptionEntry)
 *     Always show shape guide    [on/off]   (OptionEntry)
 *      [ Open config folder ]  [ Done ]     (footer buttons, side by side)
 * </pre>
 *
 * <h3>Loader safety</h3>
 * <p>This class lives in {@code common/src/…/client/} and imports only vanilla classes — no Fabric
 * or NeoForge symbols. It is referenced only from client-guarded call sites
 * ({@code VeinMinerPlusModMenu} on Fabric, {@code VeinMinerPlusNeoForgeClient} on NeoForge).
 *
 * <h3>MC 26.2 API used</h3>
 * <ul>
 *   <li>{@link ContainerObjectSelectionList} — scrolling list base that hosts interactive widgets.
 *   <li>{@link ContainerObjectSelectionList.Entry} — entry type that implements
 *       {@code ContainerEventHandler}; requires {@code extractContent}, {@code children},
 *       and {@code narratables}.
 *   <li>{@link OptionInstance#createButton(net.minecraft.client.Options, int, int, int)} — creates
 *       the slider or toggle widget at a given position and width.
 *   <li>{@link AbstractWidget#extractRenderState} — 26.2's render dispatch method on widgets.
 *   <li>{@link StringWidget} — used inside {@link ConfigList.HeaderEntry} to render the centred
 *       section title; positioned via {@code setPosition} before each
 *       {@code extractRenderState} call.
 *   <li>{@link HeaderAndFooterLayout} — manages the title strip, scrolling list, and Done button.
 * </ul>
 */
public class VeinMinerPlusConfigScreen extends Screen {

    /** Screen title rendered in the header strip. */
    private static final Component TITLE =
            Component.translatable("veinminerplusplus.config.title");

    /** The screen to return to when Done is pressed (or the screen is otherwise closed). */
    private final Screen parent;

    /** The custom scrolling list; non-null after {@link #init()}. */
    private @Nullable ConfigList list;

    /** Manages the header (title), scrollable contents (list), and footer (Done button). */
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Create a new config screen.
     *
     * @param parent the screen to return to when Done is pressed or the screen is closed
     */
    public VeinMinerPlusConfigScreen(Screen parent) {
        super(TITLE);
        this.parent = parent;
    }

    // -------------------------------------------------------------------------
    // Screen lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void init() {
        layout.addTitleHeader(TITLE, font);
        list = layout.addToContents(new ConfigList(minecraft, width, this));
        // Two side-by-side footer buttons. The footer is a FrameLayout, which stacks every child
        // centred at the same spot — so wrap the pair in a horizontal LinearLayout (the vanilla
        // ConfirmScreen pattern) to lay them out next to each other instead of overlapping.
        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(
                Button.builder(
                        Component.translatable("veinminerplusplus.config.openConfigFolder"),
                        b -> Util.getPlatform().openUri(VeinMinerPlusConfig.getConfigDir().toUri()))
                      .width(150)
                      .build());
        footer.addChild(
                Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                      .width(150)
                      .build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
        if (list != null) {
            list.updateSize(width, layout);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    /**
     * Persist the config whenever this screen is closed — whether via the Done button, Escape, or
     * any other navigation. The {@link OptionInstance.ValueUpdateListener}s inside
     * {@link ConfigList} have already written their values into the {@link VeinMinerPlusConfig}
     * POJO; {@link VeinMinerPlusConfig#save()} clamps, applies into the runtime config, and
     * flushes to disk.
     */
    @Override
    public void removed() {
        VeinMinerPlusConfig.save();
    }

    // =========================================================================
    // Custom scrolling list
    // =========================================================================

    /**
     * A single-column scrollable list that can hold two kinds of rows:
     * <ul>
     *   <li>{@link HeaderEntry} — a centred, non-interactive section title.
     *   <li>{@link OptionEntry} — one full-width option widget (slider or toggle).
     * </ul>
     */
    static final class ConfigList
            extends ContainerObjectSelectionList<ConfigList.AbstractEntry> {

        /** Row width matches OptionsList (310 px). */
        private static final int ROW_WIDTH = 310;

        /** Height of a standard option row — matches OptionsList's DEFAULT_ITEM_HEIGHT. */
        private static final int OPTION_ROW_HEIGHT = 25;

        /** Height of a section header row (single line of text, plus a little padding). */
        private static final int HEADER_ROW_HEIGHT = 18;

        /** Reference to the owning screen so option entries can read {@code screen.width}. */
        private final Screen screen;

        ConfigList(Minecraft minecraft, int screenWidth, Screen screen) {
            // height and y are set via updateSize() from repositionElements(); pass 0 for now.
            super(minecraft, screenWidth, 0, 0, OPTION_ROW_HEIGHT);
            this.centerListVertically = false;
            this.screen = screen;
            populateEntries();
        }

        @Override
        public int getRowWidth() {
            return ROW_WIDTH;
        }

        // ---------------------------------------------------------------------
        // Populate
        // ---------------------------------------------------------------------

        private void populateEntries() {
            VeinMinerPlusConfig cfg = VeinMinerPlusConfig.get();

            // ---- Limits ----
            addHeader("veinminerplusplus.config.section.limits");
            addOption(new OptionInstance<>(
                    "veinminerplusplus.config.veinMax",
                    OptionInstance.cachedConstantTooltip(
                            Component.translatable("veinminerplusplus.config.veinMax.tooltip")),
                    (caption, val) -> Component.translatable("options.generic_value", caption, Component.literal(String.valueOf(val))),
                    new OptionInstance.IntRange(1, 128),
                    cfg.veinMax,
                    val -> VeinMinerPlusConfig.get().veinMax = val));
            addOption(new OptionInstance<>(
                    "veinminerplusplus.config.spreadMax",
                    OptionInstance.cachedConstantTooltip(
                            Component.translatable("veinminerplusplus.config.spreadMax.tooltip")),
                    (caption, val) -> Component.translatable("options.generic_value", caption, Component.literal(String.valueOf(val))),
                    new OptionInstance.IntRange(1, 1024),
                    cfg.spreadMax,
                    val -> VeinMinerPlusConfig.get().spreadMax = val));
            addOption(new OptionInstance<>(
                    "veinminerplusplus.config.treeMax",
                    OptionInstance.cachedConstantTooltip(
                            Component.translatable("veinminerplusplus.config.treeMax.tooltip")),
                    (caption, val) -> Component.translatable("options.generic_value", caption, Component.literal(String.valueOf(val))),
                    new OptionInstance.IntRange(1, 512),
                    cfg.treeMax,
                    val -> VeinMinerPlusConfig.get().treeMax = val));
            addOption(new OptionInstance<>(
                    "veinminerplusplus.config.blocksPerTick",
                    OptionInstance.cachedConstantTooltip(
                            Component.translatable("veinminerplusplus.config.blocksPerTick.tooltip")),
                    (caption, val) -> Component.translatable("options.generic_value", caption, Component.literal(String.valueOf(val))),
                    new OptionInstance.IntRange(1, 64),
                    cfg.blocksPerTick,
                    val -> VeinMinerPlusConfig.get().blocksPerTick = val));

            // ---- Tree felling ----
            addHeader("veinminerplusplus.config.section.trees");
            addOption(OptionInstance.createBoolean(
                    "veinminerplusplus.config.smartTrees",
                    OptionInstance.cachedConstantTooltip(
                            Component.translatable("veinminerplusplus.config.smartTrees.tooltip")),
                    cfg.smartTrees,
                    val -> VeinMinerPlusConfig.get().smartTrees = val));
            addOption(OptionInstance.createBoolean(
                    "veinminerplusplus.config.clearLeaves",
                    OptionInstance.cachedConstantTooltip(
                            Component.translatable("veinminerplusplus.config.clearLeaves.tooltip")),
                    cfg.clearLeaves,
                    val -> VeinMinerPlusConfig.get().clearLeaves = val));

            // ---- Tooling ----
            addHeader("veinminerplusplus.config.section.tooling");
            addOption(OptionInstance.createBoolean(
                    "veinminerplusplus.config.sneakActivates",
                    OptionInstance.cachedConstantTooltip(
                            Component.translatable("veinminerplusplus.config.sneakActivates.tooltip")),
                    cfg.sneakActivates,
                    val -> VeinMinerPlusConfig.get().sneakActivates = val));
            addOption(OptionInstance.createBoolean(
                    "veinminerplusplus.config.requireTool",
                    OptionInstance.cachedConstantTooltip(
                            Component.translatable("veinminerplusplus.config.requireTool.tooltip")),
                    cfg.requireTool,
                    val -> VeinMinerPlusConfig.get().requireTool = val));
            addOption(new OptionInstance<>(
                    "veinminerplusplus.config.durabilityPercent",
                    OptionInstance.cachedConstantTooltip(
                            Component.translatable("veinminerplusplus.config.durabilityPercent.tooltip")),
                    (caption, val) -> Component.translatable("options.percent_value", caption, val),
                    new OptionInstance.IntRange(0, 150),
                    cfg.durabilityPercent,
                    val -> VeinMinerPlusConfig.get().durabilityPercent = val));
            addOption(OptionInstance.createBoolean(
                    "veinminerplusplus.config.voidBasicMaterials",
                    OptionInstance.cachedConstantTooltip(
                            Component.translatable("veinminerplusplus.config.voidBasicMaterials.tooltip")),
                    cfg.voidBasicMaterials,
                    val -> VeinMinerPlusConfig.get().voidBasicMaterials = val));

            // ---- Modes & display ----
            addHeader("veinminerplusplus.config.section.display");
            addOption(OptionInstance.createBoolean(
                    "veinminerplusplus.config.enableSpread",
                    OptionInstance.cachedConstantTooltip(
                            Component.translatable("veinminerplusplus.config.enableSpread.tooltip")),
                    cfg.enableSpread,
                    val -> VeinMinerPlusConfig.get().enableSpread = val));
            addOption(OptionInstance.createBoolean(
                    "veinminerplusplus.config.enableExtraShapes",
                    OptionInstance.cachedConstantTooltip(
                            Component.translatable("veinminerplusplus.config.enableExtraShapes.tooltip")),
                    cfg.enableExtraShapes,
                    val -> VeinMinerPlusConfig.get().enableExtraShapes = val));
            addOption(OptionInstance.createBoolean(
                    "veinminerplusplus.config.alwaysShowGuide",
                    OptionInstance.cachedConstantTooltip(
                            Component.translatable("veinminerplusplus.config.alwaysShowGuide.tooltip")),
                    cfg.alwaysShowGuide,
                    val -> VeinMinerPlusConfig.get().alwaysShowGuide = val));
        }

        private void addHeader(String langKey) {
            addEntry(new HeaderEntry(Component.translatable(langKey), minecraft), HEADER_ROW_HEIGHT);
        }

        private void addOption(OptionInstance<?> option) {
            addEntry(new OptionEntry(option, minecraft, screen), OPTION_ROW_HEIGHT);
        }

        // =====================================================================
        // Entry types
        // =====================================================================

        /** Base type shared by both entry kinds — no behaviour of its own. */
        abstract static class AbstractEntry
                extends ContainerObjectSelectionList.Entry<AbstractEntry> {
        }

        // ---------------------------------------------------------------------
        // HeaderEntry — centred, non-interactive section title
        // ---------------------------------------------------------------------

        /**
         * A non-interactive row that renders a centred section title.
         *
         * <p>Uses a {@link StringWidget} for narration support, positioned to the horizontal
         * centre of the row each frame (the row's X/width are updated by the list before
         * {@code extractContent} is called).
         */
        static final class HeaderEntry extends AbstractEntry {

            private final StringWidget widget;

            HeaderEntry(Component title, Minecraft minecraft) {
                // Width will be overridden each frame; give it full ROW_WIDTH for initial sizing.
                this.widget = new StringWidget(ROW_WIDTH, 9, title, minecraft.font);
            }

            @Override
            public void extractContent(
                    GuiGraphicsExtractor graphics,
                    int mouseX, int mouseY,
                    boolean hovered,
                    float a) {
                // Centre the text within the row. getContentXMiddle() is the horizontal midpoint
                // of this entry's content area (getX() + CONTENT_PADDING + getContentWidth()/2).
                int textWidth = this.widget.getWidth();
                int centreX = this.getContentXMiddle() - textWidth / 2;
                int centreY = this.getContentY() + (this.getContentHeight() - 9) / 2;
                this.widget.setPosition(centreX, centreY);
                this.widget.extractRenderState(graphics, mouseX, mouseY, a);
            }

            /** The header is non-interactive — no focusable children. */
            @Override
            public List<? extends GuiEventListener> children() {
                return Collections.emptyList();
            }

            /** Still provide the StringWidget as a narration target so screen-readers announce it. */
            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of(widget);
            }
        }

        // ---------------------------------------------------------------------
        // OptionEntry — one full-width option widget per row
        // ---------------------------------------------------------------------

        /**
         * A single-option row that creates the option's widget at the full row width (310 px)
         * and positions it centred within the row each frame.
         *
         * <p>{@link OptionInstance#createButton(net.minecraft.client.Options, int, int, int)} is
         * called once at construction time with {@code width = ROW_WIDTH} so the slider/toggle is
         * full-width. The X/Y position is updated in {@code extractContent} before each
         * {@code extractRenderState} call, matching the pattern used by
         * {@link net.minecraft.client.gui.components.OptionsList.Entry}'s big-entry factory.
         */
        static final class OptionEntry extends AbstractEntry {

            private final AbstractWidget widget;
            private final Screen screen;

            OptionEntry(OptionInstance<?> option, Minecraft minecraft, Screen screen) {
                this.screen = screen;
                // Create the button at position (0,0); we reposition in extractContent.
                this.widget = option.createButton(minecraft.options, 0, 0, ROW_WIDTH);
            }

            @Override
            public void extractContent(
                    GuiGraphicsExtractor graphics,
                    int mouseX, int mouseY,
                    boolean hovered,
                    float a) {
                // Centre the widget horizontally within the row.
                // getContentXMiddle() == getX() + CONTENT_PADDING + getContentWidth()/2
                int widgetX = this.getContentXMiddle() - this.widget.getWidth() / 2;
                this.widget.setPosition(widgetX, this.getContentY());
                this.widget.extractRenderState(graphics, mouseX, mouseY, a);
            }

            /** The widget is the sole interactive child for keyboard/mouse event routing. */
            @Override
            public List<? extends GuiEventListener> children() {
                return List.of(widget);
            }

            /** The widget is also the narration target. */
            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of(widget);
            }
        }
    }
}
