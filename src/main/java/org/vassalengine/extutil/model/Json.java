/*
 * Copyright (c) 2025 VASSAL Extension Utility contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 */
package org.vassalengine.extutil.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader — just enough to read the game library's project
 * documents.
 *
 * <p>Hand-rolled rather than pulled in: this project ships three small
 * dependencies and builds offline, and adding Gson or Jackson for one read-only
 * document would mean a network fetch at build time. The grammar implemented here
 * is all of RFC 8259 except that numbers are kept as {@link Double} (nothing in
 * these documents needs more), which keeps it to something reviewable.</p>
 *
 * <p>Values map to {@code Map<String,Object>}, {@code List<Object>},
 * {@link String}, {@link Double}, {@link Boolean} and {@code null}. The typed
 * accessors do the casting and return a default rather than throwing, because a
 * field the library stops sending should degrade rather than break the app.</p>
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) { this.src = src; }

    /** Parses a whole document. @throws IllegalArgumentException if malformed */
    public static Object parse(String text) {
        final Json p = new Json(text);
        p.ws();
        final Object v = p.value();
        p.ws();
        if (p.pos < p.src.length()) {
            throw new IllegalArgumentException("trailing content at offset " + p.pos);
        }
        return v;
    }

    // ---- typed accessors -------------------------------------------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object v) {
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    public static String str(Object v, String dflt) {
        return v instanceof String ? (String) v : dflt;
    }

    public static long num(Object v, long dflt) {
        return v instanceof Double ? ((Double) v).longValue() : dflt;
    }

    /** {@code get(root, "packages")} — a field of an object, or {@code null}. */
    public static Object get(Object v, String field) {
        return obj(v).get(field);
    }

    // ---- parser ----------------------------------------------------------

    private Object value() {
        if (pos >= src.length()) throw err("unexpected end of input");
        final char c = src.charAt(pos);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true");  return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null");  return null;
            default:  return number();
        }
    }

    private Map<String, Object> object() {
        final Map<String, Object> out = new LinkedHashMap<>();
        pos++;                                  // '{'
        ws();
        if (peek() == '}') { pos++; return out; }
        while (true) {
            ws();
            if (peek() != '"') throw err("expected a field name");
            final String k = string();
            ws();
            if (peek() != ':') throw err("expected ':'");
            pos++;
            ws();
            out.put(k, value());
            ws();
            final char c = peek();
            pos++;
            if (c == '}') return out;
            if (c != ',') throw err("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        final List<Object> out = new ArrayList<>();
        pos++;                                  // '['
        ws();
        if (peek() == ']') { pos++; return out; }
        while (true) {
            ws();
            out.add(value());
            ws();
            final char c = peek();
            pos++;
            if (c == ']') return out;
            if (c != ',') throw err("expected ',' or ']'");
        }
    }

    private String string() {
        pos++;                                  // opening quote
        final StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) throw err("unterminated string");
            final char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            final char e = src.charAt(pos++);
            switch (e) {
                case '"':  sb.append('"');  break;
                case '\\': sb.append('\\'); break;
                case '/':  sb.append('/');  break;
                case 'b':  sb.append('\b'); break;
                case 'f':  sb.append('\f'); break;
                case 'n':  sb.append('\n'); break;
                case 'r':  sb.append('\r'); break;
                case 't':  sb.append('\t'); break;
                case 'u':
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default: throw err("bad escape \\" + e);
            }
        }
    }

    private Double number() {
        final int start = pos;
        while (pos < src.length() && "+-.eE0123456789".indexOf(src.charAt(pos)) >= 0) pos++;
        if (start == pos) throw err("expected a value");
        try {
            return Double.valueOf(src.substring(start, pos));
        }
        catch (NumberFormatException e) {
            throw err("bad number " + src.substring(start, pos));
        }
    }

    private void expect(String word) {
        if (!src.startsWith(word, pos)) throw err("expected " + word);
        pos += word.length();
    }

    private char peek() {
        if (pos >= src.length()) throw err("unexpected end of input");
        return src.charAt(pos);
    }

    private void ws() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException("JSON: " + msg + " at offset " + pos);
    }
}
