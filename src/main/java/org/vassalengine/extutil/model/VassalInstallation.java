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
 * Locates the user's VASSAL installation — specifically {@code Vengine.jar}, the
 * engine jar whose code performs the actual Refresh Counters run.
 *
 * <p><b>Only {@code Vengine.jar} goes on the classpath</b>, never the whole
 * {@code lib/*}. Its manifest {@code Class-Path} pulls in the other 40-odd jars
 * relative to itself, which is how VASSAL launches itself. Using a wildcard
 * instead lets another jar's {@code images/} folder shadow the engine's, and
 * VASSAL then dies with <i>"Icon Family eye not found"</i> — the hazard its own
 * source flags as bug 9670.</p>
 *
 * <p>The path is resolved in this order, first hit wins:</p>
 * <ol>
 *   <li>the path saved by the user in
 *       {@code ~/.vassal-extension-utility/vassal.properties} (key {@code engine.jar});</li>
 *   <li>the {@code VASSAL_HOME} environment variable (its {@code lib} subdirectory);</li>
 *   <li>the conventional install locations for this platform.</li>
 * </ol>
 *
 * <p>Nothing here fails hard: if no engine is found the caller reports that and
 * offers the user a file chooser, whose result is then persisted.</p>
 */
public final class VassalInstallation {

    private static final Logger log = LoggerFactory.getLogger(VassalInstallation.class);

    public static final String ENGINE_JAR = "Vengine.jar";

    private static final String CONFIG_DIR = ".vassal-extension-utility";
    private static final String CONFIG_FILE = "vassal.properties";
    private static final String KEY_ENGINE_JAR = "engine.jar";

    private VassalInstallation() { }

    /** The conventional install locations, by platform. */
    private static List<File> candidates() {
        final List<File> out = new ArrayList<>();
        final String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            for (String base : new String[]{
                    System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)")}) {
                if (base == null) continue;
                final File[] dirs = new File(base).listFiles(
                        f -> f.isDirectory() && f.getName().toUpperCase().startsWith("VASSAL"));
                if (dirs != null) for (File d : dirs) out.add(new File(d, "lib"));
            }
        }
        else if (os.contains("mac")) {
            final File apps = new File("/Applications");
            final File[] dirs = apps.listFiles(
                    f -> f.isDirectory() && f.getName().toUpperCase().startsWith("VASSAL"));
            if (dirs != null) for (File d : dirs) out.add(new File(d, "Contents/Java"));
        }
        else {
            out.add(new File("/usr/share/vassal/lib"));
            out.add(new File("/usr/lib/vassal/lib"));
            out.add(new File("/opt/vassal/lib"));
            final File opt = new File("/opt");
            final File[] dirs = opt.listFiles(
                    f -> f.isDirectory() && f.getName().toUpperCase().startsWith("VASSAL"));
            if (dirs != null) for (File d : dirs) out.add(new File(d, "lib"));
        }
        return out;
    }

    /** @return the located {@code Vengine.jar}, or {@code null} if none was found. */
    public static File findEngineJar() {
        final File saved = loadSavedEngineJar();
        if (isEngineJar(saved)) return saved;

        final String home = System.getenv("VASSAL_HOME");
        if (home != null && !home.isEmpty()) {
            final File jar = new File(new File(home, "lib"), ENGINE_JAR);
            if (isEngineJar(jar)) return jar;
            final File direct = new File(home, ENGINE_JAR);
            if (isEngineJar(direct)) return direct;
        }

        for (File dir : candidates()) {
            final File jar = new File(dir, ENGINE_JAR);
            if (isEngineJar(jar)) return jar;
        }
        return null;
    }

    /** True when {@code f} is an existing, readable {@code Vengine.jar}. */
    public static boolean isEngineJar(File f) {
        return f != null && f.isFile() && f.canRead()
                && f.getName().equalsIgnoreCase(ENGINE_JAR);
    }

    /** The {@code java} launcher of the JVM running this utility. */
    public static File javaExecutable() {
        final File bin = new File(System.getProperty("java.home"), "bin");
        final File exe = new File(bin, "java.exe");
        return exe.isFile() ? exe : new File(bin, "java");
    }

    // -----------------------------------------------------------------------
    // Persisted setting
    // -----------------------------------------------------------------------

    private static File configFile() {
        return new File(new File(System.getProperty("user.home"), CONFIG_DIR), CONFIG_FILE);
    }

    /** Reads the user-chosen engine jar, or {@code null}. Failures are logged only. */
    public static File loadSavedEngineJar() {
        final File cfg = configFile();
        if (!cfg.isFile()) return null;
        final Properties p = new Properties();
        try (InputStream in = Files.newInputStream(cfg.toPath())) {
            p.load(in);
        } catch (IOException e) {
            log.debug("Could not read {}: {}", cfg, e.toString());
            return null;
        }
        final String path = p.getProperty(KEY_ENGINE_JAR, "").trim();
        return path.isEmpty() ? null : new File(path);
    }

    /** Remembers the user-chosen engine jar. Failures are logged only. */
    public static void saveEngineJar(File jar) {
        final File cfg = configFile();
        final File dir = cfg.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            log.warn("Could not create {}", dir);
            return;
        }
        final Properties p = new Properties();
        p.setProperty(KEY_ENGINE_JAR, jar.getAbsolutePath());
        try (OutputStream out = Files.newOutputStream(cfg.toPath())) {
            p.store(out, "VASSAL Extension Utility — engine location");
        } catch (IOException e) {
            log.warn("Could not write {}: {}", cfg, e.toString());
        }
    }
}
