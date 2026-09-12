/*
 * Copyright (c) 2025 VASSAL Extension Utility contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 */
package org.vassalengine.extutil.gui;

import org.vassalengine.extutil.model.RecentFilesStore;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.io.File;

/**
 * Asks for the three saved games a map swap needs: the scenario to keep, the
 * scenario whose board layouts are wanted, and the file to write the result to.
 *
 * <p>All three are collected in one form rather than in a sequence of choosers,
 * because none of them depends on the others and seeing them together is what
 * makes the direction of the swap ("this scenario, those maps") readable.</p>
 */
final class SwapMapsDialog {

    /** The three files the user chose. */
    static final class Choice {
        final File source;
        final File donor;
        final File target;

        Choice(File source, File donor, File target) {
            this.source = source;
            this.donor = donor;
            this.target = target;
        }
    }

    private final Window owner;
    private final RecentFilesStore recentFiles;

    private final JTextField sourceField = new JTextField(34);
    private final JTextField donorField  = new JTextField(34);
    private final JTextField targetField = new JTextField(34);

    SwapMapsDialog(Window owner, RecentFilesStore recentFiles) {
        this.owner = owner;
        this.recentFiles = recentFiles;
    }

    /** Shows the form; returns the chosen files, or {@code null} if cancelled. */
    Choice prompt() {
        final JPanel form = new JPanel(new GridBagLayout());
        final GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.LINE_START;

        addRow(form, c, 0, "Scenario to keep:", sourceField,
                e -> chooseOpen(sourceField, "Choose the Scenario to Keep"));
        addRow(form, c, 1, "Take maps from:", donorField,
                e -> chooseOpen(donorField, "Choose the Saved Game to Take the Maps From"));
        addRow(form, c, 2, "Save result as:", targetField,
                e -> chooseSave(targetField));

        final JPanel content = new JPanel(new BorderLayout(8, 10));
        content.setBorder(new EmptyBorder(12, 14, 12, 14));
        content.add(new JLabel("<html>Copies <b>every</b> board layout from one saved game into "
                + "another,<br>leaving that game's pieces and everything else untouched.<br><br>"
                + "The two originals are not modified — the result is written to a new file."
                + "</html>"), BorderLayout.NORTH);
        content.add(form, BorderLayout.CENTER);

        final JOptionPane pane = new JOptionPane(content, JOptionPane.PLAIN_MESSAGE,
                JOptionPane.OK_CANCEL_OPTION);
        final JDialog dialog = pane.createDialog(owner, "Swap Maps Between Saved Games");
        dialog.setResizable(true);

        while (true) {
            dialog.setVisible(true);
            final Object value = pane.getValue();
            if (!(value instanceof Integer) || (Integer) value != JOptionPane.OK_OPTION) {
                dialog.dispose();
                return null;
            }
            pane.setValue(JOptionPane.UNINITIALIZED_VALUE);   // so a re-show can be OK'd again
            final Choice choice = validate();
            if (choice != null) {
                dialog.dispose();
                return choice;
            }
        }
    }

    /** Checks the three paths, complaining and returning null if they don't make sense. */
    private Choice validate() {
        final File source = fileOf(sourceField);
        final File donor  = fileOf(donorField);
        File target = fileOf(targetField);

        if (source == null || donor == null || target == null) {
            warn("Please choose all three files.");
            return null;
        }
        if (!source.isFile()) { warn("Not a file:\n" + source); return null; }
        if (!donor.isFile())  { warn("Not a file:\n" + donor); return null; }
        if (source.equals(donor)) {
            warn("The two saved games are the same file — there would be nothing to swap.");
            return null;
        }
        if (!target.getName().toLowerCase().endsWith(".vsav")) {
            target = new File(target.getParentFile(), target.getName() + ".vsav");
        }
        if (target.equals(source) || target.equals(donor)) {
            warn("Please choose a different name for the result — both originals\n"
                    + "must be left in place.");
            return null;
        }
        if (target.exists()) {
            final int r = JOptionPane.showConfirmDialog(owner,
                    target.getName() + " already exists.\nOverwrite it?",
                    "Swap Maps", JOptionPane.OK_CANCEL_OPTION);
            if (r != JOptionPane.OK_OPTION) return null;
        }
        return new Choice(source, donor, target);
    }

    private void warn(String message) {
        JOptionPane.showMessageDialog(owner, message, "Swap Maps", JOptionPane.WARNING_MESSAGE);
    }

    private static File fileOf(JTextField field) {
        final String text = field.getText().trim();
        return text.isEmpty() ? null : new File(text);
    }

    private static void addRow(JPanel form, GridBagConstraints c, int row, String label,
                               JTextField field, java.awt.event.ActionListener browse) {
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        form.add(new JLabel(label), c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, c);

        final JButton browseBtn = new JButton("Browse…");
        browseBtn.addActionListener(browse);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        form.add(browseBtn, c);
    }

    private void chooseOpen(JTextField field, String title) {
        final JFileChooser fc = savedGameChooser(title, field);
        if (fc.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        final File chosen = fc.getSelectedFile();
        recentFiles.setLastDirFrom(RecentFilesStore.DIR_SAVED_GAME, chosen);
        field.setText(chosen.getPath());
        if (field == sourceField && targetField.getText().trim().isEmpty()) suggestTarget();
    }

    private void chooseSave(JTextField field) {
        final JFileChooser fc = savedGameChooser("Save Swapped Saved Game As", field);
        if (fc.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        field.setText(fc.getSelectedFile().getPath());
    }

    /** A .vsav chooser starting where the field points, else at the last-used folder. */
    private JFileChooser savedGameChooser(String title, JTextField field) {
        final JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(title);
        fc.setFileFilter(new FileNameExtensionFilter("VASSAL Saved Games (*.vsav)", "vsav"));
        final File current = fileOf(field);
        if (current != null) {
            fc.setSelectedFile(current);
        }
        else {
            File dir = recentFiles.getLastDir(RecentFilesStore.DIR_SAVED_GAME);
            if (dir == null) {
                final File source = fileOf(sourceField);
                dir = source != null ? source.getParentFile() : null;
            }
            if (dir != null && dir.isDirectory()) fc.setCurrentDirectory(dir);
        }
        return fc;
    }

    /** Proposes "&lt;scenario&gt; (swapped maps).vsav" beside the scenario being kept. */
    private void suggestTarget() {
        final File source = fileOf(sourceField);
        if (source == null) return;
        final String name = source.getName();
        final String base = name.toLowerCase().endsWith(".vsav")
                ? name.substring(0, name.length() - ".vsav".length()) : name;
        targetField.setText(new File(source.getParentFile(), base + " (swapped maps).vsav")
                .getPath());
    }

    /** One line of the confirmation list: greyed unless the map actually changes. */
    private static final class Row {
        final String text;
        final boolean change;
        Row(String text, boolean change) { this.text = text; this.change = change; }
        @Override public String toString() { return text; }
    }

    /** Greys every line that is not an actual change, as the extension list does. */
    private static final class RowRenderer extends DefaultListCellRenderer {
        @Override public java.awt.Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, false, false);
            if (value instanceof Row && !((Row) value).change) {
                setForeground(UiTheme.secondaryForeground());
            }
            return this;
        }
    }

    /**
     * Shows what the swap will do — which maps take the donor's layout, and which
     * maps only one of the two saved games has — and asks to go ahead.
     *
     * @return whether the user confirmed
     */
    static boolean confirm(Window owner, org.vassalengine.extutil.model.SavedGame.MapSwapPlan plan,
                           File source, File donor) {
        final int changed = plan.changes().size();

        final DefaultListModel<Row> model = new DefaultListModel<>();
        for (org.vassalengine.extutil.model.SavedGame.MapSwap sw : plan.swaps) {
            model.addElement(sw.isChange() ? new Row(sw.map, true)
                    : new Row(sw.map + "   (identical — no change)", false));
        }
        for (String map : plan.onlyInTarget) {
            model.addElement(new Row(map + "   (not in the donor — left as it is)", false));
        }
        for (String map : plan.onlyInDonor) {
            model.addElement(new Row(map + "   (only in the donor — nothing to replace)", false));
        }

        final JList<Row> list = new JList<>(model);
        list.setCellRenderer(new RowRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFocusable(false);
        list.setVisibleRowCount(14);
        final JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(460, 260));

        final JPanel content = new JPanel(new BorderLayout(6, 8));
        content.add(new JLabel("<html><b>" + changed + "</b> of " + plan.swaps.size()
                + " shared map(s) will take their board layout from<br>\""
                + donor.getName() + "\".<br><br>Everything else in \"" + source.getName()
                + "\" — pieces, extensions,<br>decks — is copied unchanged."
                + (changed == 0 ? "" : "<br><br><b>Note:</b> pieces keep their exact position on the map,"
                    + "<br>so a board that ends up in a different place leaves the"
                    + "<br>pieces standing on it behind at the old coordinates.")
                + "</html>"),
                BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);

        return JOptionPane.showConfirmDialog(owner, content, "Swap Maps",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE)
                == JOptionPane.OK_OPTION;
    }
}
