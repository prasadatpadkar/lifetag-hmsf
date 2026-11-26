package com.lifetag.hms.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, dependency-free PDF writer (A4, Helvetica) — enough for text
 * documents such as claim summaries and sanction letters: headings,
 * word-wrapped paragraphs, label/value rows, rules and automatic pagination
 * with "Page x of y" footers. Deliberately tiny so the app needs no extra
 * Maven dependency for its only two PDF outputs.
 *
 * Text is written in WinAnsi (Latin-1). The rupee sign is written as "Rs.",
 * and any other character outside Latin-1 becomes '?'.
 */
public class SimplePdf {

    private static final float PAGE_W = 595f;
    private static final float PAGE_H = 842f;
    private static final float MARGIN = 50f;
    private static final float BOTTOM = 60f;   // leave room for the footer
    private static final float CONTENT_W = PAGE_W - 2 * MARGIN;

    /** Helvetica advance widths (1/1000 em) for ASCII 32..126. */
    private static final int[] W = {
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584
    };

    private final List<StringBuilder> pages = new ArrayList<>();
    private StringBuilder cur;
    private float y;
    private String footerLeft = "";

    public SimplePdf() {
        newPage();
    }

    /** Text printed at the bottom-left of every page (e.g. generation date). */
    public SimplePdf footer(String text) {
        this.footerLeft = clean(text);
        return this;
    }

    public SimplePdf title(String text) {
        ensure(40);
        text(MARGIN, y - 22, "F2", 20, text);
        y -= 30;
        return this;
    }

    public SimplePdf subtitle(String text) {
        ensure(20);
        text(MARGIN, y - 12, "F1", 10, text);
        y -= 18;
        return this;
    }

    public SimplePdf heading(String text) {
        ensure(34);
        y -= 8;
        text(MARGIN, y - 12, "F2", 12, text);
        y -= 20;
        return this;
    }

    public SimplePdf rule() {
        ensure(10);
        cur.append("0.75 G 0.6 w ").append(f(MARGIN)).append(' ').append(f(y - 4)).append(" m ")
           .append(f(PAGE_W - MARGIN)).append(' ').append(f(y - 4)).append(" l S\n");
        y -= 10;
        return this;
    }

    public SimplePdf space(float pts) {
        y -= pts;
        return this;
    }

    /** Word-wrapped body text. */
    public SimplePdf paragraph(String text) {
        return paragraph(text, "F1", 10.5f, 0f);
    }

    /** Word-wrapped body text in bold. */
    public SimplePdf boldParagraph(String text) {
        return paragraph(text, "F2", 10.5f, 0f);
    }

    /** A small, indented, wrapped note (used for disclaimers). */
    public SimplePdf note(String text) {
        return paragraph(text, "F1", 9f, 0f);
    }

    /** "Label:   value" row; the value wraps within its column. */
    public SimplePdf row(String label, String value) {
        float labelW = 150f;
        float size = 10.5f;
        List<String> lines = wrap(value == null || value.isBlank() ? "-" : value, "F1", size, CONTENT_W - labelW);
        ensure(lines.size() * 14f + 2);
        text(MARGIN, y - 11, "F2", size, label);
        for (String line : lines) {
            text(MARGIN + labelW, y - 11, "F1", size, line);
            y -= 14f;
        }
        y -= 2f;
        return this;
    }

    private SimplePdf paragraph(String text, String font, float size, float indent) {
        for (String line : wrap(text, font, size, CONTENT_W - indent)) {
            ensure(size + 5);
            text(MARGIN + indent, y - size, font, size, line);
            y -= size + 4f;
        }
        y -= 4f;
        return this;
    }

    // ---------------------------------------------------------------- internals

    private void newPage() {
        cur = new StringBuilder();
        pages.add(cur);
        y = PAGE_H - MARGIN;
    }

    private void ensure(float needed) {
        if (y - needed < BOTTOM) newPage();
    }

    private void text(float x, float baseline, String font, float size, String s) {
        cur.append("BT /").append(font).append(' ').append(f(size)).append(" Tf 0 g ")
           .append(f(x)).append(' ').append(f(baseline)).append(" Td (").append(escape(clean(s))).append(") Tj ET\n");
    }

    static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '₹') b.append("Rs. ");
            else if (c == '\n' || c == '\r' || c == '\t') b.append(' ');
            else if (c < 32 || c > 255 || (c >= 127 && c < 160)) b.append('?');
            else b.append(c);
        }
        return b.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    private static float width(String s, String font, float size) {
        float total = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int w = (c >= 32 && c <= 126) ? W[c - 32] : 556;
            total += w;
        }
        // Bold Helvetica is slightly wider; a small safety factor keeps wrapping conservative.
        return total * size / 1000f * ("F2".equals(font) ? 1.08f : 1.0f);
    }

    private static List<String> wrap(String text, String font, float size, float maxW) {
        List<String> out = new ArrayList<>();
        String t = clean(text).trim();
        if (t.isEmpty()) {
            out.add("");
            return out;
        }
        StringBuilder line = new StringBuilder();
        for (String word : t.split("\\s+")) {
            // hard-split words longer than a full line
            while (width(word, font, size) > maxW) {
                int cut = word.length();
                while (cut > 1 && width(word.substring(0, cut), font, size) > maxW) cut--;
                if (line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                out.add(word.substring(0, cut));
                word = word.substring(cut);
            }
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (width(candidate, font, size) <= maxW) {
                line.setLength(0);
                line.append(candidate);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    private static String f(float v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    // ------------------------------------------------------------------- output

    public byte[] toBytes() {
        int n = pages.size();
        // object numbers: 1 catalog, 2 pages, 3 F1, 4 F2, then (page, content) pairs
        List<byte[]> objs = new ArrayList<>();
        objs.add(bytes("<< /Type /Catalog /Pages 2 0 R >>"));
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) kids.append(5 + 2 * i).append(" 0 R ");
        objs.add(bytes("<< /Type /Pages /Kids [" + kids + "] /Count " + n + " >>"));
        objs.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));
        objs.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"));

        for (int i = 0; i < n; i++) {
            StringBuilder content = new StringBuilder(pages.get(i));
            // footer
            content.append("0.45 g BT /F1 8 Tf ").append(f(MARGIN)).append(" 32 Td (")
                   .append(escape(footerLeft)).append(") Tj ET\n");
            String pg = "Page " + (i + 1) + " of " + n;
            content.append("BT /F1 8 Tf ").append(f(PAGE_W - MARGIN - width(pg, "F1", 8))).append(" 32 Td (")
                   .append(pg).append(") Tj ET\n");
            byte[] stream = content.toString().getBytes(StandardCharsets.ISO_8859_1);
            objs.add(bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + (int) PAGE_W + " " + (int) PAGE_H
                    + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + (6 + 2 * i) + " 0 R >>"));
            ByteArrayOutputStream so = new ByteArrayOutputStream();
            so.writeBytes(bytes("<< /Length " + stream.length + " >>\nstream\n"));
            so.writeBytes(stream);
            so.writeBytes(bytes("endstream"));
            objs.add(so.toByteArray());
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(bytes("%PDF-1.4\n"));
        int[] offsets = new int[objs.size()];
        for (int i = 0; i < objs.size(); i++) {
            offsets[i] = out.size();
            out.writeBytes(bytes((i + 1) + " 0 obj\n"));
            out.writeBytes(objs.get(i));
            out.writeBytes(bytes("\nendobj\n"));
        }
        int xref = out.size();
        StringBuilder x = new StringBuilder("xref\n0 " + (objs.size() + 1) + "\n0000000000 65535 f \n");
        for (int off : offsets) x.append(String.format("%010d 00000 n \n", off));
        x.append("trailer\n<< /Size ").append(objs.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        out.writeBytes(bytes(x.toString()));
        return out.toByteArray();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
}
