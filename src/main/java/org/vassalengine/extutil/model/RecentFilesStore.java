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
 * Persists the most-recently-opened files for the left and right panels, and
 * the directory each kind of file was last opened from.
 *
 * Each panel keeps its own ordered list (most-recent first), capped at
 * {@link #MAX_RECENT} entries.  The lists are stored as a Java properties
 * file under the user's home directory:
 * <pre>{@code ~/.vassal-extension-utility/recent-files.properties}</pre>
 * with keys {@code left.0..left.4} and {@code right.0..right.4}.
 *
 * The same file also remembers, per file kind, the directory the user last
 * opened that kind of file from ({@code lastdir.module}, {@code
 * lastdir.extension}, {@code lastdir.savedGame}), which the file choosers use
 * as their starting directory the next time — see
 * {@link #getLastDir(String)} / {@link #setLastDirFrom(String, File)}.
 *
 * All disk operations fail soft: a missing or unreadable store simply yields
 * empty lists, and save errors are logged rather than propagated, so recent-file
 * bookkeeping never blocks opening or saving archives.
 */
public class RecentFilesStore {

    private static final Logger log = LoggerFactory.getLogger(RecentFilesStore.class);

    /** Maximum number of remembered files per panel. */
    public static final int MAX_RECENT = 5;

    /** Last-directory category: opening a module. */
    public static final String DIR_MODULE = "module";
    /** Last-directory category: opening an extension. */
    public static final String DIR_EXTENSION = "extension";
    /** Last-directory category: opening a saved game. */
    public static final String DIR_SAVED_GAME = "savedGame";

    private static final String CONFIG_DIR  = ".vassal-extension-utility";
    private static final String CONFIG_FILE = "recent-files.properties";
    private static final String LEFT_PREFIX  = "left.";
    private static final String RIGHT_PREFIX = "right.";
    private static final String LASTDIR_PREFIX = "lastdir.";

    private final File storeFile;
    private final List<String> left  = new ArrayList<>();
    private final List<String> right = new ArrayList<>();
    private final java.util.Map<String, String> lastDirs = new java.util.LinkedHashMap<>();

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

    /**
     * The directory files of the given category were last opened from, or
     * {@code null} when none is recorded or it no longer exists — callers then
     * fall back to their own default.
     *
     * @param category one of {@link #DIR_MODULE}, {@link #DIR_EXTENSION},
     *                 {@link #DIR_SAVED_GAME}
     */
    public File getLastDir(String category) {
        String path = lastDirs.get(category);
        if (path == null || path.isEmpty()) return null;
        File dir = new File(path);
        return dir.isDirectory() ? dir : null;
    }

    /**
     * Records the directory to start the given category's file chooser in next
     * time: {@code chosen} itself if it is a directory, else its parent.
     */
    public void setLastDirFrom(String category, File chosen) {
        if (chosen == null) return;
        File dir = chosen.isDirectory() ? chosen : chosen.getAbsoluteFile().getParentFile();
        if (dir == null) return;
        String path = dir.getAbsolutePath();
        if (path.equals(lastDirs.get(category))) return;
        lastDirs.put(category, path);
        save();
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
        lastDirs.clear();
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
        for (String name : props.stringPropertyNames()) {
            if (name.startsWith(LASTDIR_PREFIX)) {
                lastDirs.put(name.substring(LASTDIR_PREFIX.length()), props.getProperty(name));
            }
        }
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
        for (java.util.Map.Entry<String, String> e : lastDirs.entrySet()) {
            props.setProperty(LASTDIR_PREFIX + e.getKey(), e.getValue());
        }
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
