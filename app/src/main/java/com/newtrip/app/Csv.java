package com.newtrip.app;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** كاتب CSV (UTF-8 مع BOM ليفتحه Excel بالعربية صحيحاً، وفواصل أسطر CRLF، وتهريب الفواصل والاقتباس وفق RFC 4180). */
final class Csv {
    private Csv() { }

    static final class Table {
        final String name;
        final List<Object[]> rows = new ArrayList<>();
        Table(String name) { this.name = name; }
        /** صف قيم: String أو Number أو null (خلية فارغة). NaN تُكتب خلية فارغة. */
        Table add(Object... cells) { rows.add(cells); return this; }
        /** هل فيه بيانات غير صف العناوين؟ */
        boolean hasData() { return rows.size() > 1; }
    }

    static void write(OutputStream os, Table t) throws IOException {
        StringBuilder b = new StringBuilder("\uFEFF");
        for (Object[] row : t.rows) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) b.append(',');
                b.append(cell(row[i]));
            }
            b.append("\r\n");
        }
        os.write(b.toString().getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    static String cell(Object v) {
        if (v == null) return "";
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return "";
            if (v instanceof Double || v instanceof Float) {
                if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
                return String.format(Locale.US, "%.4f", d).replaceAll("0+$", "").replaceAll("\\.$", "");
            }
            return String.valueOf(((Number) v).longValue());
        }
        String s = String.valueOf(v);
        // حماية من حقن الصيغ عند فتح الملف في Excel
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !s.matches("[-+]?\\d+(\\.\\d+)?")) s = "'" + s;
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0)
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
