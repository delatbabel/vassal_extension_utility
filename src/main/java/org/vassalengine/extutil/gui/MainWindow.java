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
import org.vassalengine.extutil.model.ComponentNode;
import org.vassalengine.extutil.model.RecentFilesStore;
import org.vassalengine.extutil.model.VassalArchive;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Main application window.
 *
 * Layout:
 *   - Split pane: left panel (module or any archive), right panel (extension or any archive)
 *   - Toolbar: open buttons, move buttons, copy buttons, delete buttons, save button
 *   - Status bar
 *
 * Multi-selection workflow:
 *   1. Open a module (left) and extension (right) via File menu or toolbar buttons.
 *   2. Select one or more components in the source panel:
 *      - Click to select one item.
 *      - Shift-click to extend the selection as a range.
 *      - Ctrl-click to add or remove individual items.
 *      - Right-click → "Search and select…" to select by partial name match.
 *      - Right-click → "Delete" to permanently remove the current selection
 *        (with its whole subtree) from this panel's archive.
 *   3. Single-click one component in the other panel as the destination parent.
 *      (If nothing is selected there, you will instead be offered the option to
 *      recreate each source component's parent path — every ancestor up to the
 *      root, without their other children — in the target panel and transfer the
 *      components into the recreated location.)
 *   4. Either:
 *      - Click "Move →" or "← Move" to move all selected source components
 *        (with their children) into the destination parent.  The originals are
 *        removed from the source.
 *      - Click "Copy →" or "← Copy" to duplicate the selected components
 *        (with their children) into the destination parent, leaving the
 *        originals in place.
 *      In both cases the whole selected subtree is carried and all images it
 *      references are copied to the destination archive.
 *   5. Click "Delete (left)" / "Delete (right)" to permanently remove the
 *      current selection (with its whole subtree) from that panel's archive.
 *   6. File > Save All (Ctrl+S) to write changes to disk.
 */
public class MainWindow extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(MainWindow.class);

    private final ArchivePanel leftPanel  = new ArchivePanel();
    private final ArchivePanel rightPanel = new ArchivePanel();
    private final JLabel statusBar = new JLabel(" ");

    private final RecentFilesStore recentFiles = new RecentFilesStore();
    private final JMenu recentMenu = new JMenu("Open Recent …");

    public MainWindow() {
        super("VASSAL Extension Utility");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setIconImages(loadAppIcons());
        leftPanel.setDeleteHandler(() -> deleteSelected(leftPanel));
        rightPanel.setDeleteHandler(() -> deleteSelected(rightPanel));
        setJMenuBar(buildMenuBar());

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        split.setResizeWeight(0.5);
        split.setDividerLocation(0.5);

        statusBar.setBorder(new EmptyBorder(2, 6, 2, 6));
        statusBar.setFont(statusBar.getFont().deriveFont(11f));

        getContentPane().setLayout(new BorderLayout(4, 4));
        getContentPane().add(buildToolbar(), BorderLayout.NORTH);
        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(statusBar, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(null);
        setMinimumSize(new Dimension(800, 500));
    }

    // -----------------------------------------------------------------------
    // Application icon
    // -----------------------------------------------------------------------

    /** Sizes of the bundled VASSAL-gear PNGs under /icons/&lt;n&gt;x&lt;n&gt;/. */
    private static final int[] ICON_SIZES = {16, 24, 32, 48, 64, 128, 256};

    /**
     * Loads the application icon at every bundled size so the window manager,
     * taskbar, and Alt-Tab switcher each pick the resolution they need
     * ({@link Window#setIconImages}). Missing/unreadable icons are skipped so a
     * packaging slip never stops the app from starting.
     */
    private List<Image> loadAppIcons() {
        List<Image> icons = new ArrayList<>();
        for (int size : ICON_SIZES) {
            String path = "/icons/" + size + "x" + size + "/VASSAL-gear.png";
            try (java.io.InputStream in = MainWindow.class.getResourceAsStream(path)) {
                if (in != null) {
                    icons.add(javax.imageio.ImageIO.read(in));
                }
            } catch (Exception e) {
                log.warn("Could not load application icon {}", path, e);
            }
        }
        return icons;
    }

    // -----------------------------------------------------------------------
    // Menu
    // -----------------------------------------------------------------------

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic('F');

        JMenuItem openMod = new JMenuItem("Open Module (left)…");
        openMod.setAccelerator(KeyStroke.getKeyStroke("ctrl M"));
        openMod.addActionListener(e -> openFile(leftPanel, false));

        JMenuItem openExt = new JMenuItem("Open Extension (right)…");
        openExt.setAccelerator(KeyStroke.getKeyStroke("ctrl E"));
        openExt.addActionListener(e -> openFile(rightPanel, true));

        JMenuItem openLeft = new JMenuItem("Open any file (left)…");
        openLeft.addActionListener(e -> openFile(leftPanel, null));

        JMenuItem openRight = new JMenuItem("Open any file (right)…");
        openRight.addActionListener(e -> openFile(rightPanel, null));

        JMenuItem newExt = new JMenuItem("New Extension (right)");
        newExt.setAccelerator(KeyStroke.getKeyStroke("ctrl N"));
        newExt.addActionListener(e -> newExtension());

        JMenuItem saveAll = new JMenuItem("Save All");
        saveAll.setAccelerator(KeyStroke.getKeyStroke("ctrl S"));
        saveAll.addActionListener(e -> saveAll());

        JMenuItem saveAsExt = new JMenuItem("Save Extension As… (right)");
        saveAsExt.setAccelerator(KeyStroke.getKeyStroke("ctrl shift S"));
        saveAsExt.addActionListener(e -> saveArchiveAs(rightPanel));

        JMenuItem quit = new JMenuItem("Quit");
        quit.setAccelerator(KeyStroke.getKeyStroke("ctrl Q"));
        quit.addActionListener(e -> { if (confirmUnsavedChanges()) System.exit(0); });

        fileMenu.add(openMod);
        fileMenu.add(openExt);
        fileMenu.addSeparator();
        fileMenu.add(openLeft);
        fileMenu.add(openRight);
        fileMenu.add(recentMenu);
        fileMenu.addSeparator();
        fileMenu.add(newExt);
        rebuildRecentMenu();
        fileMenu.addSeparator();
        fileMenu.add(saveAll);
        fileMenu.add(saveAsExt);
        fileMenu.addSeparator();
        fileMenu.add(quit);

        bar.add(fileMenu);

        JMenu toolsMenu = new JMenu("Tools");
        toolsMenu.setMnemonic('T');

        JMenuItem unusedLeft = new JMenuItem("Remove Unused Images (left)…");
        unusedLeft.addActionListener(e -> removeUnusedImages(leftPanel));
        JMenuItem unusedRight = new JMenuItem("Remove Unused Images (right)…");
        unusedRight.addActionListener(e -> removeUnusedImages(rightPanel));

        JMenuItem repairLeft = new JMenuItem("Repair Double-Wrapped Extension Elements (left)…");
        repairLeft.addActionListener(e -> repairDoubleWrappedExtensionElements(leftPanel));
        JMenuItem repairRight = new JMenuItem("Repair Double-Wrapped Extension Elements (right)…");
        repairRight.addActionListener(e -> repairDoubleWrappedExtensionElements(rightPanel));

        JMenuItem propsLeft = new JMenuItem("Edit Extension Properties (left)…");
        propsLeft.addActionListener(e -> editExtensionProperties(leftPanel));
        JMenuItem propsRight = new JMenuItem("Edit Extension Properties (right)…");
        propsRight.addActionListener(e -> editExtensionProperties(rightPanel));

        toolsMenu.add(unusedLeft);
        toolsMenu.add(unusedRight);
        toolsMenu.addSeparator();
        toolsMenu.add(repairLeft);
        toolsMenu.add(repairRight);
        toolsMenu.addSeparator();
        toolsMenu.add(propsLeft);
        toolsMenu.add(propsRight);
        bar.add(toolsMenu);

        return bar;
    }

    // -----------------------------------------------------------------------
    // Toolbar
    // -----------------------------------------------------------------------

    private JPanel buildToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));

        JButton openModBtn = new JButton("Open Module (left)");
        openModBtn.addActionListener(e -> openFile(leftPanel, false));
        toolbar.add(openModBtn);

        JButton openExtBtn = new JButton("Open Extension (right)");
        openExtBtn.addActionListener(e -> openFile(rightPanel, true));
        toolbar.add(openExtBtn);

        JButton showExtBtn = new JButton("Show Extensions");
        showExtBtn.setToolTipText(
                "List the extensions (active and inactive) for the module in the LEFT panel");
        showExtBtn.addActionListener(e -> showExtensions());
        toolbar.add(showExtBtn);

        toolbar.add(new JSeparator(SwingConstants.VERTICAL));

        JButton moveRight = new JButton("Move →");
        moveRight.setToolTipText(
                "Move all selected components from LEFT panel into selected parent in RIGHT panel");
        moveRight.addActionListener(e -> performTransfer(leftPanel, rightPanel, false));
        toolbar.add(moveRight);

        JButton moveLeft = new JButton("← Move");
        moveLeft.setToolTipText(
                "Move all selected components from RIGHT panel into selected parent in LEFT panel");
        moveLeft.addActionListener(e -> performTransfer(rightPanel, leftPanel, false));
        toolbar.add(moveLeft);

        toolbar.add(new JSeparator(SwingConstants.VERTICAL));

        JButton copyRight = new JButton("Copy →");
        copyRight.setToolTipText(
                "Copy all selected components from LEFT panel into selected parent in RIGHT panel "
                + "(child components are not copied)");
        copyRight.addActionListener(e -> performTransfer(leftPanel, rightPanel, true));
        toolbar.add(copyRight);

        JButton copyLeft = new JButton("← Copy");
        copyLeft.setToolTipText(
                "Copy all selected components from RIGHT panel into selected parent in LEFT panel "
                + "(child components are not copied)");
        copyLeft.addActionListener(e -> performTransfer(rightPanel, leftPanel, true));
        toolbar.add(copyLeft);

        toolbar.add(new JSeparator(SwingConstants.VERTICAL));

        JButton deleteLeftBtn = new JButton("Delete (left)");
        deleteLeftBtn.setToolTipText(
                "Delete all selected components (with their children) from the LEFT panel");
        deleteLeftBtn.addActionListener(e -> deleteSelected(leftPanel));
        toolbar.add(deleteLeftBtn);

        JButton deleteRightBtn = new JButton("Delete (right)");
        deleteRightBtn.setToolTipText(
                "Delete all selected components (with their children) from the RIGHT panel");
        deleteRightBtn.addActionListener(e -> deleteSelected(rightPanel));
        toolbar.add(deleteRightBtn);

        toolbar.add(new JSeparator(SwingConstants.VERTICAL));

        JButton saveBtn = new JButton("Save All");
        saveBtn.addActionListener(e -> saveAll());
        toolbar.add(saveBtn);

        return toolbar;
    }

    // -----------------------------------------------------------------------
    // File opening
    // -----------------------------------------------------------------------

    private void openFile(ArchivePanel panel, Boolean extensionOnly) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Open VASSAL Archive");

        if (Boolean.TRUE.equals(extensionOnly)) {
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "VASSAL Extensions (*.vmdx)", "vmdx"));
        } else if (Boolean.FALSE.equals(extensionOnly)) {
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "VASSAL Modules (*.vmod)", "vmod"));
        } else {
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "VASSAL Archives (*.vmod, *.vmdx)", "vmod", "vmdx"));
        }

        File defaultDir = new File(System.getProperty("user.home"), "Games/VassalModules");
        if (!defaultDir.isDirectory()) defaultDir = new File(System.getProperty("user.home"));
        fc.setCurrentDirectory(defaultDir);

        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        openArchive(panel, fc.getSelectedFile());
    }

    /**
     * Opens {@code file} into {@code panel}, records it in the panel's recent-files
     * list, and refreshes the "Open Recent" menu.  On failure the error is shown;
     * if the file no longer exists it is pruned from the recent list.
     */
    private void openArchive(ArchivePanel panel, File file) {
        try {
            VassalArchive va = VassalArchive.open(file);
            panel.setArchive(va);
            updateRoleBorders();
            recordRecent(panel, file);
            status("Opened " + file.getName());
        } catch (Exception ex) {
            log.error("Failed to open {}", file, ex);
            if (!file.exists()) {
                recentFiles.remove(file);
                rebuildRecentMenu();
            }
            JOptionPane.showMessageDialog(this,
                    "Could not open file:\n" + file + "\n\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void recordRecent(ArchivePanel panel, File file) {
        if (panel == leftPanel) {
            recentFiles.addLeft(file);
        } else {
            recentFiles.addRight(file);
        }
        rebuildRecentMenu();
    }

    // -----------------------------------------------------------------------
    // New extension + Save As
    // -----------------------------------------------------------------------

    /**
     * Creates a new, empty extension for the module loaded in the left panel and
     * places it in the right panel.  The extension is unsaved — File &gt; Save
     * Extension As… writes it to disk.
     */
    private void newExtension() {
        VassalArchive module = leftPanel.getArchive();
        if (module == null || module.isExtension()) {
            status("Load a module in the LEFT panel before creating an extension.");
            return;
        }
        if (rightPanel.getArchive() != null && rightPanel.getArchive().isModified()) {
            int r = JOptionPane.showConfirmDialog(this,
                    "The right panel has unsaved changes that will be discarded.\nContinue?",
                    "New Extension", JOptionPane.OK_CANCEL_OPTION);
            if (r != JOptionPane.OK_OPTION) return;
        }
        try {
            VassalArchive ext = VassalArchive.createExtension(module);
            rightPanel.setArchive(ext);
            updateRoleBorders();
            status("New extension created for \"" + module.getRootElement().getAttribute("name")
                    + "\". Use File > Save Extension As… to save it.");
        } catch (Exception ex) {
            log.error("Failed to create extension", ex);
            JOptionPane.showMessageDialog(this,
                    "Could not create extension:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Prompts for a destination file and writes {@code panel}'s archive there.
     * The chooser defaults to the parent module's extension directory (the module
     * file name with ".vmod" replaced by "_ext"), creating it if necessary.
     */
    private void saveArchiveAs(ArchivePanel panel) {
        VassalArchive archive = panel.getArchive();
        if (archive == null) {
            status("Nothing to save in the " + (panel == leftPanel ? "left" : "right") + " panel.");
            return;
        }

        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save Extension As");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "VASSAL Extensions (*.vmdx)", "vmdx"));

        File extDir = moduleExtensionDir();
        if (extDir != null) {
            extDir.mkdirs();                       // create the _ext directory if absent
            fc.setCurrentDirectory(extDir);
            fc.setSelectedFile(new File(extDir, suggestedExtensionName()));
        } else if (archive.getFile() != null) {
            fc.setSelectedFile(archive.getFile());
        }

        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File chosen = fc.getSelectedFile();
        if (!chosen.getName().toLowerCase().endsWith(".vmdx")) {
            chosen = new File(chosen.getParentFile(), chosen.getName() + ".vmdx");
        }
        if (chosen.exists()) {
            int r = JOptionPane.showConfirmDialog(this,
                    chosen.getName() + " already exists.\nOverwrite it?",
                    "Save Extension As", JOptionPane.OK_CANCEL_OPTION);
            if (r != JOptionPane.OK_OPTION) return;
        }

        try {
            archive.saveAs(chosen);
            panel.refresh();
            recordRecent(panel, chosen);
            updateRoleBorders();
            status("Saved extension as " + chosen.getName());
        } catch (Exception ex) {
            log.error("Save As failed for {}", chosen, ex);
            JOptionPane.showMessageDialog(this,
                    "Could not save extension:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * The conventional extension directory for the module loaded in the left
     * panel: a sibling directory named like the module file with ".vmod" replaced
     * by "_ext" (e.g. {@code EuropaNewMapV090.vmod} → {@code EuropaNewMapV090_ext/}).
     * Returns null when no module file is available.
     */
    private File moduleExtensionDir() {
        VassalArchive module = leftPanel.getArchive();
        if (module == null || module.isExtension() || module.getFile() == null) {
            return null;
        }
        File moduleFile = module.getFile();
        String name = moduleFile.getName();
        String base = name.toLowerCase().endsWith(".vmod")
                ? name.substring(0, name.length() - ".vmod".length())
                : name;
        return new File(moduleFile.getAbsoluteFile().getParentFile(), base + "_ext");
    }

    private String suggestedExtensionName() {
        VassalArchive ext = rightPanel.getArchive();
        if (ext != null && ext.getFile() != null) {
            return ext.getFile().getName();
        }
        return "NewExtension.vmdx";
    }

    // -----------------------------------------------------------------------
    // Remove unused images
    // -----------------------------------------------------------------------

    /**
     * Finds images in {@code panel}'s archive that are not referenced by any
     * component and, after user confirmation, marks the chosen ones for removal
     * (written out on the next save).  Mirrors VASSAL's "Remove Unused Images".
     */
    private void removeUnusedImages(ArchivePanel panel) {
        VassalArchive va = panel.getArchive();
        if (va == null) {
            status("Open a file in the " + (panel == leftPanel ? "left" : "right")
                    + " panel first.");
            return;
        }

        Set<String> unused = va.findUnusedImages();
        if (unused.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No unused images found in \"" + va.getDisplayName() + "\".",
                    "Remove Unused Images", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Pre-select every unused image; the user can deselect any to keep.
        JList<String> list = new JList<>(unused.toArray(new String[0]));
        list.setSelectionInterval(0, unused.size() - 1);
        list.setVisibleRowCount(Math.min(16, unused.size()));
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(380, 320));

        JPanel content = new JPanel(new BorderLayout(6, 6));
        content.add(new JLabel("<html><b>" + unused.size()
                + "</b> image(s) appear to be unreferenced in \"" + va.getDisplayName() + "\".<br>"
                + "They may still be used by custom code. Select the ones to remove:</html>"),
                BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);

        int choice = JOptionPane.showConfirmDialog(this, content,
                "Remove Unused Images", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;

        List<String> selected = list.getSelectedValuesList();
        if (selected.isEmpty()) {
            status("No images selected — nothing removed.");
            return;
        }

        for (String img : selected) {
            va.removeImage(img);
        }
        panel.refresh();   // refresh the panel title (modified marker)
        updateRoleBorders();
        status(String.format("Marked %d unused image(s) for removal from \"%s\" — Save to apply.",
                selected.size(), va.getFile() != null ? va.getFile().getName() : va.getDisplayName()));
    }

    /**
     * Repairs an extension damaged by the old double-wrapping bug: each spurious
     * outer {@code <ExtensionElement target="">} that merely wraps another
     * {@code ExtensionElement} is collapsed, lifting the inner (real) wrapper up to
     * the extension root in its place.  See the Move / Copy notes in CLAUDE.md.
     *
     * A target-less {@code ExtensionElement} whose child is a genuine component
     * (a legitimate graft at the module root) is left untouched — only wrappers
     * whose children are themselves {@code ExtensionElement}s are collapsed.  The
     * edit is in-memory and applied on the next Save.
     */
    private void repairDoubleWrappedExtensionElements(ArchivePanel panel) {
        VassalArchive va = panel.getArchive();
        if (va == null) {
            status("Open a file in the " + (panel == leftPanel ? "left" : "right")
                    + " panel first.");
            return;
        }

        int found = countDoubleWrappedExtensionElements(va.getRootElement());
        if (found == 0) {
            JOptionPane.showMessageDialog(this,
                    "No double-wrapped Extension Elements found in \""
                    + va.getDisplayName() + "\".",
                    "Repair Double-Wrapped Extension Elements",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int choice = JOptionPane.showConfirmDialog(this,
                "<html><b>" + found + "</b> double-wrapped Extension Element(s) found in \""
                + va.getDisplayName() + "\".<br><br>"
                + "Each spurious outer <tt>ExtensionElement</tt> (with an empty target) that<br>"
                + "merely wraps the real one will be collapsed, lifting the inner wrapper<br>"
                + "to the extension's top level so the VASSAL editor can edit it again.<br><br>"
                + "The change is applied on the next Save.</html>",
                "Repair Double-Wrapped Extension Elements",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;

        int repaired = collapseDoubleWrappedExtensionElements(va.getRootElement());
        if (repaired == 0) {
            status("No double-wrapped Extension Elements collapsed.");
            return;
        }
        va.setModified(true);
        panel.refresh();
        updateRoleBorders();
        status(String.format("Collapsed %d double-wrapped Extension Element(s) in \"%s\" — Save to apply.",
                repaired, va.getFile() != null ? va.getFile().getName() : va.getDisplayName()));
    }

    /**
     * Opens a modal dialog to edit an extension's properties — mirroring VASSAL's
     * ModuleExtension editor: Version, Description, a read-only Extension ID, and
     * an "Allow loading with any module" checkbox.  Enabled only for an extension
     * (not a module).  On Save the values are written via
     * {@link VassalArchive#setExtensionProperties}, updating both the buildFile
     * root and the extensiondata entry; the change reaches disk on the next Save.
     */
    private void editExtensionProperties(ArchivePanel panel) {
        VassalArchive va = panel.getArchive();
        String side = (panel == leftPanel ? "left" : "right");
        if (va == null) {
            status("Open a file in the " + side + " panel first.");
            return;
        }
        if (!va.isExtension()) {
            JOptionPane.showMessageDialog(this,
                    "The " + side + " panel holds a module, not an extension.\n"
                    + "Extension properties can only be edited for an extension (.vmdx).",
                    "Edit Extension Properties", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JTextField versionField = new JTextField(va.getExtensionVersion(), 20);
        JTextField descField = new JTextField(va.getExtensionDescription(), 20);
        JTextField idField = new JTextField(va.getExtensionId(), 20);
        idField.setEditable(false);                 // display only, like VASSAL
        idField.setFocusable(false);
        JCheckBox anyModuleBox = new JCheckBox("Allow loading with any module",
                va.getExtensionAnyModule());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        int row = 0;
        addFormRow(form, c, row++, "Version:", versionField);
        addFormRow(form, c, row++, "Description:", descField);
        addFormRow(form, c, row++, "Extension ID:", idField);
        // Checkbox spans the field column (its own label is on the box).
        c.gridx = 1; c.gridy = row++; c.fill = GridBagConstraints.NONE;
        form.add(anyModuleBox, c);

        int choice = JOptionPane.showConfirmDialog(this, form,
                "Edit Extension Properties — " + va.getDisplayName(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;

        va.setExtensionProperties(versionField.getText(), descField.getText(),
                anyModuleBox.isSelected());
        panel.refresh();            // reflect the modified marker in the title
        updateRoleBorders();
        status(String.format("Updated extension properties for \"%s\" — Save to apply.",
                va.getFile() != null ? va.getFile().getName() : va.getDisplayName()));
    }

    /** Adds a {@code label:} / field pair as one row of a GridBagLayout form. */
    private static void addFormRow(JPanel form, GridBagConstraints c, int row,
                                   String label, JComponent field) {
        c.gridx = 0; c.gridy = row; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        form.add(new JLabel(label), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        form.add(field, c);
    }

    // -----------------------------------------------------------------------
    // Show extensions
    // -----------------------------------------------------------------------

    /** The subdirectory of a module's {@code _ext} dir that holds deactivated extensions. */
    private static final String INACTIVE_DIR = "inactive";

    /** One extension entry in the Show Extensions dialog: its file and whether it is active. */
    private static final class ExtEntry {
        final File file;
        final boolean active;
        ExtEntry(File file, boolean active) { this.file = file; this.active = active; }
    }

    /**
     * Lists the extensions available for the module loaded in the left panel and
     * lets the user activate/deactivate each (by moving its {@code .vmdx} file
     * between the module's {@code _ext} directory and its {@code inactive}
     * subdirectory) or open it into the right panel for editing.
     *
     * Active extensions live directly in {@code <module>_ext/}; deactivated ones in
     * {@code <module>_ext/inactive/}.  Entries are listed alphabetically; inactive
     * ones are shown in grey with a trailing "(Inactive)" marker.
     */
    private void showExtensions() {
        VassalArchive module = leftPanel.getArchive();
        if (module == null || module.isExtension()) {
            status("Load a module in the LEFT panel before showing its extensions.");
            return;
        }
        File extDir = moduleExtensionDir();
        if (extDir == null) {
            status("The left panel's module has no file on disk yet — save it first.");
            return;
        }

        JList<ExtEntry> list = new JList<>();
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new ExtEntryRenderer());
        list.setVisibleRowCount(14);
        reloadExtensionList(list, extDir);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(420, 320));

        JButton toggleBtn = new JButton("Activate / Deactivate");
        JButton editBtn   = new JButton("Edit Extension");
        toggleBtn.setEnabled(false);
        editBtn.setEnabled(false);

        // Keep the toggle button's label and both buttons' enabled state in sync
        // with the current selection.
        list.addListSelectionListener(e -> {
            ExtEntry sel = list.getSelectedValue();
            boolean has = sel != null;
            editBtn.setEnabled(has);
            toggleBtn.setEnabled(has);
            toggleBtn.setText(has && !sel.active ? "Activate" : "Deactivate");
        });

        toggleBtn.addActionListener(e -> {
            ExtEntry sel = list.getSelectedValue();
            if (sel == null) return;
            if (toggleExtensionActive(sel, extDir)) {
                reloadExtensionList(list, extDir);
            }
        });

        editBtn.addActionListener(e -> {
            ExtEntry sel = list.getSelectedValue();
            if (sel == null) return;
            Window dialog = SwingUtilities.getWindowAncestor(list);
            openArchive(rightPanel, sel.file);
            if (dialog != null) dialog.dispose();
        });

        JPanel content = new JPanel(new BorderLayout(6, 6));
        content.add(new JLabel("<html>Extensions for <b>" + module.getDisplayName()
                + "</b>:<br>Select one, then Activate/Deactivate it or open it for editing."
                + "</html>"), BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(toggleBtn);
        buttons.add(editBtn);
        JButton closeBtn = new JButton("Close");
        buttons.add(closeBtn);
        content.add(buttons, BorderLayout.SOUTH);

        // Double-click a row to edit that extension.
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent me) {
                if (me.getClickCount() == 2 && list.getSelectedValue() != null) {
                    editBtn.doClick();
                }
            }
        });

        JDialog dialog = new JDialog(this, "Extensions — " + module.getDisplayName(), true);
        closeBtn.addActionListener(e -> dialog.dispose());
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /**
     * Scans {@code extDir} (active) and {@code extDir/inactive} (deactivated) for
     * {@code .vmdx} files and repopulates {@code list}, sorted alphabetically by
     * file name (case-insensitive) regardless of active state.
     */
    private void reloadExtensionList(JList<ExtEntry> list, File extDir) {
        List<ExtEntry> entries = new ArrayList<>();
        collectVmdx(extDir, true, entries);
        collectVmdx(new File(extDir, INACTIVE_DIR), false, entries);
        entries.sort(Comparator.comparing(en -> en.file.getName(), String.CASE_INSENSITIVE_ORDER));
        list.setListData(entries.toArray(new ExtEntry[0]));
    }

    /** Adds every {@code *.vmdx} file directly in {@code dir} to {@code out} with the given active flag. */
    private static void collectVmdx(File dir, boolean active, List<ExtEntry> out) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles(
                (d, name) -> name.toLowerCase().endsWith(".vmdx") && new File(d, name).isFile());
        if (files == null) return;
        for (File f : files) {
            out.add(new ExtEntry(f, active));
        }
    }

    /**
     * Activates or deactivates {@code entry} by moving its {@code .vmdx} file
     * between {@code extDir} (active) and {@code extDir/inactive} (deactivated).
     * Returns true when the move succeeded and the list should be reloaded.
     */
    private boolean toggleExtensionActive(ExtEntry entry, File extDir) {
        File targetDir = entry.active ? new File(extDir, INACTIVE_DIR) : extDir;
        File target = new File(targetDir, entry.file.getName());
        String verb = entry.active ? "Deactivate" : "Activate";

        if (target.exists()) {
            JOptionPane.showMessageDialog(this,
                    "A file named \"" + entry.file.getName() + "\" already exists in\n"
                    + targetDir + "\n\nCannot " + verb.toLowerCase() + " the extension.",
                    verb + " Extension", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            JOptionPane.showMessageDialog(this,
                    "Could not create the directory:\n" + targetDir,
                    verb + " Extension", JOptionPane.ERROR_MESSAGE);
            return false;
        }

        try {
            Files.move(entry.file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ex) {
            // Fall back to a non-atomic move if the platform/filesystem rejects it.
            try {
                Files.move(entry.file.toPath(), target.toPath());
            } catch (Exception ex2) {
                log.error("Failed to {} extension {}", verb.toLowerCase(), entry.file, ex2);
                JOptionPane.showMessageDialog(this,
                        "Could not " + verb.toLowerCase() + " extension:\n"
                        + entry.file.getName() + "\n\n" + ex2.getMessage(),
                        verb + " Extension", JOptionPane.ERROR_MESSAGE);
                return false;
            }
        }
        status((entry.active ? "Deactivated " : "Activated ") + entry.file.getName());
        return true;
    }

    /**
     * Renders an {@link ExtEntry}: active extensions in the normal colour, inactive
     * ones in grey with a trailing "(Inactive)" marker.  Grey is applied only when
     * the row is not selected, keeping the selection highlight legible.
     */
    private static final class ExtEntryRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof ExtEntry) {
                ExtEntry entry = (ExtEntry) value;
                setText(entry.active
                        ? entry.file.getName()
                        : entry.file.getName() + "   (Inactive)");
                if (!entry.active && !isSelected) {
                    setForeground(Color.GRAY);
                }
            }
            return this;
        }
    }

    /**
     * Rebuilds the "Open Recent" submenu from the persisted recent-files lists.
     * Each panel gets its own labelled section; selecting an entry reopens that
     * file into the corresponding panel.
     */
    private void rebuildRecentMenu() {
        recentMenu.removeAll();
        boolean addedLeft  = addRecentSection("Left panel",  recentFiles.getLeft(),  leftPanel);
        if (addedLeft) recentMenu.addSeparator();
        boolean addedRight = addRecentSection("Right panel", recentFiles.getRight(), rightPanel);

        if (!addedLeft && !addedRight) {
            JMenuItem none = new JMenuItem("(no recent files)");
            none.setEnabled(false);
            recentMenu.add(none);
        }
    }

    /**
     * Adds a non-selectable section header followed by one menu item per recent
     * file for {@code panel}.  Returns true if any item was added.
     */
    private boolean addRecentSection(String title, List<File> files, ArchivePanel panel) {
        if (files.isEmpty()) return false;
        JMenuItem header = new JMenuItem(title);
        header.setEnabled(false);
        recentMenu.add(header);
        for (File f : files) {
            JMenuItem item = new JMenuItem(f.getName());
            item.setToolTipText(f.getAbsolutePath());
            item.addActionListener(e -> openArchive(panel, f));
            recentMenu.add(item);
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Move / Copy operation
    // -----------------------------------------------------------------------

    /**
     * Moves or copies all selected components from src into dst.
     *
     * The destination parent is determined as follows:
     *   - If a node is selected in the target panel, every source component is
     *     transferred into it.
     *   - If nothing is selected in the target panel, the user is offered the
     *     chance to recreate each source component's ancestor path: every parent
     *     up to the root is shallow-cloned (without its other children) into the
     *     target archive, and the component is then transferred into the
     *     recreated parent.  Cancelling aborts the whole operation.
     *
     * Steps:
     *   1. Collect all selected source nodes; filter out descendants of other
     *      selected nodes (transferring a parent already carries its children
     *      on a Move; on a Copy a descendant of another selected node would be
     *      redundant since the parent's own copy keeps it grouped).
     *   2. Gather all image references and Pre-defined setup save files (the
     *      {@code .vsav} entry named by each {@code PredefinedSetup}) across every
     *      node to be transferred (and, when recreating parents, those referenced
     *      by the recreated parents).
     *   3. Copy any missing images and setup files to the destination archive.
     *   4. For each source element: deep-clone it (with its whole subtree) into
     *      the destination document, append to its destination parent, and — for
     *      Move only — remove it from the source document.
     *   5. On a Move, prune setup files now orphaned in the source (removed only
     *      when no remaining PredefinedSetup still references them).
     *   6. Rebuild the affected trees.
     *
     * Both Move and Copy carry the whole selected subtree; the only difference is
     * that Move removes the originals from the source and Copy leaves them.
     *
     * Images are copied but never deleted from the source — the same image may be
     * referenced by components that remain.  A Pre-defined setup's {@code .vsav}
     * save file, by contrast, is genuinely moved: copied to the destination and
     * then removed from the source if (and only if) no remaining setup references
     * it (it may be shared with components that were not moved).
     *
     * @param copy {@code true} to copy (source retained, children excluded);
     *             {@code false} to move (source removed, children carried).
     */
    private void performTransfer(ArchivePanel src, ArchivePanel dst, boolean copy) {
        String verb = copy ? "Copy" : "Move";
        String verbPast = copy ? "Copied" : "Moved";

        if (src.getArchive() == null || dst.getArchive() == null) {
            status("Open files in both panels before " + (copy ? "copying." : "moving."));
            return;
        }

        List<DefaultMutableTreeNode> rawSrcNodes = src.getSelectedNodes();
        DefaultMutableTreeNode dstNode = dst.getSelectedNode();

        if (rawSrcNodes.isEmpty()) {
            status("Select one or more components in the source panel first.");
            return;
        }

        // Remove nodes whose ancestor is also in the selection — transferring the
        // ancestor already accounts for the descendant.
        List<DefaultMutableTreeNode> srcNodes = filterDescendants(rawSrcNodes);

        // Validate all source nodes have ComponentNode user objects
        List<ComponentNode> srcComps = new ArrayList<>(srcNodes.size());
        for (DefaultMutableTreeNode n : srcNodes) {
            if (!(n.getUserObject() instanceof ComponentNode)) {
                status("One or more selected source nodes are not valid components.");
                return;
            }
            ComponentNode comp = (ComponentNode) n.getUserObject();
            if (comp.isInherited()) {
                // A greyed inherited node stands in for a module component the
                // extension only grafts into — it is not part of the extension and
                // has no element to transfer.
                status("Inherited (grey) module components can't be "
                        + (copy ? "copied — select an extension component." : "moved — select an extension component."));
                return;
            }
            srcComps.add(comp);
        }

        // Determine the destination parent.  With a node selected in the target
        // panel everything goes under it; with nothing selected we instead offer
        // to recreate each source component's ancestor path under the target root.
        boolean recreateParents = (dstNode == null);
        Element dstElem = null;
        boolean dstInherited = false;
        if (!recreateParents) {
            if (!(dstNode.getUserObject() instanceof ComponentNode)) {
                status("Select a valid destination parent node.");
                return;
            }
            ComponentNode dstComp = (ComponentNode) dstNode.getUserObject();
            dstElem = dstComp.getElement();
            dstInherited = dstComp.isInherited();
        }

        // Grafting into an extension happens in two ways:
        //   • top level — nothing selected, or the extension root selected: each
        //     component is grafted at the module location it came from (its original
        //     parent path), via moduleTargetPath;
        //   • into a chosen inherited (grey) node — the component is grafted at that
        //     node's module location instead, via its reconstructed target path.
        Element dstRoot = dst.getArchive().getRootElement();
        boolean graftTopLevel = dst.getArchive().isExtension()
                && (recreateParents || (dstElem != null && dstElem.isSameNode(dstRoot)));
        boolean graftIntoInherited = dst.getArchive().isExtension() && dstInherited;
        boolean graftIntoExtension = graftTopLevel || graftIntoInherited;
        // Explicit target when grafting into a chosen inherited module location.
        String graftTarget = graftIntoInherited ? syntheticTargetPath(dstNode) : null;

        // Confirm
        String srcSummary = srcNodes.size() == 1
                ? "\"" + srcComps.get(0).getDisplayName() + "\""
                : srcNodes.size() + " components";

        // Guard: a module must never contain an ExtensionElement.  When the
        // destination is a module and no parent is chosen (so the source's ancestor
        // path would be recreated), a source that lives inside an extension's
        // ExtensionElement would clone that wrapper into the module — producing an
        // illegal module that loads but breaks (e.g. VASSAL's Tools > Refresh
        // Counters rejects it).  Refuse and tell the user to pick a real parent.
        if (recreateParents && !graftIntoExtension) {
            for (ComponentNode comp : srcComps) {
                if (hasExtensionElementAncestor(comp.getElement())) {
                    JOptionPane.showMessageDialog(this,
                            "Can't " + verb.toLowerCase() + " " + srcSummary
                            + " into the module here.\n\n"
                            + "The selected component sits inside an extension's Extension Element.\n"
                            + "With no destination parent selected, its parent path would be recreated\n"
                            + "in the module — adding an Extension Element to the module, which is not\n"
                            + "allowed (VASSAL loads such a module but Tools → Refresh Counters fails).\n\n"
                            + "Select the parent component in the module tree to " + verb.toLowerCase()
                            + " into, then try again.",
                            verb + " Not Allowed", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }
        }

        if (graftIntoInherited) {
            int confirm = JOptionPane.showConfirmDialog(this,
                    verb + " " + srcSummary + " into \""
                    + ((ComponentNode) dstNode.getUserObject()).getDisplayName() + "\".\n\n"
                    + "Each will be wrapped in an Extension Element targeting that module\n"
                    + "location, so VASSAL grafts it in there.\n\n"
                    + "Referenced images and Pre-defined setup files will be copied "
                    + "to the destination archive.",
                    "Graft into Extension", JOptionPane.OK_CANCEL_OPTION);
            if (confirm != JOptionPane.OK_OPTION) return;
        } else if (graftIntoExtension) {
            int confirm = JOptionPane.showConfirmDialog(this,
                    verb + " " + srcSummary + " into the extension at the top level.\n\n"
                    + "Each will be wrapped in an Extension Element targeting its original\n"
                    + "location in the module's tree, so VASSAL grafts it back into the same\n"
                    + "position.\n\n"
                    + "Referenced images and Pre-defined setup files will be copied "
                    + "to the destination archive.",
                    "Graft into Extension", JOptionPane.OK_CANCEL_OPTION);
            if (confirm != JOptionPane.OK_OPTION) return;
        } else if (recreateParents) {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "No destination parent is selected in the target panel.\n\n"
                    + "Copy the parent component(s) of " + srcSummary + " — every ancestor\n"
                    + "up to the root, without their other children — into the target panel,\n"
                    + "then " + verb.toLowerCase() + " " + srcSummary
                    + " into the recreated location?\n\n"
                    + "Referenced images and Pre-defined setup files will be copied "
                    + "to the destination archive.",
                    "Recreate Parent Path", JOptionPane.OK_CANCEL_OPTION);
            if (confirm != JOptionPane.OK_OPTION) return;
        } else {
            int confirm = JOptionPane.showConfirmDialog(this,
                    verb + " " + srcSummary + " (with all child components)\n  into  \""
                    + ((ComponentNode) dstNode.getUserObject()).getDisplayName() + "\"?\n\n"
                    + "Referenced images and Pre-defined setup files will be copied "
                    + "to the destination archive.",
                    "Confirm " + verb, JOptionPane.OK_CANCEL_OPTION);
            if (confirm != JOptionPane.OK_OPTION) return;
        }

        try {
            VassalArchive srcArchive = src.getArchive();
            VassalArchive dstArchive = dst.getArchive();
            Document dstDoc = dstArchive.getBuildDocument();
            Set<String> srcImageNames = srcArchive.getImageNames();

            // 1. Collect image references and Pre-defined setup save files from all
            //    source components in one pass.  Both Move and Copy carry the full
            //    subtree, so the whole tree is scanned (recurse) either way.
            Set<String> allImageRefs = new HashSet<>();
            Set<String> allSetupFiles = new HashSet<>();
            for (ComponentNode comp : srcComps) {
                allImageRefs.addAll(comp.collectImageReferences(srcImageNames, true));
                allSetupFiles.addAll(comp.collectSetupFileReferences(true));
            }
            // Setup files referenced by the components actually being transferred
            // (before any recreated-ancestor files are added below) — only these
            // are candidates for pruning from the source on a Move.
            Set<String> movedSetupFiles = new HashSet<>(allSetupFiles);

            // 1b. Resolve each source component's destination parent.  When
            //     recreating parents, the ancestor chain is shallow-cloned into
            //     the destination up front (reusing equivalent existing elements),
            //     and any images / setup files those ancestors reference are queued too.
            List<Element> dstParents = new ArrayList<>(srcComps.size());
            if (graftIntoExtension) {
                // Wrap each component in its OWN ExtensionElement targeting its
                // original module location. A VASSAL ExtensionElement holds exactly
                // one component (ExtensionElement.build reads only the first child
                // element), so components must never share a wrapper — even when
                // they graft to the same target — or VASSAL silently drops all but
                // the first.
                //
                // When the selected source is ITSELF an ExtensionElement (copying
                // between extensions), it is already a valid top-level extension
                // child: graft it directly onto the destination root. Wrapping it in
                // a fresh ExtensionElement would double-wrap it — an outer, target-less
                // wrapper (moduleTargetPath returns "" since the source wrapper's
                // parent is the extension root) around the real one, which the VASSAL
                // editor cannot edit.
                for (ComponentNode comp : srcComps) {
                    Element srcElem = comp.getElement();
                    if (EXTENSION_ELEMENT.equals(srcElem.getTagName())) {
                        dstParents.add(dstRoot);
                    } else {
                        // Into a chosen inherited node: that node's module path.
                        // At the top level: the component's own original path.
                        String target = graftIntoInherited ? graftTarget : moduleTargetPath(srcElem);
                        dstParents.add(createExtensionElement(dstDoc, dstRoot, target));
                    }
                }
            } else if (recreateParents) {
                for (ComponentNode comp : srcComps) {
                    dstParents.add(ensureAncestorPath(comp.getElement(), dstDoc, dstRoot,
                            allImageRefs, allSetupFiles, srcImageNames));
                }
            } else {
                for (int i = 0; i < srcComps.size(); i++) {
                    dstParents.add(dstElem);
                }
            }

            // 2. Copy images not already present in the destination.  The source
            //    entry's modification time is carried over so VASSAL's image-tile
            //    cache treats the moved image as unchanged (see VassalArchive.getEntryTime).
            int imagesCopied = 0;
            for (String imgName : allImageRefs) {
                if (!dstArchive.getImageNames().contains(imgName)) {
                    String entry = VassalArchive.IMAGE_DIR + imgName;
                    byte[] data = srcArchive.readEntry(entry);
                    if (data != null) {
                        dstArchive.addPendingImage(imgName, data, srcArchive.getEntryTime(entry));
                        imagesCopied++;
                    }
                }
            }

            // 2b. Copy Pre-defined setup save files (e.g. .vsav), stored at the
            //     archive root, that are not already present in the destination.
            int setupFilesCopied = 0;
            for (String setupFile : allSetupFiles) {
                if (!dstArchive.hasEntry(setupFile)) {
                    byte[] data = srcArchive.readEntry(setupFile);
                    if (data != null) {
                        dstArchive.addPendingFile(setupFile, data, srcArchive.getEntryTime(setupFile));
                        setupFilesCopied++;
                    } else {
                        log.warn("Pre-defined setup file not found in source archive: {}", setupFile);
                    }
                }
            }

            // 3. Transfer each source element into its resolved destination parent
            int transferred = 0;
            for (int i = 0; i < srcNodes.size(); i++) {
                Element srcElem = srcComps.get(i).getElement();
                Node srcParent = srcElem.getParentNode();
                if (srcParent == null) {
                    // Already detached — skip (shouldn't happen after filterDescendants)
                    log.warn("Source element already detached, skipping: {}",
                            srcComps.get(i).getDisplayName());
                    continue;
                }
                // Deep import (carry the whole subtree) for both Move and Copy.
                Node imported = dstDoc.importNode(srcElem, true);
                dstParents.get(i).appendChild(imported);
                if (!copy) {
                    srcParent.removeChild(srcElem);
                }
                transferred++;
            }

            // 3a. On a Move out of an extension, a grafted component that was the
            //     only child of its ExtensionElement leaves an EMPTY wrapper. VASSAL
            //     crashes on an ExtensionElement with no component (its build() reads
            //     no child, then addTo() dereferences a null), so drop any now-empty
            //     ExtensionElement from the source extension.
            int emptyWrappersRemoved = 0;
            if (!copy && srcArchive.isExtension()) {
                emptyWrappersRemoved = removeEmptyExtensionElements(srcArchive.getRootElement());
            }

            // 3b. On a Move, prune setup files now orphaned in the source.  A file
            //     is removed only when no PredefinedSetup remaining in the source
            //     tree still references it (it may be shared with components that
            //     were not moved).
            int setupFilesPruned = 0;
            if (!copy && !movedSetupFiles.isEmpty()) {
                Set<String> stillReferenced = new ComponentNode(srcArchive.getRootElement())
                        .collectSetupFileReferences(true);
                for (String setupFile : movedSetupFiles) {
                    if (!stillReferenced.contains(setupFile) && srcArchive.hasEntry(setupFile)) {
                        srcArchive.removeEntry(setupFile);
                        setupFilesPruned++;
                    }
                }
            }

            if (transferred > 0) {
                if (!copy) {
                    srcArchive.setModified(true);
                }
                dstArchive.setModified(true);
            }

            // The source tree only changes on a Move.
            if (!copy) {
                src.refresh();
            }
            dst.refresh();
            updateRoleBorders();

            String summary = String.format("%s %d component(s) — %d image(s)%s copied.",
                    verbPast, transferred, imagesCopied,
                    setupFilesCopied > 0 ? String.format(", %d setup file(s)", setupFilesCopied) : "");
            if (setupFilesPruned > 0) {
                summary += String.format(" %d orphaned setup file(s) removed from source.",
                        setupFilesPruned);
            }
            if (emptyWrappersRemoved > 0) {
                summary += String.format(" %d empty Extension Element(s) removed from source.",
                        emptyWrappersRemoved);
            }
            status(summary);
        } catch (Exception ex) {
            log.error("{} failed", verb, ex);
            JOptionPane.showMessageDialog(this,
                    verb + " failed:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Returns a copy of {@code nodes} with any node removed whose tree ancestor
     * is also present in the list.  This prevents double-processing when both a
     * parent and one of its children are selected.
     */
    private static List<DefaultMutableTreeNode> filterDescendants(
            List<DefaultMutableTreeNode> nodes) {
        List<DefaultMutableTreeNode> result = new ArrayList<>();
        for (DefaultMutableTreeNode node : nodes) {
            if (!hasSelectedAncestor(node, nodes)) {
                result.add(node);
            }
        }
        return result;
    }

    private static boolean hasSelectedAncestor(DefaultMutableTreeNode node,
                                                List<DefaultMutableTreeNode> selection) {
        DefaultMutableTreeNode cursor =
                (DefaultMutableTreeNode) node.getParent();
        while (cursor != null) {
            if (selection.contains(cursor)) return true;
            cursor = (DefaultMutableTreeNode) cursor.getParent();
        }
        return false;
    }

    /**
     * Recreates {@code srcElem}'s chain of ancestors — every parent from just
     * below the source root down to its immediate parent — under {@code dstRoot}
     * in the destination document, and returns the destination element that
     * corresponds to {@code srcElem}'s immediate parent.
     *
     * Each ancestor is shallow-cloned (its own attributes only, none of its
     * children), so the recreated path carries no siblings of the selected
     * component.  An ancestor is reused rather than re-created when the
     * destination already contains an equivalent element (same tag name and
     * attributes) at that level — this consolidates shared paths across multiple
     * selected components and avoids duplicating pre-existing destination nodes.
     *
     * Images and Pre-defined setup save files referenced by the recreated
     * ancestors themselves are added to {@code imageRefsOut} / {@code setupFilesOut}
     * so they can be copied alongside the components.
     *
     * The source root maps onto {@code dstRoot}; if the component is already a
     * direct child of the source root, {@code dstRoot} itself is returned.
     */
    private static Element ensureAncestorPath(Element srcElem, Document dstDoc, Element dstRoot,
                                              Set<String> imageRefsOut, Set<String> setupFilesOut,
                                              Set<String> srcImageNames) {
        Element srcRoot = srcElem.getOwnerDocument().getDocumentElement();

        // Walk up from the immediate parent, collecting ancestors below the root.
        List<Element> ancestors = new ArrayList<>();
        Node p = srcElem.getParentNode();
        while (p instanceof Element && !p.isSameNode(srcRoot)) {
            ancestors.add((Element) p);
            p = p.getParentNode();
        }
        Collections.reverse(ancestors);   // top-most first, to descend from dstRoot

        Element cursor = dstRoot;
        for (Element anc : ancestors) {
            Element match = findMatchingChild(cursor, anc);
            if (match == null) {
                match = (Element) dstDoc.importNode(anc, false);   // shallow: no children
                cursor.appendChild(match);
            }
            ComponentNode ancNode = new ComponentNode(anc);
            imageRefsOut.addAll(ancNode.collectImageReferences(srcImageNames, false));
            setupFilesOut.addAll(ancNode.collectSetupFileReferences(false));
            cursor = match;
        }
        return cursor;
    }

    /**
     * Returns the first child element of {@code parent} that is equivalent to
     * {@code template} (same tag name and identical attributes), or {@code null}
     * if none exists.
     */
    private static Element findMatchingChild(Element parent, Element template) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && sameElement((Element) child, template)) {
                return (Element) child;
            }
        }
        return null;
    }

    /** True when two elements have the same tag name and identical attribute sets. */
    private static boolean sameElement(Element a, Element b) {
        if (!a.getTagName().equals(b.getTagName())) return false;
        NamedNodeMap aAttrs = a.getAttributes();
        if (aAttrs.getLength() != b.getAttributes().getLength()) return false;
        for (int i = 0; i < aAttrs.getLength(); i++) {
            Node attr = aAttrs.item(i);
            String name = attr.getNodeName();
            if (!b.hasAttribute(name)
                    || !attr.getNodeValue().equals(b.getAttribute(name))) {
                return false;
            }
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Delete
    // -----------------------------------------------------------------------

    /**
     * Permanently removes every currently selected component (with its whole
     * subtree) from {@code panel}'s archive, after confirmation.
     *
     * Selection semantics mirror Move/Copy: {@link #filterDescendants} drops any
     * node whose ancestor is also selected, since removing the ancestor already
     * removes it.  Deletion is plain DOM removal ({@code Node.removeChild}), which
     * works the same whether the target has children or not — an already-empty
     * container (e.g. an {@code ExtensionElement} left with no child after its
     * one component was otherwise removed, or any empty Folder/container) is just
     * as deletable as a component with a full subtree; no special-casing is
     * needed. The archive root itself cannot be deleted.
     *
     * Unlike Move, referenced images and Pre-defined setup save files are left in
     * the archive untouched, even if the deletion leaves them unreferenced — the
     * user can run Tools &gt; Remove Unused Images separately if desired.
     */
    private void deleteSelected(ArchivePanel panel) {
        VassalArchive va = panel.getArchive();
        if (va == null) {
            status("Open a file in the " + (panel == leftPanel ? "left" : "right")
                    + " panel first.");
            return;
        }

        List<DefaultMutableTreeNode> rawNodes = panel.getSelectedNodes();
        if (rawNodes.isEmpty()) {
            status("Select one or more components to delete first.");
            return;
        }

        List<DefaultMutableTreeNode> nodes = filterDescendants(rawNodes);

        Element root = va.getRootElement();
        List<ComponentNode> comps = new ArrayList<>(nodes.size());
        for (DefaultMutableTreeNode n : nodes) {
            if (!(n.getUserObject() instanceof ComponentNode)) {
                status("One or more selected nodes are not valid components.");
                return;
            }
            ComponentNode comp = (ComponentNode) n.getUserObject();
            if (comp.isInherited()) {
                // A greyed inherited node belongs to the module, not the extension.
                status("Inherited (grey) module components can't be deleted "
                        + "— select an extension component.");
                return;
            }
            if (comp.getElement().isSameNode(root)) {
                status("The archive root cannot be deleted.");
                return;
            }
            comps.add(comp);
        }

        String summary = comps.size() == 1
                ? "\"" + comps.get(0).getDisplayName() + "\""
                : comps.size() + " components";
        int confirm = JOptionPane.showConfirmDialog(this,
                "Permanently delete " + summary + " (with all child components)\nfrom \""
                + va.getDisplayName() + "\"?\n\n"
                + "Referenced images and Pre-defined setup files are left in the\n"
                + "archive even if this leaves them unreferenced.",
                "Confirm Delete", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.OK_OPTION) return;

        int deleted = 0;
        for (ComponentNode comp : comps) {
            Element elem = comp.getElement();
            Node parent = elem.getParentNode();
            if (parent == null) continue;   // already detached; shouldn't happen post-filter
            parent.removeChild(elem);
            deleted++;
        }

        // Deleting a grafted component whose ExtensionElement wrapper is now empty
        // would leave an invalid wrapper VASSAL crashes on — drop any such wrappers.
        int emptyWrappersRemoved = 0;
        if (va.isExtension()) {
            emptyWrappersRemoved = removeEmptyExtensionElements(va.getRootElement());
        }

        if (deleted > 0) {
            va.setModified(true);
        }
        panel.refresh();
        updateRoleBorders();
        String resultMsg = String.format("Deleted %d component(s) from \"%s\".", deleted,
                va.getFile() != null ? va.getFile().getName() : va.getDisplayName());
        if (emptyWrappersRemoved > 0) {
            resultMsg += String.format(" %d empty Extension Element(s) removed.",
                    emptyWrappersRemoved);
        }
        status(resultMsg);
    }

    // -----------------------------------------------------------------------
    // Grafting into an extension
    // -----------------------------------------------------------------------
    //
    // An extension (.vmdx) does not hold components directly: each is wrapped in a
    // <VASSAL.build.module.ExtensionElement target="..."> whose target names the
    // path, in the *parent module's* tree, of the component to graft into.  So a
    // component moved/copied to the top level of an extension must be placed inside
    // an ExtensionElement targeting its original parent, not appended raw (which
    // VASSAL silently ignores).

    private static final String EXTENSION_ELEMENT = "VASSAL.build.module.ExtensionElement";

    /**
     * True when any ancestor of {@code srcElem} (up to, but excluding, its document
     * root) is an {@link #EXTENSION_ELEMENT} — i.e. the element lives inside an
     * extension's wrapper.  Used to prevent recreating that wrapper inside a module.
     */
    private static boolean hasExtensionElementAncestor(Element srcElem) {
        Element srcRoot = srcElem.getOwnerDocument().getDocumentElement();
        Node p = srcElem.getParentNode();
        while (p instanceof Element && !p.isSameNode(srcRoot)) {
            if (EXTENSION_ELEMENT.equals(((Element) p).getTagName())) return true;
            p = p.getParentNode();
        }
        return false;
    }

    /**
     * Builds the {@code target} path for an {@link #EXTENSION_ELEMENT} that grafts
     * {@code srcElem} back into the position its parent occupies in the module tree.
     * The path is the chain of {@code className:configureName} tokens from the
     * top-level module component down to {@code srcElem}'s immediate parent, encoded
     * exactly as VASSAL's {@code ComponentPathBuilder}/{@code SequenceEncoder} do
     * ('/'-joined tokens, ':'-joined class/name, delimiters backslash-escaped).
     *
     * If the source is itself an extension, an ancestor {@code ExtensionElement}'s
     * own target is used as the prefix.  An empty result means "graft at the module
     * root" (a direct child of {@code GameModule}).
     */
    private static String moduleTargetPath(Element srcElem) {
        Element srcRoot = srcElem.getOwnerDocument().getDocumentElement();
        List<String> tokens = new ArrayList<>();   // top-most first
        String prefix = null;
        Node p = srcElem.getParentNode();
        while (p instanceof Element && !p.isSameNode(srcRoot)) {
            Element e = (Element) p;
            if (EXTENSION_ELEMENT.equals(e.getTagName())) {
                String t = e.getAttribute("target");
                if (t != null && !t.isEmpty()) prefix = t;
                break;   // the target already encodes the module path to here
            }
            tokens.add(0, seqToken(e.getTagName(), new ComponentNode(e).getConfigureName()));
            p = p.getParentNode();
        }
        String path = seqJoin('/', tokens);
        if (prefix != null) {
            // Both prefix and path are already '/'-encoded; splice with a literal '/'.
            return path.isEmpty() ? prefix : prefix + "/" + path;
        }
        return path;
    }

    /**
     * Builds the {@code target} path for an {@link #EXTENSION_ELEMENT} grafting into a
     * selected inherited (grey) tree node — the reconstructed module component the
     * user chose as the destination.  The path is the chain of {@code className:name}
     * tokens of the node's inherited ancestors and the node itself (which is the
     * parent to graft into), encoded like {@link #moduleTargetPath}.  Empty means the
     * module root.
     */
    private static String syntheticTargetPath(DefaultMutableTreeNode node) {
        LinkedList<String> tokens = new LinkedList<>();   // top-most first
        DefaultMutableTreeNode cur = node;
        while (cur != null && cur.getUserObject() instanceof ComponentNode) {
            ComponentNode cn = (ComponentNode) cur.getUserObject();
            if (!cn.isInherited()) break;   // reached the (real) extension root
            tokens.addFirst(seqToken(cn.getClassName(), cn.getConfigureName()));
            cur = (DefaultMutableTreeNode) cur.getParent();
        }
        return seqJoin('/', tokens);
    }

    /**
     * Creates a new {@link #EXTENSION_ELEMENT} with the given target and appends
     * it to {@code extRoot}.  A separate wrapper is created for every component —
     * they are never shared, because a VASSAL {@code ExtensionElement} builds only
     * its first child element ({@code ExtensionElement.build}), so a shared wrapper
     * would silently discard all but the first grafted component.  This matches
     * what the VASSAL editor writes: one {@code ExtensionElement} per component,
     * even when several target the same location.
     */
    private static Element createExtensionElement(Document dstDoc, Element extRoot,
                                                  String target) {
        Element ee = dstDoc.createElement(EXTENSION_ELEMENT);
        ee.setAttribute("target", target);
        extRoot.appendChild(ee);
        return ee;
    }

    /**
     * Removes every {@link #EXTENSION_ELEMENT} child of {@code extRoot} that has
     * no component (no child element).  Such empty wrappers are invalid — VASSAL
     * crashes loading them (`ExtensionElement.build` reads no child, leaving its
     * {@code extension} null, then `addTo` dereferences it) — and are left behind
     * when the only component grafted through a wrapper is moved back out.
     * Returns the number removed.
     */
    private static int removeEmptyExtensionElements(Element extRoot) {
        List<Element> empties = new ArrayList<>();
        NodeList children = extRoot.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && EXTENSION_ELEMENT.equals(((Element) child).getTagName())
                    && !hasElementChild((Element) child)) {
                empties.add((Element) child);
            }
        }
        for (Element ee : empties) {
            extRoot.removeChild(ee);
        }
        return empties.size();
    }

    private static boolean hasElementChild(Element el) {
        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i).getNodeType() == Node.ELEMENT_NODE) return true;
        }
        return false;
    }

    /** The element children of {@code el}, in document order. */
    private static List<Element> elementChildren(Element el) {
        List<Element> kids = new ArrayList<>();
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) kids.add((Element) child);
        }
        return kids;
    }

    /**
     * True when {@code el} is a spurious outer wrapper produced by the old
     * double-wrapping bug: an {@code ExtensionElement} with an empty {@code target}
     * whose element children are all themselves {@code ExtensionElement}s (the real
     * wrappers).  A target-less {@code ExtensionElement} wrapping a genuine component
     * (a legitimate graft at the module root) is NOT double-wrapped and is left alone.
     */
    private static boolean isDoubleWrappedExtensionElement(Element el) {
        if (!EXTENSION_ELEMENT.equals(el.getTagName())) return false;
        if (!el.getAttribute("target").isEmpty()) return false;
        List<Element> kids = elementChildren(el);
        if (kids.isEmpty()) return false;
        for (Element kid : kids) {
            if (!EXTENSION_ELEMENT.equals(kid.getTagName())) return false;
        }
        return true;
    }

    /** Counts the double-wrapped {@code ExtensionElement} children of {@code extRoot}. */
    private static int countDoubleWrappedExtensionElements(Element extRoot) {
        int count = 0;
        for (Element child : elementChildren(extRoot)) {
            if (isDoubleWrappedExtensionElement(child)) count++;
        }
        return count;
    }

    /**
     * Collapses every double-wrapped {@code ExtensionElement} child of {@code extRoot}
     * (see {@link #isDoubleWrappedExtensionElement}): the inner {@code ExtensionElement}(s)
     * are lifted up to replace their empty-target outer wrapper, which is then removed.
     * Returns the number of outer wrappers collapsed.
     */
    private static int collapseDoubleWrappedExtensionElements(Element extRoot) {
        int collapsed = 0;
        for (Element outer : elementChildren(extRoot)) {
            if (!isDoubleWrappedExtensionElement(outer)) continue;
            for (Element inner : elementChildren(outer)) {
                extRoot.insertBefore(inner, outer);   // moves inner out of outer
            }
            extRoot.removeChild(outer);
            collapsed++;
        }
        return collapsed;
    }

    // --- Minimal faithful port of VASSAL's SequenceEncoder string encoding ------

    /** Encodes a {@code className:name} token (name may be null) like {@code SequenceEncoder(class, ':').append(name)}. */
    private static String seqToken(String className, String name) {
        StringBuilder buf = new StringBuilder();
        seqAppend(buf, className, ':', false);
        if (name != null) {
            seqAppend(buf, name, ':', true);
        }
        return buf.toString();
    }

    /** Joins encoded tokens with {@code delim} like {@code SequenceEncoder(delim)}. */
    private static String seqJoin(char delim, List<String> tokens) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            seqAppend(buf, tokens.get(i), delim, i > 0);
        }
        return buf.toString();
    }

    /** Appends one element to a SequenceEncoder-style buffer (delimiter-escaped, quoted when needed). */
    private static void seqAppend(StringBuilder buf, String s, char delim, boolean addDelimiter) {
        if (addDelimiter) buf.append(delim);
        if (s == null || s.isEmpty()) return;
        boolean quote = s.charAt(0) == '\\'
                || (s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'');
        if (quote) buf.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == delim) buf.append('\\');
            buf.append(c);
        }
        if (quote) buf.append('\'');
    }

    // -----------------------------------------------------------------------
    // Saving
    // -----------------------------------------------------------------------

    private void saveAll() {
        int saved = 0;
        StringBuilder errors = new StringBuilder();

        for (ArchivePanel panel : new ArchivePanel[]{leftPanel, rightPanel}) {
            VassalArchive va = panel.getArchive();
            if (va == null || !va.isModified()) continue;
            if (va.getFile() == null) {
                // A new, never-saved extension — route through the Save As dialog.
                saveArchiveAs(panel);
                if (va.getFile() != null && !va.isModified()) saved++;
                continue;
            }
            try {
                va.save();
                panel.refresh();
                saved++;
            } catch (Exception ex) {
                log.error("Save failed for {}", va.getFile(), ex);
                errors.append(va.getFile().getName())
                      .append(": ").append(ex.getMessage()).append("\n");
            }
        }

        if (errors.length() > 0) {
            JOptionPane.showMessageDialog(this,
                    "Errors while saving:\n" + errors,
                    "Save Error", JOptionPane.ERROR_MESSAGE);
        } else if (saved == 0) {
            status("No unsaved changes.");
        } else {
            status("Saved " + saved + " archive(s).");
        }
        updateRoleBorders();
    }

    private boolean confirmUnsavedChanges() {
        boolean anyModified =
                (leftPanel.getArchive() != null && leftPanel.getArchive().isModified()) ||
                (rightPanel.getArchive() != null && rightPanel.getArchive().isModified());
        if (!anyModified) return true;
        int r = JOptionPane.showConfirmDialog(this,
                "You have unsaved changes. Quit anyway?",
                "Unsaved Changes", JOptionPane.YES_NO_OPTION);
        return r == JOptionPane.YES_OPTION;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void updateRoleBorders() {
        leftPanel.setRole(ArchivePanel.BORDER_SOURCE);
        rightPanel.setRole(ArchivePanel.BORDER_TARGET);
    }

    private void status(String msg) {
        statusBar.setText(msg);
        log.info(msg);
    }
}
