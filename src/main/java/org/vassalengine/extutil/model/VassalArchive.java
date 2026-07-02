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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
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
    private Map<String, Long> pendingImageTimes; // bareName -> source mod time (epoch ms), if known
    private Map<String, Long> pendingFileTimes;  // entry name -> source mod time (epoch ms), if known
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
        va.pendingImageTimes = new HashMap<>();
        va.pendingFileTimes = new HashMap<>();
        va.pendingDeletions = new HashSet<>();
        va.modified = false;
        va.load();
        return va;
    }

    /**
     * Creates a new, empty extension (.vmdx) bound to the given parent module.
     *
     * The returned archive is in-memory only with no backing file — it is marked
     * modified and must be written with {@link #saveAs(File)}.  Its buildFile root
     * is a {@code ModuleExtension} referencing the module's name and version; it
     * carries a freshly built {@code extensiondata} and a copy of the module's
     * {@code moduledata} (VASSAL stores the parent module's moduledata inside the
     * extension).  The {@code extensionId} is generated the way VASSAL does — the
     * last three characters of a random UUID.
     *
     * @param module a loaded module (not an extension)
     */
    public static VassalArchive createExtension(VassalArchive module) throws Exception {
        if (module == null || module.isExtension()) {
            throw new IllegalArgumentException("A module must be loaded to create an extension.");
        }

        VassalArchive va = new VassalArchive();
        va.file = null;                       // unsaved — requires saveAs()
        va.imageNames = new HashSet<>();
        va.entryNames = new HashSet<>();
        va.pendingImages = new HashMap<>();
        va.pendingFiles = new HashMap<>();
        va.pendingImageTimes = new HashMap<>();
        va.pendingFileTimes = new HashMap<>();
        va.pendingDeletions = new HashSet<>();

        Element modRoot = module.getRootElement();
        String moduleName    = modRoot.getAttribute("name");
        String moduleVersion = modRoot.getAttribute("version");
        String vassalVersion = modRoot.getAttribute("VassalVersion");
        String extensionId   = UUID.randomUUID().toString();
        extensionId = extensionId.substring(extensionId.length() - 3);
        // Start the extension at the parent module's version, not 0.0.
        String extVersion = (moduleVersion == null || moduleVersion.isEmpty())
                ? "0.0" : moduleVersion;

        DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document doc = db.newDocument();
        Element root = doc.createElement(EXT_ROOT);
        root.setAttribute("anyModule", "false");
        root.setAttribute("description", "");
        root.setAttribute("extensionId", extensionId);
        root.setAttribute("module", moduleName);
        root.setAttribute("moduleVersion", moduleVersion);
        root.setAttribute("nextPieceSlotId", "0");
        root.setAttribute("vassalVersion", vassalVersion);
        root.setAttribute("version", extVersion);
        doc.appendChild(root);
        va.buildDocument = doc;

        // The extension embeds a copy of the parent module's moduledata.
        byte[] moduledata = module.readEntry("moduledata");
        if (moduledata != null) {
            va.addPendingFile("moduledata", moduledata);
        } else {
            log.warn("Module {} has no moduledata entry", module.getFile());
        }
        va.addPendingFile("extensiondata", buildExtensionData(extVersion, vassalVersion));
        va.entryNames.add(BUILD_FILE);
        va.modified = true;
        return va;
    }

    private static byte[] buildExtensionData(String extVersion, String vassalVersion) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
                + "<data version=\"1\">\n"
                + "  <version>" + xmlEscape(extVersion) + "</version>\n"
                + "  <extra1/>\n"
                + "  <extra2/>\n"
                + "  <VassalVersion>" + xmlEscape(vassalVersion) + "</VassalVersion>\n"
                + "  <dateSaved>" + System.currentTimeMillis() + "</dateSaved>\n"
                + "  <description></description>\n"
                + "  <universal>false</universal>\n"
                + "</data>\n";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static String xmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
        String name = (file != null) ? file.getName() : "(unsaved extension)";
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

    /**
     * Returns the modification time (epoch millis) of a ZIP entry, or -1 if the
     * entry is absent or this archive has no backing file.  Used to preserve an
     * asset's timestamp when it is copied to another archive — VASSAL keys its
     * image-tile cache freshness on entry modification time, so resetting it to
     * "now" would force large images to be re-tiled on every load.
     */
    public long getEntryTime(String entryName) throws IOException {
        if (file == null) return -1L;
        try (ZipFile zf = new ZipFile(file)) {
            ZipEntry entry = zf.getEntry(entryName);
            return (entry == null) ? -1L : entry.getTime();
        }
    }

    // -----------------------------------------------------------------------
    // Adding images
    // -----------------------------------------------------------------------

    /** Queues an image to be written on the next save, keeping no source timestamp. */
    public void addPendingImage(String bareName, byte[] data) {
        addPendingImage(bareName, data, -1L);
    }

    /**
     * Queues an image to be written to this archive on the next save, stamped with
     * {@code timeMillis} (its source modification time; pass a negative value to
     * use the current time).  If the image already exists it is still queued
     * (overwrite on save).
     */
    public void addPendingImage(String bareName, byte[] data, long timeMillis) {
        pendingImages.put(bareName, data);
        if (timeMillis >= 0) pendingImageTimes.put(bareName, timeMillis);
        else pendingImageTimes.remove(bareName);
        pendingDeletions.remove(IMAGE_DIR + bareName);
        imageNames.add(bareName);
        modified = true;
    }

    /** True if the archive contains (or has queued) a ZIP entry with this exact name. */
    public boolean hasEntry(String entryName) {
        return entryNames.contains(entryName);
    }

    /** Queues a non-image entry to be written on the next save, keeping no source timestamp. */
    public void addPendingFile(String entryName, byte[] data) {
        addPendingFile(entryName, data, -1L);
    }

    /**
     * Queues a non-image entry (e.g. a Pre-defined setup's {@code .vsav} save file)
     * to be written on the next save, stamped with {@code timeMillis} (its source
     * modification time; pass a negative value to use the current time).  If the
     * entry already exists it is still queued (overwrite on save).
     */
    public void addPendingFile(String entryName, byte[] data, long timeMillis) {
        pendingFiles.put(entryName, data);
        if (timeMillis >= 0) pendingFileTimes.put(entryName, timeMillis);
        else pendingFileTimes.remove(entryName);
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
        pendingFileTimes.remove(entryName);
        pendingDeletions.add(entryName);
        modified = true;
    }

    /**
     * Marks an image (bare name) to be dropped from this archive on the next save,
     * and removes it from the live image set.  No effect if the image is absent.
     */
    public void removeImage(String bareName) {
        removeEntry(IMAGE_DIR + bareName);
        imageNames.remove(bareName);
        pendingImages.remove(bareName);
        pendingImageTimes.remove(bareName);
    }

    // -----------------------------------------------------------------------
    // Unused images
    // -----------------------------------------------------------------------

    /**
     * Returns the bare names of images present in this archive that are not
     * referenced by any component in its build tree — the candidates for VASSAL's
     * "Remove Unused Images" operation.  Detection is a heuristic (an image may
     * still be used by custom code), so removal is always user-confirmed; the
     * legacy suffix-less {@code .gif} reference form is honoured (see
     * {@link ComponentNode#collectReferencedImages}).
     */
    public Set<String> findUnusedImages() {
        Set<String> referenced =
                new ComponentNode(getRootElement()).collectReferencedImages(imageNames);
        Set<String> unused = new TreeSet<>(imageNames);
        unused.removeAll(referenced);
        return unused;
    }

    // -----------------------------------------------------------------------
    // Saving
    // -----------------------------------------------------------------------

    /**
     * Serialises the current buildDocument and writes the entire archive back to
     * its existing file, merging in any pending images/files and applying pending
     * deletions.
     *
     * @throws IllegalStateException if this archive has no file yet (use {@link #saveAs(File)}).
     */
    public void save() throws Exception {
        if (file == null) {
            throw new IllegalStateException("Archive has no file; use saveAs(File).");
        }
        writeArchive(file, file);
    }

    /**
     * Writes the archive to {@code target} and makes it the archive's file.  Used
     * to save a newly created extension, or to write an existing archive to a new
     * location.  The target's parent directory is created if necessary.
     */
    public void saveAs(File target) throws Exception {
        File source = (file != null && file.isFile()) ? file : null;
        writeArchive(source, target);
        file = target;
    }

    /**
     * Writes the archive to {@code target}, copying surviving entries from
     * {@code source} (its current backing file, or null for a brand-new archive)
     * and merging pending images/files and the rewritten buildFile.xml.  On
     * success pending changes are cleared and the modified flag is reset.
     */
    private void writeArchive(File source, File target) throws Exception {
        byte[] xmlBytes = serialiseBuildFile();
        Files.createDirectories(target.getAbsoluteFile().getParentFile().toPath());
        File tmp = File.createTempFile("vassalext_", ".zip",
                target.getAbsoluteFile().getParentFile());
        try {
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp.toPath()))) {
                out.setMethod(ZipOutputStream.DEFLATED);

                // Copy surviving entries from the source archive (if any), skipping
                // buildFile.xml (rewritten), deleted entries, and entries replaced
                // by pending images/files.
                if (source != null && source.isFile()) {
                    try (ZipFile zf = new ZipFile(source)) {
                        Enumeration<? extends ZipEntry> entries = zf.entries();
                        while (entries.hasMoreElements()) {
                            ZipEntry entry = entries.nextElement();
                            String name = entry.getName();
                            if (name.equals(BUILD_FILE)) continue;
                            if (pendingDeletions.contains(name)) continue;
                            if (name.startsWith(IMAGE_DIR)) {
                                String bare = name.substring(IMAGE_DIR.length());
                                if (pendingImages.containsKey(bare)) continue;
                            }
                            if (pendingFiles.containsKey(name)) continue;
                            // Preserve the entry's modification time — VASSAL keys
                            // its image-tile cache freshness on it, so stamping
                            // "now" would force large images to be re-tiled.
                            ZipEntry copy = new ZipEntry(name);
                            long mtime = entry.getTime();
                            if (mtime >= 0) copy.setTime(mtime);
                            out.putNextEntry(copy);
                            try (InputStream is = zf.getInputStream(entry)) {
                                is.transferTo(out);
                            }
                            out.closeEntry();
                        }
                    }
                }

                // Write pending images (preserving their source modification time)
                for (Map.Entry<String, byte[]> img : pendingImages.entrySet()) {
                    ZipEntry ze = new ZipEntry(IMAGE_DIR + img.getKey());
                    Long mtime = pendingImageTimes.get(img.getKey());
                    if (mtime != null) ze.setTime(mtime);
                    out.putNextEntry(ze);
                    out.write(img.getValue());
                    out.closeEntry();
                }

                // Write pending non-image entries (moduledata, extensiondata, .vsav, …)
                for (Map.Entry<String, byte[]> entry : pendingFiles.entrySet()) {
                    ZipEntry ze = new ZipEntry(entry.getKey());
                    Long mtime = pendingFileTimes.get(entry.getKey());
                    if (mtime != null) ze.setTime(mtime);
                    out.putNextEntry(ze);
                    out.write(entry.getValue());
                    out.closeEntry();
                }

                // Write the buildFile.xml
                out.putNextEntry(new ZipEntry(BUILD_FILE));
                out.write(xmlBytes);
                out.closeEntry();
            }

            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            pendingImages.clear();
            pendingFiles.clear();
            pendingImageTimes.clear();
            pendingFileTimes.clear();
            pendingDeletions.clear();
            modified = false;
            log.info("Saved {}", target.getName());

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
