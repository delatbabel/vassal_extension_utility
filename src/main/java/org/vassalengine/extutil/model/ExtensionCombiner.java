/*
 * Copyright (c) 2025 VASSAL Extension Utility contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 */
package org.vassalengine.extutil.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Folds a module's extensions into the module, and unfolds them again.
 *
 * <p>The VASSAL editor can do things to a monolithic module that it cannot do to
 * one carrying extensions, but the thing to ship is still a module plus
 * extensions. {@link #combine} makes the monolith to edit; {@link #uncombine}
 * puts the edits back where they came from.</p>
 *
 * <p><b>How a component knows where it lives.</b> An extension never holds
 * components directly: each sits inside an {@code ExtensionElement} whose
 * {@code target} names the path, in the <em>parent module's</em> tree, to graft
 * into (see {@link ComponentPath}). Combining is therefore resolving that path in
 * the module and appending the component there; uncombining is resolving it again
 * and taking the component back out. Neither step invents structure — a target
 * that does not resolve is reported, never guessed at.</p>
 *
 * <p><b>Neither direction touches a version number</b>, and combining does not
 * modify, activate or deactivate any extension: it only reads them.</p>
 */
public final class ExtensionCombiner {

    private static final Logger log = LoggerFactory.getLogger(ExtensionCombiner.class);

    private ExtensionCombiner() { }

    // =======================================================================
    // Combine
    // =======================================================================

    /** What one combine run did, for the summary and the log. */
    public static final class CombineReport {
        /** One line per component copied in: extension, target, component. */
        public final List<String> copied = new ArrayList<>();
        /** Components skipped because the module already had one with that identity. */
        public final List<String> alreadyPresent = new ArrayList<>();
        /** Components skipped because their target path does not exist in the module. */
        public final List<String> unresolved = new ArrayList<>();
        /** Image entries copied from an extension into the module. */
        public final Set<String> imagesCopied = new LinkedHashSet<>();
        /** Pre-defined setup (and other root-level) files copied in. */
        public final Set<String> filesCopied = new LinkedHashSet<>();
        /** Extensions that contributed at least one component. */
        public final Set<String> extensionsUsed = new LinkedHashSet<>();

        public boolean changedAnything() { return !copied.isEmpty(); }
    }

    /** One pending graft: the component wrapper, and the extension it came from. */
    private static final class Pending {
        final VassalArchive ext;
        final String extName;
        final Element wrapper;          // the ExtensionElement
        Pending(VassalArchive ext, String extName, Element wrapper) {
            this.ext = ext; this.extName = extName; this.wrapper = wrapper;
        }
    }

    /**
     * Copies every component of every given extension into {@code module}, each at
     * the place in the module tree its {@code target} names, together with the
     * images and setup files it references.
     *
     * <p>Grafts are applied in <b>repeated passes</b> rather than one sweep, because
     * one extension may graft into a component another extension supplies — a target
     * that does not resolve yet may resolve once an earlier pass has added the
     * component it names. Passes continue while any progress is made; whatever is
     * still unresolved after that genuinely is not in the module.</p>
     *
     * <p>The extensions are only read. The module is modified in memory and is the
     * caller's to save.</p>
     */
    public static CombineReport combine(VassalArchive module, List<VassalArchive> extensions)
            throws IOException {
        final CombineReport report = new CombineReport();
        final Element moduleRoot = module.getRootElement();
        final Document moduleDoc = module.getBuildDocument();

        final List<Pending> pending = new ArrayList<>();
        for (VassalArchive ext : extensions) {
            final String extName = nameOf(ext);
            for (Element child : ComponentPath.elementChildren(ext.getRootElement())) {
                collectWrappers(ext, extName, child, pending);
            }
        }

        boolean progress = true;
        while (progress && !pending.isEmpty()) {
            progress = false;
            final List<Pending> stillPending = new ArrayList<>();
            for (Pending p : pending) {
                final String target = p.wrapper.getAttribute("target");
                final Element parent = ComponentPath.resolve(moduleRoot, target);
                if (parent == null) {
                    stillPending.add(p);
                    continue;
                }
                graft(module, moduleDoc, parent, p, target, report);
                progress = true;
            }
            pending.clear();
            pending.addAll(stillPending);
        }

        for (Pending p : pending) {
            final String target = p.wrapper.getAttribute("target");
            for (Element comp : realComponents(p.wrapper)) {
                report.unresolved.add(p.extName + ": " + ComponentPath.label(comp)
                        + "  →  " + ComponentPath.labelTarget(target)
                        + "   (no such place in the module)");
            }
        }
        if (report.changedAnything()) module.setModified(true);
        return report;
    }

    /** Flattens an extension child into pending grafts, unwrapping any nested wrapper. */
    private static void collectWrappers(VassalArchive ext, String extName,
                                        Element child, List<Pending> out) {
        if (!ComponentPath.EXTENSION_ELEMENT.equals(child.getTagName())) {
            return;      // an extension's real content always sits inside a wrapper
        }
        boolean nestedOnly = false;
        for (Element inner : ComponentPath.elementChildren(child)) {
            if (ComponentPath.EXTENSION_ELEMENT.equals(inner.getTagName())) {
                // Double-wrapped (see Tools > Repair…): the inner target is absolute.
                collectWrappers(ext, extName, inner, out);
                nestedOnly = true;
            }
        }
        if (!nestedOnly) out.add(new Pending(ext, extName, child));
    }

    /** Copies one wrapper's components (and their assets) under {@code parent}. */
    private static void graft(VassalArchive module, Document moduleDoc, Element parent,
                              Pending p, String target, CombineReport report)
            throws IOException {
        for (Element comp : realComponents(p.wrapper)) {
            final String label = ComponentPath.label(comp);
            final Element existing = ComponentPath.findChild(
                    parent, comp.getTagName(), new ComponentNode(comp).getConfigureName());
            if (existing != null) {
                report.alreadyPresent.add(p.extName + ": " + label
                        + "  →  " + ComponentPath.labelTarget(target)
                        + "   (the module already has one; left alone)");
                continue;
            }
            parent.appendChild(moduleDoc.importNode(comp, true));
            copyAssetsInto(p.ext, module, comp, report.imagesCopied, report.filesCopied);
            report.copied.add(p.extName + ": " + label
                    + "  →  " + ComponentPath.labelTarget(target));
            report.extensionsUsed.add(p.extName);
        }
    }

    /** The wrapper's genuine components (a nested wrapper is handled separately). */
    private static List<Element> realComponents(Element wrapper) {
        final List<Element> out = new ArrayList<>();
        for (Element e : ComponentPath.elementChildren(wrapper)) {
            if (!ComponentPath.EXTENSION_ELEMENT.equals(e.getTagName())) out.add(e);
        }
        return out;
    }

    // =======================================================================
    // Uncombine
    // =======================================================================

    /** One object's fate during uncombine, for the log. */
    public static final class ChangeRecord {
        public enum Kind {
            /** The module's copy differed; the extension was updated to match it. */
            UPDATED,
            /** Module and extension agreed; the module's copy was removed. */
            REVERTED,
            /** The extension has it but the module does not; nothing done. */
            MISSING_IN_MODULE,
            /** The target path no longer exists in the module; nothing done. */
            TARGET_MISSING
        }
        public final Kind kind;
        public final String extension;
        public final String component;
        public final String target;
        public final String detail;

        ChangeRecord(Kind kind, String extension, String component,
                     String target, String detail) {
            this.kind = kind; this.extension = extension;
            this.component = component; this.target = target; this.detail = detail;
        }
    }

    /** What one uncombine run did. */
    public static final class UncombineReport {
        public final List<ChangeRecord> changes = new ArrayList<>();
        /** Extensions whose content changed and which therefore need saving. */
        public final Set<VassalArchive> modifiedExtensions = new LinkedHashSet<>();
        /** Image entries copied into an extension because its updated component needs them. */
        public final Set<String> imagesCopiedToExtensions = new LinkedHashSet<>();
        /** Setup files copied into an extension for the same reason. */
        public final Set<String> filesCopiedToExtensions = new LinkedHashSet<>();
        /** Components removed from the module. */
        public int removedFromModule;
        /**
         * Pre-defined setup files left referenced by nothing once the components
         * that used them went back to their extensions. Unused images are the
         * caller's to find ({@link VassalArchive#findUnusedImages()}); these are
         * not images, so they are worked out here.
         */
        public final Set<String> orphanedSetupFiles = new LinkedHashSet<>();

        public List<ChangeRecord> of(ChangeRecord.Kind kind) {
            final List<ChangeRecord> out = new ArrayList<>();
            for (ChangeRecord c : changes) if (c.kind == kind) out.add(c);
            return out;
        }
        public int count(ChangeRecord.Kind kind) { return of(kind).size(); }
        public boolean changedAnything() {
            return removedFromModule > 0 || !modifiedExtensions.isEmpty();
        }
    }

    /**
     * Takes every component the given extensions own back out of {@code module}.
     *
     * <p>For each component an extension contributes, the matching component is
     * looked up at the same place in the module:</p>
     * <ul>
     *   <li><b>Identical</b> — the module's copy is removed; the extension already
     *       holds it ({@link ChangeRecord.Kind#REVERTED}).</li>
     *   <li><b>Different</b> — the module's copy was edited, so it replaces the
     *       extension's, and is then removed from the module
     *       ({@link ChangeRecord.Kind#UPDATED}). The edit ends up in exactly one
     *       place, which is what makes this the inverse of {@link #combine}; any
     *       image or setup file the new version needs is copied into the extension
     *       too, or the updated extension would reference an asset it lacks.</li>
     *   <li><b>Absent</b> — recorded and nothing is touched. Deleting from an
     *       extension is not something this can infer from a missing component.</li>
     * </ul>
     *
     * <p>Components the module has that no extension claims are left where they
     * are: they are the module's own, or new work. Unused images are the caller's
     * to clear afterwards ({@link VassalArchive#findUnusedImages()}), since which
     * ones to drop is a decision to confirm. Nothing is written: the module and the
     * modified extensions are the caller's to save.</p>
     */
    public static UncombineReport uncombine(VassalArchive module, List<VassalArchive> extensions)
            throws IOException {
        final UncombineReport report = new UncombineReport();
        final Element moduleRoot = module.getRootElement();

        for (VassalArchive ext : extensions) {
            final String extName = nameOf(ext);
            final Document extDoc = ext.getBuildDocument();
            for (Element wrapper : wrappersOf(ext)) {
                final String target = wrapper.getAttribute("target");
                final String targetLabel = ComponentPath.labelTarget(target);
                final Element moduleParent = ComponentPath.resolve(moduleRoot, target);

                for (Element extComp : realComponents(wrapper)) {
                    final String label = ComponentPath.label(extComp);
                    if (moduleParent == null) {
                        report.changes.add(new ChangeRecord(ChangeRecord.Kind.TARGET_MISSING,
                                extName, label, targetLabel,
                                "the module has no such place any more"));
                        continue;
                    }
                    final Element modComp = ComponentPath.findChild(moduleParent,
                            extComp.getTagName(), new ComponentNode(extComp).getConfigureName());
                    if (modComp == null) {
                        report.changes.add(new ChangeRecord(ChangeRecord.Kind.MISSING_IN_MODULE,
                                extName, label, targetLabel,
                                "not present in the module; the extension is unchanged"));
                        continue;
                    }

                    if (sameComponent(modComp, extComp)) {
                        moduleParent.removeChild(modComp);
                        report.removedFromModule++;
                        report.changes.add(new ChangeRecord(ChangeRecord.Kind.REVERTED,
                                extName, label, targetLabel, "identical; removed from the module"));
                    }
                    else {
                        final String diff = firstDifference(modComp, extComp);
                        wrapper.replaceChild(extDoc.importNode(modComp, true), extComp);
                        copyAssetsInto(module, ext, modComp,
                                report.imagesCopiedToExtensions, report.filesCopiedToExtensions);
                        ext.setModified(true);
                        report.modifiedExtensions.add(ext);
                        moduleParent.removeChild(modComp);
                        report.removedFromModule++;
                        report.changes.add(new ChangeRecord(ChangeRecord.Kind.UPDATED,
                                extName, label, targetLabel, diff));
                    }
                }
            }
        }
        if (report.removedFromModule > 0) {
            module.setModified(true);
            findOrphanedSetupFiles(module, extensions, report.orphanedSetupFiles);
        }
        return report;
    }

    /**
     * The setup files an extension owns that nothing in the module references any
     * more. Only files an extension actually names are considered, so a module's
     * own saved game is never dropped just because this cannot see what uses it.
     */
    private static void findOrphanedSetupFiles(VassalArchive module,
                                               List<VassalArchive> extensions,
                                               Set<String> out) {
        final Set<String> fromExtensions = new LinkedHashSet<>();
        for (VassalArchive ext : extensions) {
            fromExtensions.addAll(
                    new ComponentNode(ext.getRootElement()).collectSetupFileReferences(true));
        }
        if (fromExtensions.isEmpty()) return;
        final Set<String> stillUsed =
                new ComponentNode(module.getRootElement()).collectSetupFileReferences(true);
        for (String f : fromExtensions) {
            if (!stillUsed.contains(f) && module.hasEntry(f)) out.add(f);
        }
    }

    /** Every wrapper of an extension, nested ones included, as a flat list. */
    private static List<Element> wrappersOf(VassalArchive ext) {
        final List<Pending> pending = new ArrayList<>();
        for (Element child : ComponentPath.elementChildren(ext.getRootElement())) {
            collectWrappers(ext, nameOf(ext), child, pending);
        }
        final List<Element> out = new ArrayList<>();
        for (Pending p : pending) out.add(p.wrapper);
        return out;
    }

    // =======================================================================
    // Comparison
    // =======================================================================

    /**
     * Whether two components are the same for the purpose of "has this been edited":
     * same tag, same attributes, same text, same children in order, recursively.
     *
     * <p>Whitespace between elements is ignored and text is compared trimmed,
     * because the archive is written with an indenting transformer: a component can
     * come back from a save-and-reload with different layout and identical meaning,
     * and treating that as an edit would copy every component into every extension
     * on the first run.</p>
     */
    public static boolean sameComponent(Element a, Element b) {
        if (!a.getTagName().equals(b.getTagName())) return false;
        if (!sameAttributes(a, b)) return false;
        if (!textOf(a).equals(textOf(b))) return false;
        final List<Element> ca = ComponentPath.elementChildren(a);
        final List<Element> cb = ComponentPath.elementChildren(b);
        if (ca.size() != cb.size()) return false;
        for (int i = 0; i < ca.size(); i++) {
            if (!sameComponent(ca.get(i), cb.get(i))) return false;
        }
        return true;
    }

    private static boolean sameAttributes(Element a, Element b) {
        final Map<String, String> ma = attributes(a);
        final Map<String, String> mb = attributes(b);
        return ma.equals(mb);
    }

    private static Map<String, String> attributes(Element e) {
        final Map<String, String> out = new LinkedHashMap<>();
        final org.w3c.dom.NamedNodeMap at = e.getAttributes();
        for (int i = 0; i < at.getLength(); i++) {
            final Node n = at.item(i);
            out.put(n.getNodeName(), n.getNodeValue());
        }
        return out;
    }

    /**
     * The element's own text (its direct text and CDATA children), trimmed. For a
     * {@code PieceSlot} this is the serialised piece definition — the part that
     * actually differs when a counter is edited.
     */
    private static String textOf(Element e) {
        final StringBuilder sb = new StringBuilder();
        final NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            final Node n = kids.item(i);
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(n.getNodeValue());
            }
        }
        return sb.toString().trim();
    }

    /** A short human-readable reason two components differ, for the log. */
    static String firstDifference(Element mod, Element ext) {
        if (!sameAttributes(mod, ext)) {
            final Map<String, String> ma = attributes(mod);
            final Map<String, String> mb = attributes(ext);
            final Set<String> keys = new LinkedHashSet<>(ma.keySet());
            keys.addAll(mb.keySet());
            final List<String> changed = new ArrayList<>();
            for (String k : keys) {
                if (!Objects.equals(ma.get(k), mb.get(k))) changed.add(k);
            }
            changed.sort(Comparator.naturalOrder());
            return "attribute(s) changed: " + String.join(", ", changed);
        }
        if (!textOf(mod).equals(textOf(ext))) {
            return "definition text changed";
        }
        final int na = ComponentPath.elementChildren(mod).size();
        final int nb = ComponentPath.elementChildren(ext).size();
        if (na != nb) {
            return "child components: " + nb + " → " + na;
        }
        return "a child component changed";
    }

    // =======================================================================
    // Assets
    // =======================================================================

    /**
     * Copies every image and Pre-defined-setup file {@code comp}'s subtree references
     * from {@code from} into {@code to}, skipping anything {@code to} already has.
     * Each copy keeps its source entry's modification time, because VASSAL's tile
     * cache decides whether a board image needs re-tiling by comparing mtimes.
     */
    private static void copyAssetsInto(VassalArchive from, VassalArchive to, Element comp,
                                       Set<String> imagesOut, Set<String> filesOut)
            throws IOException {
        final ComponentNode node = new ComponentNode(comp);

        for (String img : node.collectImageReferences(from.getImageNames(), true)) {
            if (to.getImageNames().contains(img)) continue;
            final String entry = VassalArchive.IMAGE_DIR + img;
            try {
                to.addPendingImage(img, from.readEntry(entry), from.getEntryTime(entry));
                imagesOut.add(img);
            } catch (IOException e) {
                log.warn("Could not copy image {} from {}: {}", img, nameOf(from), e.toString());
            }
        }

        for (String f : node.collectSetupFileReferences(true)) {
            if (to.hasEntry(f)) continue;
            try {
                to.addPendingFile(f, from.readEntry(f), from.getEntryTime(f));
                filesOut.add(f);
            } catch (IOException e) {
                log.warn("Could not copy setup file {} from {}: {}", f, nameOf(from), e.toString());
            }
        }
    }

    private static String nameOf(VassalArchive a) {
        return a.getFile() != null ? a.getFile().getName() : a.getDisplayName();
    }
}
