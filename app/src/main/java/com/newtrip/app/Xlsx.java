package com.newtrip.app;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** كاتب ملفات Excel (.xlsx) بسيط بلا مكتبات خارجية: أوراق متعددة، صف عناوين عريض ومثبّت، وأعمدة بعرض مناسب. */
final class Xlsx {
    private Xlsx() { }

    static final class Sheet {
        final String name;
        final List<Object[]> rows = new ArrayList<>();
        Sheet(String name) { this.name = name; }
        /** صف قيم: String أو Number أو null (خلية فارغة). NaN تُكتب خلية فارغة. */
        Sheet add(Object... cells) { rows.add(cells); return this; }
    }

    static void write(OutputStream os, List<Sheet> sheets) throws IOException {
        ZipOutputStream z = new ZipOutputStream(os);
        put(z, "[Content_Types].xml", contentTypes(sheets.size()));
        put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>");
        StringBuilder wb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < sheets.size(); i++) {
            wb.append("<sheet name=\"").append(esc(safeName(sheets.get(i).name))).append("\" sheetId=\"").append(i + 1)
              .append("\" r:id=\"rId").append(i + 1).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(i + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                .append(i + 1).append(".xml\"/>");
        }
        rels.append("<Relationship Id=\"rId").append(sheets.size() + 1)
            .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
        wb.append("</sheets></workbook>");
        put(z, "xl/workbook.xml", wb.toString());
        put(z, "xl/_rels/workbook.xml.rels", rels.toString());
        put(z, "xl/styles.xml", styles());
        for (int i = 0; i < sheets.size(); i++) put(z, "xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml(sheets.get(i)));
        z.finish();
        z.flush();
    }

    private static String contentTypes(int n) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 1; i <= n; i++)
            b.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
             .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        return b.append("</Types>").toString();
    }

    private static String styles() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                + "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font></fonts>"
                + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1F4E79\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/></cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "</styleSheet>";
    }

    private static String sheetXml(Sheet sh) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        b.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>");
        int cols = 0;
        for (Object[] r : sh.rows) cols = Math.max(cols, r.length);
        if (cols > 0) {
            b.append("<cols>");
            for (int c = 0; c < cols; c++) {
                int w = 10;
                for (int r = 0; r < Math.min(sh.rows.size(), 60); r++) {
                    Object[] row = sh.rows.get(r);
                    if (c < row.length && row[c] instanceof String) w = Math.max(w, Math.min(40, ((String) row[c]).length() + 2));
                }
                b.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"").append(w).append("\" customWidth=\"1\"/>");
            }
            b.append("</cols>");
        }
        b.append("<sheetData>");
        for (int r = 0; r < sh.rows.size(); r++) {
            Object[] row = sh.rows.get(r);
            b.append("<row r=\"").append(r + 1).append("\">");
            for (int c = 0; c < row.length; c++) {
                Object v = row[c];
                if (v == null) continue;
                if (v instanceof Number) {
                    double d = ((Number) v).doubleValue();
                    if (Double.isNaN(d) || Double.isInfinite(d)) continue;
                    b.append("<c r=\"").append(ref(c, r + 1)).append("\"").append(r == 0 ? " s=\"1\"" : "").append("><v>")
                     .append(v instanceof Double || v instanceof Float ? trim(d) : String.valueOf(((Number) v).longValue()))
                     .append("</v></c>");
                } else {
                    String t = String.valueOf(v);
                    b.append("<c r=\"").append(ref(c, r + 1)).append("\" t=\"inlineStr\"").append(r == 0 ? " s=\"1\"" : "")
                     .append("><is><t xml:space=\"preserve\">").append(esc(t)).append("</t></is></c>");
                }
            }
            b.append("</row>");
        }
        return b.append("</sheetData></worksheet>").toString();
    }

    private static String trim(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        return String.valueOf(Math.round(d * 10000.0) / 10000.0);
    }

    static String ref(int col, int row) {
        StringBuilder s = new StringBuilder();
        int c = col;
        do { s.insert(0, (char) ('A' + c % 26)); c = c / 26 - 1; } while (c >= 0);
        return s.append(row).toString();
    }

    private static String safeName(String n) {
        String s = n.replaceAll("[\\[\\]:*?/\\\\]", " ");
        return s.length() > 31 ? s.substring(0, 31) : s;
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') b.append("&amp;");
            else if (c == '<') b.append("&lt;");
            else if (c == '>') b.append("&gt;");
            else if (c == '"') b.append("&quot;");
            else if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') continue;
            else b.append(c);
        }
        return b.toString();
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }
}
