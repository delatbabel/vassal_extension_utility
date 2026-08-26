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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a project from the VASSAL game library and downloads its files.
 *
 * <p>The library's REST API is the one the library website itself uses — the
 * frontend's {@code config.toml.sample} names it as
 * {@code https://vassalengine.org/api/gls/v1}. {@code GET /projects/{project}}
 * returns the whole project as JSON: packages, each with releases, each with
 * files carrying a direct download {@code url}, {@code size} and {@code sha256}.</p>
 *
 * <p><b>Choosing "the latest" is per file, not per release.</b> A release is a
 * batch upload, so the same extension may appear in several: in the WiF project
 * {@code 23-DoD-III.vmdx} is in releases 2.1.3, 2.1.2 and 2.1.1, and
 * {@code 10-SiF.vmdx} in 2.1.2 and 2.1.1, while most extensions only ever
 * appeared in 2.1.1. Taking the newest release alone would fetch two extensions
 * and miss twenty-two; taking every release would fetch three copies of some. So
 * {@link Package#latestFiles()} groups by filename and keeps the copy from the
 * highest release version, comparing versions numerically component-by-component
 * and falling back to publication time.</p>
 */
public final class GameLibrary {

    private static final Logger log = LoggerFactory.getLogger(GameLibrary.class);

    /** The public API the library website uses. */
    public static final String DEFAULT_API = "https://vassalengine.org/api/gls/v1";

    private static final int TIMEOUT_MS = 30_000;
    private static final String AGENT = "VASSAL-Extension-Utility";

    private final String apiBase;

    public GameLibrary(String apiBase) {
        this.apiBase = (apiBase == null || apiBase.trim().isEmpty()
                ? DEFAULT_API : apiBase.trim()).replaceAll("/+$", "");
    }

    // -----------------------------------------------------------------------
    // Project identity
    // -----------------------------------------------------------------------

    /**
     * The project name from whatever the user pasted — a full page URL such as
     * {@code https://vassalengine.org/library/projects/Some_Project}, or the bare
     * name. Trailing slashes, query strings and fragments are ignored, so a URL
     * copied out of a browser works as-is.
     */
    public static String projectNameFrom(String input) {
        String s = input == null ? "" : input.trim();
        final int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        final int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        s = s.replaceAll("/+$", "");
        final int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    // -----------------------------------------------------------------------
    // Model
    // -----------------------------------------------------------------------

    /** One downloadable file, in the release that published it. */
    public static final class RemoteFile {
        public final String filename, url, sha256, publishedAt, releaseVersion;
        public final long size;

        RemoteFile(String filename, String url, long size, String sha256,
                   String publishedAt, String releaseVersion) {
            this.filename = filename; this.url = url; this.size = size;
            this.sha256 = sha256; this.publishedAt = publishedAt;
            this.releaseVersion = releaseVersion;
        }

        public boolean isModule()    { return endsWith(".vmod"); }
        public boolean isExtension() { return endsWith(".vmdx"); }
        public boolean isSavedGame() { return endsWith(".vsav"); }

        private boolean endsWith(String suffix) {
            return filename != null
                && filename.toLowerCase(Locale.ROOT).endsWith(suffix);
        }

        /** {@code 10-SiF.vmdx} → {@code 10-SiF}, i.e. the extension name a save records. */
        public String extensionName() {
            final int dot = filename.lastIndexOf('.');
            return dot > 0 ? filename.substring(0, dot) : filename;
        }

        @Override public String toString() { return filename + " (" + releaseVersion + ')'; }
    }

    /** A package: a named group of releases, e.g. "Extensions for …". */
    public static final class Package {
        public final String name;
        public final List<RemoteFile> files = new ArrayList<>();

        Package(String name) { this.name = name; }

        /**
         * One file per filename — the copy from the highest release version.
         * See the class notes for why this is per file rather than per release.
         */
        public List<RemoteFile> latestFiles() {
            final Map<String, RemoteFile> best = new LinkedHashMap<>();
            for (RemoteFile f : files) {
                final RemoteFile cur = best.get(f.filename);
                if (cur == null || newer(f, cur)) best.put(f.filename, f);
            }
            final List<RemoteFile> out = new ArrayList<>(best.values());
            out.sort(Comparator.comparing(f -> f.filename == null ? "" : f.filename));
            return out;
        }

        public boolean hasExtensions() {
            for (RemoteFile f : files) if (f.isExtension()) return true;
            return false;
        }

        @Override public String toString() { return name; }
    }

    /** A whole project. */
    public static final class Project {
        public final String name;
        public final List<Package> packages = new ArrayList<>();

        Project(String name) { this.name = name; }

        /** Packages containing at least one {@code .vmod}. */
        public List<Package> modulePackages() {
            final List<Package> out = new ArrayList<>();
            for (Package p : packages) {
                for (RemoteFile f : p.latestFiles()) {
                    if (f.isModule()) { out.add(p); break; }
                }
            }
            return out;
        }

        /** Every extension in the project, latest copy of each, across packages. */
        public List<RemoteFile> latestExtensions() {
            final Map<String, RemoteFile> best = new LinkedHashMap<>();
            for (Package p : packages) {
                for (RemoteFile f : p.latestFiles()) {
                    if (!f.isExtension()) continue;
                    final RemoteFile cur = best.get(f.filename);
                    if (cur == null || newer(f, cur)) best.put(f.filename, f);
                }
            }
            final List<RemoteFile> out = new ArrayList<>(best.values());
            out.sort(Comparator.comparing(f -> f.filename));
            return out;
        }
    }

    /** Higher release version wins; publication time breaks ties. */
    static boolean newer(RemoteFile a, RemoteFile b) {
        final int c = compareVersions(a.releaseVersion, b.releaseVersion);
        if (c != 0) return c > 0;
        return a.publishedAt != null && b.publishedAt != null
            && a.publishedAt.compareTo(b.publishedAt) > 0;
    }

    /** Numeric component-wise comparison: {@code 2.1.10} sorts above {@code 2.1.9}. */
    static int compareVersions(String a, String b) {
        final String[] x = (a == null ? "" : a).split("[.\\-+]");
        final String[] y = (b == null ? "" : b).split("[.\\-+]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            final String xi = i < x.length ? x[i] : "";
            final String yi = i < y.length ? y[i] : "";
            final int c;
            if (xi.matches("\\d+") && yi.matches("\\d+")) {
                c = Long.compare(Long.parseLong(xi), Long.parseLong(yi));
            }
            else {
                c = xi.compareTo(yi);
            }
            if (c != 0) return c;
        }
        return 0;
    }

    // -----------------------------------------------------------------------
    // Fetching
    // -----------------------------------------------------------------------

    /** {@code GET /projects/{name}} and parse it. */
    public Project fetchProject(String projectName) throws IOException {
        final String url = apiBase + "/projects/" + encode(projectName);
        log.info("Fetching {}", url);
        final String body = getText(url);
        final Object root;
        try {
            root = Json.parse(body);
        }
        catch (IllegalArgumentException e) {
            throw new IOException("the library returned something that is not JSON: "
                    + e.getMessage(), e);
        }
        final Project project = new Project(Json.str(Json.get(root, "name"), projectName));
        for (Object po : Json.arr(Json.get(root, "packages"))) {
            final Package pkg = new Package(Json.str(Json.get(po, "name"), "(unnamed)"));
            for (Object ro : Json.arr(Json.get(po, "releases"))) {
                final String version = Json.str(Json.get(ro, "version"), "");
                for (Object fo : Json.arr(Json.get(ro, "files"))) {
                    final String fn = Json.str(Json.get(fo, "filename"), null);
                    final String fu = Json.str(Json.get(fo, "url"), null);
                    if (fn == null || fu == null) continue;
                    pkg.files.add(new RemoteFile(fn, fu,
                            Json.num(Json.get(fo, "size"), -1),
                            Json.str(Json.get(fo, "sha256"), null),
                            Json.str(Json.get(fo, "published_at"), null),
                            version));
                }
            }
            project.packages.add(pkg);
        }
        return project;
    }

    private static String encode(String s) {
        // Project names are path segments; only a few characters need escaping and
        // URLEncoder would turn spaces into '+', which is wrong in a path.
        return s.replace(" ", "%20").replace("#", "%23").replace("?", "%3F");
    }

    private String getText(String url) throws IOException {
        final HttpURLConnection c = open(url, "application/json");
        try (InputStream in = c.getInputStream()) {
            final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            copy(in, buf, null, 0);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
        finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String accept) throws IOException {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", AGENT);
        if (accept != null) c.setRequestProperty("Accept", accept);
        final int code = c.getResponseCode();
        if (code == 404) {
            throw new IOException("not found (404): " + url);
        }
        if (code / 100 != 2) {
            throw new IOException("HTTP " + code + " from " + url);
        }
        return c;
    }

    /** Progress sink for a download; return false from {@link #onProgress} to cancel. */
    public interface Progress {
        boolean onProgress(String filename, long done, long total);
    }

    /**
     * Downloads one file into {@code dir}, verifying its SHA-256 when the library
     * supplied one. Writes to a temp file and moves it into place, so a failed or
     * cancelled download never leaves a truncated module behind.
     *
     * @return the file written, or {@code null} if cancelled
     */
    public java.io.File download(RemoteFile f, java.io.File dir, Progress progress)
            throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        final java.io.File target = new java.io.File(dir, f.filename);
        final java.io.File tmp = java.io.File.createTempFile(f.filename + ".", ".part", dir);
        final HttpURLConnection c = open(f.url, null);
        boolean cancelled = false;
        try (InputStream in = c.getInputStream();
             OutputStream out = Files.newOutputStream(tmp.toPath())) {
            final MessageDigest digest = digest();
            cancelled = !copy(in, out, digest, f.size, f.filename, progress);
            if (!cancelled && f.sha256 != null && digest != null) {
                final String got = hex(digest.digest());
                if (!got.equalsIgnoreCase(f.sha256)) {
                    throw new IOException(f.filename + ": SHA-256 mismatch — expected "
                            + f.sha256 + ", got " + got);
                }
            }
        }
        finally {
            c.disconnect();
            if (cancelled) Files.deleteIfExists(tmp.toPath());
        }
        if (cancelled) return null;
        try {
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException atomicUnsupported) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (Exception e) {                       // NOPMD - verification is a bonus
            log.warn("SHA-256 unavailable; downloads will not be verified");
            return null;
        }
    }

    private static boolean copy(InputStream in, OutputStream out, MessageDigest digest,
                                long total) throws IOException {
        return copy(in, out, digest, total, null, null);
    }

    private static boolean copy(InputStream in, OutputStream out, MessageDigest digest,
                                long total, String name, Progress progress)
            throws IOException {
        final byte[] buf = new byte[1 << 16];
        long done = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (digest != null) digest.update(buf, 0, n);
            done += n;
            if (progress != null && !progress.onProgress(name, done, total)) return false;
        }
        return true;
    }

    private static String hex(byte[] b) {
        final StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
