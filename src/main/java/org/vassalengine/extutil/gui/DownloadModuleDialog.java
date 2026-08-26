/*
 * Copyright (c) 2025 VASSAL Extension Utility contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 */
package org.vassalengine.extutil.gui;

import org.vassalengine.extutil.model.GameLibrary;
import org.vassalengine.extutil.model.SavedGame;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Downloads a module and its extensions from the VASSAL game library.
 *
 * <p>The flow is a sequence of prompts rather than one big form, because each step
 * depends on the previous one: what the library actually offers decides which
 * module the user can pick, and the module's filename decides the name of the
 * extensions folder.</p>
 *
 * <ol>
 *   <li>the project — a library page URL or the bare project name;</li>
 *   <li>which module, if the project publishes more than one (the WiF project has
 *       both a 31 MB module and a 7 KB placeholder pointing at Google Drive, so
 *       picking automatically would be a coin toss);</li>
 *   <li>the folder to download into;</li>
 *   <li>optionally a saved game, to fetch only the extensions it names.</li>
 * </ol>
 *
 * <p>Extensions go in a sibling {@code <module>_ext} folder — the same convention
 * the rest of this application and VASSAL itself use — created on demand. Only
 * the newest copy of each extension is fetched; see
 * {@link GameLibrary.Package#latestFiles()} for why that is decided per file
 * rather than per release.</p>
 */
public final class DownloadModuleDialog {

    private final Window owner;

    public DownloadModuleDialog(Window owner) { this.owner = owner; }

    /** Runs the whole flow. Returns a short summary for the status bar. */
    public String run(File startDir) {
        final String input = (String) JOptionPane.showInputDialog(owner,
                "<html>Paste the library page for the module, or just its project name:<br>"
                + "<tt>https://vassalengine.org/library/projects/<b>Project_Name</b></tt><br><br>"
                + "Browse the library at <tt>https://vassalengine.org/library/projects</tt></html>",
                "Download Module from Library", JOptionPane.QUESTION_MESSAGE, null, null, "");
        if (input == null || input.trim().isEmpty()) return null;

        final String project = GameLibrary.projectNameFrom(input);
        final GameLibrary library = new GameLibrary(null);

        final GameLibrary.Project info;
        try {
            info = fetchWithProgress(library, project);
        }
        catch (Exception e) {                       // NOPMD - report to the user, not the log
            JOptionPane.showMessageDialog(owner,
                    "<html>Could not read <b>" + escape(project) + "</b> from the library:<br><br>"
                    + escape(String.valueOf(e.getMessage())) + "</html>",
                    "Library Unavailable", JOptionPane.ERROR_MESSAGE);
            return "Library lookup failed for " + project + ".";
        }
        if (info == null) return null;              // cancelled

        // --- which module -------------------------------------------------
        final List<GameLibrary.RemoteFile> modules = new ArrayList<>();
        for (GameLibrary.Package p : info.modulePackages()) {
            for (GameLibrary.RemoteFile f : p.latestFiles()) {
                if (f.isModule()) modules.add(f);
            }
        }
        if (modules.isEmpty()) {
            JOptionPane.showMessageDialog(owner,
                    "<html><b>" + escape(project) + "</b> publishes no <tt>.vmod</tt> file.</html>",
                    "No Module Found", JOptionPane.WARNING_MESSAGE);
            return "No module published by " + project + ".";
        }
        final GameLibrary.RemoteFile module;
        if (modules.size() == 1) {
            module = modules.get(0);
        }
        else {
            final Object[] choices = modules.toArray();
            final Object picked = JOptionPane.showInputDialog(owner,
                    "This project publishes more than one module. Which one?",
                    "Choose Module", JOptionPane.QUESTION_MESSAGE, null, choices, choices[0]);
            if (picked == null) return null;
            module = (GameLibrary.RemoteFile) picked;
        }

        // --- where to put it ----------------------------------------------
        final JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Choose the folder to download into");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (startDir != null && startDir.isDirectory()) fc.setCurrentDirectory(startDir);
        if (fc.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return null;
        final File dir = fc.getSelectedFile();

        // --- optional scenario filter -------------------------------------
        List<GameLibrary.RemoteFile> extensions = info.latestExtensions();
        Set<String> missingFromLibrary = new LinkedHashSet<>();
        String scenarioNote = "";
        if (!extensions.isEmpty()) {
            final int scope = JOptionPane.showConfirmDialog(owner,
                    "<html>This project has <b>" + extensions.size()
                    + "</b> extension(s).<br><br>Download <b>all</b> of them?<br><br>"
                    + "Choose <b>No</b> to pick a saved game and fetch only the extensions "
                    + "it needs.</html>",
                    "Extensions", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (scope == JOptionPane.CANCEL_OPTION || scope == JOptionPane.CLOSED_OPTION) {
                return null;
            }
            if (scope == JOptionPane.NO_OPTION) {
                final JFileChooser sc = new JFileChooser();
                sc.setDialogTitle("Choose a saved game");
                sc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                        "VASSAL Saved Games (*.vsav)", "vsav"));
                if (startDir != null && startDir.isDirectory()) sc.setCurrentDirectory(startDir);
                if (sc.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return null;
                final Set<String> needed;
                try {
                    needed = SavedGame.open(sc.getSelectedFile()).getExtensionNames();
                }
                catch (Exception e) {               // NOPMD - user-facing
                    JOptionPane.showMessageDialog(owner,
                            "Could not read that saved game: " + e.getMessage(),
                            "Unreadable Saved Game", JOptionPane.ERROR_MESSAGE);
                    return null;
                }
                final List<GameLibrary.RemoteFile> filtered = new ArrayList<>();
                final Set<String> available = new LinkedHashSet<>();
                for (GameLibrary.RemoteFile f : extensions) {
                    available.add(f.extensionName());
                    if (needed.contains(f.extensionName())) filtered.add(f);
                }
                for (String n : needed) {
                    if (!available.contains(n)) missingFromLibrary.add(n);
                }
                extensions = filtered;
                scenarioNote = " for the chosen scenario";
                if (!missingFromLibrary.isEmpty()) {
                    final int go = JOptionPane.showConfirmDialog(owner,
                            "<html>The scenario names <b>" + missingFromLibrary.size()
                            + "</b> extension(s) the library does not publish:<br><br><tt>"
                            + escape(String.join("<br>", missingFromLibrary))
                            + "</tt><br><br>Continue with the "
                            + extensions.size() + " that are available?</html>",
                            "Some Extensions Unavailable",
                            JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (go != JOptionPane.OK_OPTION) return null;
                }
            }
        }

        // --- confirm and go -----------------------------------------------
        final File extDir = extensionsDirFor(dir, module.filename);
        final StringBuilder ask = new StringBuilder("<html>Download into <tt>")
                .append(escape(dir.getAbsolutePath())).append("</tt>:<br><br><b>")
                .append(escape(module.filename)).append("</b> (")
                .append(mb(module.size)).append(")");
        if (!extensions.isEmpty()) {
            long total = 0;
            for (GameLibrary.RemoteFile f : extensions) total += Math.max(f.size, 0);
            ask.append("<br>and <b>").append(extensions.size()).append("</b> extension(s)")
               .append(scenarioNote).append(" (").append(mb(total)).append(") into <tt>")
               .append(escape(extDir.getName())).append("/</tt>");
        }
        ask.append("</html>");
        if (JOptionPane.showConfirmDialog(owner, ask.toString(), "Confirm Download",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE)
                != JOptionPane.OK_OPTION) {
            return null;
        }
        return downloadAll(library, module, extensions, dir, extDir, missingFromLibrary);
    }

    /** {@code Foo.vmod} → {@code <dir>/Foo_ext}, the convention VASSAL expects. */
    static File extensionsDirFor(File dir, String moduleFilename) {
        String stem = moduleFilename;
        if (stem.toLowerCase(java.util.Locale.ROOT).endsWith(".vmod")) {
            stem = stem.substring(0, stem.length() - ".vmod".length());
        }
        return new File(dir, stem + "_ext");
    }

    private GameLibrary.Project fetchWithProgress(GameLibrary library, String project)
            throws Exception {
        final JDialog d = busy("Reading <b>" + escape(project) + "</b> from the library…");
        final SwingWorker<GameLibrary.Project, Void> w =
                new SwingWorker<GameLibrary.Project, Void>() {
            @Override protected GameLibrary.Project doInBackground() throws Exception {
                return library.fetchProject(project);
            }
            @Override protected void done() { d.dispose(); }
        };
        w.execute();
        d.setVisible(true);
        return w.get();
    }

    private String downloadAll(GameLibrary library, GameLibrary.RemoteFile module,
                               List<GameLibrary.RemoteFile> extensions,
                               File dir, File extDir, Set<String> unavailable) {
        final List<GameLibrary.RemoteFile> queue = new ArrayList<>();
        queue.add(module);
        queue.addAll(extensions);

        final JDialog d = new JDialog(owner, "Downloading", Dialog.ModalityType.APPLICATION_MODAL);
        d.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        final JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        final JTextArea logArea = new JTextArea(12, 66);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        final JButton stop = new JButton("Stop");
        final JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(stop);
        final JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(new EmptyBorder(12, 12, 12, 12));
        content.add(bar, BorderLayout.NORTH);
        content.add(new JScrollPane(logArea), BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        d.setContentPane(content);
        d.pack();
        d.setLocationRelativeTo(owner);

        final int[] okFailed = {0, 0};
        final SwingWorker<Void, String> worker = new SwingWorker<Void, String>() {
            @Override protected Void doInBackground() {
                int n = 0;
                for (GameLibrary.RemoteFile f : queue) {
                    if (isCancelled()) break;
                    n++;
                    final File into = f.isExtension() ? extDir : dir;
                    final int index = n;
                    publish("[" + index + "/" + queue.size() + "] " + f.filename
                            + "  (" + mb(f.size) + ", release " + f.releaseVersion + ")\n");
                    try {
                        final File got = library.download(f, into, (name, done, total) -> {
                            if (isCancelled()) return false;
                            if (total > 0) {
                                setProgress((int) Math.min(100, done * 100 / total));
                            }
                            return true;
                        });
                        if (got == null) { publish("    cancelled\n"); break; }
                        okFailed[0]++;
                    }
                    catch (Exception e) {           // NOPMD - continue with the rest
                        okFailed[1]++;
                        publish("    FAILED: " + e.getMessage() + "\n");
                    }
                }
                return null;
            }
            @Override protected void process(List<String> chunks) {
                for (String c : chunks) logArea.append(c);
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
            @Override protected void done() { d.dispose(); }
        };
        worker.addPropertyChangeListener(ev -> {
            if ("progress".equals(ev.getPropertyName())) {
                bar.setValue((Integer) ev.getNewValue());
            }
        });
        stop.addActionListener(e -> worker.cancel(true));
        worker.execute();
        d.setVisible(true);

        final StringBuilder done = new StringBuilder("<html>Downloaded <b>")
                .append(okFailed[0]).append("</b> file(s) into <tt>")
                .append(escape(dir.getAbsolutePath())).append("</tt>.");
        if (okFailed[1] > 0) done.append("<br><b>").append(okFailed[1]).append("</b> failed.");
        if (!unavailable.isEmpty()) {
            done.append("<br><br>Not published by the library: <tt>")
                .append(escape(String.join(", ", unavailable))).append("</tt>");
        }
        done.append("</html>");
        JOptionPane.showMessageDialog(owner, done.toString(), "Download Complete",
                okFailed[1] > 0 ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
        return "Downloaded " + okFailed[0] + " file(s)"
                + (okFailed[1] > 0 ? ", " + okFailed[1] + " failed" : "") + ".";
    }

    private JDialog busy(String html) {
        final JDialog d = new JDialog(owner, "Working…", Dialog.ModalityType.APPLICATION_MODAL);
        d.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        final JPanel p = new JPanel(new BorderLayout(10, 10));
        p.setBorder(new EmptyBorder(16, 20, 16, 20));
        p.add(new JLabel("<html>" + html + "</html>"), BorderLayout.NORTH);
        final JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        p.add(bar, BorderLayout.CENTER);
        d.setContentPane(p);
        d.pack();
        d.setMinimumSize(new Dimension(380, d.getHeight()));
        d.setLocationRelativeTo(owner);
        return d;
    }

    private static String mb(long bytes) {
        if (bytes < 0) return "size unknown";
        return bytes < 1024 * 1024
                ? String.format("%.0f KB", bytes / 1024.0)
                : String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
