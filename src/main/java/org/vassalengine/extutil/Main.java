/*
 * Copyright (c) 2025 VASSAL Extension Utility contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 */
package org.vassalengine.extutil;

import org.vassalengine.extutil.gui.MainWindow;

import javax.swing.*;

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
            MainWindow window = new MainWindow();
            window.setVisible(true);
        });
    }
}
