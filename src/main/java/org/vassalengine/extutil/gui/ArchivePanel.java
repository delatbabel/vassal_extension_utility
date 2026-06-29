/*
 * Copyright (c) 2025 VASSAL Extension Utility contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 */
package org.vassalengine.extutil.gui;

import org.vassalengine.extutil.model.ComponentNode;
import org.vassalengine.extutil.model.VassalArchive;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Panel showing the component hierarchy of one VassalArchive as a JTree.
 *
 * Supports multi-selection via Shift-click (range) and Ctrl-click (discontiguous).
 * Right-clicking opens a search dialog that selects all nodes whose display name
 * contains the entered string (case-insensitive partial match).
 */
public class ArchivePanel extends JPanel {

    private VassalArchive archive;
    private JTree tree;
    private DefaultTreeModel treeModel;
    private TitledBorder titledBorder;
    private Color currentBorderColor = BORDER_DEFAULT;

    // Role colours: default, source panel, target panel
    static final Color BORDER_DEFAULT = Color.GRAY;
    static final Color BORDER_SOURCE  = new Color(0, 100, 200);
    static final Color BORDER_TARGET  = new Color(0, 150, 50);

    public ArchivePanel() {
        setLayout(new BorderLayout(0, 4));

        titledBorder = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(BORDER_DEFAULT, 2),
                "(no file open)",
                TitledBorder.LEFT,
                TitledBorder.TOP);
        setBorder(titledBorder);

        treeModel = new DefaultTreeModel(new DefaultMutableTreeNode("(no file open)"));
        tree = buildTree();
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setPreferredSize(new Dimension(420, 600));
        add(scroll, BorderLayout.CENTER);
    }

    // -----------------------------------------------------------------------
    // Loading
    // -----------------------------------------------------------------------

    public void setArchive(VassalArchive va) {
        this.archive = va;
        refresh();
    }

    public void refresh() {
        if (archive == null) {
            treeModel.setRoot(new DefaultMutableTreeNode("(no file open)"));
            updateTitle();
            return;
        }
        DefaultMutableTreeNode root = buildTreeNode(archive.getRootElement());
        treeModel.setRoot(root);
        expandToDepth(tree, new TreePath(root), 1);
        updateTitle();
    }

    private void updateTitle() {
        String title = archive == null ? "(no file open)" : archive.getDisplayName();
        if (archive != null) {
            title += archive.isExtension() ? "  [extension]" : "  [module]";
        }
        titledBorder = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(currentBorderColor, 2),
                title,
                TitledBorder.LEFT,
                TitledBorder.TOP);
        setBorder(titledBorder);
        repaint();
    }

    // -----------------------------------------------------------------------
    // Selection — single (for destination) and multiple (for source)
    // -----------------------------------------------------------------------

    /**
     * Returns the first selected node (used to identify the destination parent).
     * Returns null if nothing is selected.
     */
    public DefaultMutableTreeNode getSelectedNode() {
        TreePath path = tree.getSelectionPath();
        if (path == null) return null;
        return (DefaultMutableTreeNode) path.getLastPathComponent();
    }

    /**
     * Returns all currently selected nodes (used to collect move sources).
     * Never returns null; returns an empty list when nothing is selected.
     */
    public List<DefaultMutableTreeNode> getSelectedNodes() {
        TreePath[] paths = tree.getSelectionPaths();
        if (paths == null) return Collections.emptyList();
        List<DefaultMutableTreeNode> result = new ArrayList<>(paths.length);
        for (TreePath path : paths) {
            Object last = path.getLastPathComponent();
            if (last instanceof DefaultMutableTreeNode) {
                result.add((DefaultMutableTreeNode) last);
            }
        }
        return result;
    }

    public VassalArchive getArchive() { return archive; }

    // -----------------------------------------------------------------------
    // Search and select
    // -----------------------------------------------------------------------

    /**
     * Opens an input dialog and selects all tree nodes whose display name contains
     * the entered string (case-insensitive).  Scrolls to the first match.
     * Replaces any existing selection.
     */
    public void showSearchDialog() {
        String query = (String) JOptionPane.showInputDialog(
                this,
                "Select all components whose name contains:",
                "Search and Select",
                JOptionPane.PLAIN_MESSAGE,
                null, null, "");
        if (query == null) return;   // cancelled
        selectMatching(query.trim());
    }

    private void selectMatching(String query) {
        if (query.isEmpty()) return;
        String lower = query.toLowerCase();

        List<TreePath> matches = new ArrayList<>();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        Enumeration<TreeNode> all = root.depthFirstEnumeration();
        while (all.hasMoreElements()) {
            TreeNode tn = all.nextElement();
            if (!(tn instanceof DefaultMutableTreeNode)) continue;
            DefaultMutableTreeNode dmtn = (DefaultMutableTreeNode) tn;
            Object uo = dmtn.getUserObject();
            if (!(uo instanceof ComponentNode)) continue;
            String display = ((ComponentNode) uo).getDisplayName().toLowerCase();
            if (display.contains(lower)) {
                matches.add(new TreePath(dmtn.getPath()));
            }
        }

        if (matches.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No components matched \"" + query + "\".",
                    "Search", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        tree.setSelectionPaths(matches.toArray(new TreePath[0]));
        tree.scrollPathToVisible(matches.get(0));
    }

    // -----------------------------------------------------------------------
    // Visual role indication
    // -----------------------------------------------------------------------

    public void setRole(Color borderColor) {
        currentBorderColor = borderColor;
        String title = titledBorder.getTitle();
        titledBorder = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(borderColor, 2),
                title,
                TitledBorder.LEFT,
                TitledBorder.TOP);
        setBorder(titledBorder);
        repaint();
    }

    // -----------------------------------------------------------------------
    // Tree building
    // -----------------------------------------------------------------------

    private JTree buildTree() {
        JTree t = new JTree(treeModel);
        // Allow Shift-click (range) and Ctrl-click (discontiguous) multi-selection
        t.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        t.setRootVisible(true);
        t.setShowsRootHandles(true);
        t.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        t.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) showContextMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) showContextMenu(e);
            }
        });

        return t;
    }

    private void showContextMenu(MouseEvent e) {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem searchItem = new JMenuItem("Search and select…");
        searchItem.addActionListener(ae -> showSearchDialog());
        popup.add(searchItem);
        popup.show(tree, e.getX(), e.getY());
    }

    private DefaultMutableTreeNode buildTreeNode(Element element) {
        ComponentNode comp = new ComponentNode(element);
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(comp);

        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                node.add(buildTreeNode((Element) child));
            }
        }
        return node;
    }

    private static void expandToDepth(JTree tree, TreePath path, int depth) {
        if (depth < 0) return;
        tree.expandPath(path);
        Object last = path.getLastPathComponent();
        if (last instanceof DefaultMutableTreeNode) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) last;
            for (int i = 0; i < node.getChildCount(); i++) {
                expandToDepth(tree,
                        path.pathByAddingChild(node.getChildAt(i)),
                        depth - 1);
            }
        }
    }
}
