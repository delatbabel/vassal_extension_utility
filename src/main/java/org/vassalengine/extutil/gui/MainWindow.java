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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Main application window.
 *
 * Layout:
 *   - Split pane: left panel (module or any archive), right panel (extension or any archive)
 *   - Toolbar: open buttons, move buttons, copy buttons, save button
 *   - Status bar
 *
 * Multi-selection workflow:
 *   1. Open a module (left) and extension (right) via File menu or toolbar buttons.
 *   2. Select one or more components in the source panel:
 *      - Click to select one item.
 *      - Shift-click to extend the selection as a range.
 *      - Ctrl-click to add or remove individual items.
 *      - Right-click → "Search and select…" to select by partial name match.
 *   3. Single-click one component in the other panel as the destination parent.
 *      (If nothing is selected there, you will instead be offered the option to
 *      recreate each source component's parent path — every ancestor up to the
 *      root, without their other children — in the target panel and transfer the
 *      components into the recreated location.)
 *   4. Either:
 *      - Click "Move →" or "← Move" to move all selected source components
 *        (with their children) into the destination parent.  The originals are
 *        removed from the source.
 *      - Click "Copy →" or "← Copy" to duplicate the selected components into
 *        the destination parent.  Only the components themselves are copied —
 *        their child components are not — and the originals are retained.
 *      In both cases all images referenced by the transferred components are
 *      copied to the destination archive.
 *   5. File > Save All (Ctrl+S) to write changes to disk.
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

        toolsMenu.add(unusedLeft);
        toolsMenu.add(unusedRight);
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
     *   4. For each source element: clone into the destination document
     *      (deep for Move, shallow for Copy), append to its destination parent,
     *      and — for Move only — remove from the source document.
     *   5. On a Move, prune setup files now orphaned in the source (removed only
     *      when no remaining PredefinedSetup still references them).
     *   6. Rebuild the affected trees.
     *
     * On a Move the whole subtree is carried; on a Copy only the selected
     * elements themselves are duplicated (their child components are not copied).
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
            srcComps.add((ComponentNode) n.getUserObject());
        }

        // Determine the destination parent.  With a node selected in the target
        // panel everything goes under it; with nothing selected we instead offer
        // to recreate each source component's ancestor path under the target root.
        boolean recreateParents = (dstNode == null);
        Element dstElem = null;
        if (!recreateParents) {
            if (!(dstNode.getUserObject() instanceof ComponentNode)) {
                status("Select a valid destination parent node.");
                return;
            }
            dstElem = ((ComponentNode) dstNode.getUserObject()).getElement();
        }

        // Grafting into an extension: when the target is an extension and the
        // destination would be its top level (nothing selected, or the extension
        // root selected), each component is wrapped in an ExtensionElement that
        // targets its original module location, rather than recreated raw.
        Element dstRoot = dst.getArchive().getRootElement();
        boolean graftIntoExtension = dst.getArchive().isExtension()
                && (recreateParents || (dstElem != null && dstElem.isSameNode(dstRoot)));

        // Confirm
        String srcSummary = srcNodes.size() == 1
                ? "\"" + srcComps.get(0).getDisplayName() + "\""
                : srcNodes.size() + " components";
        if (graftIntoExtension) {
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
            String childNote = copy
                    ? "\n\nOnly the selected component(s) will be copied — their child components will not."
                    : "";
            int confirm = JOptionPane.showConfirmDialog(this,
                    verb + " " + srcSummary + "\n  into  \""
                    + ((ComponentNode) dstNode.getUserObject()).getDisplayName() + "\"?\n\n"
                    + "Referenced images and Pre-defined setup files will be copied "
                    + "to the destination archive."
                    + childNote,
                    "Confirm " + verb, JOptionPane.OK_CANCEL_OPTION);
            if (confirm != JOptionPane.OK_OPTION) return;
        }

        try {
            VassalArchive srcArchive = src.getArchive();
            VassalArchive dstArchive = dst.getArchive();
            Document dstDoc = dstArchive.getBuildDocument();
            Set<String> srcImageNames = srcArchive.getImageNames();

            // 1. Collect image references and Pre-defined setup save files from all
            //    source components in one pass.  Move carries the full subtree
            //    (recurse); Copy duplicates only the element itself, so only its own
            //    attributes/file are scanned.
            Set<String> allImageRefs = new HashSet<>();
            Set<String> allSetupFiles = new HashSet<>();
            for (ComponentNode comp : srcComps) {
                allImageRefs.addAll(comp.collectImageReferences(srcImageNames, !copy));
                allSetupFiles.addAll(comp.collectSetupFileReferences(!copy));
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
                for (ComponentNode comp : srcComps) {
                    String target = moduleTargetPath(comp.getElement());
                    dstParents.add(createExtensionElement(dstDoc, dstRoot, target));
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
                // Deep import for Move (carry children); shallow for Copy (element only).
                Node imported = dstDoc.importNode(srcElem, !copy);
                dstParents.get(i).appendChild(imported);
                if (!copy) {
                    srcParent.removeChild(srcElem);
                }
                transferred++;
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
