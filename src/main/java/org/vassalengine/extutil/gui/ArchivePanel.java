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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private Runnable deleteHandler;

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
        // Loading a different archive: start from the default expansion, do not
        // try to carry over state from the previously displayed archive.
        refresh(false);
    }

    /**
     * Rebuilds the tree from the (possibly mutated) DOM, preserving the user's
     * expansion state, selection, and approximate scroll position.  Call this
     * after components have been added to or removed from the archive.
     */
    public void refresh() {
        refresh(true);
    }

    /**
     * @param preserveState when true, the existing tree's expanded/collapsed
     *     state, selection, and scroll position are captured and re-applied to
     *     the rebuilt tree (matched by DOM {@link Element} identity, which is
     *     stable across a rebuild of the same archive).  When false, the tree
     *     opens at its default depth (used when first loading an archive).
     */
    private void refresh(boolean preserveState) {
        if (archive == null) {
            treeModel.setRoot(new DefaultMutableTreeNode("(no file open)"));
            updateTitle();
            return;
        }

        Object oldRoot = treeModel.getRoot();
        boolean canPreserve = preserveState
                && oldRoot instanceof DefaultMutableTreeNode
                && elementOf((DefaultMutableTreeNode) oldRoot) != null;

        Set<Element> expanded = null;
        List<Element> selection = null;
        Element topElement = null;
        if (canPreserve) {
            expanded   = captureExpandedElements();
            selection  = captureSelectedElements();
            topElement = captureTopElement();
        }

        DefaultMutableTreeNode root = buildTreeNode(archive.getRootElement());
        treeModel.setRoot(root);

        if (canPreserve) {
            restoreState(root, expanded, selection, topElement);
        } else {
            expandToDepth(tree, new TreePath(root), 1);
        }
        updateTitle();
    }

    // -----------------------------------------------------------------------
    // Expansion / selection / scroll preservation across rebuilds
    // -----------------------------------------------------------------------

    private static Element elementOf(DefaultMutableTreeNode node) {
        Object uo = node.getUserObject();
        return (uo instanceof ComponentNode) ? ((ComponentNode) uo).getElement() : null;
    }

    /** Elements whose tree node is currently expanded (children visible). */
    private Set<Element> captureExpandedElements() {
        Set<Element> result = new HashSet<>();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        Enumeration<TreePath> expanded = tree.getExpandedDescendants(new TreePath(root));
        if (expanded != null) {
            while (expanded.hasMoreElements()) {
                Element el = elementOf(
                        (DefaultMutableTreeNode) expanded.nextElement().getLastPathComponent());
                if (el != null) result.add(el);
            }
        }
        return result;
    }

    /** Elements of the currently selected nodes, in selection order. */
    private List<Element> captureSelectedElements() {
        List<Element> result = new ArrayList<>();
        TreePath[] paths = tree.getSelectionPaths();
        if (paths != null) {
            for (TreePath p : paths) {
                Element el = elementOf((DefaultMutableTreeNode) p.getLastPathComponent());
                if (el != null) result.add(el);
            }
        }
        return result;
    }

    /** Element of the row currently nearest the top of the viewport (for scroll restore). */
    private Element captureTopElement() {
        Rectangle visible = tree.getVisibleRect();
        int row = tree.getClosestRowForLocation(visible.x, visible.y);
        if (row < 0) return null;
        TreePath p = tree.getPathForRow(row);
        return (p == null) ? null
                : elementOf((DefaultMutableTreeNode) p.getLastPathComponent());
    }

    private void restoreState(DefaultMutableTreeNode root, Set<Element> expanded,
                              List<Element> selection, Element topElement) {
        // Index the rebuilt tree by DOM element identity.
        Map<Element, DefaultMutableTreeNode> byElement = new HashMap<>();
        Enumeration<TreeNode> all = root.breadthFirstEnumeration();
        while (all.hasMoreElements()) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) all.nextElement();
            Element el = elementOf(n);
            if (el != null) byElement.put(el, n);
        }

        // Root is always shown expanded; then re-expand previously expanded nodes.
        // expandPath also expands ancestors, so order does not matter.
        tree.expandPath(new TreePath(root));
        for (Element el : expanded) {
            DefaultMutableTreeNode n = byElement.get(el);
            if (n != null && !n.isLeaf()) {
                tree.expandPath(new TreePath(n.getPath()));
            }
        }

        // Restore selection (nodes that no longer exist are simply dropped).
        List<TreePath> selectionPaths = new ArrayList<>();
        for (Element el : selection) {
            DefaultMutableTreeNode n = byElement.get(el);
            if (n != null) selectionPaths.add(new TreePath(n.getPath()));
        }
        if (!selectionPaths.isEmpty()) {
            tree.setSelectionPaths(selectionPaths.toArray(new TreePath[0]));
        }

        // Restore approximate scroll position: put the previously-top row back at
        // the top.  If that node is gone, fall back to revealing the selection.
        DefaultMutableTreeNode topNode = (topElement != null) ? byElement.get(topElement) : null;
        if (topNode != null) {
            scrollRowToTop(new TreePath(topNode.getPath()));
        } else if (!selectionPaths.isEmpty()) {
            tree.scrollPathToVisible(selectionPaths.get(0));
        }
    }

    /** Scrolls so the given path's row sits at (approximately) the top of the viewport. */
    private void scrollRowToTop(TreePath path) {
        int row = tree.getRowForPath(path);
        if (row < 0) return;
        Rectangle rowBounds = tree.getRowBounds(row);
        if (rowBounds == null) return;
        // Request a viewport-height region starting at this row so it lands at the top.
        Rectangle target = new Rectangle(
                rowBounds.x, rowBounds.y, rowBounds.width, tree.getVisibleRect().height);
        tree.scrollRectToVisible(target);
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

    /**
     * Registers the callback invoked when the user chooses "Delete" from this
     * panel's right-click context menu.  {@code MainWindow} wires this to its
     * own delete logic (which needs the archive and the sibling panel context),
     * keeping this panel's role limited to tree display and selection.
     */
    public void setDeleteHandler(Runnable handler) {
        this.deleteHandler = handler;
    }

    // -----------------------------------------------------------------------
    // Search and select
    // -----------------------------------------------------------------------

    /**
     * Opens an input dialog and selects all tree nodes <em>under the currently
     * selected component</em> whose display name contains the entered string.
     * The match is <strong>case-sensitive</strong> ("HW" does not match "hw")
     * and <strong>space-significant</strong>: the query is used verbatim, with no
     * leading, trailing, or internal spaces stripped, so searching for " T " finds
     * "CHCOM T MiG7" but not "CHCOM MOT".
     * Scrolls to the first match and replaces the existing selection.
     *
     * The search is scoped to the subtree of the first selected node; when
     * nothing is selected it falls back to the whole tree (the root).
     */
    public void showSearchDialog() {
        DefaultMutableTreeNode scope = getSelectedNode();
        if (scope == null) {
            scope = (DefaultMutableTreeNode) treeModel.getRoot();
        }

        String query = (String) JOptionPane.showInputDialog(
                this,
                "Select components under \"" + scopeLabel(scope) + "\"\n"
                        + "whose name contains (case-sensitive; spaces are significant):",
                "Search and Select",
                JOptionPane.PLAIN_MESSAGE,
                null, null, "");
        if (query == null) return;   // cancelled
        selectMatching(query, scope);   // verbatim — spaces are part of the search
    }

    private void selectMatching(String query, DefaultMutableTreeNode scope) {
        if (query.isEmpty()) return;

        List<TreePath> matches = new ArrayList<>();
        Enumeration<TreeNode> all = scope.depthFirstEnumeration();
        while (all.hasMoreElements()) {
            TreeNode tn = all.nextElement();
            if (!(tn instanceof DefaultMutableTreeNode)) continue;
            DefaultMutableTreeNode dmtn = (DefaultMutableTreeNode) tn;
            if (dmtn == scope) continue;   // search strictly under the scope node
            Object uo = dmtn.getUserObject();
            if (!(uo instanceof ComponentNode)) continue;
            String display = ((ComponentNode) uo).getDisplayName();
            if (display.contains(query)) {   // case-sensitive substring match
                matches.add(new TreePath(dmtn.getPath()));
            }
        }

        if (matches.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No components under \"" + scopeLabel(scope)
                            + "\" matched \"" + query + "\".",
                    "Search", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        tree.setSelectionPaths(matches.toArray(new TreePath[0]));
        tree.scrollPathToVisible(matches.get(0));
    }

    /** Display label of a tree node, used to name the search scope in dialogs. */
    private static String scopeLabel(DefaultMutableTreeNode node) {
        Object uo = node.getUserObject();
        return (uo instanceof ComponentNode)
                ? ((ComponentNode) uo).getDisplayName()
                : String.valueOf(uo);
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

        popup.addSeparator();
        JMenuItem deleteItem = new JMenuItem("Delete");
        deleteItem.setEnabled(deleteHandler != null && !getSelectedNodes().isEmpty());
        deleteItem.addActionListener(ae -> {
            if (deleteHandler != null) deleteHandler.run();
        });
        popup.add(deleteItem);

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
