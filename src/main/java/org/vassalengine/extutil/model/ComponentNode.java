/*
 * Copyright (c) 2025 VASSAL Extension Utility contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 */
package org.vassalengine.extutil.model;

import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Wraps a DOM Element for display in a JTree node.
 *
 * Display names match the labels used in the VASSAL module editor, sourced from
 * VASSAL's Editor.properties (Editor.{ClassName}.component_type entries).
 * When a class is not in the table the short Java class name is used as a fallback.
 */
public class ComponentNode {

    // Maps simple Java class name → editor display name.
    // Sourced from Editor.*.component_type entries in VASSAL's Editor.properties.
    // Where the property key differs from the class name, both are noted in comments.
    private static final Map<String, String> DISPLAY_NAMES = new HashMap<>();
    static {
        // Core module structure
        DISPLAY_NAMES.put("GameModule",                    "Module");
        DISPLAY_NAMES.put("BasicCommandEncoder",           "Basic Command Encoder");
        DISPLAY_NAMES.put("Documentation",                 "Help Menu");
        DISPLAY_NAMES.put("GlobalOptions",                 "Global Options");        // key: GlobalOption
        DISPLAY_NAMES.put("PlayerRoster",                  "Definition of Player Sides");
        DISPLAY_NAMES.put("NotesWindow",                   "Notes Window");

        // Maps and boards
        DISPLAY_NAMES.put("Map",                           "Map Window");
        DISPLAY_NAMES.put("PrivateMap",                    "Private Window");
        DISPLAY_NAMES.put("PlayerHand",                    "Player Hand");
        DISPLAY_NAMES.put("BoardPicker",                   "Map Boards");
        DISPLAY_NAMES.put("Board",                         "Board");
        DISPLAY_NAMES.put("HexGrid",                       "Hex Grid");
        DISPLAY_NAMES.put("SquareGrid",                    "Rectangular Grid");      // key: RectangleGrid
        DISPLAY_NAMES.put("IrregularGrid",                 "Irregular Grid");
        DISPLAY_NAMES.put("MultiZoneGrid",                 "Multi-zoned Grid");
        DISPLAY_NAMES.put("ZonedGrid",                     "Multi-zoned Grid");
        DISPLAY_NAMES.put("Zone",                          "Zone");
        DISPLAY_NAMES.put("Region",                        "Region");
        DISPLAY_NAMES.put("HexGridNumbering",              "Grid Numbering");
        DISPLAY_NAMES.put("SquareGridNumbering",           "Grid Numbering");
        DISPLAY_NAMES.put("RegularGridNumbering",          "Grid Numbering");
        DISPLAY_NAMES.put("ZonedGridHighlighter",          "Zone Highlighters");

        // Map sub-components
        DISPLAY_NAMES.put("SetupStack",                    "At-Start Stack");        // key: StartStack
        DISPLAY_NAMES.put("Zoomer",                        "Zoom capability");       // key: Zoom
        DISPLAY_NAMES.put("GlobalMap",                     "Overview window");
        DISPLAY_NAMES.put("ImageSaver",                    "Image Capture Tool");
        DISPLAY_NAMES.put("TextSaver",                     "Text Capture Tool");
        DISPLAY_NAMES.put("HidePiecesButton",              "Hide Pieces Button");
        DISPLAY_NAMES.put("StackMetrics",                  "Stacking options");      // key: Stacking
        DISPLAY_NAMES.put("CounterDetailViewer",           "Mouse-over Stack Viewer");// key: MouseOverStackViewer
        DISPLAY_NAMES.put("LOS_Thread",                    "Line of Sight Thread");  // key: LosThread
        DISPLAY_NAMES.put("HighlightLastMoved",            "Last Move Highlighter");
        DISPLAY_NAMES.put("LayeredPieceCollection",        "Game Piece Layers");     // key: GamePieceLayers
        DISPLAY_NAMES.put("LayerControl",                  "Game Piece Layer Control");
        DISPLAY_NAMES.put("SelectionHighlighters",         "Additional Selection Highlighters");
        DISPLAY_NAMES.put("MapShader",                     "Map Shading");
        DISPLAY_NAMES.put("PieceRecenterer",               "Recenter Pieces Button");
        DISPLAY_NAMES.put("MoveCameraButton",              "Move Camera Button");
        DISPLAY_NAMES.put("Flare",                         "Flare");

        // Forwarding helpers (shown in tree but have no special label)
        DISPLAY_NAMES.put("ForwardToKeyBuffer",            "Forward to Key Buffer");
        DISPLAY_NAMES.put("ForwardToChatter",              "Forward to Chatter");
        DISPLAY_NAMES.put("KeyBufferer",                   "Key Bufferer");
        DISPLAY_NAMES.put("MenuDisplayer",                 "Menu Displayer");
        DISPLAY_NAMES.put("MapCenterer",                   "Map Centerer");
        DISPLAY_NAMES.put("StackExpander",                 "Stack Expander");
        DISPLAY_NAMES.put("PieceMover",                    "Piece Mover");
        DISPLAY_NAMES.put("Scroller",                      "Scroller");

        // Piece palette and counters
        DISPLAY_NAMES.put("PieceWindow",                   "Game Piece Palette");
        DISPLAY_NAMES.put("PieceSlot",                     "Single piece");
        DISPLAY_NAMES.put("CardSlot",                      "Card");
        DISPLAY_NAMES.put("DrawPile",                      "Deck");   // editor class for a deck
        DISPLAY_NAMES.put("Deck",                          "Deck");

        // Charts and help
        DISPLAY_NAMES.put("ChartWindow",                   "Chart Window Menu");
        DISPLAY_NAMES.put("Chart",                         "Chart");
        DISPLAY_NAMES.put("HtmlChart",                     "HTML Chart");   // actual class name
        DISPLAY_NAMES.put("HTMLChart",                     "HTML Chart");
        DISPLAY_NAMES.put("AboutScreen",                   "About Screen");
        DISPLAY_NAMES.put("HelpFile",                      "Plain Text Help File");
        DISPLAY_NAMES.put("BrowserHelpFile",               "HTML Help File");
        DISPLAY_NAMES.put("BrowserPDFFile",                "PDF Help File");

        // Widgets (used inside ChartWindow and PieceWindow)
        DISPLAY_NAMES.put("TabWidget",                     "Tabbed Panel");
        DISPLAY_NAMES.put("BoxWidget",                     "Pull-down Menu");
        DISPLAY_NAMES.put("PanelWidget",                   "Panel");
        DISPLAY_NAMES.put("ListWidget",                    "Scrollable List");
        DISPLAY_NAMES.put("MapWidget",                     "Map");
        DISPLAY_NAMES.put("WidgetMap",                     "Map");

        // Prototypes
        DISPLAY_NAMES.put("PrototypesContainer",           "Game Piece Prototype Definitions");
        DISPLAY_NAMES.put("PrototypeDefinition",           "Definition");            // key: Prototype

        // Scenarios
        DISPLAY_NAMES.put("PredefinedSetup",               "Pre-defined setup");

        // Turn tracker
        DISPLAY_NAMES.put("TurnTracker",                   "Turn Counter");
        DISPLAY_NAMES.put("ListTurnLevel",                 "List");
        DISPLAY_NAMES.put("CounterTurnLevel",              "Counter");
        DISPLAY_NAMES.put("TurnGlobalHotkey",              "Global Hotkey (Turn)");

        // Dice and buttons
        DISPLAY_NAMES.put("DiceButton",                    "Dice Button");
        DISPLAY_NAMES.put("InternetDiceButton",            "Internet Dice Button");
        DISPLAY_NAMES.put("SpecialDiceButton",             "Symbolic Dice Button");
        DISPLAY_NAMES.put("SpecialDie",                    "Symbolic Die");
        DISPLAY_NAMES.put("SpecialDieFace",                "Symbolic Die Face");
        DISPLAY_NAMES.put("DieManager",                    "Die Manager");
        DISPLAY_NAMES.put("DoActionButton",                "Action Button");         // key: DoAction
        DISPLAY_NAMES.put("RandomTextButton",              "Random Text Button");
        DISPLAY_NAMES.put("MultiActionButton",             "Multi-Action Button");
        DISPLAY_NAMES.put("ToolbarMenu",                   "Toolbar Menu");
        DISPLAY_NAMES.put("ChangePropertyButton",          "Change-property Toolbar Button");

        // Global key commands
        DISPLAY_NAMES.put("GlobalKeyCommand",              "Global Key Command");
        DISPLAY_NAMES.put("StartupGlobalKeyCommand",       "Startup Global Key Command");
        DISPLAY_NAMES.put("DeckGlobalKeyCommand",          "Deck Global Key Command");
        DISPLAY_NAMES.put("DeckSendKeyCommand",            "Deck Send Key Command");
        DISPLAY_NAMES.put("DeckSortKeyCommand",            "Deck Sort Key Command");

        // Properties and preferences
        DISPLAY_NAMES.put("GlobalProperties",              "Global Properties");
        DISPLAY_NAMES.put("GlobalProperty",                "Global Property");
        DISPLAY_NAMES.put("GlobalTranslatableMessages",    "Global Translatable Messages");
        DISPLAY_NAMES.put("GlobalTranslatableMessage",     "Global Translatable Message");
        DISPLAY_NAMES.put("ZoneProperty",                  "Global Property (Zone)");
        DISPLAY_NAMES.put("Inventory",                     "Game Piece Inventory Window");
        DISPLAY_NAMES.put("KeyNamer",                      "Key Namer");

        // Game Piece Image Definitions
        DISPLAY_NAMES.put("GamePieceImageDefinitions",     "Game Piece Image Definitions");
        DISPLAY_NAMES.put("GamePieceLayoutsContainer",     "Game Piece Layouts");
        DISPLAY_NAMES.put("GamePieceLayout",               "Game Piece Layout");
        DISPLAY_NAMES.put("GamePieceImage",                "Game Piece Image");
        DISPLAY_NAMES.put("ColorManager",                  "Named Colors");
        DISPLAY_NAMES.put("ColorSwatch",                   "Named Color");
        DISPLAY_NAMES.put("FontManager",                   "Font Styles");
        DISPLAY_NAMES.put("FontStyle",                     "Font Style");
        DISPLAY_NAMES.put("ImageItem",                     "Image");
        DISPLAY_NAMES.put("ShapeItem",                     "Shape");
        DISPLAY_NAMES.put("TextItem",                      "Label");
        DISPLAY_NAMES.put("TextBoxItem",                   "Text Box");
        DISPLAY_NAMES.put("SymbolItem",                    "Symbol");
        DISPLAY_NAMES.put("IconFamily",                    "Icon Family");

        // Folders
        DISPLAY_NAMES.put("PrototypeFolder",               "Folder");
        DISPLAY_NAMES.put("Folder",                        "Folder");

        // Extension-specific
        DISPLAY_NAMES.put("ModuleExtension",               "Extension");
        DISPLAY_NAMES.put("Chatter",                       "Chat Log");
    }

    // Maps simple Java class name → the XML attribute that holds the component's
    // "configure name" (the editable name shown in the VASSAL module editor).
    // VASSAL routes one attribute per class to Configurable.setConfigureName();
    // most classes use "name", so only the exceptions are listed here.  Classes
    // absent from this map fall back to FALLBACK_NAME_ATTRS (starting with "name").
    private static final Map<String, String> NAME_ATTRIBUTES = new HashMap<>();
    static {
        // Maps store their name in "mapName" (PrivateMap/PlayerHand extend Map)
        NAME_ATTRIBUTES.put("Map",                  "mapName");
        NAME_ATTRIBUTES.put("PrivateMap",           "mapName");
        NAME_ATTRIBUTES.put("PlayerHand",           "mapName");

        // Charts
        NAME_ATTRIBUTES.put("Chart",                "chartName");
        NAME_ATTRIBUTES.put("HtmlChart",            "chartName");
        NAME_ATTRIBUTES.put("HTMLChart",            "chartName");

        // Title-named components
        NAME_ATTRIBUTES.put("AboutScreen",          "title");
        NAME_ATTRIBUTES.put("HelpFile",             "title");

        // Widgets and piece slots use "entryName"
        NAME_ATTRIBUTES.put("TabWidget",            "entryName");
        NAME_ATTRIBUTES.put("BoxWidget",            "entryName");
        NAME_ATTRIBUTES.put("PanelWidget",          "entryName");
        NAME_ATTRIBUTES.put("ListWidget",           "entryName");
        NAME_ATTRIBUTES.put("MapWidget",            "entryName");
        NAME_ATTRIBUTES.put("PieceSlot",            "entryName");
        NAME_ATTRIBUTES.put("CardSlot",             "entryName");

        // Deck key commands are named by their menu text
        NAME_ATTRIBUTES.put("DeckGlobalKeyCommand", "menuText");
        NAME_ATTRIBUTES.put("DeckSendKeyCommand",   "menuText");
        NAME_ATTRIBUTES.put("DeckSortKeyCommand",   "menuText");

        // Misc components with non-standard name attributes
        NAME_ATTRIBUTES.put("ChangePropertyButton", "text");
        NAME_ATTRIBUTES.put("SpecialDieFace",        "text");
        NAME_ATTRIBUTES.put("ChessClock",            "side");
        NAME_ATTRIBUTES.put("Flare",                 "flareName");
    }

    // Best-effort name attributes for classes not listed in NAME_ATTRIBUTES.
    // "name" first (VASSAL's overwhelming default), then other common keys.
    private static final String[] FALLBACK_NAME_ATTRS =
            {"name", "entryName", "mapName", "chartName", "title", "description", "fileName"};

    private final Element element;

    public ComponentNode(Element element) {
        this.element = element;
    }

    public Element getElement() {
        return element;
    }

    /**
     * Returns a human-readable label matching the VASSAL module editor naming
     * conventions.  VASSAL renders each tree node as
     * <code>configureName [Component Type]</code> — the component's editable name
     * first, then its type in brackets (see {@code ConfigureTree.ConfigureTreeNode}).
     * When a component has no configure name only the bracketed type is shown.
     * For ExtensionElement: "Extension Element → target path".
     */
    public String getDisplayName() {
        String tagName = element.getTagName();

        if ("VASSAL.build.module.ExtensionElement".equals(tagName)) {
            String target = element.getAttribute("target");
            return "Extension Element → " + target;
        }

        String simpleClass = shortClassName(tagName);
        String typeName = DISPLAY_NAMES.getOrDefault(simpleClass, simpleClass);

        String instanceLabel = pickLabel(element, simpleClass);
        if (instanceLabel != null && !instanceLabel.isEmpty()) {
            return instanceLabel + " [" + typeName + "]";
        }
        return "[" + typeName + "]";
    }

    /**
     * Collects all image filenames referenced anywhere in this element's subtree.
     * Image references are attribute values that appear in the given archive's image set.
     */
    public Set<String> collectImageReferences(Set<String> archiveImageNames) {
        return collectImageReferences(archiveImageNames, true);
    }

    /**
     * Collects image filenames referenced by this element.
     * When {@code recurse} is true the entire subtree is scanned (used by Move,
     * which carries descendants); when false only this element's own attributes
     * are scanned (used by Copy, which copies the element without its children).
     */
    public Set<String> collectImageReferences(Set<String> archiveImageNames, boolean recurse) {
        Set<String> refs = new HashSet<>();
        collectImageRefs(element, archiveImageNames, refs, recurse);
        return refs;
    }

    private void collectImageRefs(Element el, Set<String> archiveImageNames, Set<String> refs,
                                  boolean recurse) {
        NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            String value = attrs.item(i).getNodeValue();
            if (value != null && !value.isEmpty()) {
                String bare = value.startsWith("/images/") ? value.substring(8) :
                              value.startsWith("images/") ? value.substring(7) : value;
                if (archiveImageNames.contains(bare)) {
                    refs.add(bare);
                }
            }
        }
        if (!recurse) return;
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                collectImageRefs((Element) child, archiveImageNames, refs, true);
            }
        }
    }

    private static String shortClassName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    /**
     * Returns the component's configure name (the editable name shown in the
     * VASSAL editor), or null if it has none.
     *
     * For classes listed in {@link #NAME_ATTRIBUTES} the exact VASSAL name
     * attribute is used, so the label matches the module editor.  Unlisted
     * classes (which almost always use "name") fall back to a short list of
     * common name attributes.
     */
    private static String pickLabel(Element el, String simpleClass) {
        String attr = NAME_ATTRIBUTES.get(simpleClass);
        String val = (attr != null) ? el.getAttribute(attr) : firstNonEmpty(el, FALLBACK_NAME_ATTRS);
        if (val == null || val.isEmpty()) return null;
        return val.length() > 60 ? val.substring(0, 57) + "..." : val;
    }

    private static String firstNonEmpty(Element el, String[] attrs) {
        for (String attr : attrs) {
            String val = el.getAttribute(attr);
            if (val != null && !val.isEmpty()) {
                return val;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}
