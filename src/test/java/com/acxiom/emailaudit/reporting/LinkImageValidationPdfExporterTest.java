package com.acxiom.emailaudit.reporting;

import com.acxiom.emailaudit.campaign.CampaignValidationResult;
import com.acxiom.emailaudit.output.ExecutionOutputManager;
import com.acxiom.emailaudit.reporting.dashboard.EmailMetadataData;
import com.acxiom.emailaudit.reporting.dashboard.FileAuditData;
import com.acxiom.emailaudit.reporting.dashboard.ImageAuditData;
import com.acxiom.emailaudit.reporting.dashboard.LinkAuditData;
import com.acxiom.emailaudit.reporting.dashboard.RuleAuditData;
import com.acxiom.emailaudit.reporting.dashboard.RunAuditData;
import com.acxiom.emailaudit.reporting.dashboard.SectionCheckResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.cos.COSName;
import org.testng.annotations.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class LinkImageValidationPdfExporterTest {

    @Test
    public void generatesPreSendPdfInsideCurrentExecutionFolderWithExpectedEvidence()
            throws Exception {

        final ExecutionOutputManager.ExecutionOutput output =
                ExecutionOutputManager.startNewExecution();
        final Path screenshot = writeScreenshot(output.screenshotsDir().resolve("evidence.png"));
        final Path pdf = LinkImageValidationPdfExporter.export(
                sampleData(
                        "GM",
                        "PRE_SEND",
                        EmailMetadataData.notAvailable(),
                        "../screenshots/evidence.png"),
                LinkImageValidationPdfExporter.outputPath());

        final String text = pdfText(pdf);
        assertEquals(pdf.getParent(), output.executionRoot());
        assertEquals(pdf.getFileName().toString(), "LinkImageValidationReport.pdf");
        assertTrue(Files.isRegularFile(screenshot));
        assertTrue(text.contains("EMAIL CAMPAIGN VALIDATION REPORT"));
        assertTrue(text.contains("Client: GM"));
        assertTrue(text.contains("Validation Type: PRE-SEND"));
        assertTrue(text.contains("Total: 2"));
        assertTrue(text.contains("Passed: 1"));
        assertTrue(text.contains("Failed: 1"));
        assertTrue(text.contains("Search Inventory"));
        assertTrue(text.contains("Hero Banner"));
        assertTrue(text.contains("ATTENTION REQUIRED"));
        assertTrue(imageCount(pdf) >= 1);
        assertFalse(text.contains("ACCESSIBILITY_AXE"));
        assertFalse(text.contains("HTML_VALIDATION"));
        assertFalse(text.contains("Campaign Specification"));
    }

    @Test
    public void generatesPostSendPdfWithEmailSubjectMetadata()
            throws Exception {

        ExecutionOutputManager.startNewExecution();
        final EmailMetadataData emailMetadata = new EmailMetadataData(
                true,
                "GM Offers - Email Test",
                "sender@example.test",
                List.of("qa@example.test"),
                List.of(),
                List.of(),
                "",
                Instant.parse("2026-08-10T15:00:00Z"),
                "message-id");

        final Path pdf = LinkImageValidationPdfExporter.export(
                sampleData("AT&T", "POST_SEND", emailMetadata, ""),
                LinkImageValidationPdfExporter.outputPath());

        final String text = pdfText(pdf);
        assertTrue(text.contains("Client: AT&T"));
        assertTrue(text.contains("Validation Type: POST-SEND"));
        assertTrue(text.contains("Email Subject: GM Offers - Email Test"));
    }

    @Test
    public void missingScreenshotDoesNotFailPdfGeneration()
            throws Exception {

        ExecutionOutputManager.startNewExecution();
        final Path pdf = LinkImageValidationPdfExporter.export(
                sampleData("General", "PRE_SEND", EmailMetadataData.notAvailable(), "../screenshots/missing.png"),
                LinkImageValidationPdfExporter.outputPath());

        final String text = pdfText(pdf);
        assertTrue(Files.isRegularFile(pdf));
        assertTrue(text.contains("Screenshot unavailable"));
    }

    @Test
    public void longUrlsRemainPresentInPdfText()
            throws Exception {

        ExecutionOutputManager.startNewExecution();
        final String longUrl = "https://example.test/landing-page/path/with/a/very-long-url-segment"
                + "?evar36=C2000000014&cmp=spring-sale&hid=ABCDEF1234567890"
                + "&utm_campaign=client-facing-link-image-validation-report";
        final Path pdf = LinkImageValidationPdfExporter.export(
                dataWithLongUrl(longUrl),
                LinkImageValidationPdfExporter.outputPath());

        final String normalisedText = pdfText(pdf).replace("\n", "");
        assertTrue(normalisedText.contains("very-long-url-segment"));
        assertTrue(normalisedText.contains("client-facing-link-image-validation-report"));
    }

    private static RunAuditData sampleData(
            final String client,
            final String validationMode,
            final EmailMetadataData emailMetadata,
            final String screenshotPath) {

        final LinkAuditData passLink = new LinkAuditData(
                "Search Inventory",
                "https://example.test/search",
                "https://example.test/search",
                "HTTP",
                "",
                "PASS",
                "Rendered successfully",
                "Search Inventory",
                200,
                "OK",
                0,
                List.of(),
                300L,
                screenshotPath,
                "CTA",
                "",
                "",
                "",
                1,
                "");
        final LinkAuditData failLink = new LinkAuditData(
                "Broken Offer",
                "https://example.test/broken",
                "https://example.test/broken",
                "HTTP",
                "",
                "FAIL",
                "404 Not Found",
                "Broken Offer",
                404,
                "Not Found",
                0,
                List.of(),
                200L,
                "",
                "Text Link",
                "",
                "",
                "",
                2,
                "");

        final ImageAuditData passImage = new ImageAuditData(
                "https://example.test/hero.png",
                "Hero Banner",
                200,
                "PASS",
                false,
                800,
                300,
                800,
                300,
                true,
                true,
                screenshotPath,
                screenshotPath,
                "Rendered correctly",
                "",
                "Image");
        final ImageAuditData failImage = new ImageAuditData(
                "https://example.test/missing.png",
                "Missing Promo",
                404,
                "FAIL",
                false,
                null,
                null,
                null,
                null,
                false,
                false,
                "",
                "",
                "404 Not Found",
                "",
                "Image");

        final FileAuditData file = new FileAuditData(
                "campaign.html",
                "FAIL",
                2,
                1,
                1,
                List.of(new SectionCheckResult("Accessibility", "FAIL", "HIGH", 1, "Do not include")),
                List.of(
                        new RuleAuditData(
                                "Accessibility",
                                "ACCESSIBILITY_AXE",
                                "FAIL",
                                "HIGH",
                                List.of("ACCESSIBILITY_AXE hidden from PDF"),
                                "Do not include",
                                ""),
                        new RuleAuditData(
                                "HTML Validation",
                                "HTML_VALIDATION",
                                "FAIL",
                                "HIGH",
                                List.of("HTML_VALIDATION hidden from PDF"),
                                "Do not include",
                                "")),
                List.of(passLink, failLink),
                List.of(passImage, failImage),
                CampaignValidationResult.noSpecification(),
                screenshotPath);

        return new RunAuditData(
                client,
                validationMode,
                "HTML Folder",
                1,
                0,
                1,
                0,
                0,
                1250L,
                Instant.parse("2026-08-10T15:00:00Z"),
                emailMetadata,
                List.of(file));
    }

    private static RunAuditData dataWithLongUrl(final String url) {
        final LinkAuditData link = new LinkAuditData(
                "Learn More",
                url,
                url,
                "HTTP",
                "",
                "PASS",
                "Rendered successfully",
                "Learn More",
                200,
                "OK",
                0,
                List.of(),
                100L,
                "",
                "CTA",
                "",
                "",
                "",
                1,
                "");
        final FileAuditData file = new FileAuditData(
                "long-url.html",
                "PASS",
                1,
                1,
                0,
                List.of(),
                List.of(),
                List.of(link),
                List.of(),
                CampaignValidationResult.noSpecification(),
                null);
        return new RunAuditData(
                "General",
                "PRE_SEND",
                "HTML Folder",
                1,
                1,
                0,
                0,
                0,
                1000L,
                Instant.parse("2026-08-10T15:00:00Z"),
                EmailMetadataData.notAvailable(),
                List.of(file));
    }

    private static Path writeScreenshot(final Path path) throws Exception {
        Files.createDirectories(path.getParent());
        final BufferedImage image = new BufferedImage(360, 180, BufferedImage.TYPE_INT_RGB);
        final Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(new Color(30, 90, 160));
        graphics.fillRect(24, 24, 312, 132);
        graphics.setColor(Color.WHITE);
        graphics.drawString("Destination screenshot", 86, 94);
        graphics.dispose();
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    private static String pdfText(final Path pdf) throws Exception {
        try (PDDocument document = PDDocument.load(pdf.toFile())) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static int imageCount(final Path pdf) throws Exception {
        int count = 0;
        try (PDDocument document = PDDocument.load(pdf.toFile())) {
            for (final PDPage page : document.getPages()) {
                final PDResources resources = page.getResources();
                if (resources == null) {
                    continue;
                }
                for (final COSName name : resources.getXObjectNames()) {
                    if (resources.getXObject(name) instanceof PDImageXObject) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
