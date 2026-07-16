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
     *     stable across a rebuild of the same archive).  When false, only the
     *     root is expanded so every folder starts closed (used when first
     *     loading an archive).
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
                && keyOf((DefaultMutableTreeNode) oldRoot) != null;

        Set<Object> expanded = null;
        List<Object> selection = null;
        Object topKey = null;
        if (canPreserve) {
            expanded  = captureExpandedKeys();
            selection = captureSelectedKeys();
            topKey    = captureTopKey();
        }

        // Extensions are shown as the reconstructed module hierarchy (grafted
        // components under greyed inherited path nodes); modules are shown directly.
        DefaultMutableTreeNode root = archive.isExtension()
                ? buildExtensionTree(archive.getRootElement())
                : buildTreeNode(archive.getRootElement());
        treeModel.setRoot(root);

        if (canPreserve) {
            restoreState(root, expanded, selection, topKey);
        } else {
            expandToDepth(tree, new TreePath(root), 0);
        }
        updateTitle();
    }

    // -----------------------------------------------------------------------
    // Expansion / selection / scroll preservation across rebuilds
    // -----------------------------------------------------------------------

    /**
     * A stable identity key for a tree node, used to carry expansion/selection/scroll
     * state across a rebuild.  Real components are keyed by their DOM {@link Element}
     * (stable across a rebuild of the same archive); synthetic inherited nodes, which
     * have no element, are keyed by their structural path (which is likewise stable,
     * since the same ExtensionElement targets rebuild the same synthetic hierarchy).
     * Returns null for non-component nodes (e.g. the "(no file open)" placeholder).
     */
    private static Object keyOf(DefaultMutableTreeNode node) {
        Object uo = node.getUserObject();
        if (!(uo instanceof ComponentNode)) return null;
        ComponentNode cn = (ComponentNode) uo;
        if (cn.getElement() != null) return cn.getElement();
        return "SYN:" + syntheticPath(node);
    }

    /** Structural path (className:name/…) of a synthetic node, from just below the root. */
    private static String syntheticPath(DefaultMutableTreeNode node) {
        java.util.ArrayDeque<String> segs = new java.util.ArrayDeque<>();
        DefaultMutableTreeNode cur = node;
        while (cur != null) {
            Object uo = cur.getUserObject();
            if (!(uo instanceof ComponentNode)) break;
            ComponentNode cn = (ComponentNode) uo;
            if (cn.getElement() != null) break;   // reached the (real) root
            segs.addFirst(cn.getClassName() + ":" + cn.getConfigureName());
            cur = (DefaultMutableTreeNode) cur.getParent();
        }
        return String.join("/", segs);
    }

    /** Keys of the nodes currently expanded (children visible). */
    private Set<Object> captureExpandedKeys() {
        Set<Object> result = new HashSet<>();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        Enumeration<TreePath> expanded = tree.getExpandedDescendants(new TreePath(root));
        if (expanded != null) {
            while (expanded.hasMoreElements()) {
                Object k = keyOf(
                        (DefaultMutableTreeNode) expanded.nextElement().getLastPathComponent());
                if (k != null) result.add(k);
            }
        }
        return result;
    }

    /** Keys of the currently selected nodes, in selection order. */
    private List<Object> captureSelectedKeys() {
        List<Object> result = new ArrayList<>();
        TreePath[] paths = tree.getSelectionPaths();
        if (paths != null) {
            for (TreePath p : paths) {
                Object k = keyOf((DefaultMutableTreeNode) p.getLastPathComponent());
                if (k != null) result.add(k);
            }
        }
        return result;
    }

    /** Key of the row currently nearest the top of the viewport (for scroll restore). */
    private Object captureTopKey() {
        Rectangle visible = tree.getVisibleRect();
        int row = tree.getClosestRowForLocation(visible.x, visible.y);
        if (row < 0) return null;
        TreePath p = tree.getPathForRow(row);
        return (p == null) ? null
                : keyOf((DefaultMutableTreeNode) p.getLastPathComponent());
    }

    private void restoreState(DefaultMutableTreeNode root, Set<Object> expanded,
                              List<Object> selection, Object topKey) {
        // Index the rebuilt tree by node key (DOM element or synthetic path).
        Map<Object, DefaultMutableTreeNode> byKey = new HashMap<>();
        Enumeration<TreeNode> all = root.breadthFirstEnumeration();
        while (all.hasMoreElements()) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) all.nextElement();
            Object k = keyOf(n);
            if (k != null) byKey.put(k, n);
        }

        // Root is always shown expanded; then re-expand previously expanded nodes.
        // expandPath also expands ancestors, so order does not matter.
        tree.expandPath(new TreePath(root));
        for (Object k : expanded) {
            DefaultMutableTreeNode n = byKey.get(k);
            if (n != null && !n.isLeaf()) {
                tree.expandPath(new TreePath(n.getPath()));
            }
        }

        // Restore selection (nodes that no longer exist are simply dropped).
        List<TreePath> selectionPaths = new ArrayList<>();
        for (Object k : selection) {
            DefaultMutableTreeNode n = byKey.get(k);
            if (n != null) selectionPaths.add(new TreePath(n.getPath()));
        }
        if (!selectionPaths.isEmpty()) {
            tree.setSelectionPaths(selectionPaths.toArray(new TreePath[0]));
        }

        // Restore approximate scroll position: put the previously-top row back at
        // the top.  If that node is gone, fall back to revealing the selection.
        DefaultMutableTreeNode topNode = (topKey != null) ? byKey.get(topKey) : null;
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
        t.setCellRenderer(new InheritedAwareRenderer());

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

    // -----------------------------------------------------------------------
    // Extension tree building
    // -----------------------------------------------------------------------
    //
    // An extension does not hold components directly: each grafted component sits
    // inside a <VASSAL.build.module.ExtensionElement target="…"> whose target names
    // the path, in the parent module's tree, of the component to graft into.  Rather
    // than showing those wrappers as flat "Extension Element → …" rows, this
    // reconstructs the module hierarchy the targets describe: the path segments
    // become greyed inherited nodes (shared prefixes merged), and each wrapper's
    // real component subtree hangs beneath its target as normal (black) nodes.

    private static final String EXTENSION_ELEMENT = "VASSAL.build.module.ExtensionElement";

    private DefaultMutableTreeNode buildExtensionTree(Element extRoot) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(new ComponentNode(extRoot));
        for (Element child : elementChildren(extRoot)) {
            graftInto(root, child);
        }
        return root;
    }

    /**
     * Attaches one extension child under {@code root}: an {@code ExtensionElement} is
     * grafted at the (greyed, shared-prefix-merged) inherited path named by its target,
     * with its real component(s) hung beneath; anything else is shown as-is.  A nested
     * {@code ExtensionElement} (as left by the double-wrapping bug) is re-grafted from
     * the root, since its target is an absolute module path — so even a doubly-wrapped
     * extension displays as the correct module hierarchy.
     */
    private void graftInto(DefaultMutableTreeNode root, Element child) {
        if (!EXTENSION_ELEMENT.equals(child.getTagName())) {
            root.add(buildTreeNode(child));
            return;
        }
        DefaultMutableTreeNode parent = root;
        for (String[] seg : decodeTarget(child.getAttribute("target"))) {
            parent = findOrCreateInherited(parent, seg[0], seg[1]);
        }
        for (Element comp : elementChildren(child)) {
            if (EXTENSION_ELEMENT.equals(comp.getTagName())) {
                graftInto(root, comp);           // nested wrapper: absolute target
            } else {
                parent.add(buildTreeNode(comp));
            }
        }
    }

    /**
     * Finds an existing synthetic (inherited) child of {@code parent} matching the
     * given class/name, or creates and appends one.  Merging on (className, name)
     * consolidates the shared path prefixes of ExtensionElements targeting nearby
     * module locations, exactly as they appear in the module's own tree.
     */
    private static DefaultMutableTreeNode findOrCreateInherited(
            DefaultMutableTreeNode parent, String className, String name) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode c = (DefaultMutableTreeNode) parent.getChildAt(i);
            Object uo = c.getUserObject();
            if (uo instanceof ComponentNode) {
                ComponentNode cn = (ComponentNode) uo;
                if (cn.isInherited()
                        && className.equals(cn.getClassName())
                        && java.util.Objects.equals(name, cn.getConfigureName())) {
                    return c;
                }
            }
        }
        DefaultMutableTreeNode created =
                new DefaultMutableTreeNode(new ComponentNode(className, name));
        parent.add(created);
        return created;
    }

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
     * Decodes an ExtensionElement {@code target} into a list of {@code {className, name}}
     * pairs (name may be null).  The target is a SequenceEncoder string: '/'-joined
     * tokens, each a ':'-joined {@code className:name} — decoded here exactly as
     * VASSAL's {@code SequenceEncoder.Decoder} / {@code ComponentPathBuilder} do.
     * An empty target yields an empty list (graft at the module root).
     */
    private static List<String[]> decodeTarget(String target) {
        List<String[]> segs = new ArrayList<>();
        if (target == null || target.isEmpty()) return segs;
        for (String token : seqDecode(target, '/')) {
            if (token.isEmpty()) continue;
            List<String> parts = seqDecode(token, ':');
            String className = parts.isEmpty() ? "" : parts.get(0);
            String name = parts.size() > 1 ? parts.get(1) : null;
            segs.add(new String[]{className, name});
        }
        return segs;
    }

    /**
     * Faithful port of VASSAL's {@code SequenceEncoder.Decoder}: splits {@code val}
     * on unescaped {@code delim}, honouring {@code \}-escaped delimiters and single-quote
     * enclosure, so it is the exact inverse of the encoder that produced the target.
     */
    private static List<String> seqDecode(String val, char delim) {
        List<String> out = new ArrayList<>();
        if (val == null) return out;
        final int stop = val.length();
        int start = 0;
        boolean done = false;
        while (!done) {
            if (start == stop) {           // trailing empty token (== "null")
                out.add("");
                break;
            }
            StringBuilder buf = null;
            String tok = null;
            int i = start;
            for (; i < stop; i++) {
                if (val.charAt(i) == delim) {
                    if (i > 0 && val.charAt(i - 1) == '\\') {
                        if (buf == null) buf = new StringBuilder();
                        buf.append(val, start, i - 1);
                        start = i;
                    } else {
                        if (buf == null || buf.length() == 0) {
                            tok = val.substring(start, i);
                        } else {
                            buf.append(val, start, i);
                        }
                        start = i + 1;
                        break;
                    }
                }
            }
            if (start < i) {               // reached the end without a real delimiter
                if (buf == null || buf.length() == 0) {
                    tok = val.substring(start);
                } else {
                    buf.append(val, start, stop);
                }
                done = true;
            }
            out.add(unquote(tok != null ? tok : (buf != null ? buf.toString() : "")));
        }
        return out;
    }

    /** Strips a single-quote enclosure, matching {@code SequenceEncoder.Decoder.unquote}. */
    private static String unquote(String s) {
        int len = s.length();
        return (len > 1 && s.charAt(0) == '\'' && s.charAt(len - 1) == '\'')
                ? s.substring(1, len - 1) : s;
    }

    /**
     * Tree renderer that greys nodes representing module components inherited by an
     * extension (synthetic {@link ComponentNode#isInherited()} nodes), distinguishing
     * them from the extension's own components, which render in the normal colour.
     */
    private static class InheritedAwareRenderer extends javax.swing.tree.DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            boolean inherited = false;
            if (value instanceof DefaultMutableTreeNode) {
                Object uo = ((DefaultMutableTreeNode) value).getUserObject();
                inherited = (uo instanceof ComponentNode) && ((ComponentNode) uo).isInherited();
            }
            // Grey only when not selected, so the selection highlight stays legible.
            if (inherited && !sel) {
                setForeground(Color.GRAY);
            }
            return this;
        }
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
