/*
 * Copyright (c) 2025 VASSAL Extension Utility contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 */
package org.vassalengine.extutil.refresh;

import org.vassalengine.extutil.model.ExtensionIndex;
import org.vassalengine.extutil.model.SavedGame;

import VASSAL.Info;
import VASSAL.build.GameModule;
import VASSAL.build.module.ExtensionsLoader;
import VASSAL.build.module.ExtensionsManager;
import VASSAL.build.module.GameState;
import VASSAL.build.module.Map;
import VASSAL.build.module.metadata.SaveMetaData;
import VASSAL.build.widget.PieceSlot;
import VASSAL.counters.Decorator;
import VASSAL.counters.Embellishment;
import VASSAL.counters.GamePiece;
import VASSAL.counters.PieceCloner;
import VASSAL.counters.Properties;
import VASSAL.i18n.Localization;
import VASSAL.launch.StandardConfig;
import VASSAL.tools.DataArchive;
import VASSAL.tools.menu.MenuBarProxy;
import VASSAL.tools.menu.MenuManager;

import javax.swing.JFrame;
import javax.swing.JMenuBar;
import java.awt.Point;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Adds counters to a saved game, built by the engine itself.
 *
 * <p>Byte-level surgery can copy an existing piece and patch a couple of traits
 * (see {@code tools/fix_sif_subs.py --add}), but only where the new counter's
 * definition differs from an existing one in a known, small way. To add an
 * arbitrary counter you need its definition <em>expanded</em> — prototypes
 * inlined exactly as VASSAL inlines them — and the only thing that gets that
 * reliably right is VASSAL. So this asks the engine to make each piece the same
 * way dragging one off the palette does:</p>
 *
 * <pre>
 * GamePiece p = PieceCloner.getInstance().clonePiece(slot.getPiece());
 * p.setProperty(Properties.PIECE_ID, slot.getGpId());
 * map.placeOrMerge(p, point);
 * </pre>
 *
 * <p>{@code placeOrMerge} is what gives the piece its stacking behaviour: dropped
 * at the coordinates of an existing counter it merges into that counter's stack,
 * which is how "put it in the same stack as X" is expressed. It also registers
 * the piece with {@code GameState}, which is what makes it part of the save.</p>
 *
 * <p><b>Job file</b> (UTF-8), one directive per line:</p>
 * <pre>
 * module=/path/to/Module.vmod
 * save=/path/to/scenario.vsav
 * add=&lt;gpid&gt;\t&lt;map identifier&gt;\t&lt;x&gt;\t&lt;y&gt;[\tlayer:&lt;name&gt;=&lt;level&gt;]...
 * </pre>
 *
 * <p>An optional {@code layer:<name>=<level>} field sets the Layer trait
 * (Embellishment) named {@code <name>} to the given <b>1-based</b> level
 * before the piece is placed — how a nation is chosen on a shared layered
 * marker such as the WiF {@code Hex Control Marker} (its
 * {@code majorhexcontroller} layer has one level per major power). The level
 * is state, not type, so the piece stays byte-identical to a hand-placed one
 * cycled to that level.</p>
 *
 * <p>As in {@code RefreshRunner}, the scenario's own extension list and board
 * layouts are captured before and reapplied after, since saving rebuilds both
 * from whatever happens to be loaded.</p>
 */
public final class AddCountersRunner {

    private static final String P = "!!";
    private static PrintStream out;

    private static final class HeadlessMenuManager extends MenuManager {
        private final MenuBarProxy bar = new MenuBarProxy();
        @Override public JMenuBar getMenuBarFor(JFrame f) { return bar.createPeer(); }
        @Override public MenuBarProxy getMenuBarProxyFor(JFrame f) { return bar; }
    }

    /** One counter to add: which slot, where to drop it, and any layer levels. */
    private static final class Add {
        final String gpid, mapId;
        final int x, y;
        final List<String[]> layers;    // {layer name, 1-based level}
        Add(String gpid, String mapId, int x, int y, List<String[]> layers) {
            this.gpid = gpid; this.mapId = mapId; this.x = x; this.y = y;
            this.layers = layers;
        }
    }

    public static void main(String[] args) {
        try {
            out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),
                    true, StandardCharsets.UTF_8.name());
        }
        catch (IOException e) {
            out = System.out;
        }
        int exit = 0;
        try {
            exit = run(new File(args[0]));
        }
        catch (Throwable t) {                       // NOPMD - report, never propagate
            say("FATAL", describe(t));
            exit = 1;
        }
        System.exit(exit);                          // AWT threads would keep us alive
    }

    private static int run(File jobFile) throws Exception {
        File module = null, save = null;
        final List<Add> adds = new ArrayList<>();
        for (String line : Files.readAllLines(jobFile.toPath(), StandardCharsets.UTF_8)) {
            final int eq = line.indexOf('=');
            if (eq < 0) continue;
            final String key = line.substring(0, eq), val = line.substring(eq + 1);
            if ("module".equals(key)) module = new File(val);
            else if ("save".equals(key)) save = new File(val);
            else if ("add".equals(key)) {
                final String[] f = val.split("\t", -1);
                if (f.length < 4) { say("SKIP", "malformed add: " + val); continue; }
                final List<String[]> layers = new ArrayList<>();
                for (int i = 4; i < f.length; i++) {
                    final int sep = f[i].indexOf('=');
                    if (f[i].startsWith("layer:") && sep > 6) {
                        layers.add(new String[]{
                                f[i].substring(6, sep), f[i].substring(sep + 1) });
                    }
                    else if (!f[i].isEmpty()) {
                        say("SKIP", "unknown add field: " + f[i]);
                    }
                }
                adds.add(new Add(f[0], f[1],
                        Integer.parseInt(f[2]), Integer.parseInt(f[3]), layers));
            }
        }
        if (module == null || save == null || adds.isEmpty()) {
            say("FATAL", "job file needs module=, save= and at least one add=");
            return 2;
        }
        if (!module.isFile() || !save.isFile()) {
            say("FATAL", "module or save not readable");
            return 2;
        }

        Info.setConfig(new StandardConfig());
        new HeadlessMenuManager();
        GameModule.init(new GameModule(new DataArchive(module.getPath())));
        final GameModule mod = GameModule.getGameModule();
        new ExtensionsLoader().addTo(mod);
        Localization.getInstance().translate();
        mod.getPrefs().setValue(SaveMetaData.PROMPT_LOG_COMMENT, false);

        final java.util.Map<String, PieceSlot> slots = new HashMap<>();
        for (PieceSlot s : mod.getAllDescendantComponentsOf(PieceSlot.class)) {
            final String g = s.getGpId();
            if (g != null && !g.trim().isEmpty()) slots.putIfAbsent(g.trim(), s);
        }
        final java.util.Map<String, Map> maps = new HashMap<>();
        // Descendants, not children: the force-pool maps are contributed by
        // extensions and grafted deeper into the tree, so getComponentsOf misses
        // them (it found 7 of them, not the dozens that exist).
        for (Map m : mod.getAllDescendantComponentsOf(Map.class)) {
            maps.putIfAbsent(m.getIdentifier(), m);
            maps.putIfAbsent(m.getMapName(), m);
        }
        say("READY", mod.getGameVersion() + "\t" + slots.size() + "\t" + maps.size());

        final ExtensionIndex index = ExtensionIndex.read(
                module, new ExtensionsManager(module).getActiveExtensions());
        final SavedGame.PreservedState preserved =
                SavedGame.PreservedState.capture(SavedGame.open(save));

        final GameState gs = mod.getGameState();
        gs.setup(false);
        mod.setRefreshingSemaphore(true);
        final File tmp = File.createTempFile(save.getName() + ".", ".tmp",
                save.getAbsoluteFile().getParentFile());
        int placed = 0, failed = 0;
        try {
            gs.setupRefresh();
            try (InputStream in = new BufferedInputStream(Files.newInputStream(save.toPath()))) {
                gs.loadGameInForeground(save.getName(), in);
            }
            gs.getAttachmentManager().resolvePendingAttachments();

            for (Add a : adds) {
                final PieceSlot slot = slots.get(a.gpid);
                final Map map = maps.get(a.mapId);
                if (slot == null) { say("FAIL", a.gpid + "\tno palette slot with that gpid"); failed++; continue; }
                if (map == null)  { say("FAIL", a.gpid + "\tno map named " + a.mapId); failed++; continue; }
                try {
                    final GamePiece piece =
                            PieceCloner.getInstance().clonePiece(slot.getPiece());
                    piece.setProperty(Properties.PIECE_ID, slot.getGpId());
                    for (String[] spec : a.layers) {
                        if (!setLayer(piece, spec[0], Integer.parseInt(spec[1]))) {
                            say("WARN", a.gpid + "\tno Layer trait named " + spec[0]);
                        }
                    }
                    map.placeOrMerge(piece, new Point(a.x, a.y));
                    say("ADDED", a.gpid + "\t" + slot.getConfigureName() + "\t" + a.mapId
                            + "\t" + a.x + "," + a.y);
                    placed++;
                }
                catch (Throwable t) {               // NOPMD - one bad counter must not stop the rest
                    say("FAIL", a.gpid + "\t" + describe(t));
                    failed++;
                }
            }

            gs.saveGame(tmp);
            gs.updateDone();
            gs.closeGame();
            move(tmp, save);
            final SavedGame.PreservedState.Result r = preserved.restore(save, index);
            say("PRESERVED", r.extensionsRestored + "\t"
                    + r.strippedBoardPickerMaps.size() + "\t"
                    + String.join(", ", r.addedExtensions));
        }
        finally {
            mod.setRefreshingSemaphore(false);
            Files.deleteIfExists(tmp.toPath());
        }
        say("SUMMARY", placed + "\t" + failed);
        return failed == 0 ? 0 : 1;
    }

    /**
     * Sets every Layer trait (Embellishment) named {@code layerName} in the
     * piece's decorator chain to the given 1-based level.
     * {@link Embellishment#setValue(int)} takes a 0-based level and leaves the
     * activation status alone, which is what a shared always-active layered
     * marker needs.
     *
     * @return whether any matching layer was found
     */
    private static boolean setLayer(GamePiece piece, String layerName, int level) {
        boolean found = false;
        for (GamePiece p = piece; p instanceof Decorator; p = ((Decorator) p).getInner()) {
            if (p instanceof Embellishment
                    && layerName.equals(((Embellishment) p).getLayerName())) {
                ((Embellishment) p).setValue(level - 1);
                found = true;
            }
        }
        return found;
    }

    private static void move(File from, File to) throws IOException {
        try {
            Files.move(from.toPath(), to.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException atomicUnsupported) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void say(String tag, String rest) {
        out.println(P + tag + "\t" + rest.replace('\n', ' ').replace('\r', ' '));
        out.flush();
    }

    private static String describe(Throwable t) {
        final String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
    }
}
