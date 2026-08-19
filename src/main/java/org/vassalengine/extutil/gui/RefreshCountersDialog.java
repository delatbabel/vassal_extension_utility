/*
 * Copyright (c) 2025 VASSAL Extension Utility contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 */
package org.vassalengine.extutil.gui;

import org.vassalengine.extutil.model.RefreshOptions;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.io.File;
import java.util.List;

/**
 * The Refresh Counters options dialog, presenting the same choices in the same
 * order and wording as VASSAL's own ({@code GameRefresher.RefreshDialog}) so that
 * anyone who has used the engine's tool recognises it.
 *
 * <p>Two differences, both forced by this being a batch tool over external files:
 * the header names the scenarios about to be rewritten, and there is no Help
 * button (VASSAL's opens its bundled reference manual, which we cannot assume is
 * installed).</p>
 *
 * <p>As in the engine, "Refresh piece definitions" is checked and disabled — it is
 * what guarantees at least one main option is on — and the sub-options of
 * "Use counter names" and "Refresh decks" appear only while their parent is
 * ticked. Test mode and "delete pieces with no map" are deliberately not offered,
 * matching the engine, which has them commented out over its issues 12695 and
 * 12902.</p>
 */
public class RefreshCountersDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final JCheckBox refreshPieces =
            new JCheckBox("Refresh piece definitions with latest settings from module", true);
    private final JCheckBox nameCheck =
            new JCheckBox("Use counter names to identify unknown counters");
    private final JCheckBox fixGpid =
            new JCheckBox("Refreshed counter will adopt matching counter's Piece Id");
    private final JCheckBox labelerNameCheck =
            new JCheckBox("Use Label descriptions to match modified Text Label traits", true);
    private final JCheckBox layerNameCheck =
            new JCheckBox("Use Layer names to match modified Layer traits", true);
    private final JCheckBox rotateNameCheck =
            new JCheckBox("Use Rotator names to match modified Can Rotate traits", true);
    private final JCheckBox refreshDecks =
            new JCheckBox("Refresh decks' properties with latest settings from module");
    private final JCheckBox deleteOldDecks = new JCheckBox(
            "Delete decks which no longer exist in the module "
            + "(any contents will be left on map in a stack)");
    private final JCheckBox addNewDecks = new JCheckBox(
            "Add decks to game which have been added to the module since this game "
            + "was created (empty deck will be added)");
    private final JCheckBox fireHotkey = new JCheckBox(
            "<html>After refresh trigger Global Hotkey <i>VassalPostRefreshGHK</i></html>");

    private RefreshOptions result;

    public RefreshCountersDialog(Window owner, List<File> saves, String moduleName) {
        super(owner, "Refresh Counters", ModalityType.APPLICATION_MODAL);
        buildUi(saves, moduleName);
    }

    /** @return the chosen options, or {@code null} if the user cancelled. */
    public RefreshOptions getResult() {
        return result;
    }

    private void buildUi(List<File> saves, String moduleName) {
        final JPanel options = new JPanel();
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        options.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEtchedBorder(), new EmptyBorder(10, 12, 10, 12)));

        final JLabel header = new JLabel(
                "<html><b>Refresh Counters:</b> updates the pieces in "
                + count(saves.size(), "saved game", "saved games")
                + " to the current definitions in <b>" + escape(moduleName)
                + "</b> and its active extensions.<br>"
                + "Each scenario is backed up before it is rewritten.</html>");
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        options.add(header);
        options.add(Box.createVerticalStrut(8));
        final JSeparator sep = new JSeparator(SwingConstants.HORIZONTAL);
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
        options.add(sep);
        options.add(Box.createVerticalStrut(8));

        // Locked on, exactly as the engine locks it.
        refreshPieces.setEnabled(false);

        add(options, refreshPieces, 0);
        add(options, nameCheck, 1);
        add(options, fixGpid, 2);
        add(options, labelerNameCheck, 1);
        add(options, layerNameCheck, 1);
        add(options, rotateNameCheck, 1);
        options.add(Box.createVerticalStrut(6));
        add(options, refreshDecks, 0);
        add(options, deleteOldDecks, 1);
        add(options, addNewDecks, 1);
        options.add(Box.createVerticalStrut(6));
        add(options, fireHotkey, 0);

        // Sub-options appear only while their parent is ticked (engine behaviour).
        nameCheck.addChangeListener(e -> fixGpid.setVisible(nameCheck.isSelected()));
        refreshDecks.addChangeListener(e -> {
            deleteOldDecks.setVisible(refreshDecks.isSelected());
            addNewDecks.setVisible(refreshDecks.isSelected());
        });
        fixGpid.setVisible(false);
        deleteOldDecks.setVisible(false);
        addNewDecks.setVisible(false);

        final JButton run = new JButton("Run");
        run.addActionListener(e -> {
            result = collect();
            dispose();
        });
        final JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());

        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setBorder(new EmptyBorder(10, 0, 0, 0));
        buttons.add(run);
        buttons.add(cancel);

        final JPanel content = new JPanel(new BorderLayout());
        content.setBorder(new EmptyBorder(12, 12, 12, 12));
        content.add(options, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);

        getRootPane().setDefaultButton(run);
        pack();
        setSize(new Dimension(Math.max(620, getWidth()), getHeight()));
        setLocationRelativeTo(getOwner());
    }

    private static void add(JPanel panel, JCheckBox box, int indent) {
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.setBorder(new EmptyBorder(1, 14 * indent, 1, 0));
        panel.add(box);
    }

    private RefreshOptions collect() {
        final RefreshOptions o = new RefreshOptions();
        o.useName = nameCheck.isSelected();
        o.fixGpid = nameCheck.isSelected() && fixGpid.isSelected();
        o.useLabelerName = labelerNameCheck.isSelected();
        o.useLayerName = layerNameCheck.isSelected();
        o.useRotateName = rotateNameCheck.isSelected();
        o.refreshDecks = refreshDecks.isSelected();
        o.deleteOldDecks = refreshDecks.isSelected() && deleteOldDecks.isSelected();
        o.addNewDecks = refreshDecks.isSelected() && addNewDecks.isSelected();
        o.fireHotkey = fireHotkey.isSelected();
        return o;
    }

    static String count(int n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
