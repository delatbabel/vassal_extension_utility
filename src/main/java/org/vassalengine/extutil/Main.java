/*
 * Copyright (c) 2025 VASSAL Extension Utility contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 */
package org.vassalengine.extutil;

import org.vassalengine.extutil.gui.MainWindow;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.Image;
import java.awt.Taskbar;

/**
 * Entry point for the VASSAL Extension Utility.
 */
public class Main {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // fall back to default L&F
            }
            setTaskbarIcon();
            MainWindow window = new MainWindow();
            window.setVisible(true);
        });
    }

    /**
     * Sets the icon shown in the OS taskbar/Dock when running unpackaged (jar or
     * {@code mvn exec:java}). Installed packages carry their own platform icon;
     * the per-window icon is set in {@link MainWindow}. Best-effort: the Taskbar
     * API is unsupported on some platforms, so any failure is ignored.
     */
    private static void setTaskbarIcon() {
        try {
            if (Taskbar.isTaskbarSupported()) {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    Image icon = ImageIO.read(
                            Main.class.getResource("/icons/256x256/VASSAL-gear.png"));
                    taskbar.setIconImage(icon);
                }
            }
        } catch (Exception ignored) {
            // no taskbar icon support on this platform — window icon still applies
        }
    }
}
