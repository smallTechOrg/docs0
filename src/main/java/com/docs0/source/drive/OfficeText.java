package com.docs0.source.drive;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Plain text from Microsoft Office Open XML files (.docx, .xlsx, .pptx), which are zip files of XML. Read with the
 * JDK's own XML parser (no external entities or DTDs), so no library is needed.
 */
final class OfficeText {

    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    /** Guards against a zip bomb: no single part is read beyond this many bytes. */
    private static final long MAX_PART_BYTES = 60L * 1024 * 1024;

    private OfficeText() {}

    static boolean supported(String mimeType) {
        return DOCX.equals(mimeType) || XLSX.equals(mimeType) || PPTX.equals(mimeType);
    }

    static String extract(String mimeType, byte[] content) throws IOException {
        TreeMap<String, byte[]> parts = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                if (wanted(mimeType, name)) {
                    parts.put(name, zip.readNBytes((int) MAX_PART_BYTES));
                }
            }
        }
        try {
            return switch (mimeType) {
                case DOCX -> docx(parts);
                case XLSX -> xlsx(parts);
                default -> pptx(parts);
            };
        } catch (XMLStreamException e) {
            throw new IOException("Office file is not readable: " + e.getMessage(), e);
        }
    }

    private static boolean wanted(String mimeType, String name) {
        if (!name.endsWith(".xml")) {
            return false;
        }
        return switch (mimeType) {
            case DOCX -> name.equals("word/document.xml") || name.matches("word/(header|footer|footnotes|endnotes)\\d*\\.xml");
            case XLSX -> name.equals("xl/sharedStrings.xml") || name.equals("xl/workbook.xml")
                    || name.matches("xl/worksheets/sheet\\d+\\.xml");
            default -> name.matches("ppt/slides/slide\\d+\\.xml") || name.matches("ppt/notesSlides/notesSlide\\d+\\.xml");
        };
    }

    // ---- docx: paragraphs (w:p) of text runs (w:t); tabs and breaks become spaces/newlines ----

    private static String docx(TreeMap<String, byte[]> parts) throws XMLStreamException {
        StringBuilder out = new StringBuilder();
        append(out, text(parts.get("word/document.xml"), "p"));
        parts.forEach((name, xml) -> {
            if (!name.equals("word/document.xml")) {
                try {
                    append(out, text(xml, "p"));
                } catch (XMLStreamException ignored) {
                    // a damaged header or footnote part must not lose the body
                }
            }
        });
        return out.toString().strip();
    }

    // ---- pptx: slides in numeric order, then their notes ----

    private static String pptx(TreeMap<String, byte[]> parts) throws XMLStreamException {
        List<String> names = new ArrayList<>(parts.keySet());
        names.sort((a, b) -> {
            boolean an = a.contains("notesSlide");
            boolean bn = b.contains("notesSlide");
            return an != bn ? Boolean.compare(an, bn) : Integer.compare(number(a), number(b));
        });
        StringBuilder out = new StringBuilder();
        for (String name : names) {
            append(out, text(parts.get(name), "p"));
        }
        return out.toString().strip();
    }

    // ---- xlsx: shared strings resolve cell values; one tab-separated line per row, per sheet ----

    private static String xlsx(TreeMap<String, byte[]> parts) throws XMLStreamException {
        List<String> shared = new ArrayList<>();
        byte[] sharedXml = parts.get("xl/sharedStrings.xml");
        if (sharedXml != null) {
            XMLStreamReader r = reader(sharedXml);
            StringBuilder item = null;
            boolean inText = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String n = r.getLocalName();
                    if (n.equals("si")) {
                        item = new StringBuilder();
                    } else if (n.equals("t")) {
                        inText = true;
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS && inText && item != null) {
                    item.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String n = r.getLocalName();
                    if (n.equals("t")) {
                        inText = false;
                    } else if (n.equals("si") && item != null) {
                        shared.add(item.toString());
                        item = null;
                    }
                }
            }
        }
        List<String> sheetNames = new ArrayList<>();
        byte[] workbook = parts.get("xl/workbook.xml");
        if (workbook != null) {
            XMLStreamReader r = reader(workbook);
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("sheet")) {
                    sheetNames.add(r.getAttributeValue(null, "name"));
                }
            }
        }
        List<String> sheets = new ArrayList<>(parts.keySet().stream().filter(n -> n.startsWith("xl/worksheets/")).toList());
        sheets.sort((a, b) -> Integer.compare(number(a), number(b)));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sheets.size(); i++) {
            if (i < sheetNames.size() && sheetNames.get(i) != null) {
                out.append("## ").append(sheetNames.get(i)).append('\n');
            }
            XMLStreamReader r = reader(parts.get(sheets.get(i)));
            List<String> row = new ArrayList<>();
            String type = null;
            boolean inValue = false;
            boolean inInline = false;
            StringBuilder cell = new StringBuilder();
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "row" -> row.clear();
                        case "c" -> {
                            type = r.getAttributeValue(null, "t");
                            cell.setLength(0);
                        }
                        case "v" -> inValue = true;
                        case "t" -> inInline = true;
                        default -> { }
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS && (inValue || inInline)) {
                    cell.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "v" -> inValue = false;
                        case "t" -> inInline = false;
                        case "c" -> {
                            String value = cell.toString();
                            if ("s".equals(type)) {
                                try {
                                    value = shared.get(Integer.parseInt(value.strip()));
                                } catch (RuntimeException e) {
                                    value = "";
                                }
                            }
                            row.add(value);
                        }
                        case "row" -> {
                            String line = String.join("\t", row).stripTrailing();
                            if (!line.isBlank()) {
                                out.append(line).append('\n');
                            }
                        }
                        default -> { }
                    }
                }
            }
            out.append('\n');
        }
        return out.toString().strip();
    }

    /** Text of every {@code a:t}/{@code w:t} run, one line per paragraph element named {@code paragraph}. */
    private static String text(byte[] xml, String paragraph) throws XMLStreamException {
        if (xml == null) {
            return "";
        }
        XMLStreamReader r = reader(xml);
        StringBuilder out = new StringBuilder();
        boolean inRun = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "t" -> inRun = true;
                    case "tab" -> out.append('\t');
                    case "br" -> out.append('\n');
                    default -> { }
                }
            } else if (ev == XMLStreamConstants.CHARACTERS && inRun) {
                out.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String n = r.getLocalName();
                if (n.equals("t")) {
                    inRun = false;
                } else if (n.equals(paragraph)) {
                    out.append('\n');
                }
            }
        }
        return out.toString();
    }

    private static void append(StringBuilder out, String text) {
        if (!text.isBlank()) {
            out.append(text.stripTrailing()).append("\n\n");
        }
    }

    private static int number(String name) {
        String digits = name.replaceAll("\\D+", " ").strip();
        if (digits.isEmpty()) {
            return 0;
        }
        String[] parts = digits.split(" ");
        try {
            return Integer.parseInt(parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static XMLStreamReader reader(byte[] xml) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        InputStream in = new ByteArrayInputStream(xml);
        return f.createXMLStreamReader(in);
    }
}
