/*
 * Copyright (c) 2025 VASSAL Extension Utility contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 */
package org.vassalengine.extutil.refresh;

import org.vassalengine.extutil.model.SavedGame;

import VASSAL.Info;
import VASSAL.build.GameModule;
import VASSAL.build.GpIdChecker;
import VASSAL.build.module.ExtensionsLoader;
import VASSAL.build.module.GameRefresher;
import VASSAL.build.module.GameState;
import VASSAL.build.module.ModuleExtension;
import VASSAL.build.module.PrototypesContainer;
import VASSAL.build.module.metadata.SaveMetaData;
import VASSAL.i18n.Localization;
import VASSAL.launch.StandardConfig;
import VASSAL.tools.DataArchive;
import VASSAL.tools.menu.MenuBarProxy;
import VASSAL.tools.menu.MenuManager;
import VASSAL.build.widget.PieceSlot;

import javax.swing.JFrame;
import javax.swing.JMenuBar;
import javax.swing.JTextPane;
import javax.swing.text.Document;
import java.awt.Component;
import java.awt.Container;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * Runs VASSAL's own <b>Refresh Counters</b> over a list of saved games, in a
 * separate JVM with the engine on the classpath.
 *
 * <p>This is the only part of the utility that links against VASSAL. It exists as
 * a subprocess rather than in-process because {@code GameModule.init()} throws if
 * called twice, so a JVM can host exactly one module for its whole life — the
 * utility, which opens modules freely, cannot live with that. Running the engine
 * out of process also keeps its Swing windows, preferences and any crash away
 * from the utility.</p>
 *
 * <p><b>Invocation.</b> A job file (UTF-8) is the sole argument, avoiding
 * command-line length limits and quoting problems with many paths:</p>
 * <pre>
 * module=/path/to/Module.vmod
 * option=RefreshPieces
 * option=UseLayerName
 * file=/path/to/scenario.vsav
 * file=/path/to/another.vsav
 * </pre>
 *
 * <p><b>Progress</b> is reported on stdout as {@code !!}-prefixed lines, which the
 * parent parses; any other output is the engine's own chatter and is passed
 * through for the log.</p>
 *
 * <p><b>Per file:</b> the original is copied to {@code <name>-backup.vsav} (never
 * overwriting an existing backup), refreshed, written to a temp file and moved
 * over the original. Two things the engine rebuilds from whatever happens to be
 * loaded are captured beforehand and reapplied afterwards — the scenario's own
 * extension list, and the set of maps it has board layouts for; see
 * {@link SavedGame.PreservedState}. The module name/version recorded in the save
 * is <em>not</em> preserved: the engine stamps the running module's, which is the
 * intended update.</p>
 */
public final class RefreshRunner {

    /** Progress-line prefix. Chosen not to collide with the engine's chatter. */
    private static final String P = "!!";

    private static PrintStream out;

    /** A no-op menu manager; the engine requires one to exist. */
    private static final class HeadlessMenuManager extends MenuManager {
        private final MenuBarProxy bar = new MenuBarProxy();
        @Override public JMenuBar getMenuBarFor(JFrame f) { return bar.createPeer(); }
        @Override public MenuBarProxy getMenuBarProxyFor(JFrame f) { return bar; }
    }

    public static void main(String[] args) {
        // The engine writes plenty to stdout; keep the protocol on a stream we own
        // and force UTF-8 so scenario names with non-ASCII survive the pipe.
        try {
            out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),
                    true, StandardCharsets.UTF_8.name());
        }
        catch (IOException e) {
            out = System.out;
        }

        int exit = 0;
        try {
            if (args.length != 1) {
                say("FATAL", "usage: RefreshRunner <job-file>");
                System.exit(2);
            }
            exit = run(new File(args[0]));
        }
        catch (Throwable t) {                       // NOPMD - must not escape: the parent reads exit codes
            say("FATAL", describe(t));
            exit = 1;
        }
        // Swing/AWT threads are alive by now; without this the JVM never exits.
        System.exit(exit);
    }

    private static int run(File jobFile) throws Exception {
        File module = null;
        final Set<String> options = new LinkedHashSet<>();
        final List<File> saves = new ArrayList<>();

        for (String line : Files.readAllLines(jobFile.toPath(), StandardCharsets.UTF_8)) {
            final int eq = line.indexOf('=');
            if (eq < 0) continue;
            final String key = line.substring(0, eq);
            final String val = line.substring(eq + 1);
            if ("module".equals(key)) module = new File(val);
            else if ("option".equals(key)) options.add(val);
            else if ("file".equals(key)) saves.add(new File(val));
        }
        if (module == null || saves.isEmpty()) {
            say("FATAL", "job file names no module or no saved games");
            return 2;
        }

        Info.setConfig(new StandardConfig());       // else the save records VassalVersion 1.2.3
        new HeadlessMenuManager();

        GameModule.init(new GameModule(new DataArchive(module.getPath())));
        final GameModule mod = GameModule.getGameModule();
        new ExtensionsLoader().addTo(mod);
        Localization.getInstance().translate();

        // Suppress the "enter save comments" dialog, exactly as the engine's own
        // batch refresh (GameState.saveGameRefresh) does.
        mod.getPrefs().setValue(SaveMetaData.PROMPT_LOG_COMMENT, false);

        say("READY", mod.getGameVersion() + "\t"
                + mod.getComponentsOf(ModuleExtension.class).size());

        // The engine refuses to refresh anything while the module's piece
        // definitions have GPID errors, and says only "module was saved with an
        // older vassal version" — which is not what is wrong. Ask it the same
        // question up front, and name the actual culprits, rather than rewriting
        // every scenario for no gain.
        final GpIdChecker checker = new GpIdChecker(options);
        for (final PieceSlot slot : mod.getAllDescendantComponentsOf(PieceSlot.class)) {
            checker.add(slot);
        }
        for (final PrototypesContainer pc : mod.getComponentsOf(PrototypesContainer.class)) {
            pc.getDefinitions().forEach(checker::add);
        }
        if (checker.hasErrors()) {
            for (String problem : describeGpIdErrors(mod)) say("BLOCKED", problem);
            say("SUMMARY", "0\t" + saves.size());
            return 3;
        }

        final GameState gs = mod.getGameState();
        final ChatterTail chatter = new ChatterTail(mod);

        int refreshed = 0;
        int failed = 0;
        for (int i = 0; i < saves.size(); i++) {
            final File save = saves.get(i);
            say("FILE", (i + 1) + "\t" + saves.size() + "\t" + save.getName());
            chatter.mark();
            try {
                final int warnings = refreshOne(mod, gs, save, options);
                for (String line : chatter.since()) say("LOG", line);
                say("OK", save.getName() + "\t" + warnings);
                refreshed++;
            }
            catch (Throwable t) {                   // NOPMD - one bad file must not stop the batch
                for (String line : chatter.since()) say("LOG", line);
                say("FAIL", save.getName() + "\t" + describe(t));
                failed++;
            }
        }

        gs.setup(false);                            // drop the last game's pieces and listeners
        say("SUMMARY", refreshed + "\t" + failed);
        return failed == 0 ? 0 : 1;
    }


    /**
     * Names the piece definitions the engine's {@code GpIdChecker} objects to: a
     * slot with no GPID, or two slots sharing one. Duplicates usually mean two
     * extensions allocated from the same number range, or one was copied from the
     * other; either way the engine will not refresh until they are given distinct
     * GPIDs in the editor.
     *
     * <p>Advisory only — {@code GpIdChecker.hasErrors()} makes the actual call.
     * This just re-walks the same slots to say <em>which</em> they are, which the
     * engine keeps to itself.</p>
     */
    private static List<String> describeGpIdErrors(GameModule mod) {
        final Map<String, List<String>> byId = new LinkedHashMap<>();
        final List<String> blank = new ArrayList<>();
        for (final PieceSlot slot : mod.getAllDescendantComponentsOf(PieceSlot.class)) {
            final String id = slot.getGpId() == null ? "" : slot.getGpId().trim();
            final String name = slot.getConfigureName() == null ? "(unnamed)" : slot.getConfigureName();
            if (id.isEmpty()) blank.add(name);
            else byId.computeIfAbsent(id, k -> new ArrayList<>()).add(name);
        }

        final List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byId.entrySet()) {
            if (e.getValue().size() > 1) {
                out.add("duplicate GPID " + e.getKey() + ": " + String.join(", ", e.getValue()));
            }
        }
        for (String name : blank) out.add("no GPID: " + name);
        if (out.isEmpty()) {
            out.add("the module has GPID errors the editor can pinpoint "
                    + "(run Tools \u2192 Refresh Counters in VASSAL's editor)");
        }
        return out;
    }

    /**
     * Backs up, refreshes and rewrites one saved game. Mirrors the engine's own
     * {@code PredefinedSetup.refreshWithStatus()}, but reads and writes an
     * external file instead of an entry inside the module.
     *
     * @return the engine's count of refresh anomalies for this file
     */
    private static int refreshOne(GameModule mod, GameState gs, File save, Set<String> options)
            throws IOException {

        if (!save.isFile()) throw new IOException("not a file: " + save);

        // What the engine would otherwise rebuild from the fully-loaded module:
        // the scenario's own extension list, and the set of maps it has board
        // layouts for. Captured before the refresh, reapplied after it.
        final SavedGame.PreservedState preserved =
                SavedGame.PreservedState.capture(SavedGame.open(save));

        final File backup = backupFor(save);
        Files.copy(save.toPath(), backup.toPath());
        say("BACKUP", save.getName() + "\t" + backup.getName());

        gs.setup(false);                            // clear anything left from the previous file
        mod.setRefreshingSemaphore(true);           // suppress GameState.setup() side effects
        final File tmp = File.createTempFile(save.getName() + ".", ".tmp",
                save.getAbsoluteFile().getParentFile());
        try {
            gs.setupRefresh();
            try (InputStream in = new BufferedInputStream(Files.newInputStream(save.toPath()))) {
                gs.loadGameInForeground(save.getName(), in);
            }
            gs.getAttachmentManager().resolvePendingAttachments();

            final GameRefresher refresher = new GameRefresher(mod);
            refresher.execute(options, null);

            // Write to a temp file beside the original, then move it into place, so
            // an interrupted write cannot leave a truncated .vsav at the real path.
            gs.saveGame(tmp);
            gs.updateDone();
            gs.closeGame();

            move(tmp, save);

            final SavedGame.PreservedState.Result restored = preserved.restore(save);
            if (restored.changedAnything()) {
                say("PRESERVED", save.getName() + "\t" + restored.extensionsRestored
                        + "\t" + restored.strippedBoardPickerMaps.size() + "\t"
                        + String.join(", ", restored.strippedBoardPickerMaps));
            }
            return refresher.warnings();
        }
        finally {
            mod.setRefreshingSemaphore(false);
            Files.deleteIfExists(tmp.toPath());     // no-op once moved
        }
    }

    /**
     * {@code <name>.vsav} → {@code <name>-backup.vsav}, numbering rather than
     * overwriting if that already exists, so an earlier pristine copy is never
     * destroyed by a second run.
     */
    static File backupFor(File save) {
        final String name = save.getName();
        final int dot = name.lastIndexOf('.');
        final String stem = dot > 0 ? name.substring(0, dot) : name;
        final String ext = dot > 0 ? name.substring(dot) : "";
        final File dir = save.getAbsoluteFile().getParentFile();

        File candidate = new File(dir, stem + "-backup" + ext);
        for (int n = 2; candidate.exists(); n++) {
            candidate = new File(dir, stem + "-backup-" + n + ext);
        }
        return candidate;
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
        final String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null || msg.isEmpty() ? "" : ": " + msg);
    }

    /**
     * Reads what the engine has written to its chatter since the last
     * {@link #mark()}. {@code GameRefresher.log()} and {@code GpIdChecker.chat()}
     * report there — the per-piece detail that never reaches a return value — so
     * tailing it is the only way to pass that on to the user.
     */
    private static final class ChatterTail {
        private final JTextPane pane;
        private int mark;

        ChatterTail(GameModule mod) {
            this.pane = findTextPane(mod.getChatter());
        }

        void mark() { this.mark = length(); }

        List<String> since() {
            final List<String> lines = new ArrayList<>();
            if (pane == null) return lines;
            final Document doc = pane.getDocument();
            final int len = doc.getLength();
            if (len <= mark) return lines;
            try {
                for (String line : doc.getText(mark, len - mark).split("\n")) {
                    final String trimmed = line.trim();
                    if (!trimmed.isEmpty()) lines.add(trimmed);
                }
            }
            catch (Exception e) {                   // NOPMD - diagnostics only, never fatal
                lines.add("(could not read chatter: " + describe(e) + ")");
            }
            return lines;
        }

        private int length() {
            return pane == null ? 0 : pane.getDocument().getLength();
        }

        private static JTextPane findTextPane(Component c) {
            if (c instanceof JTextPane) return (JTextPane) c;
            if (c instanceof Container) {
                for (Component child : ((Container) c).getComponents()) {
                    final JTextPane found = findTextPane(child);
                    if (found != null) return found;
                }
            }
            return null;
        }
    }
}
