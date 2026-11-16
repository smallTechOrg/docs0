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
}
