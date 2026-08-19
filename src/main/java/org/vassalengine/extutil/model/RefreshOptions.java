/*
 * Copyright (c) 2025 VASSAL Extension Utility contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 */
package org.vassalengine.extutil.model;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The option flags of VASSAL's <b>Refresh Counters</b>, as chosen in the options
 * dialog and passed through to the engine.
 *
 * <p>The string constants are copied verbatim from {@code VASSAL.build.module
 * .GameRefresher} — they are the exact tokens the engine's
 * {@code GameRefresher.execute(Set&lt;String&gt;, Command)} tests for, so they must
 * not be "tidied". Keeping them here (rather than referencing the engine class)
 * lets the GUI build an option set without VASSAL on its classpath; the set is
 * handed to the subprocess runner, which passes it straight to the engine.</p>
 *
 * <p>Only the options VASSAL itself exposes are offered. {@code TEST_MODE} and
 * {@code DELETE_NO_MAP} are deliberately absent from the dialog for the same
 * reason they are commented out of the engine's own: VASSAL issues 12695 and
 * 12902. {@code DELETE_NO_MAP} is nevertheless always sent, matching the engine
 * dialog's hard-coded {@code true}.</p>
 */
public final class RefreshOptions {

    /** Refresh game pieces. Always on — the engine locks this checkbox too. */
    public static final String REFRESH_PIECES = "RefreshPieces";
    /** Fall back to matching a piece by its BasicPiece name when its GPID is unknown. */
    public static final String USE_NAME = "UseName";
    /** With {@link #USE_NAME}: write the matched slot's GPID onto the refreshed piece. */
    public static final String FIX_GPID = "fixGPID";
    /** Match Text Label traits by description rather than by exact trait type. */
    public static final String USE_LABELER_NAME = "UseLabelerName";
    /** Match Layer traits by layer name rather than by exact trait type. */
    public static final String USE_LAYER_NAME = "UseLayerName";
    /** Match Can Rotate traits by name rather than by exact trait type. */
    public static final String USE_ROTATE_NAME = "UseRotateName";
    /** Delete pieces that ended up with no map. Sent unconditionally, as the engine does. */
    public static final String DELETE_NO_MAP = "DeleteNoMap";
    /** Refresh decks as well as pieces. */
    public static final String REFRESH_DECKS = "RefreshDecks";
    /** With {@link #REFRESH_DECKS}: delete decks no longer defined in the module. */
    public static final String DELETE_OLD_DECKS = "DeleteOldDecks";
    /** With {@link #REFRESH_DECKS}: add decks newly defined in the module. */
    public static final String ADD_NEW_DECKS = "AddNewDecks";
    /** Fire the module's global "refresh" hotkey after refreshing. Off by default. */
    public static final String USE_HOTKEY = "UseHotkey";

    // Piece options
    public boolean useName;
    public boolean fixGpid;
    public boolean useLabelerName = true;
    public boolean useLayerName = true;
    public boolean useRotateName = true;
    // Deck options
    public boolean refreshDecks;
    public boolean deleteOldDecks;
    public boolean addNewDecks;
    // Misc
    public boolean fireHotkey;

    /**
     * Builds the engine option set, exactly as {@code GameRefresher.RefreshDialog
     * .setOptions()} does — note the nesting: the sub-options of a switched-off
     * parent are not sent.
     */
    public Set<String> toEngineOptions() {
        final Set<String> o = new LinkedHashSet<>();
        o.add(REFRESH_PIECES);
        if (useName) {
            o.add(USE_NAME);
            if (fixGpid) o.add(FIX_GPID);
        }
        if (useLabelerName) o.add(USE_LABELER_NAME);
        if (useLayerName) o.add(USE_LAYER_NAME);
        if (useRotateName) o.add(USE_ROTATE_NAME);
        o.add(DELETE_NO_MAP);
        if (refreshDecks) {
            o.add(REFRESH_DECKS);
            if (deleteOldDecks) o.add(DELETE_OLD_DECKS);
            if (addNewDecks) o.add(ADD_NEW_DECKS);
        }
        if (fireHotkey) o.add(USE_HOTKEY);
        return o;
    }
}
