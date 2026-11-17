package com.docs0.source.drive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class DriveTextTest {

    @Test
    void extractsTextFromEveryPageOfAPdf() throws IOException {
        byte[] pdf = pdf("Quarterly revenue grew by twelve percent.", "Second page mentions the okapi.");

        String text = DriveText.extract(DriveText.PDF, pdf);

        assertThat(text).contains("Quarterly revenue grew by twelve percent.");
        assertThat(text).contains("Second page mentions the okapi.");
    }

    @Test
    void corruptPdfThrowsInsteadOfReturningGarbage() {
        assertThatThrownBy(() -> DriveText.extract(DriveText.PDF, "not a pdf".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void textTypesAreDecodedAsUtf8WithoutTheByteOrderMark() throws IOException {
        byte[] exported = "﻿Café résumé — naïve".getBytes(StandardCharsets.UTF_8);

        assertThat(DriveText.extract(DriveText.GOOGLE_DOC, exported)).isEqualTo("Café résumé — naïve");
        assertThat(DriveText.extract("text/markdown", "# Title".getBytes(StandardCharsets.UTF_8))).isEqualTo("# Title");
        assertThat(DriveText.extract("text/csv", "a,b\n1,2".getBytes(StandardCharsets.UTF_8))).isEqualTo("a,b\n1,2");
    }

    @Test
    void knowsWhichTypesAreIndexedAndHowGoogleTypesAreExported() {
        assertThat(DriveText.exportMimeType(DriveText.GOOGLE_DOC)).contains("text/plain");
        assertThat(DriveText.exportMimeType(DriveText.GOOGLE_SHEET)).contains("text/csv");
        assertThat(DriveText.exportMimeType(DriveText.GOOGLE_SLIDES)).contains("text/plain");
        assertThat(DriveText.exportMimeType(DriveText.PDF)).isEmpty();
        assertThat(DriveText.exportMimeType("text/plain")).isEmpty();

        for (String type : new String[] {DriveText.GOOGLE_DOC, DriveText.GOOGLE_SHEET, DriveText.GOOGLE_SLIDES,
                DriveText.PDF, "text/plain", "text/markdown", "text/csv"}) {
            assertThat(DriveText.supported(type)).as(type).isTrue();
        }
        for (String type : new String[] {DriveText.FOLDER, "image/png", "application/zip",
                "application/vnd.google-apps.shortcut", "application/vnd.google-apps.form", null}) {
            assertThat(DriveText.supported(type)).as(String.valueOf(type)).isFalse();
        }
    }

    static byte[] pdf(String... pages) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String line : pages) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 700);
                    content.showText(line);
                    content.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void extractsParagraphsTablesAndHeadersFromADocx() throws IOException {
        String body = "<w:document xmlns:w='w'><w:body>"
                + "<w:p><w:r><w:t>Outreach Strategy</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t xml:space='preserve'>Warm </w:t></w:r><w:r><w:t>accounts first</w:t></w:r></w:p>"
                + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Tier 1</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
                + "</w:body></w:document>";
        byte[] docx = zip("word/document.xml", body, "word/header1.xml",
                "<w:hdr xmlns:w='w'><w:p><w:r><w:t>Confidential header</w:t></w:r></w:p></w:hdr>");

        String text = DriveText.extract(OfficeText.DOCX, docx);

        assertThat(text).contains("Outreach Strategy").contains("Warm accounts first").contains("Tier 1")
                .contains("Confidential header");
        assertThat(DriveText.indexable(OfficeText.DOCX, 17810L)).isTrue();
    }

    @Test
    void extractsSheetsWithSharedStringsAndSheetNamesFromAnXlsx() throws IOException {
        byte[] xlsx = zip(
                "xl/workbook.xml", "<workbook><sheets><sheet name='Outreach' sheetId='1'/></sheets></workbook>",
                "xl/sharedStrings.xml", "<sst><si><t>Company</t></si><si><t>Sharp HRD</t></si></sst>",
                "xl/worksheets/sheet1.xml", "<worksheet><sheetData>"
                        + "<row><c t='s'><v>0</v></c><c><v>Count</v></c></row>"
                        + "<row><c t='s'><v>1</v></c><c><v>42</v></c></row></sheetData></worksheet>");

        String text = DriveText.extract(OfficeText.XLSX, xlsx);

        assertThat(text).contains("## Outreach").contains("Company").contains("Sharp HRD\t42");
    }

    @Test
    void extractsSlidesInNumericOrderThenNotesFromAPptx() throws IOException {
        byte[] pptx = zip(
                "ppt/slides/slide10.xml", "<p:sld xmlns:p='p' xmlns:a='a'><a:p><a:r><a:t>Tenth</a:t></a:r></a:p></p:sld>",
                "ppt/slides/slide2.xml", "<p:sld xmlns:p='p' xmlns:a='a'><a:p><a:r><a:t>Second</a:t></a:r></a:p></p:sld>",
                "ppt/notesSlides/notesSlide2.xml", "<p:notes xmlns:p='p' xmlns:a='a'><a:p><a:r><a:t>Speaker note</a:t></a:r></a:p></p:notes>");

        String text = DriveText.extract(OfficeText.PPTX, pptx);

        assertThat(text.indexOf("Second")).isLessThan(text.indexOf("Tenth"));
        assertThat(text.indexOf("Tenth")).isLessThan(text.indexOf("Speaker note"));
    }

    @Test
    void aCorruptOfficeFileThrowsInsteadOfReturningGarbage() {
        assertThatThrownBy(() -> DriveText.extract(OfficeText.DOCX,
                zip("word/document.xml", "<w:document><oops").clone()))
                .isInstanceOf(IOException.class);
    }

    private static byte[] zip(String... namesAndContents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zip.putNextEntry(new java.util.zip.ZipEntry(namesAndContents[i]));
                zip.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void htmlLosesItsTagsScriptsAndStyles() throws IOException {
        String html = "<html><style>p{color:red}</style><body><h1>Pricing</h1><p>Plans &amp; terms</p>"
                + "<script>var x=1;</script></body></html>";

        String text = DriveText.extract(DriveText.HTML, html.getBytes(StandardCharsets.UTF_8));

        assertThat(text).contains("Pricing").contains("Plans & terms").doesNotContain("color").doesNotContain("var x");
        assertThat(DriveText.supported("text/vtt")).isTrue();
        assertThat(DriveText.supported("application/json")).isTrue();
    }
}
