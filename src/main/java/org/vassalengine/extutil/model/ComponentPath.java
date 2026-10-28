/*
 * Copyright (c) 2025 VASSAL Extension Utility contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 */
package org.vassalengine.extutil.model;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The {@code target} path of an {@code ExtensionElement}: where, in the parent
 * module's component tree, an extension grafts a component.
 *
 * <p>A target is a {@code SequenceEncoder} string — {@code '/'}-joined tokens,
 * each a {@code ':'}-joined {@code className:configureName} — written by VASSAL's
 * {@code ComponentPathBuilder}. This class decodes one and resolves it against a
 * real module document, which is what lets the extension tree be displayed in
 * module order and what lets {@link ExtensionCombiner} find the place a component
 * belongs.</p>
 *
 * <p>Decoding lives here rather than in its callers because three of them need it
 * and it must stay the <em>exact</em> inverse of the encoder VASSAL used; a second
 * copy would be a second chance to drift.</p>
 */
public final class ComponentPath {

    /** The wrapper element an extension puts round every component it contributes. */
    public static final String EXTENSION_ELEMENT = "VASSAL.build.module.ExtensionElement";

    private ComponentPath() { }

    /**
     * Decodes a target into {@code {className, configureName}} pairs, the name being
     * null when the component has none. An empty target yields an empty list, which
     * means "graft at the module root".
     */
    public static List<String[]> decode(String target) {
        final List<String[]> segs = new ArrayList<>();
        if (target == null || target.isEmpty()) return segs;
        for (String token : seqDecode(target, '/')) {
            if (token.isEmpty()) continue;
            final List<String> parts = seqDecode(token, ':');
            final String className = parts.isEmpty() ? "" : parts.get(0);
            final String name = parts.size() > 1 ? parts.get(1) : null;
            segs.add(new String[]{className, name});
        }
        return segs;
    }

    /**
     * Faithful port of VASSAL's {@code SequenceEncoder.Decoder}: splits {@code val}
     * on unescaped {@code delim}, honouring {@code \}-escaped delimiters and
     * single-quote enclosure, so it is the exact inverse of the encoder that
     * produced the target.
     */
    public static List<String> seqDecode(String val, char delim) {
        final List<String> out = new ArrayList<>();
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
        final int len = s.length();
        return (len > 1 && s.charAt(0) == '\'' && s.charAt(len - 1) == '\'')
                ? s.substring(1, len - 1) : s;
    }

    // -----------------------------------------------------------------------
    // Resolving a path against a real document
    // -----------------------------------------------------------------------

    /**
     * Walks {@code target} down from {@code root} and returns the component it
     * names, or {@code null} if any segment matches no child or more than one.
     *
     * <p>A segment matches a child whose tag is the segment's class <em>and</em>
     * whose configure name equals the segment's name — read through
     * {@link ComponentNode#getConfigureName()}, because VASSAL routes a different
     * XML attribute to the configure name per class ({@code mapName} for a Map,
     * {@code entryName} for a widget, and so on), and matching on the wrong
     * attribute would silently resolve to the wrong component.</p>
     */
    public static Element resolve(Element root, String target) {
        Element cur = root;
        for (String[] seg : decode(target)) {
            cur = findChild(cur, seg[0], seg[1]);
            if (cur == null) return null;
        }
        return cur;
    }

    /**
     * The single element child of {@code parent} with this class and configure name,
     * or {@code null} when there is none or the choice is ambiguous.
     *
     * <p>An <b>unnamed</b> segment falls back to matching on the class alone when no
     * child reports a null configure name. VASSAL writes no name into a path segment
     * for a component whose {@code getConfigureName()} is null, but
     * {@link ComponentNode#getConfigureName()} has to guess the name attribute for
     * classes it does not know, and its fallback list includes {@code title} — so a
     * {@code BoardPicker}, whose {@code title} is the "Choose Boards" dialog caption
     * and not a configure name at all, looks named when it is not. Without this,
     * every board an extension adds to a map ({@code Map:…/BoardPicker}, the most
     * common graft there is) failed to resolve.</p>
     */
    public static Element findChild(Element parent, String className, String name) {
        final Element exact = single(parent, className, name, true);
        if (exact != null || name != null) return exact;
        return single(parent, className, null, false);   // unnamed: class alone
    }

    /** The one child of this class (optionally also matching {@code name}), else null. */
    private static Element single(Element parent, String className,
                                  String name, boolean matchName) {
        Element found = null;
        for (Element child : elementChildren(parent)) {
            if (!child.getTagName().equals(className)) continue;
            if (matchName
                    && !Objects.equals(name, new ComponentNode(child).getConfigureName())) {
                continue;
            }
            if (found != null) return null;        // ambiguous — refuse to guess
            found = child;
        }
        return found;
    }

    /** The element children of {@code el}, in document order. */
    public static List<Element> elementChildren(Element el) {
        final List<Element> kids = new ArrayList<>();
        final NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) kids.add((Element) child);
        }
        return kids;
    }

    /** A readable {@code Class:name} label for one path segment or component. */
    public static String label(Element el) {
        final ComponentNode cn = new ComponentNode(el);
        final String cls = el.getTagName();
        final String shortCls = cls.substring(cls.lastIndexOf('.') + 1);
        final String name = cn.getConfigureName();
        return name == null ? shortCls : shortCls + ":" + name;
    }

    /** A readable rendering of a target path, for a report ("(module root)" when empty). */
    public static String labelTarget(String target) {
        final List<String[]> segs = decode(target);
        if (segs.isEmpty()) return "(module root)";
        final StringBuilder sb = new StringBuilder();
        for (String[] s : segs) {
            if (sb.length() > 0) sb.append(" / ");
            final String cls = s[0].substring(s[0].lastIndexOf('.') + 1);
            sb.append(s[1] == null ? cls : cls + ":" + s[1]);
        }
        return sb.toString();
    }
}
