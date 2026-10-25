package com.aresstack.argos.document;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Text of Office Open XML (docx, xlsx, pptx) and OpenDocument (odt, ods, odp) files: both are ZIP archives of
 * XML parts. Only text runs and paragraph/cell boundaries are kept; formatting, images and macros are ignored.
 * DTDs and external entities are disabled and the unpacked size is limited against ZIP bombs.
 */
final class ZippedXmlText {
    private static final long MAX_UNPACKED_BYTES = 64L * 1024 * 1024;
    private static final int MAX_ENTRIES = 2_000;
    private static final int MAX_COLUMNS = 1_000;
    private static final Pattern NUMBERED_PART = Pattern.compile(".*?(\\d+)\\.xml$");

    private ZippedXmlText() { }

    static String read(DocumentFormat format, byte[] content) throws Exception {
        Map<String, byte[]> parts = unpack(content, format);
        return switch (format) {
            case DOCX -> paragraphs(parts.get("word/document.xml"), "p", "t", "tab", "br");
            case PPTX -> slides(parts);
            case XLSX -> spreadsheet(parts);
            case ODT, ODS, ODP -> paragraphs(parts.get("content.xml"), "p|h|table-row", "", "tab", "line-break");
            default -> throw new IllegalArgumentException("Not a zipped XML format: " + format);
        };
    }

    private static boolean wanted(DocumentFormat format, String name) {
        return switch (format) {
            case DOCX -> name.equals("word/document.xml");
            case PPTX -> name.startsWith("ppt/slides/slide") && name.endsWith(".xml");
            case XLSX -> name.equals("xl/sharedStrings.xml")
                    || (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"));
            default -> name.equals("content.xml");
        };
    }

    private static Map<String, byte[]> unpack(byte[] content, DocumentFormat format) throws IOException {
        Map<String, byte[]> parts = new TreeMap<>();
        long unpacked = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("Too many archive entries");
                // Ignored entries are decompressed too when the stream skips them, so they count as well.
                boolean keep = !entry.isDirectory() && wanted(format, entry.getName());
                ByteArrayOutputStream part = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = zip.read(buffer)) > 0) {
                    unpacked += read;
                    if (unpacked > MAX_UNPACKED_BYTES) throw new IOException("Archive unpacks too large");
                    if (keep) part.write(buffer, 0, read);
                }
                if (!keep) continue;
                parts.put(entry.getName(), part.toByteArray());
            }
        }
        if (parts.isEmpty()) throw new IOException("No text parts in archive");
        return parts;
    }

    private static XMLStreamReader reader(byte[] xml) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        InputStream input = new ByteArrayInputStream(xml);
        return factory.createXMLStreamReader(input);
    }

    /**
     * Collects character data. {@code blocks} (regex of local names) end a line; {@code textElement} restricts
     * text to that element (empty: all character data); {@code tab}/{@code lineBreak} are inline separators.
     */
    private static String paragraphs(byte[] xml, String blocks, String textElement, String tab, String lineBreak)
            throws Exception {
        if (xml == null) throw new IOException("Main document part missing");
        StringBuilder text = new StringBuilder();
        XMLStreamReader reader = reader(xml);
        int inText = 0;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String name = reader.getLocalName();
                if (name.equals(textElement)) inText++;
                else if (name.equals(tab)) text.append('\t');
                else if (name.equals(lineBreak)) text.append('\n');
                else if (name.equals("table-cell")) text.append(text.isEmpty()
                        || text.charAt(text.length() - 1) == '\n' ? "" : "\t");
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String name = reader.getLocalName();
                if (name.equals(textElement)) inText--;
                if (name.matches(blocks)) text.append('\n');
            } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                    && (textElement.isEmpty() || inText > 0)) {
                text.append(reader.getText());
            }
        }
        return text.toString().replaceAll("\\n{3,}", "\n\n").strip();
    }

    private static String slides(Map<String, byte[]> parts) throws Exception {
        StringBuilder text = new StringBuilder();
        List<String> names = numbered(parts, "ppt/slides/slide");
        for (int i = 0; i < names.size(); i++) {
            String slide = paragraphs(parts.get(names.get(i)), "p", "t", "tab", "br");
            text.append("--- Slide ").append(i + 1).append(" ---\n").append(slide).append("\n\n");
        }
        return text.toString().strip();
    }

    private static String spreadsheet(Map<String, byte[]> parts) throws Exception {
        List<String> shared = sharedStrings(parts.get("xl/sharedStrings.xml"));
        StringBuilder text = new StringBuilder();
        List<String> sheets = numbered(parts, "xl/worksheets/sheet");
        for (int i = 0; i < sheets.size(); i++) {
            text.append("--- Sheet ").append(i + 1).append(" ---\n");
            XMLStreamReader reader = reader(parts.get(sheets.get(i)));
            String cellType = "";
            boolean inValue = false;
            int nextColumn = 0;
            int cellColumn = 0;
            StringBuilder value = new StringBuilder();
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "row" -> nextColumn = 0;
                        case "c" -> {
                            cellType = reader.getAttributeValue(null, "t");
                            cellType = cellType == null ? "" : cellType;
                            int referenced = columnOf(reader.getAttributeValue(null, "r"));
                            cellColumn = referenced >= 0 ? referenced : nextColumn;
                        }
                        case "v", "t" -> {
                            inValue = true;
                            value.setLength(0);
                        }
                        default -> { }
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && inValue) {
                    value.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "v", "t" -> {
                            inValue = false;
                            String cell = value.toString();
                            if (cellType.equals("s") && cell.matches("\\d{1,9}")) {
                                int index = Integer.parseInt(cell);
                                cell = index < shared.size() ? shared.get(index) : "";
                            }
                            // Empty cells are not stored; keep the following values in their columns.
                            text.append("\t".repeat(Math.max(0, Math.min(cellColumn, MAX_COLUMNS)
                                    - nextColumn + (nextColumn > 0 ? 1 : 0))));
                            text.append(cell);
                            nextColumn = Math.min(cellColumn, MAX_COLUMNS) + 1;
                        }
                        case "row" -> text.append('\n');
                        default -> { }
                    }
                }
            }
            text.append('\n');
        }
        return text.toString().strip();
    }

    /** Zero-based column of a cell reference such as {@code C2}, or -1 when absent. */
    static int columnOf(String reference) {
        if (reference == null) return -1;
        int column = 0;
        int letters = 0;
        for (char c : reference.toCharArray()) {
            if (c < 'A' || c > 'Z') break;
            column = column * 26 + (c - 'A' + 1);
            if (++letters > 3) return -1;
        }
        return letters == 0 ? -1 : column - 1;
    }

    private static List<String> sharedStrings(byte[] xml) throws Exception {
        List<String> strings = new ArrayList<>();
        if (xml == null) return strings;
        XMLStreamReader reader = reader(xml);
        StringBuilder current = null;
        boolean inText = false;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if (reader.getLocalName().equals("si")) current = new StringBuilder();
                else if (reader.getLocalName().equals("t")) inText = true;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                if (reader.getLocalName().equals("si") && current != null) strings.add(current.toString());
                else if (reader.getLocalName().equals("t")) inText = false;
            } else if (event == XMLStreamConstants.CHARACTERS && inText && current != null) {
                current.append(reader.getText());
            }
        }
        return strings;
    }

    private static List<String> numbered(Map<String, byte[]> parts, String prefix) {
        return parts.keySet().stream().filter(name -> name.startsWith(prefix))
                .sorted(Comparator.comparingInt(ZippedXmlText::partNumber)).toList();
    }

    private static int partNumber(String name) {
        Matcher matcher = NUMBERED_PART.matcher(name);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }
}
