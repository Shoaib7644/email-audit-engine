package com.acxiom.emailaudit.reporting;

import com.acxiom.emailaudit.orchestration.AuditOrchestrator;
import com.acxiom.emailaudit.output.ExecutionOutputManager;
import com.acxiom.emailaudit.reporting.dashboard.DashboardDataCollector;
import com.acxiom.emailaudit.reporting.dashboard.EmailMetadataData;
import com.acxiom.emailaudit.reporting.dashboard.FileAuditData;
import com.acxiom.emailaudit.reporting.dashboard.ImageAuditData;
import com.acxiom.emailaudit.reporting.dashboard.LinkAuditData;
import com.acxiom.emailaudit.reporting.dashboard.RunAuditData;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Generates the client-facing Link & Image Validation PDF evidence report.
 */
public final class LinkImageValidationPdfExporter {

    private static final Logger log =
            LoggerFactory.getLogger(LinkImageValidationPdfExporter.class);

    private static final String OUTPUT_FILE_NAME =
            "LinkImageValidationReport.pdf";

    private LinkImageValidationPdfExporter() {
    }

    public static Path outputPath() {
        return ExecutionOutputManager.ensureCurrentExecution().linkImagePdfPath();
    }

    public static Path export(final AuditOrchestrator.RunSummary summary) {
        Objects.requireNonNull(summary, "RunSummary must not be null");
        return export(DashboardDataCollector.collect(summary), outputPath());
    }

    public static Path export(
            final RunAuditData data,
            final Path outputFile) {

        Objects.requireNonNull(data, "RunAuditData must not be null");
        Objects.requireNonNull(outputFile, "outputFile must not be null");

        try {
            Files.createDirectories(outputFile.toAbsolutePath().getParent());
            new PdfWriter(data, outputFile.toAbsolutePath().normalize()).write();
            log.info("Link & Image Validation PDF written to: {}", outputFile.toAbsolutePath());
            return outputFile;
        } catch (final Exception ex) {
            throw new PdfExportException(
                    "Unable to generate Link & Image Validation PDF: " + ex.getMessage(),
                    ex);
        }
    }

    public static String fileName() {
        return OUTPUT_FILE_NAME;
    }

    public static final class PdfExportException extends RuntimeException {
        public PdfExportException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    private static final class PdfWriter {

        private static final float MARGIN = 42f;
        private static final float FOOTER_RESERVED = 34f;
        private static final float CONTENT_WIDTH = PDRectangle.A4.getWidth() - (MARGIN * 2);
        private static final float LINE_HEIGHT = 13f;
        private static final float CELL_PADDING = 5f;

        private static final PDFont FONT_REGULAR = PDType1Font.HELVETICA;
        private static final PDFont FONT_BOLD = PDType1Font.HELVETICA_BOLD;

        private static final Color TEXT = new Color(37, 43, 55);
        private static final Color MUTED = new Color(91, 101, 117);
        private static final Color BORDER = new Color(210, 216, 226);
        private static final Color HEADER_BG = new Color(235, 240, 248);
        private static final Color ROW_ALT = new Color(248, 250, 253);
        private static final Color TITLE = new Color(21, 45, 82);
        private static final Color PASS = new Color(34, 130, 82);
        private static final Color FAIL = new Color(190, 51, 44);
        private static final Color WARNING = new Color(189, 118, 35);

        private final RunAuditData data;
        private final Path outputFile;
        private final Path dashboardDirectory;
        private final List<LinkAuditData> links;
        private final List<ImageAuditData> images;
        private final Totals totals;

        private PDDocument document;
        private PDPage page;
        private PDPageContentStream content;
        private float y;

        private PdfWriter(
                final RunAuditData data,
                final Path outputFile) {

            this.data = data;
            this.outputFile = outputFile;
            this.dashboardDirectory = outputFile.getParent().resolve("dashboard");
            this.links = flattenLinks(data);
            this.images = flattenImages(data);
            this.totals = calculateTotals(links, images);
        }

        private void write() throws IOException {
            try (PDDocument pdf = new PDDocument()) {
                document = pdf;
                addPage();

                writeCoverSummary();
                writeLinksSection();
                writeImagesSection();
                writeFinalSummary();

                closeContent();
                addFooters();
                document.save(outputFile.toFile());
            }
        }

        private void writeCoverSummary() throws IOException {
            writeText("EMAIL CAMPAIGN VALIDATION REPORT", FONT_BOLD, 18, TITLE);
            y -= 8;
            drawRule();
            y -= 12;

            writeKeyValue("Client", display(data.client(), "General"));
            writeKeyValue("Campaign / Email", campaignName());
            writeKeyValue("Validation Type", validationModeLabel(data.validationMode()));
            writeKeyValue("Email Subject", emailSubject());
            writeKeyValue("Execution Date", formatInstant(data.generatedAt()));
            y -= 12;

            final float cardWidth = (CONTENT_WIDTH - 16f) / 2f;
            final float cardY = y;
            drawMetricCard(MARGIN, cardY, cardWidth, "LINKS",
                    "Total: " + totals.totalLinks(),
                    "Passed: " + totals.passedLinks(),
                    "Failed: " + totals.failedLinks());
            drawMetricCard(MARGIN + cardWidth + 16f, cardY, cardWidth, "IMAGES",
                    "Total: " + totals.totalImages(),
                    "Passed: " + totals.passedImages(),
                    "Failed: " + totals.failedImages());
            y -= 94f;
        }

        private void writeLinksSection() throws IOException {
            sectionTitle("1. LINKS VALIDATION");

            final List<List<CellValue>> rows = new ArrayList<>();
            int index = 1;
            for (final LinkAuditData link : links) {
                rows.add(List.of(
                        cell(String.valueOf(index++)),
                        cell(display(link.visibleText(), "(No Visible Text)")),
                        cell(destinationUrl(link)),
                        cell(httpStatus(link.httpStatus(), link.statusText())),
                        statusCell(link.validationStatus()),
                        cell(linkType(link))));
            }

            drawTable(
                    List.of("#", "Link Text", "Destination URL", "HTTP Status", "Validation Result", "Link Type"),
                    rows,
                    new float[] { 24f, 100f, 204f, 62f, 75f, 50f },
                    8f);

            for (int i = 0; i < links.size(); i++) {
                final LinkAuditData link = links.get(i);
                writeLinkEvidence(i + 1, link);
            }
        }

        private void writeLinkEvidence(
                final int index,
                final LinkAuditData link) throws IOException {

            ensureSpace(118f);
            writeText("Link #" + index + " - " + display(link.visibleText(), "(No Visible Text)"),
                    FONT_BOLD,
                    11,
                    TEXT);
            writeWrappedLabel("Destination", destinationUrl(link));
            writeWrappedLabel("HTTP Status", httpStatus(link.httpStatus(), link.statusText()));
            writeWrappedLabel("Validation", statusOrDash(link.validationStatus()));
            writeWrappedLabel("Link Type", linkType(link));
            writeScreenshot(resolveScreenshot(link.screenshotPath()), 230f);
            y -= 8f;
        }

        private void writeImagesSection() throws IOException {
            sectionTitle("2. IMAGES VALIDATION");

            final List<List<CellValue>> rows = new ArrayList<>();
            int index = 1;
            for (final ImageAuditData image : images) {
                rows.add(List.of(
                        cell(String.valueOf(index++)),
                        cell(display(image.altText(), "(No Alt Text)")),
                        cell(display(image.imageUrl(), "-")),
                        statusCell(image.validationStatus())));
            }

            drawTable(
                    List.of("#", "Image / Alt Text", "Image URL", "Validation Result"),
                    rows,
                    new float[] { 24f, 156f, 250f, 85f },
                    8f);

            for (int i = 0; i < images.size(); i++) {
                final ImageAuditData image = images.get(i);
                writeImageEvidence(i + 1, image);
            }
        }

        private void writeImageEvidence(
                final int index,
                final ImageAuditData image) throws IOException {

            ensureSpace(110f);
            writeText("Image #" + index + " - " + display(image.altText(), "(No Alt Text)"),
                    FONT_BOLD,
                    11,
                    TEXT);
            writeWrappedLabel("Image URL", display(image.imageUrl(), "-"));
            writeWrappedLabel("Validation", statusOrDash(image.validationStatus()));
            writeScreenshot(resolveScreenshot(image.screenshotPath()), 205f);
            y -= 8f;
        }

        private void writeFinalSummary() throws IOException {
            sectionTitle("3. FINAL SUMMARY");

            drawTable(
                    List.of("Category", "Total", "Passed", "Failed"),
                    List.of(
                            List.of(
                                    cell("Links"),
                                    cell(String.valueOf(totals.totalLinks())),
                                    cell(String.valueOf(totals.passedLinks())),
                                    cell(String.valueOf(totals.failedLinks()))),
                            List.of(
                                    cell("Images"),
                                    cell(String.valueOf(totals.totalImages())),
                                    cell(String.valueOf(totals.passedImages())),
                                    cell(String.valueOf(totals.failedImages())))),
                    new float[] { 220f, 95f, 100f, 100f },
                    8f);

            ensureSpace(60f);
            final boolean failed = totals.failedLinks() > 0 || totals.failedImages() > 0;
            writeText(failed ? "ATTENTION REQUIRED" : "VALIDATION PASSED",
                    FONT_BOLD,
                    14,
                    failed ? FAIL : PASS);
            final String message = failed
                    ? totals.failedLinks() + " link(s) and/or "
                            + totals.failedImages() + " image(s) require attention."
                    : "All links and images passed validation.";
            writeText(message, FONT_REGULAR, 10, TEXT);
        }

        private void sectionTitle(final String title) throws IOException {
            ensureSpace(84f);
            y -= 10f;
            writeText(title, FONT_BOLD, 14, TITLE);
            drawRule();
            y -= 8f;
        }

        private void writeKeyValue(
                final String key,
                final String value) throws IOException {

            ensureSpace(18f);
            writeAt(key + ":", FONT_BOLD, 9.5f, MUTED, MARGIN, y);
            writeAt(value, FONT_REGULAR, 9.5f, TEXT, MARGIN + 118f, y);
            y -= 16f;
        }

        private void writeWrappedLabel(
                final String label,
                final String value) throws IOException {

            ensureSpace(24f);
            writeAt(label + ":", FONT_BOLD, 9f, MUTED, MARGIN, y);
            final List<String> lines = wrap(value, FONT_REGULAR, 9f, CONTENT_WIDTH - 92f);
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    y -= 11f;
                    ensureSpace(18f);
                }
                writeAt(lines.get(i), FONT_REGULAR, 9f, TEXT, MARGIN + 92f, y);
            }
            y -= 14f;
        }

        private void drawMetricCard(
                final float x,
                final float top,
                final float width,
                final String title,
                final String total,
                final String passed,
                final String failed) throws IOException {

            content.setNonStrokingColor(new Color(248, 250, 253));
            content.addRect(x, top - 74f, width, 74f);
            content.fill();
            content.setStrokingColor(BORDER);
            content.addRect(x, top - 74f, width, 74f);
            content.stroke();

            writeAt(title, FONT_BOLD, 11f, TITLE, x + 12f, top - 18f);
            writeAt(total, FONT_REGULAR, 9.5f, TEXT, x + 12f, top - 36f);
            writeAt(passed, FONT_REGULAR, 9.5f, PASS, x + 12f, top - 51f);
            writeAt(failed, FONT_REGULAR, 9.5f, FAIL, x + 12f, top - 66f);
        }

        private void drawTable(
                final List<String> headers,
                final List<List<CellValue>> rows,
                final float[] widths,
                final float fontSize) throws IOException {

            if (!rows.isEmpty()) {
                final float firstRowHeight = tableRowHeight(rows.getFirst(), widths, fontSize);
                ensureSpace(24f + firstRowHeight);
            }
            drawTableHeader(headers, widths, fontSize);
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                final List<CellValue> row = rows.get(rowIndex);
                final float rowHeight = tableRowHeight(row, widths, fontSize);
                if (y - rowHeight < MARGIN + FOOTER_RESERVED) {
                    addPage();
                    drawTableHeader(headers, widths, fontSize);
                }
                drawTableRow(row, widths, rowHeight, fontSize, rowIndex % 2 == 1);
            }
            y -= 12f;
        }

        private void drawTableHeader(
                final List<String> headers,
                final float[] widths,
                final float fontSize) throws IOException {

            final List<CellValue> headerCells = headers.stream()
                    .map(value -> new CellValue(value, true, TEXT))
                    .toList();
            drawTableRow(headerCells, widths, 24f, fontSize, false, HEADER_BG);
        }

        private void drawTableRow(
                final List<CellValue> row,
                final float[] widths,
                final float height,
                final float fontSize,
                final boolean alternate) throws IOException {

            drawTableRow(row, widths, height, fontSize, alternate, alternate ? ROW_ALT : Color.WHITE);
        }

        private void drawTableRow(
                final List<CellValue> row,
                final float[] widths,
                final float height,
                final float fontSize,
                final boolean alternate,
                final Color background) throws IOException {

            float x = MARGIN;
            final float top = y;
            for (int column = 0; column < widths.length; column++) {
                final float width = widths[column];
                content.setNonStrokingColor(background);
                content.addRect(x, top - height, width, height);
                content.fill();
                content.setStrokingColor(BORDER);
                content.addRect(x, top - height, width, height);
                content.stroke();

                final CellValue cell = column < row.size() ? row.get(column) : cell("");
                final PDFont font = cell.bold() ? FONT_BOLD : FONT_REGULAR;
                final List<String> lines = wrap(cell.text(), font, fontSize, width - (CELL_PADDING * 2));
                float lineY = top - CELL_PADDING - fontSize;
                for (final String line : lines) {
                    if (lineY < top - height + CELL_PADDING) {
                        break;
                    }
                    writeAt(line, font, fontSize, cell.color(), x + CELL_PADDING, lineY);
                    lineY -= fontSize + 2f;
                }

                x += width;
            }
            y -= height;
        }

        private float tableRowHeight(
                final List<CellValue> row,
                final float[] widths,
                final float fontSize) throws IOException {

            int maxLines = 1;
            for (int column = 0; column < widths.length; column++) {
                final CellValue cell = column < row.size() ? row.get(column) : cell("");
                final PDFont font = cell.bold() ? FONT_BOLD : FONT_REGULAR;
                maxLines = Math.max(
                        maxLines,
                        wrap(cell.text(), font, fontSize, widths[column] - (CELL_PADDING * 2)).size());
            }
            return Math.max(24f, (maxLines * (fontSize + 2f)) + (CELL_PADDING * 2));
        }

        private void writeScreenshot(
                final Path screenshot,
                final float maxHeight) throws IOException {

            ensureSpace(40f);
            if (screenshot == null || !Files.isRegularFile(screenshot)) {
                writeUnavailableScreenshot();
                return;
            }

            try {
                final PDImageXObject image =
                        PDImageXObject.createFromFileByContent(screenshot.toFile(), document);
                final float maxWidth = CONTENT_WIDTH;
                final float scale = Math.min(maxWidth / image.getWidth(), maxHeight / image.getHeight());
                final float width = Math.max(1f, image.getWidth() * scale);
                final float height = Math.max(1f, image.getHeight() * scale);
                ensureSpace(height + 26f);
                writeAt("Screenshot:", FONT_BOLD, 9f, MUTED, MARGIN, y);
                y -= 12f;
                content.drawImage(image, MARGIN, y - height, width, height);
                content.setStrokingColor(BORDER);
                content.addRect(MARGIN, y - height, width, height);
                content.stroke();
                y -= height + 6f;
            } catch (final Exception ex) {
                log.warn("PDF screenshot embedding skipped for '{}': {}",
                        screenshot,
                        ex.getMessage());
                writeUnavailableScreenshot();
            }
        }

        private void writeUnavailableScreenshot() throws IOException {
            ensureSpace(42f);
            writeAt("Screenshot:", FONT_BOLD, 9f, MUTED, MARGIN, y);
            y -= 12f;
            content.setNonStrokingColor(new Color(248, 250, 253));
            content.addRect(MARGIN, y - 28f, CONTENT_WIDTH, 28f);
            content.fill();
            content.setStrokingColor(BORDER);
            content.addRect(MARGIN, y - 28f, CONTENT_WIDTH, 28f);
            content.stroke();
            writeAt("Screenshot unavailable", FONT_REGULAR, 9f, MUTED, MARGIN + 8f, y - 18f);
            y -= 38f;
        }

        private Path resolveScreenshot(final String value) {
            if (value == null || value.isBlank()) {
                return null;
            }

            final String path = value.trim();
            try {
                if (path.startsWith("file://")) {
                    return Path.of(URI.create(path)).toAbsolutePath().normalize();
                }
                if (path.contains("://")) {
                    return null;
                }

                final Path candidate = Path.of(path);
                if (candidate.isAbsolute()) {
                    return candidate.normalize();
                }
                return dashboardDirectory.resolve(candidate).normalize();
            } catch (final RuntimeException ex) {
                log.warn("Unable to resolve PDF screenshot path '{}': {}", value, ex.getMessage());
                return null;
            }
        }

        private void addPage() throws IOException {
            closeContent();
            page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            y = page.getMediaBox().getHeight() - MARGIN;
        }

        private void ensureSpace(final float requiredHeight) throws IOException {
            if (y - requiredHeight < MARGIN + FOOTER_RESERVED) {
                addPage();
            }
        }

        private void writeText(
                final String text,
                final PDFont font,
                final float size,
                final Color color) throws IOException {

            ensureSpace(size + 8f);
            writeAt(text, font, size, color, MARGIN, y);
            y -= size + 8f;
        }

        private void writeAt(
                final String text,
                final PDFont font,
                final float size,
                final Color color,
                final float x,
                final float baseline) throws IOException {

            content.beginText();
            content.setFont(font, size);
            content.setNonStrokingColor(color);
            content.newLineAtOffset(x, baseline);
            content.showText(safePdfText(text, font));
            content.endText();
        }

        private void drawRule() throws IOException {
            content.setStrokingColor(BORDER);
            content.moveTo(MARGIN, y);
            content.lineTo(MARGIN + CONTENT_WIDTH, y);
            content.stroke();
        }

        private void closeContent() throws IOException {
            if (content != null) {
                content.close();
                content = null;
            }
        }

        private void addFooters() throws IOException {
            final int pages = document.getNumberOfPages();
            for (int index = 0; index < pages; index++) {
                final PDPage footerPage = document.getPage(index);
                try (PDPageContentStream footer = new PDPageContentStream(
                        document,
                        footerPage,
                        PDPageContentStream.AppendMode.APPEND,
                        true,
                        true)) {

                    final String text = "Email Campaign Validation Report | Page "
                            + (index + 1) + " of " + pages;
                    footer.setStrokingColor(BORDER);
                    footer.moveTo(MARGIN, MARGIN - 12f);
                    footer.lineTo(MARGIN + CONTENT_WIDTH, MARGIN - 12f);
                    footer.stroke();
                    footer.beginText();
                    footer.setFont(FONT_REGULAR, 8f);
                    footer.setNonStrokingColor(MUTED);
                    footer.newLineAtOffset(MARGIN, MARGIN - 25f);
                    footer.showText(text);
                    footer.endText();
                }
            }
        }

        private List<String> wrap(
                final String text,
                final PDFont font,
                final float size,
                final float maxWidth) throws IOException {

            final String cleaned = display(text, "-");
            final List<String> lines = new ArrayList<>();
            String current = "";
            for (final String token : cleaned.split("\\s+")) {
                if (token.isBlank()) {
                    continue;
                }
                final String candidate = current.isBlank() ? token : current + " " + token;
                if (textWidth(candidate, font, size) <= maxWidth) {
                    current = candidate;
                } else {
                    if (!current.isBlank()) {
                        lines.add(current);
                    }
                    lines.addAll(breakLongToken(token, font, size, maxWidth));
                    current = lines.isEmpty() || lines.get(lines.size() - 1).equals(token) ? "" : "";
                }
            }
            if (!current.isBlank()) {
                lines.add(current);
            }
            return lines.isEmpty() ? List.of("-") : lines;
        }

        private List<String> breakLongToken(
                final String token,
                final PDFont font,
                final float size,
                final float maxWidth) throws IOException {

            if (textWidth(token, font, size) <= maxWidth) {
                return List.of(token);
            }

            final List<String> parts = new ArrayList<>();
            String current = "";
            for (int i = 0; i < token.length(); i++) {
                final String candidate = current + token.charAt(i);
                if (!current.isBlank() && textWidth(candidate, font, size) > maxWidth) {
                    parts.add(current);
                    current = String.valueOf(token.charAt(i));
                } else {
                    current = candidate;
                }
            }
            if (!current.isBlank()) {
                parts.add(current);
            }
            return parts;
        }

        private float textWidth(
                final String text,
                final PDFont font,
                final float size) throws IOException {

            return font.getStringWidth(safePdfText(text, font)) / 1000f * size;
        }

        private String campaignName() {
            if (data.files().isEmpty()) {
                return "-";
            }
            if (data.files().size() == 1) {
                return display(data.files().getFirst().fileName(), "-");
            }
            return data.files().size() + " email files";
        }

        private String emailSubject() {
            final EmailMetadataData metadata = data.emailMetadata();
            if (metadata != null && metadata.available()) {
                return display(metadata.subject(), "-");
            }
            return "-";
        }
    }

    private static List<LinkAuditData> flattenLinks(final RunAuditData data) {
        return data.files().stream()
                .map(FileAuditData::links)
                .flatMap(List::stream)
                .toList();
    }

    private static List<ImageAuditData> flattenImages(final RunAuditData data) {
        return data.files().stream()
                .map(FileAuditData::images)
                .flatMap(List::stream)
                .toList();
    }

    private static Totals calculateTotals(
            final List<LinkAuditData> links,
            final List<ImageAuditData> images) {

        final int passedLinks = (int) links.stream()
                .filter(link -> "PASS".equals(normalisedStatus(link.validationStatus())))
                .count();
        final int failedLinks = (int) links.stream()
                .filter(link -> isFailedStatus(link.validationStatus()))
                .count();
        final int passedImages = (int) images.stream()
                .filter(image -> "PASS".equals(normalisedStatus(image.validationStatus())))
                .count();
        final int failedImages = (int) images.stream()
                .filter(image -> isFailedStatus(image.validationStatus()))
                .count();

        return new Totals(
                links.size(),
                passedLinks,
                failedLinks,
                images.size(),
                passedImages,
                failedImages);
    }

    private static boolean isFailedStatus(final String status) {
        final String normalised = normalisedStatus(status);
        return "FAIL".equals(normalised) || "ERROR".equals(normalised);
    }

    private static CellValue cell(final String text) {
        return new CellValue(text, false, new Color(37, 43, 55));
    }

    private static CellValue statusCell(final String status) {
        return new CellValue(statusOrDash(status), false, statusColor(status));
    }

    private static Color statusColor(final String status) {
        final String normalised = normalisedStatus(status);
        if ("PASS".equals(normalised)) {
            return new Color(34, 130, 82);
        }
        if ("FAIL".equals(normalised) || "ERROR".equals(normalised)) {
            return new Color(190, 51, 44);
        }
        return new Color(189, 118, 35);
    }

    private static String destinationUrl(final LinkAuditData link) {
        return display(
                link.finalUrl() == null || link.finalUrl().isBlank()
                        ? link.originalUrl()
                        : link.finalUrl(),
                "-");
    }

    private static String linkType(final LinkAuditData link) {
        return display(
                link.element() == null || link.element().isBlank()
                        ? link.linkType()
                        : link.element(),
                "-");
    }

    private static String httpStatus(
            final Integer status,
            final String statusText) {

        if (status == null) {
            return "N/A";
        }
        final String text = statusText == null ? "" : statusText.trim();
        return text.isBlank() ? String.valueOf(status) : status + " " + text;
    }

    private static String statusOrDash(final String status) {
        final String normalised = normalisedStatus(status);
        return normalised.isBlank() ? "-" : normalised;
    }

    private static String normalisedStatus(final String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    }

    private static String display(
            final String value,
            final String fallback) {

        return value == null || value.isBlank() || "Unknown".equalsIgnoreCase(value)
                ? fallback
                : value.trim();
    }

    private static String formatInstant(final Instant instant) {
        if (instant == null) {
            return "-";
        }
        return DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.US)
                .withZone(ZoneId.systemDefault())
                .format(instant);
    }

    private static String validationModeLabel(final String validationMode) {
        return display(validationMode, "PRE_SEND")
                .trim()
                .toUpperCase(Locale.ROOT)
                .replace('_', '-');
    }

    private static String safePdfText(
            final String text,
            final PDFont font) {

        final String value = display(text, "-")
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .replace('\u2022', '-');
        final StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            final char ch = value.charAt(i);
            if (ch >= 32 && ch <= 126) {
                builder.append(ch);
            } else {
                builder.append('?');
            }
        }
        return builder.toString();
    }

    private record CellValue(String text, boolean bold, Color color) {
    }

    private record Totals(
            int totalLinks,
            int passedLinks,
            int failedLinks,
            int totalImages,
            int passedImages,
            int failedImages) {
    }

}
