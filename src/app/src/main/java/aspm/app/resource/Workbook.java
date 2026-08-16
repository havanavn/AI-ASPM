package aspm.app.resource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A minimal writer for the Office Open XML spreadsheet format — an {@code .xlsx} file.
 *
 * <h2>Why this exists rather than a library</h2>
 *
 * <p>The two usual candidates (Apache POI, EasyExcel) are large dependencies with a wide surface,
 * and this platform holds the exploitable attack surface of an entire group: every dependency added
 * here is a dependency on the software composition dashboard three floors down, and one that parses
 * untrusted spreadsheet formats to WRITE one is a poor trade. An xlsx is a zip of four small XML
 * parts, and writing them costs less than reviewing a parser.
 *
 * <p><b>It writes; it does not read.</b> That asymmetry is the safety argument: nothing here consumes
 * a file anybody sent, so the format's parsing history is not inherited.
 *
 * <h2>Why not CSV</h2>
 *
 * <p>CSV opens in Excel and would have been a line of code. It also silently mangles the data this
 * particular export carries: a CVE identifier is fine, but a version like {@code 1.2.10} is read as a
 * date in several locales, a purl containing a comma splits a column, and a leading {@code =} in any
 * cell is a formula — which is the CSV injection class this product exists to find in other people's
 * software. Every cell here is written as an inline string with the type declared, so none of that
 * can happen.
 */
public final class Workbook {

    /** One sheet: a name, a header row, and the rows under it. */
    public record Sheet(String name, List<String> headers, List<List<String>> rows) {
    }

    private Workbook() {
    }

    /** Renders the sheets as the bytes of an {@code .xlsx} file. */
    public static byte[] write(List<Sheet> sheets) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "[Content_Types].xml", contentTypes(sheets.size()));
            put(zip, "_rels/.rels",
                    """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>""");
            put(zip, "xl/workbook.xml", workbook(sheets));
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels(sheets.size()));
            for (int i = 0; i < sheets.size(); i++) {
                put(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheet(sheets.get(i)));
            }
        }
        return out.toByteArray();
    }

    // ----------------------------------------------------------------------------------------------

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String contentTypes(int sheets) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""");
        for (int i = 1; i <= sheets; i++) {
            xml.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
               .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return xml.append("</Types>").toString();
    }

    private static String workbook(List<Sheet> sheets) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets>""");
        for (int i = 0; i < sheets.size(); i++) {
            xml.append("<sheet name=\"").append(escape(sheetName(sheets.get(i).name())))
               .append("\" sheetId=\"").append(i + 1).append("\" r:id=\"rId").append(i + 1)
               .append("\"/>");
        }
        return xml.append("</sheets></workbook>").toString();
    }

    private static String workbookRels(int sheets) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""");
        for (int i = 1; i <= sheets; i++) {
            xml.append("<Relationship Id=\"rId").append(i)
               .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
               .append(i).append(".xml\"/>");
        }
        return xml.append("</Relationships>").toString();
    }

    private static String sheet(Sheet sheet) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""");
        row(xml, 1, sheet.headers());
        int number = 2;
        for (List<String> values : sheet.rows()) {
            row(xml, number++, values);
        }
        return xml.append("</sheetData></worksheet>").toString();
    }

    private static void row(StringBuilder xml, int number, List<String> values) {
        xml.append("<row r=\"").append(number).append("\">");
        for (int column = 0; column < values.size(); column++) {
            String value = values.get(column) == null ? "" : values.get(column);
            // t="inlineStr" on every cell, deliberately. Excel's type inference is what turns a
            // version string into a date and a leading "=" into a formula; declaring the type is what
            // stops both. It costs a little file size and removes a whole class of wrong answer.
            xml.append("<c r=\"").append(reference(column, number))
               .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
               .append(escape(value)).append("</t></is></c>");
        }
        xml.append("</row>");
    }

    /** {@code A1}, {@code B1}, … {@code AA1}. */
    private static String reference(int column, int row) {
        StringBuilder name = new StringBuilder();
        int index = column;
        do {
            name.insert(0, (char) ('A' + index % 26));
            index = index / 26 - 1;
        } while (index >= 0);
        return name.append(row).toString();
    }

    /**
     * A sheet name Excel will accept.
     *
     * <p>Thirty-one characters, and none of {@code []:*?/\}. Excel refuses the file outright rather
     * than repairing it, so a scope named "Payments / Cards" would produce a download that does not
     * open — a failure the reader would blame on the platform, correctly.
     */
    private static String sheetName(String raw) {
        String cleaned = raw.replaceAll("[\\[\\]:*?/\\\\]", "-");
        return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
    }

    /**
     * XML escaping, including the control characters XML 1.0 cannot represent at all.
     *
     * <p>Finding titles and advisory summaries are attacker-authored text — that is the fifth
     * highest-risk surface in this product. A stray control byte in one of them would produce a
     * corrupt workbook, and the export is exactly where such content ends up.
     */
    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    if (c == '\t' || c == '\n' || c == '\r' || c >= 0x20) {
                        out.append(c);
                    }
                    // Anything else is dropped. XML 1.0 has no representation for it, so the choice
                    // is between dropping the character and producing a file that will not open.
                }
            }
        }
        return out.toString();
    }
}
