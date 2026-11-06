package com.katreo.vcfsim;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** vCard 2.1 / 3.0 / 4.0 okuyucu (iPhone, Android, Google dışa aktarımları). */
final class VcfParser {

    static final class Phone {
        final String number;
        String label;
        final String group;

        Phone(String number, String label, String group) {
            this.number = number;
            this.label = label;
            this.group = group;
        }
    }

    static final class Card {
        String fn = "";
        String n = "";
        String org = "";
        final List<Phone> phones = new ArrayList<>();

        String displayName() {
            if (!fn.isEmpty()) return fn;
            if (!n.isEmpty()) return n;
            return org;
        }
    }

    private VcfParser() {}

    static List<Card> parse(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);

        List<Card> cards = new ArrayList<>();
        Card cur = null;
        for (String line : unfold(text)) {
            int colon = valueColon(line);
            if (colon < 0) continue;
            String[] head = line.substring(0, colon).split(";");
            String value = line.substring(colon + 1);

            String prop = head[0];
            String group = "";
            int dot = prop.lastIndexOf('.');
            if (dot >= 0) { // "item1.TEL" -> "TEL"
                group = prop.substring(0, dot).trim().toUpperCase(Locale.ROOT);
                prop = prop.substring(dot + 1);
            }
            prop = prop.trim().toUpperCase(Locale.ROOT);

            if (prop.equals("BEGIN") && value.trim().equalsIgnoreCase("VCARD")) {
                cur = new Card();
                continue;
            }
            if (prop.equals("END") && value.trim().equalsIgnoreCase("VCARD")) {
                if (cur != null) cards.add(cur);
                cur = null;
                continue;
            }
            if (cur == null) continue;

            boolean qp = false;
            String charset = null;
            List<String> types = new ArrayList<>();
            for (int i = 1; i < head.length; i++) {
                String p = head[i].trim();
                int eq = p.indexOf('=');
                String key = eq < 0 ? "TYPE" : p.substring(0, eq).trim().toUpperCase(Locale.ROOT);
                String val = eq < 0 ? p : p.substring(eq + 1).trim();
                if (val.length() >= 2 && val.startsWith("\"") && val.endsWith("\"")) {
                    val = val.substring(1, val.length() - 1);
                }
                if (key.equals("ENCODING") || val.equalsIgnoreCase("QUOTED-PRINTABLE")) {
                    if (val.equalsIgnoreCase("QUOTED-PRINTABLE")) qp = true;
                } else if (key.equals("CHARSET")) {
                    charset = val;
                } else if (key.equals("TYPE")) {
                    for (String t : val.split(",")) types.add(t.trim().toUpperCase(Locale.ROOT));
                }
            }
            if (qp) value = decodeQuotedPrintable(value, charset);

            switch (prop) {
                case "FN":
                    cur.fn = clean(unescape(value));
                    break;
                case "N": {
                    List<String> c = splitUnescaped(value);
                    // N: Soyad;Ad;İkinci ad;Ön ek;Son ek
                    String[] order = {get(c, 3), get(c, 1), get(c, 2), get(c, 0), get(c, 4)};
                    StringBuilder sb = new StringBuilder();
                    for (String s : order) {
                        if (s.isEmpty()) continue;
                        if (sb.length() > 0) sb.append(' ');
                        sb.append(s);
                    }
                    cur.n = clean(sb.toString());
                    break;
                }
                case "ORG":
                    cur.org = clean(get(splitUnescaped(value), 0));
                    break;
                case "TEL": {
                    String num = value.trim();
                    if (num.regionMatches(true, 0, "tel:", 0, 4)) num = num.substring(4);
                    if (!num.isEmpty()) cur.phones.add(new Phone(num, label(types), group));
                    break;
                }
                case "X-ABLABEL": { // iPhone: item1.X-ABLabel:_$!<Mobile>!$_
                    String l = appleLabel(unescape(value).trim());
                    for (Phone p : cur.phones) {
                        if (!group.isEmpty() && group.equals(p.group) && !l.isEmpty()) p.label = l;
                    }
                    break;
                }
                default:
                    break;
            }
        }
        return cards;
    }

    /** Katlanmış satırları (boşlukla devam eden ve QP "=" ile biten) birleştirir. */
    private static List<String> unfold(String text) {
        String[] raw = text.split("\r\n|\r|\n", -1);
        List<String> out = new ArrayList<>();
        for (String l : raw) {
            if (!out.isEmpty()) {
                int last = out.size() - 1;
                String prev = out.get(last);
                if (prev.endsWith("=") && isQuotedPrintable(prev)) {
                    out.set(last, prev.substring(0, prev.length() - 1) + l);
                    continue;
                }
                if (l.startsWith(" ") || l.startsWith("\t")) {
                    out.set(last, prev + l.substring(1));
                    continue;
                }
            }
            if (!l.isEmpty()) out.add(l);
        }
        return out;
    }

    private static boolean isQuotedPrintable(String line) {
        int colon = valueColon(line);
        return colon > 0 && line.substring(0, colon).toUpperCase(Locale.ROOT).contains("QUOTED-PRINTABLE");
    }

    private static int valueColon(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == ':' && !quoted) return i;
        }
        return -1;
    }

    private static String decodeQuotedPrintable(String s, String charset) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' && i + 2 < s.length()
                    && Character.digit(s.charAt(i + 1), 16) >= 0 && Character.digit(s.charAt(i + 2), 16) >= 0) {
                b.write(Character.digit(s.charAt(i + 1), 16) * 16 + Character.digit(s.charAt(i + 2), 16));
                i += 2;
            } else if (c == '=') {
                // yarım kalmış yumuşak satır sonu
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                b.write(bytes, 0, bytes.length);
            }
        }
        Charset cs = StandardCharsets.UTF_8;
        try {
            if (charset != null) cs = Charset.forName(charset);
        } catch (Exception ignored) {
        }
        return new String(b.toByteArray(), cs);
    }

    private static List<String> splitUnescaped(String value) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                sb.append(c).append(value.charAt(++i));
            } else if (c == ';') {
                out.add(unescape(sb.toString()).trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        out.add(unescape(sb.toString()).trim());
        return out;
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 'n' || n == 'N' ? ' ' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String clean(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String get(List<String> l, int i) {
        return i < l.size() ? l.get(i) : "";
    }

    private static String appleLabel(String l) {
        if (l.startsWith("_$!<") && l.endsWith(">!$_")) l = l.substring(4, l.length() - 4);
        switch (l.toLowerCase(Locale.ROOT)) {
            case "mobile": case "iphone": case "cell": return "Cep";
            case "home": return "Ev";
            case "work": return "İş";
            case "main": return "Ana";
            case "other": return "Diğer";
            case "homefax": case "workfax": case "otherfax": return "Faks";
            case "pager": return "Çağrı";
            default: return clean(l);
        }
    }

    private static String label(List<String> types) {
        if (types.contains("FAX")) return "Faks";
        if (types.contains("CELL") || types.contains("IPHONE") || types.contains("MOBILE")) return "Cep";
        if (types.contains("HOME")) return "Ev";
        if (types.contains("WORK")) return "İş";
        if (types.contains("MAIN")) return "Ana";
        if (types.contains("PAGER")) return "Çağrı";
        return "";
    }
}
