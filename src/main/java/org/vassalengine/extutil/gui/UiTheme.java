/*
 * Copyright (c) 2025 VASSAL Extension Utility contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 */
package org.vassalengine.extutil.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Properties;

/**
 * The application's look and feel: the theme the user picks from
 * <b>View &rarr; Theme</b>, remembered between sessions.
 *
 * <p>Three choices. {@link #LIGHT} and {@link #DARK} come from
 * <a href="https://www.formdev.com/flatlaf/">FlatLaf</a> — the plain
 * {@code FlatLightLaf} / {@code FlatDarkLaf} pair rather than any of its
 * IDE-flavoured variants, because the point is a clean, neutral window and not a
 * styled one — and {@link #LIGHT} is the default. {@link #SYSTEM} is the platform
 * look and feel, which is what the application used before and is kept for users
 * who want it to match the rest of their desktop.</p>
 *
 * <p><b>Colours must be asked for, not remembered.</b> A theme can change while
 * the application is running, so anything that paints outside the standard
 * component colours reads {@link #secondaryForeground()} or one of the
 * {@code border*()} colours at paint time. A {@code static final Color} captured
 * at class-load would be a light-theme colour on a dark window (and, before this,
 * a hard-coded {@code Color.GRAY} that all but vanished against a dark
 * background).</p>
 *
 * <p>The choice is stored in {@code ~/.vassal-extension-utility/ui.properties}
 * under {@code theme}. All disk I/O fails soft, as in
 * {@link org.vassalengine.extutil.model.RecentFilesStore}: an unreadable or absent
 * file simply means {@link #LIGHT}, and a failed write is logged and no more —
 * the theme is a convenience and must never stop the application starting.</p>
 */
public enum UiTheme {

    /** FlatLaf's plain light theme. */
    LIGHT("Light") {
        @Override boolean install() { return FlatLaf.setup(new FlatLightLaf()); }
    },
    /** FlatLaf's plain dark theme. */
    DARK("Dark") {
        @Override boolean install() { return FlatLaf.setup(new FlatDarkLaf()); }
    },
    /**
     * Whatever the platform provides — the Windows or macOS native look, GTK on
     * Linux — for users who would rather the application matched the rest of
     * their desktop than looked the same on every one. This was the application's
     * only look and feel before the two above were added.
     */
    SYSTEM("System") {
        @Override boolean install() {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                return true;
            } catch (Exception e) {
                log.warn("Could not install the system look and feel: {}", e.toString());
                return false;
            }
        }
    };

    private static final Logger log = LoggerFactory.getLogger(UiTheme.class);

    private static final String CONFIG_DIR  = ".vassal-extension-utility";
    private static final String CONFIG_FILE = "ui.properties";
    private static final String KEY_THEME   = "theme";

    /** The theme to fall back on: nothing saved, or an unreadable/unknown value. */
    public static final UiTheme DEFAULT = LIGHT;

    /** The theme in force. Tracked, not derived: SYSTEM is neither light nor dark. */
    private static UiTheme active = DEFAULT;

    private final String displayName;

    UiTheme(String displayName) {
        this.displayName = displayName;
    }

    /** Installs this theme's look and feel; false if it could not be set. */
    abstract boolean install();

    /** The name shown in the View menu. */
    public String displayName() { return displayName; }

    // -----------------------------------------------------------------------
    // Installing
    // -----------------------------------------------------------------------

    /**
     * Installs this theme. Call before the first window is created; to change
     * theme with windows already on screen, use {@link #applyAndRefresh}.
     *
     * @return whether it was installed (a failure leaves the current one in place)
     */
    public boolean apply() {
        if (!install()) {
            log.warn("Could not install the {} theme; keeping {}",
                    displayName, UIManager.getLookAndFeel().getName());
            return false;
        }
        active = this;
        return true;
    }

    /**
     * Installs this theme and repaints every open window in it.
     *
     * <p>{@link FlatLaf#updateUI()} re-runs {@code updateComponentTreeUI} over all
     * windows, which is enough for every component that takes its colours from the
     * look and feel. It is not enough for a colour this application chose itself
     * and handed to a component — a {@code TitledBorder}'s line colour, say — so
     * those are rebuilt by {@code themeChanged()} on the windows that own them.</p>
     */
    public boolean applyAndRefresh() {
        if (!apply()) return false;
        if (UIManager.getLookAndFeel() instanceof FlatLaf) {
            FlatLaf.updateUI();
        }
        else {
            // FlatLaf.updateUI() is for its own themes; the platform look and feel
            // has to be pushed through each window tree by hand.
            for (Window w : Window.getWindows()) {
                SwingUtilities.updateComponentTreeUI(w);
                w.validate();
                w.repaint();
            }
        }
        for (Window w : Window.getWindows()) {
            if (w instanceof ThemeAware) ((ThemeAware) w).themeChanged();
        }
        return true;
    }

    /** A window holding colours of its own, which a theme change must rebuild. */
    public interface ThemeAware {
        void themeChanged();
    }

    /** Installs the saved theme (or {@link #DEFAULT}); returns the one installed. */
    public static UiTheme applySaved() {
        final UiTheme theme = load();
        theme.apply();
        return theme;
    }

    /** The theme currently installed. */
    public static UiTheme current() { return active; }

    /**
     * Whether the current look and feel is a dark one — asked of the actual
     * {@code Panel.background} rather than of {@link #DARK}, because
     * {@link #SYSTEM} can be either: a desktop set to a dark GTK theme gives a
     * dark window, and the colours picked below have to follow the window.
     */
    public static boolean isDark() {
        final Color bg = UIManager.getColor("Panel.background");
        if (bg == null) return active == DARK;
        // Rec. 601 luma; below half-bright counts as dark.
        return (0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue()) < 128;
    }

    // -----------------------------------------------------------------------
    // Theme-aware colours
    // -----------------------------------------------------------------------

    /**
     * The colour for text that is present but secondary — an inactive extension,
     * an inherited (module) tree node, a map whose layout will not change. Taken
     * from the look and feel's own disabled-text colour so it is legible in both
     * themes, which a fixed grey is not.
     */
    public static Color secondaryForeground() {
        Color c = UIManager.getColor("Label.disabledForeground");
        if (c == null) c = UIManager.getColor("textInactiveText");
        return c != null ? c : Color.GRAY;
    }

    /** Panel border colour before a role is assigned. */
    public static Color borderDefault() {
        final Color c = UIManager.getColor("Component.borderColor");
        return c != null ? c : Color.GRAY;
    }

    /** Panel border colour marking the source (left) panel. */
    public static Color borderSource() {
        return isDark() ? new Color(90, 165, 255) : new Color(0, 100, 200);
    }

    /** Panel border colour marking the target (right) panel. */
    public static Color borderTarget() {
        return isDark() ? new Color(80, 200, 120) : new Color(0, 150, 50);
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    /** The saved theme, or {@link #DEFAULT} when nothing usable is stored. */
    public static UiTheme load() {
        final File cfg = configFile();
        if (!cfg.isFile()) return DEFAULT;
        final Properties p = new Properties();
        try (InputStream in = Files.newInputStream(cfg.toPath())) {
            p.load(in);
        } catch (IOException e) {
            log.debug("Could not read {}: {}", cfg, e.toString());
            return DEFAULT;
        }
        final String name = p.getProperty(KEY_THEME, "").trim();
        for (UiTheme t : values()) {
            if (t.name().equalsIgnoreCase(name)) return t;
        }
        return DEFAULT;
    }

    /** Remembers this theme for the next session. Failures are logged only. */
    public void save() {
        final File cfg = configFile();
        final Properties p = new Properties();
        try {
            Files.createDirectories(cfg.getParentFile().toPath());
            try (OutputStream out = Files.newOutputStream(cfg.toPath())) {
                p.setProperty(KEY_THEME, name());
                p.store(out, "VASSAL Extension Utility — user interface");
            }
        } catch (IOException e) {
            log.warn("Could not write {}: {}", cfg, e.toString());
        }
    }

    private static File configFile() {
        return new File(new File(System.getProperty("user.home"), CONFIG_DIR), CONFIG_FILE);
    }

}
