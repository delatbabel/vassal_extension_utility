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
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which extension supplies which piece definition, for a module and the
 * extensions in its {@code _ext} directory.
 *
 * <p>Used to work out the extensions a saved game actually depends on: every
 * piece in a save carries the GPID of the slot it came from, so mapping those
 * GPIDs back to their archives gives the true dependency list — as opposed to the
 * list the save happens to record, which is only what was loaded when it was last
 * written.</p>
 *
 * <p>The attribution is exact only while GPIDs are unique across the module and
 * its extensions. They are supposed to be — VASSAL refuses to refresh at all
 * otherwise (see {@code refresh/RefreshRunner}) — but two archives claiming one
 * GPID would make this last-one-wins, so duplicates are counted and exposed via
 * {@link #getDuplicateCount()} rather than passed over in silence.</p>
 *
 * <p>Read from the files rather than from a built {@code GameModule}: the engine
 * grafts every extension's components into one module-wide tree, which makes
 * asking "which archive did this slot come from?" awkward, and doing it this way
 * keeps the class usable without VASSAL on the classpath.</p>
 */
public final class ExtensionIndex {

    private static final Logger log = LoggerFactory.getLogger(ExtensionIndex.class);

    private static final String[] SLOT_TAGS = {
        "VASSAL.build.widget.PieceSlot", "VASSAL.build.widget.CardSlot"
    };

    /** Name used for the module itself, which is never an extension dependency. */
    public static final String MODULE = "";

    private final Map<String, String> extensionByGpid = new HashMap<>();
    private final Map<String, String> versionByExtension = new LinkedHashMap<>();
    private int duplicates;

    private ExtensionIndex() { }

    /**
     * Reads the module and every {@code .vmdx} in its {@code _ext} directory.
     * An archive that cannot be read is logged and skipped — the caller is
     * already refusing to proceed if the module itself is unusable.
     */
    public static ExtensionIndex read(File moduleFile, List<File> extensionFiles) {
        final ExtensionIndex index = new ExtensionIndex();
        index.add(moduleFile, MODULE);
        for (File f : extensionFiles) {
            index.add(f, stripSuffix(f.getName()));
        }
        return index;
    }

    /** {@code 10-SiF.vmdx} → {@code 10-SiF}, matching {@code ModuleExtension.getName()}. */
    public static String stripSuffix(String fileName) {
        final int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private void add(File archive, String owner) {
        final VassalArchive va;
        try {
            va = VassalArchive.open(archive);
        }
        catch (Exception e) {                       // NOPMD - one bad archive must not stop the rest
            log.warn("Could not index {}: {}", archive, e.toString());
            return;
        }
        if (!MODULE.equals(owner)) {
            versionByExtension.put(owner, va.getExtensionVersion());
        }
        collect(va.getRootElement(), owner);
    }

    private void collect(Element el, String owner) {
        if (isSlot(el.getTagName())) {
            final String gpid = el.getAttribute("gpid").trim();
            if (!gpid.isEmpty()) {
                final String previous = extensionByGpid.put(gpid, owner);
                if (previous != null && !previous.equals(owner)) {
                    duplicates++;
                }
            }
        }
        final NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node n = children.item(i);
            if (n instanceof Element) collect((Element) n, owner);
        }
    }

    private static boolean isSlot(String tagName) {
        for (String t : SLOT_TAGS) {
            if (t.equals(tagName)) return true;
        }
        return false;
    }

    /** GPIDs claimed by more than one archive; should be 0. */
    public int getDuplicateCount() { return duplicates; }

    public int size() { return extensionByGpid.size(); }

    /** The recorded version of an indexed extension, or {@code null}. */
    public String versionOf(String extensionName) {
        return versionByExtension.get(extensionName);
    }

    /**
     * The extensions supplying any of {@code gpids}, in indexing order. GPIDs
     * belonging to the module, and GPIDs no archive defines (an orphaned piece —
     * see {@code docs/vsav-excess-units.md}), contribute nothing.
     */
    public Set<String> extensionsFor(Set<String> gpids) {
        final Set<String> out = new LinkedHashSet<>();
        for (String name : versionByExtension.keySet()) {
            out.add(name);                          // seed order, filtered below
        }
        final Set<String> needed = new LinkedHashSet<>();
        for (String name : out) {
            for (String gpid : gpids) {
                if (name.equals(extensionByGpid.get(gpid))) {
                    needed.add(name);
                    break;
                }
            }
        }
        return Collections.unmodifiableSet(needed);
    }
}
