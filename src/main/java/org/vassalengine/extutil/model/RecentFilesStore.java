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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Persists the most-recently-opened files for the left and right panels.
 *
 * Each panel keeps its own ordered list (most-recent first), capped at
 * {@link #MAX_RECENT} entries.  The two lists are stored as a Java properties
 * file under the user's home directory:
 * <pre>{@code ~/.vassal-extension-utility/recent-files.properties}</pre>
 * with keys {@code left.0..left.4} and {@code right.0..right.4}.
 *
 * All disk operations fail soft: a missing or unreadable store simply yields
 * empty lists, and save errors are logged rather than propagated, so recent-file
 * bookkeeping never blocks opening or saving archives.
 */
public class RecentFilesStore {

    private static final Logger log = LoggerFactory.getLogger(RecentFilesStore.class);

    /** Maximum number of remembered files per panel. */
    public static final int MAX_RECENT = 5;

    private static final String CONFIG_DIR  = ".vassal-extension-utility";
    private static final String CONFIG_FILE = "recent-files.properties";
    private static final String LEFT_PREFIX  = "left.";
    private static final String RIGHT_PREFIX = "right.";

    private final File storeFile;
    private final List<String> left  = new ArrayList<>();
    private final List<String> right = new ArrayList<>();

    public RecentFilesStore() {
        this(new File(new File(System.getProperty("user.home"), CONFIG_DIR), CONFIG_FILE));
    }

    /** Package-visible constructor allowing the store location to be overridden (tests). */
    RecentFilesStore(File storeFile) {
        this.storeFile = storeFile;
        load();
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    /** Recent files for the left panel, most-recent first. */
    public List<File> getLeft()  { return toFiles(left); }

    /** Recent files for the right panel, most-recent first. */
    public List<File> getRight() { return toFiles(right); }

    /** Records {@code file} as the most-recently-opened file for the left panel. */
    public void addLeft(File file)  { add(left, file);  save(); }

    /** Records {@code file} as the most-recently-opened file for the right panel. */
    public void addRight(File file) { add(right, file); save(); }

    /** Removes {@code file} from both panels' lists (e.g. when it no longer exists). */
    public void remove(File file) {
        String path = file.getAbsolutePath();
        boolean changed = left.remove(path) | right.remove(path);
        if (changed) save();
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private static void add(List<String> list, File file) {
        String path = file.getAbsolutePath();
        list.remove(path);          // de-dupe: move existing entry to the front
        list.add(0, path);
        while (list.size() > MAX_RECENT) {
            list.remove(list.size() - 1);
        }
    }

    private static List<File> toFiles(List<String> paths) {
        List<File> files = new ArrayList<>(paths.size());
        for (String p : paths) files.add(new File(p));
        return files;
    }

    private void load() {
        left.clear();
        right.clear();
        if (!storeFile.isFile()) return;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(storeFile.toPath())) {
            props.load(in);
        } catch (IOException e) {
            log.warn("Could not read recent-files store {}", storeFile, e);
            return;
        }
        loadList(props, LEFT_PREFIX,  left);
        loadList(props, RIGHT_PREFIX, right);
    }

    private static void loadList(Properties props, String prefix, List<String> into) {
        for (int i = 0; i < MAX_RECENT; i++) {
            String value = props.getProperty(prefix + i);
            if (value != null && !value.isEmpty()) into.add(value);
        }
    }

    private void save() {
        Properties props = new Properties();
        storeList(props, LEFT_PREFIX,  left);
        storeList(props, RIGHT_PREFIX, right);
        try {
            Files.createDirectories(storeFile.getParentFile().toPath());
            try (OutputStream out = Files.newOutputStream(storeFile.toPath())) {
                props.store(out, "VASSAL Extension Utility — recently opened files");
            }
        } catch (IOException e) {
            log.warn("Could not write recent-files store {}", storeFile, e);
        }
    }

    private static void storeList(Properties props, String prefix, List<String> list) {
        for (int i = 0; i < list.size(); i++) {
            props.setProperty(prefix + i, list.get(i));
        }
    }
}
