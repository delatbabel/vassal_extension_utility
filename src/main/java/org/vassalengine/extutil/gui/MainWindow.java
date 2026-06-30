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
import org.vassalengine.extutil.model.VassalArchive;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
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

        JMenuItem saveAll = new JMenuItem("Save All");
        saveAll.setAccelerator(KeyStroke.getKeyStroke("ctrl S"));
        saveAll.addActionListener(e -> saveAll());

        JMenuItem quit = new JMenuItem("Quit");
        quit.setAccelerator(KeyStroke.getKeyStroke("ctrl Q"));
        quit.addActionListener(e -> { if (confirmUnsavedChanges()) System.exit(0); });

        fileMenu.add(openMod);
        fileMenu.add(openExt);
        fileMenu.addSeparator();
        fileMenu.add(openLeft);
        fileMenu.add(openRight);
        fileMenu.addSeparator();
        fileMenu.add(saveAll);
        fileMenu.addSeparator();
        fileMenu.add(quit);

        bar.add(fileMenu);
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

        File chosen = fc.getSelectedFile();
        try {
            VassalArchive va = VassalArchive.open(chosen);
            panel.setArchive(va);
            updateRoleBorders();
            status("Opened " + chosen.getName());
        } catch (Exception ex) {
            log.error("Failed to open {}", chosen, ex);
            JOptionPane.showMessageDialog(this,
                    "Could not open file:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // -----------------------------------------------------------------------
    // Move / Copy operation
    // -----------------------------------------------------------------------

    /**
     * Moves or copies all selected components from src into the selected parent
     * in dst.
     *
     * Steps:
     *   1. Collect all selected source nodes; filter out descendants of other
     *      selected nodes (transferring a parent already carries its children
     *      on a Move; on a Copy a descendant of another selected node would be
     *      redundant since the parent's own copy keeps it grouped).
     *   2. Gather all image references across every node to be transferred.
     *   3. Copy any missing images to the destination archive.
     *   4. For each source element: clone into the destination document
     *      (deep for Move, shallow for Copy), append to the destination parent,
     *      and — for Move only — remove from the source document.
     *   5. Rebuild the affected trees.
     *
     * On a Move the whole subtree is carried; on a Copy only the selected
     * elements themselves are duplicated (their child components are not copied).
     *
     * Images are copied but never deleted from the source — the same image may
     * be referenced by other components that remain in the source archive.
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
        if (dstNode == null) {
            status("Select a destination parent in the target panel first.");
            return;
        }
        if (!(dstNode.getUserObject() instanceof ComponentNode)) {
            status("Select a valid destination parent node.");
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

        ComponentNode dstComp = (ComponentNode) dstNode.getUserObject();
        Element dstElem = dstComp.getElement();

        // Confirm
        String srcSummary = srcNodes.size() == 1
                ? "\"" + srcComps.get(0).getDisplayName() + "\""
                : srcNodes.size() + " components";
        String childNote = copy
                ? "\n\nOnly the selected component(s) will be copied — their child components will not."
                : "";
        int confirm = JOptionPane.showConfirmDialog(this,
                verb + " " + srcSummary + "\n  into  \""
                + dstComp.getDisplayName() + "\"?\n\n"
                + "Referenced images will be copied to the destination archive."
                + childNote,
                "Confirm " + verb, JOptionPane.OK_CANCEL_OPTION);
        if (confirm != JOptionPane.OK_OPTION) return;

        try {
            VassalArchive srcArchive = src.getArchive();
            VassalArchive dstArchive = dst.getArchive();
            Document dstDoc = dstArchive.getBuildDocument();

            // 1. Collect image references from all source components in one pass.
            //    Move carries the full subtree (recurse); Copy duplicates only the
            //    element itself, so only its own attributes are scanned.
            Set<String> allImageRefs = new HashSet<>();
            for (ComponentNode comp : srcComps) {
                allImageRefs.addAll(comp.collectImageReferences(srcArchive.getImageNames(), !copy));
            }

            // 2. Copy images not already present in the destination
            int imagesCopied = 0;
            for (String imgName : allImageRefs) {
                if (!dstArchive.getImageNames().contains(imgName)) {
                    byte[] data = srcArchive.readEntry(VassalArchive.IMAGE_DIR + imgName);
                    if (data != null) {
                        dstArchive.addPendingImage(imgName, data);
                        imagesCopied++;
                    }
                }
            }

            // 3. Transfer each source element
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
                dstElem.appendChild(imported);
                if (!copy) {
                    srcParent.removeChild(srcElem);
                }
                transferred++;
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

            status(String.format("%s %d component(s) — %d image(s) copied.",
                    verbPast, transferred, imagesCopied));
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

    // -----------------------------------------------------------------------
    // Saving
    // -----------------------------------------------------------------------

    private void saveAll() {
        int saved = 0;
        StringBuilder errors = new StringBuilder();

        for (ArchivePanel panel : new ArchivePanel[]{leftPanel, rightPanel}) {
            VassalArchive va = panel.getArchive();
            if (va != null && va.isModified()) {
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
