/*
 * Copyright (c) 2025 VASSAL Extension Utility contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 */
package org.vassalengine.extutil.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.event.HyperlinkEvent;
import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Window;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * The Help menu's dialogs: <b>Users Guide</b> and <b>About</b>.
 *
 * <p>The Users Guide displays the repository's own {@code README.md}, bundled
 * into the jar at {@code /help/README.md} by the build (see the
 * {@code <resources>} section of {@code pom.xml}), so the in-application guide
 * can never drift from the shipped documentation. The developer-facing
 * sections ("Developing", and the "Changelog" pointer to a file that is not
 * bundled) are omitted from the display — that material lives in
 * {@code DEVELOPERS-GUIDE.md}.</p>
 *
 * <p>The README's Markdown is rendered by a deliberately small converter
 * covering only what the README uses — headings, ordered and nested bullet
 * lists, bold, inline code and links — hand-rolled, like {@code model/Json},
 * so the project keeps building with no new dependency. Web links open in the
 * user's browser; links to repository files are shown as plain code, since
 * those files are not present in an installed copy.</p>
 *
 * <p>The About dialog shows the version the jar was built as, read from
 * {@code /version.properties}, which Maven resource filtering stamps with
 * {@code ${project.version}} at build time.</p>
 */
public final class HelpDialogs {

    private static final Logger log = LoggerFactory.getLogger(HelpDialogs.class);

    private static final String GUIDE_RESOURCE = "/help/README.md";
    private static final String VERSION_RESOURCE = "/version.properties";

    /** README sections not shown in the in-application guide. */
    private static final Set<String> OMITTED_SECTIONS =
            new HashSet<>(Arrays.asList("Developing", "Changelog"));

    private HelpDialogs() { }

    // -----------------------------------------------------------------------
    // About
    // -----------------------------------------------------------------------

    /** The application version stamped into the jar, or "unknown". */
    public static String version() {
        Properties props = new Properties();
        try (InputStream in = HelpDialogs.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (in != null) props.load(in);
        } catch (IOException e) {
            log.warn("Could not read {}", VERSION_RESOURCE, e);
        }
        String v = props.getProperty("version", "").trim();
        return v.isEmpty() || v.startsWith("${") ? "unknown" : v;
    }

    /** Help &gt; About: the application name and version. */
    public static void showAbout(Window owner) {
        JOptionPane.showMessageDialog(owner,
                "<html><h2>VASSAL Extension Utility</h2>"
                + "Version <b>" + version() + "</b><br><br>"
                + "A desktop utility for working with VASSAL game module files<br>"
                + "(<tt>.vmod</tt>) and their extensions (<tt>.vmdx</tt>).<br><br>"
                + "Licensed under the GNU Lesser General Public License v2.1,<br>"
                + "the same as the VASSAL engine.</html>",
                "About VASSAL Extension Utility", JOptionPane.INFORMATION_MESSAGE);
    }

    // -----------------------------------------------------------------------
    // Users Guide
    // -----------------------------------------------------------------------

    /** Help &gt; Users Guide: the bundled README, developer sections omitted. */
    public static void showUsersGuide(Window owner) {
        final String markdown = readGuide();
        if (markdown == null) {
            JOptionPane.showMessageDialog(owner,
                    "The Users Guide is missing from this build.",
                    "Users Guide", JOptionPane.WARNING_MESSAGE);
            return;
        }

        final JEditorPane pane = new JEditorPane("text/html", toHtml(markdown));
        pane.setEditable(false);
        pane.setCaretPosition(0);
        pane.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                browse(e.getDescription());
            }
        });

        final JDialog dialog = new JDialog(owner, "Users Guide",
                Dialog.ModalityType.MODELESS);
        dialog.add(new JScrollPane(pane));
        dialog.setSize(new Dimension(760, 640));
        dialog.setLocationRelativeTo(owner);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setVisible(true);
    }

    private static void browse(String url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception e) {                     // NOPMD - best-effort convenience
            log.warn("Could not open {}", url, e);
        }
    }

    /** The bundled README with the omitted sections removed, or null. */
    static String readGuide() {
        try (InputStream in = HelpDialogs.class.getResourceAsStream(GUIDE_RESOURCE)) {
            if (in == null) return null;
            StringBuilder kept = new StringBuilder();
            boolean skipping = false;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("## ")) {
                        skipping = OMITTED_SECTIONS.contains(line.substring(3).trim());
                    }
                    if (!skipping) kept.append(line).append('\n');
                }
            }
            return kept.toString();
        } catch (IOException e) {
            log.warn("Could not read {}", GUIDE_RESOURCE, e);
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Markdown rendering — only the subset the README uses
    // -----------------------------------------------------------------------

    /** Converts the README's Markdown subset to the HTML 3.2 Swing renders. */
    static String toHtml(String markdown) {
        StringBuilder html = new StringBuilder(
                "<html><body style='font-family:sans-serif'>");
        // open block elements, innermost last: "ol", "ul", "p"
        java.util.Deque<String> open = new java.util.ArrayDeque<>();

        for (String line : markdown.split("\n", -1)) {
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                closeTo(html, open, null);
                continue;
            }
            if (trimmed.startsWith("# ")) {
                closeTo(html, open, null);
                html.append("<h1>").append(inline(trimmed.substring(2))).append("</h1>");
                continue;
            }
            if (trimmed.startsWith("## ")) {
                closeTo(html, open, null);
                html.append("<h2>").append(inline(trimmed.substring(3))).append("</h2>");
                continue;
            }
            if (trimmed.matches("\\d+\\. .*")) {
                closeTo(html, open, "ol");
                html.append("<li>").append(inline(trimmed.replaceFirst("\\d+\\. ", "")));
                continue;
            }
            if (trimmed.startsWith("- ")) {
                // indented bullets nest inside the current ordered item
                if (line.startsWith(" ") && "ol".equals(outermostList(open))) {
                    if (!"ul".equals(open.peekLast())) {
                        html.append("<ul>");
                        open.addLast("ul");
                    }
                } else {
                    closeTo(html, open, "ul");
                }
                html.append("<li>").append(inline(trimmed.substring(2)));
                continue;
            }
            // plain text: a paragraph, or the continuation of a list item
            if (open.isEmpty()) {
                html.append("<p>");
                open.addLast("p");
            } else {
                html.append(' ');
            }
            html.append(inline(trimmed));
        }
        closeTo(html, open, null);
        return html.append("</body></html>").toString();
    }

    /** Closes open blocks (innermost first) until {@code target} is the current
     *  block, opening it if nothing suitable remains open. */
    private static void closeTo(StringBuilder html, java.util.Deque<String> open,
                                String target) {
        while (!open.isEmpty() && !open.peekLast().equals(target)) {
            html.append("</").append(open.removeLast()).append('>');
        }
        if (target != null && open.isEmpty()) {
            html.append('<').append(target).append('>');
            open.addLast(target);
        }
    }

    private static String outermostList(java.util.Deque<String> open) {
        return open.isEmpty() ? null : open.getFirst();
    }

    /** Escapes HTML, then renders bold, inline code, and links. */
    private static String inline(String text) {
        String s = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
        s = s.replaceAll("`([^`]+)`", "<code>$1</code>");
        // web links open in the browser; repository-file links are not present
        // in an installed copy, so their text is shown as plain code instead
        s = s.replaceAll("\\[([^]]+)]\\((https?://[^)]+)\\)", "<a href='$2'>$1</a>");
        s = s.replaceAll("\\[([^]]+)]\\([^)]+\\)", "<code>$1</code>");
        return s;
    }
}
