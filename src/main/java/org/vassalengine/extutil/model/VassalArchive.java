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

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Represents an open VASSAL archive (.vmod or .vmdx).
 * Provides access to the buildFile.xml DOM and the set of image assets.
 * Supports adding images from another archive and saving back to disk.
 */
public class VassalArchive {

    private static final Logger log = LoggerFactory.getLogger(VassalArchive.class);

    public static final String BUILD_FILE = "buildFile.xml";
    public static final String IMAGE_DIR = "images/";
    public static final String EXT_ROOT = "VASSAL.build.module.ModuleExtension";
    public static final String MOD_ROOT = "VASSAL.build.GameModule";

    private File file;
    private Document buildDocument;
    private Set<String> imageNames;           // bare names without "images/" prefix
    private Set<String> entryNames;           // all non-directory ZIP entry names (full paths)
    private Map<String, byte[]> pendingImages; // images to be added on next save
    private Map<String, byte[]> pendingFiles;  // non-image entries to add on next save (full names)
    private Set<String> pendingDeletions;      // entry names to drop on next save (full names)
    private boolean modified;

    // -----------------------------------------------------------------------
    // Opening
    // -----------------------------------------------------------------------

    public static VassalArchive open(File f) throws Exception {
        VassalArchive va = new VassalArchive();
        va.file = f;
        va.pendingImages = new HashMap<>();
        va.pendingFiles = new HashMap<>();
        va.pendingDeletions = new HashSet<>();
        va.modified = false;
        va.load();
        return va;
    }

    private void load() throws Exception {
        imageNames = new HashSet<>();
        entryNames = new HashSet<>();
        try (ZipFile zf = new ZipFile(file)) {
            // Collect entry names (and the subset under images/)
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                entryNames.add(e.getName());
                if (e.getName().startsWith(IMAGE_DIR)) {
                    imageNames.add(e.getName().substring(IMAGE_DIR.length()));
                }
            }
            // Parse buildFile.xml
            ZipEntry buildEntry = zf.getEntry(BUILD_FILE);
            if (buildEntry == null) {
                throw new IOException("Not a valid VASSAL archive — missing " + BUILD_FILE);
            }
            try (InputStream is = zf.getInputStream(buildEntry)) {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                DocumentBuilder db = dbf.newDocumentBuilder();
                buildDocument = db.parse(is);
                buildDocument.normalizeDocument();
            }
        }
        log.info("Opened {} ({} images)", file.getName(), imageNames.size());
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public File getFile() { return file; }

    public Document getBuildDocument() { return buildDocument; }

    public Element getRootElement() { return buildDocument.getDocumentElement(); }

    public boolean isExtension() {
        return EXT_ROOT.equals(getRootElement().getTagName());
    }

    public boolean isModified() { return modified; }

    public void setModified(boolean m) { modified = m; }

    /** Returns bare image names (no "images/" prefix). */
    public Set<String> getImageNames() { return imageNames; }

    public String getDisplayName() {
        String name = file.getName();
        return modified ? name + " *" : name;
    }

    // -----------------------------------------------------------------------
    // Reading entries
    // -----------------------------------------------------------------------

    /**
     * Reads a raw ZIP entry by name and returns its bytes.
     * Returns null if the entry does not exist.
     */
    public byte[] readEntry(String entryName) throws IOException {
        try (ZipFile zf = new ZipFile(file)) {
            ZipEntry entry = zf.getEntry(entryName);
            if (entry == null) return null;
            try (InputStream is = zf.getInputStream(entry)) {
                return is.readAllBytes();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Adding images
    // -----------------------------------------------------------------------

    /**
     * Queues an image to be written to this archive on the next save.
     * If the image already exists in the archive it is still queued (overwrite on save).
     */
    public void addPendingImage(String bareName, byte[] data) {
        pendingImages.put(bareName, data);
        imageNames.add(bareName);
        modified = true;
    }

    /** True if the archive contains (or has queued) a ZIP entry with this exact name. */
    public boolean hasEntry(String entryName) {
        return entryNames.contains(entryName);
    }

    /**
     * Queues a non-image entry (e.g. a Pre-defined setup's {@code .vsav} save file)
     * to be written to this archive on the next save, under its exact entry name.
     * If the entry already exists it is still queued (overwrite on save).
     */
    public void addPendingFile(String entryName, byte[] data) {
        pendingFiles.put(entryName, data);
        pendingDeletions.remove(entryName);
        entryNames.add(entryName);
        modified = true;
    }

    /**
     * Marks an entry to be dropped from this archive on the next save (e.g. a
     * Pre-defined setup's {@code .vsav} save file orphaned by a Move).  No effect
     * if the entry is not present.
     */
    public void removeEntry(String entryName) {
        if (!entryNames.remove(entryName)) return;
        pendingFiles.remove(entryName);
        pendingDeletions.add(entryName);
        modified = true;
    }

    // -----------------------------------------------------------------------
    // Saving
    // -----------------------------------------------------------------------

    /**
     * Serialises the current buildDocument and writes the entire archive to disk,
     * merging in any pending images.
     */
    public void save() throws Exception {
        byte[] xmlBytes = serialiseBuildFile();
        File tmp = File.createTempFile("vassalext_", ".zip", file.getParentFile());
        try {
            try (ZipFile source = new ZipFile(file);
                 ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp.toPath()))) {

                out.setMethod(ZipOutputStream.DEFLATED);

                // Copy existing entries, skipping buildFile.xml (we'll rewrite it)
                // and skipping images that are being overwritten by pending images
                Enumeration<? extends ZipEntry> entries = source.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.equals(BUILD_FILE)) continue;
                    if (pendingDeletions.contains(name)) continue;     // dropped from this archive
                    if (name.startsWith(IMAGE_DIR)) {
                        String bare = name.substring(IMAGE_DIR.length());
                        if (pendingImages.containsKey(bare)) continue; // will be written below
                    }
                    if (pendingFiles.containsKey(name)) continue;      // will be written below
                    ZipEntry newEntry = new ZipEntry(name);
                    out.putNextEntry(newEntry);
                    try (InputStream is = source.getInputStream(entry)) {
                        is.transferTo(out);
                    }
                    out.closeEntry();
                }

                // Write pending images
                for (Map.Entry<String, byte[]> img : pendingImages.entrySet()) {
                    ZipEntry imgEntry = new ZipEntry(IMAGE_DIR + img.getKey());
                    out.putNextEntry(imgEntry);
                    out.write(img.getValue());
                    out.closeEntry();
                }

                // Write pending non-image entries (e.g. .vsav save files) under their exact names
                for (Map.Entry<String, byte[]> entry : pendingFiles.entrySet()) {
                    ZipEntry ze = new ZipEntry(entry.getKey());
                    out.putNextEntry(ze);
                    out.write(entry.getValue());
                    out.closeEntry();
                }

                // Write updated buildFile.xml
                ZipEntry buildEntry = new ZipEntry(BUILD_FILE);
                out.putNextEntry(buildEntry);
                out.write(xmlBytes);
                out.closeEntry();
            }

            // Atomically replace original
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            pendingImages.clear();
            pendingFiles.clear();
            pendingDeletions.clear();
            modified = false;
            log.info("Saved {}", file.getName());

        } catch (Exception e) {
            Files.deleteIfExists(tmp.toPath());
            throw e;
        }
    }

    private byte[] serialiseBuildFile() throws Exception {
        Transformer tf = TransformerFactory.newInstance().newTransformer();
        tf.setOutputProperty(OutputKeys.INDENT, "yes");
        tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        tf.setOutputProperty(OutputKeys.STANDALONE, "no");
        tf.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        tf.transform(new DOMSource(buildDocument), new StreamResult(baos));
        return baos.toByteArray();
    }
}
