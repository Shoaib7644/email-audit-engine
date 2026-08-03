package com.acxiom.emailaudit.reporting;

import com.acxiom.emailaudit.config.ConfigurationManager;
import com.acxiom.emailaudit.orchestration.AuditOrchestrator;
import com.acxiom.emailaudit.reporting.dashboard.DashboardDataCollector;
import com.acxiom.emailaudit.reporting.dashboard.FileAuditData;
import com.acxiom.emailaudit.reporting.dashboard.ImageAuditData;
import com.acxiom.emailaudit.reporting.dashboard.LinkAuditData;
import com.acxiom.emailaudit.reporting.dashboard.RuleAuditData;
import com.acxiom.emailaudit.reporting.dashboard.RunAuditData;
import com.acxiom.emailaudit.reporting.dashboard.SectionCheckResult;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Exports a concise, business-friendly Excel workbook from the same structured
 * audit data that powers the HTML dashboard.
 */
public final class ExcelExporter {

    private static final String OUTPUT_FILE_NAME = "EmailAuditSummary.xlsx";

    private static final String SHEET_AUDIT_SUMMARY = "Audit Summary";
    private static final String SHEET_LINKS = "Links";
    private static final String SHEET_IMAGES = "Images";

    private static final DateTimeFormatter AUDIT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.US)
                    .withZone(ZoneId.systemDefault());

    private ExcelExporter() {
    }

    public static Path export(final AuditOrchestrator.RunSummary summary) {
        try {
            final Path outputFile = Paths.get("output", OUTPUT_FILE_NAME);
            Files.createDirectories(outputFile.getParent());

            final RunAuditData dashboardData = DashboardDataCollector.collect(summary);
            final ConfigurationManager config = ConfigurationManager.getInstance();

            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                final WorkbookStyles styles = WorkbookStyles.create(workbook);

                buildAuditSummarySheet(workbook, dashboardData, config, styles);
                buildLinksSheet(workbook, dashboardData, styles);
                buildImagesSheet(workbook, dashboardData, styles);

                try (OutputStream outputStream = Files.newOutputStream(outputFile)) {
                    workbook.write(outputStream);
                }
            }

            return outputFile;
        } catch (final Exception ex) {
            throw new RuntimeException("Unable to generate excel report", ex);
        }
    }

    private static void buildAuditSummarySheet(
            final Workbook workbook,
            final RunAuditData data,
            final ConfigurationManager config,
            final WorkbookStyles styles) {

        final Sheet sheet = workbook.createSheet(SHEET_AUDIT_SUMMARY);
        int rowIndex = 0;

        titleRow(sheet, rowIndex++, "EMAIL AUDIT SUMMARY", styles);
        rowIndex++;

        rowIndex = keyValueRow(sheet, rowIndex, "Campaign:", campaignLabel(data), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Audit Date:", AUDIT_DATE_FORMAT.format(data.generatedAt()), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Execution Time:", formatDuration(data.executionTimeMs()), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Browser:", browserLabel(config), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Execution Mode:", config.isHeadless() ? "Headless" : "Headed", styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Overall Result:", overallResult(data), styles);
        applyStatusStyle(sheet.getRow(rowIndex - 1).getCell(1), overallResult(data), styles);
        rowIndex += 2;

        sectionRow(sheet, rowIndex++, "AUDIT RESULTS", styles);
        final int categoryHeaderRow = rowIndex;
        headerRow(sheet, rowIndex++, List.of("Category", "Status", "Passed", "Failed", "Warning"), styles);

        for (final CategorySummary category : categorySummaries(data)) {
            final Row row = sheet.createRow(rowIndex++);
            final CellStyle rowStyle = bodyStyle(styles, rowIndex);
            writeCell(row, 0, category.name(), rowStyle);
            final Cell statusCell = writeCell(row, 1, category.status(), rowStyle);
            applyStatusStyle(statusCell, category.status(), styles);
            writeNumber(row, 2, category.passed(), rowStyle);
            writeNumber(row, 3, category.failed(), rowStyle);
            writeNumber(row, 4, category.warning(), rowStyle);
        }
        applyAutoFilter(sheet, categoryHeaderRow, rowIndex - 1, 0, 4);
        rowIndex += 2;

        sectionRow(sheet, rowIndex++, "TOTALS", styles);
        final DashboardTotals totals = totals(data);
        rowIndex = keyValueRow(sheet, rowIndex, "Total Links", String.valueOf(totals.totalLinks()), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Total Images", String.valueOf(totals.totalImages()), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Screenshots Captured", String.valueOf(totals.screenshotsCaptured()), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Broken Links", String.valueOf(totals.brokenLinks()), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Broken Images", String.valueOf(totals.brokenImages()), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Accessibility Warnings", String.valueOf(totals.accessibilityWarnings()), styles);
        rowIndex += 2;

        sectionRow(sheet, rowIndex++, "OVERALL RESULT", styles);
        final Row resultRow = sheet.createRow(rowIndex);
        final Cell resultCell = writeCell(resultRow, 0, overallResult(data), styles.result());
        applyStatusStyle(resultCell, overallResult(data), styles);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, 4));

        sheet.createFreezePane(0, 1);
        autoSizeColumns(sheet, 28, 32, 14, 14, 14);
    }

    private static void buildLinksSheet(
            final Workbook workbook,
            final RunAuditData data,
            final WorkbookStyles styles) {

        final Sheet sheet = workbook.createSheet(SHEET_LINKS);
        final List<String> headers = List.of(
                "#",
                "Visible Text",
                "Destination URL",
                "Destination Title",
                "Validation",
                "HTTP Status",
                "Screenshot",
                "Notes");

        headerRow(sheet, 0, headers, styles);

        int rowIndex = 1;
        int count = 1;
        for (final FileAuditData file : data.files()) {
            for (final LinkAuditData link : file.links()) {
                final Row row = sheet.createRow(rowIndex++);
                final CellStyle rowStyle = bodyStyle(styles, rowIndex);
                final CellStyle urlStyle = hyperlinkStyle(styles, rowIndex);
                writeNumber(row, 0, count++, rowStyle);
                writeCell(row, 1, displayText(link.visibleText(), "(No Visible Text)"), rowStyle);
                writeHyperlink(row, 2, destinationUrl(link), urlStyle, workbook.getCreationHelper());
                writeCell(row, 3, displayText(link.pageTitle(), "-"), rowStyle);

                final Cell validationCell = writeCell(row, 4, statusOrDash(link.validationStatus()), rowStyle);
                applyStatusStyle(validationCell, link.validationStatus(), styles);

                writeCell(row, 5, httpStatus(link.httpStatus(), link.statusText()), rowStyle);
                writeCell(row, 6, hasScreenshot(link.screenshotPath()) ? "Captured" : "No Screenshot", rowStyle);
                writeCell(row, 7, displayText(link.reason(), link.validationNote()), rowStyle);
            }
        }

        finishTabularSheet(sheet, rowIndex, headers.size());
        autoSizeColumns(sheet, 8, 28, 60, 36, 16, 18, 18, 48);
    }

    private static void buildImagesSheet(
            final Workbook workbook,
            final RunAuditData data,
            final WorkbookStyles styles) {

        final Sheet sheet = workbook.createSheet(SHEET_IMAGES);
        final List<String> headers = List.of(
                "#",
                "Alt Text",
                "Image URL",
                "HTTP Status",
                "Validation",
                "Screenshot",
                "Notes");

        headerRow(sheet, 0, headers, styles);

        int rowIndex = 1;
        int count = 1;
        for (final FileAuditData file : data.files()) {
            for (final ImageAuditData image : file.images()) {
                final Row row = sheet.createRow(rowIndex++);
                final CellStyle rowStyle = bodyStyle(styles, rowIndex);
                final CellStyle urlStyle = hyperlinkStyle(styles, rowIndex);
                writeNumber(row, 0, count++, rowStyle);
                writeCell(row, 1, displayText(image.altText(), "(No Alt Text)"), rowStyle);
                writeHyperlink(row, 2, image.imageUrl(), urlStyle, workbook.getCreationHelper());
                writeCell(row, 3, httpStatus(image.httpStatus(), ""), rowStyle);

                final Cell validationCell = writeCell(row, 4, statusOrDash(image.validationStatus()), rowStyle);
                applyStatusStyle(validationCell, image.validationStatus(), styles);

                writeCell(row, 5, hasScreenshot(image.screenshotPath()) ? "Captured" : "No Screenshot", rowStyle);
                writeCell(row, 6, displayText(image.notes(), image.validationStatus()), rowStyle);
            }
        }

        finishTabularSheet(sheet, rowIndex, headers.size());
        autoSizeColumns(sheet, 8, 34, 64, 18, 16, 18, 48);
    }

    private static List<CategorySummary> categorySummaries(final RunAuditData data) {
        return List.of(
                linksSummary(data),
                imagesSummary(data),
                sectionSummary(data, "Accessibility", "Accessibility"),
                ruleSummary(data, "Privacy Links", List.of("PRIVACY_LINK")),
                ruleSummary(data, "View In Browser", List.of("VIEW_ONLINE_LINK")),
                ruleSummary(data, "Unsubscribe", List.of("BROKEN_ANCHOR")),
                ruleSummary(data, "Reply-To / Disclaimer", List.of("DISCLAIMER_PRESENT"))
        );
    }

    private static CategorySummary linksSummary(final RunAuditData data) {
        int passed = 0;
        int failed = 0;
        int warning = 0;
        for (final FileAuditData file : data.files()) {
            for (final LinkAuditData link : file.links()) {
                final String status = normalisedStatus(link.validationStatus());
                if ("PASS".equals(status)) {
                    passed++;
                } else if ("FAIL".equals(status)) {
                    failed++;
                } else if ("WARNING".equals(status) || "PROTECTED".equals(status)) {
                    warning++;
                }
            }
        }
        return new CategorySummary("Links", aggregateStatus(passed, failed, warning), passed, failed, warning);
    }

    private static CategorySummary imagesSummary(final RunAuditData data) {
        int passed = 0;
        int failed = 0;
        int warning = 0;
        for (final FileAuditData file : data.files()) {
            for (final ImageAuditData image : file.images()) {
                final String status = normalisedStatus(image.validationStatus());
                if ("PASS".equals(status)) {
                    passed++;
                } else if ("FAIL".equals(status)) {
                    failed++;
                } else if ("WARNING".equals(status) || image.warning()) {
                    warning++;
                }
            }
        }
        return new CategorySummary("Images", aggregateStatus(passed, failed, warning), passed, failed, warning);
    }

    private static CategorySummary sectionSummary(
            final RunAuditData data,
            final String label,
            final String sectionName) {

        int passed = 0;
        int failed = 0;
        int warning = 0;

        for (final FileAuditData file : data.files()) {
            final SectionCheckResult section = findSection(file, sectionName);
            if (section == null) {
                continue;
            }
            final String status = normalisedStatus(section.status());
            if ("PASS".equals(status)) {
                passed++;
            } else if ("FAIL".equals(status) || "ERROR".equals(status)) {
                failed += Math.max(1, section.findingCount());
            } else if ("WARNING".equals(status)) {
                warning += Math.max(1, section.findingCount());
            }
        }

        return new CategorySummary(label, aggregateStatus(passed, failed, warning), passed, failed, warning);
    }

    private static CategorySummary ruleSummary(
            final RunAuditData data,
            final String label,
            final List<String> ruleIds) {

        int passed = 0;
        int failed = 0;
        int warning = 0;

        for (final FileAuditData file : data.files()) {
            for (final RuleAuditData rule : file.rules()) {
                if (!ruleIds.contains(rule.ruleId())) {
                    continue;
                }

                final String status = normalisedStatus(rule.status());
                if ("PASS".equals(status)) {
                    passed++;
                } else if ("FAIL".equals(status) || "ERROR".equals(status)) {
                    failed += Math.max(1, rule.findings().size());
                } else if ("WARNING".equals(status)) {
                    warning += Math.max(1, rule.findings().size());
                }
            }
        }

        return new CategorySummary(label, aggregateStatus(passed, failed, warning), passed, failed, warning);
    }

    private static DashboardTotals totals(final RunAuditData data) {
        int totalLinks = 0;
        int totalImages = 0;
        int screenshotsCaptured = 0;
        int brokenLinks = 0;
        int brokenImages = 0;
        int accessibilityWarnings = 0;

        for (final FileAuditData file : data.files()) {
            totalLinks += file.links().size();
            totalImages += file.images().size();

            for (final LinkAuditData link : file.links()) {
                if (hasScreenshot(link.screenshotPath())) {
                    screenshotsCaptured++;
                }
                if ("FAIL".equals(normalisedStatus(link.validationStatus()))) {
                    brokenLinks++;
                }
            }

            for (final ImageAuditData image : file.images()) {
                if (hasScreenshot(image.screenshotPath())) {
                    screenshotsCaptured++;
                }
                if ("FAIL".equals(normalisedStatus(image.validationStatus()))) {
                    brokenImages++;
                }
            }

            for (final RuleAuditData rule : findRule(file, "ACCESSIBILITY_AXE")) {
                accessibilityWarnings += rule.findings().size();
            }
        }

        return new DashboardTotals(
                totalLinks,
                totalImages,
                screenshotsCaptured,
                brokenLinks,
                brokenImages,
                accessibilityWarnings);
    }

    private static SectionCheckResult findSection(final FileAuditData file, final String sectionName) {
        return file.sections()
                .stream()
                .filter(section -> section.sectionName().equalsIgnoreCase(sectionName))
                .findFirst()
                .orElse(null);
    }

    private static List<RuleAuditData> findRule(final FileAuditData file, final String ruleId) {
        return file.rules()
                .stream()
                .filter(rule -> rule.ruleId().equalsIgnoreCase(ruleId))
                .toList();
    }

    private static String aggregateStatus(final int passed, final int failed, final int warning) {
        if (failed > 0) {
            return "FAIL";
        }
        if (warning > 0) {
            return "WARNING";
        }
        if (passed > 0) {
            return "PASS";
        }
        return "-";
    }

    private static String overallResult(final RunAuditData data) {
        if (data.failedFiles() > 0 || data.erroredFiles() > 0) {
            return "FAIL";
        }

        final DashboardTotals totals = totals(data);
        if (totals.brokenLinks() > 0
                || totals.brokenImages() > 0
                || totals.accessibilityWarnings() > 0
                || hasWarnings(data)) {
            return "PASS WITH WARNINGS";
        }

        return "PASS";
    }

    private static boolean hasWarnings(final RunAuditData data) {
        return data.files()
                .stream()
                .flatMap(file -> file.images().stream())
                .anyMatch(image -> image.warning()
                        || "WARNING".equals(normalisedStatus(image.validationStatus())));
    }

    private static String campaignLabel(final RunAuditData data) {
        if (data.files().isEmpty()) {
            return "-";
        }
        if (data.files().size() == 1) {
            return data.files().getFirst().fileName();
        }
        return data.files().size() + " audited emails";
    }

    private static String browserLabel(final ConfigurationManager config) {
        final String channel = config.getBrowserChannel();
        if (channel == null || channel.isBlank()) {
            return config.getBrowser();
        }
        return config.getBrowser() + " (" + channel + ")";
    }

    private static String destinationUrl(final LinkAuditData link) {
        if (link.finalUrl() != null && !link.finalUrl().isBlank()) {
            return link.finalUrl();
        }
        return link.originalUrl();
    }

    private static String httpStatus(final Integer status, final String statusText) {
        if (status == null) {
            return "-";
        }
        final String text = statusText == null ? "" : statusText.trim();
        return text.isBlank() ? String.valueOf(status) : status + " " + text;
    }

    private static boolean hasScreenshot(final String screenshotPath) {
        return screenshotPath != null && !screenshotPath.isBlank();
    }

    private static String statusOrDash(final String status) {
        final String normalised = normalisedStatus(status);
        return normalised.isBlank() ? "-" : normalised;
    }

    private static String normalisedStatus(final String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    }

    private static String displayText(final String value, final String fallback) {
        return value == null || value.isBlank() || "Unknown".equalsIgnoreCase(value)
                ? fallback
                : value;
    }

    private static String formatDuration(final long millis) {
        final long totalSeconds = millis / 1000;
        final long minutes = totalSeconds / 60;
        final long seconds = totalSeconds % 60;
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    private static void titleRow(
            final Sheet sheet,
            final int rowIndex,
            final String title,
            final WorkbookStyles styles) {

        final Row row = sheet.createRow(rowIndex);
        writeCell(row, 0, title, styles.title());
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, 4));
    }

    private static void sectionRow(
            final Sheet sheet,
            final int rowIndex,
            final String title,
            final WorkbookStyles styles) {

        final Row row = sheet.createRow(rowIndex);
        writeCell(row, 0, title, styles.section());
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, 4));
    }

    private static int keyValueRow(
            final Sheet sheet,
            final int rowIndex,
            final String key,
            final String value,
            final WorkbookStyles styles) {

        final Row row = sheet.createRow(rowIndex);
        writeCell(row, 0, key, styles.label());
        writeCell(row, 1, value, styles.normal());
        return rowIndex + 1;
    }

    private static void headerRow(
            final Sheet sheet,
            final int rowIndex,
            final List<String> headers,
            final WorkbookStyles styles) {

        final Row row = sheet.createRow(rowIndex);
        for (int column = 0; column < headers.size(); column++) {
            writeCell(row, column, headers.get(column), styles.header());
        }
    }

    private static Cell writeCell(
            final Row row,
            final int column,
            final String value,
            final CellStyle style) {

        final Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        if (style != null) {
            cell.setCellStyle(style);
        }
        return cell;
    }

    private static void writeNumber(
            final Row row,
            final int column,
            final int value,
            final CellStyle style) {

        final Cell cell = row.createCell(column);
        cell.setCellValue(value);
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    private static void writeHyperlink(
            final Row row,
            final int column,
            final String url,
            final CellStyle style,
            final CreationHelper creationHelper) {

        final Cell cell = writeCell(row, column, displayText(url, "-"), style);
        if (url == null || url.isBlank() || !isExternalUrl(url)) {
            return;
        }

        final Hyperlink hyperlink = creationHelper.createHyperlink(HyperlinkType.URL);
        hyperlink.setAddress(url);
        cell.setHyperlink(hyperlink);
    }

    private static boolean isExternalUrl(final String url) {
        final String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:");
    }

    private static void applyStatusStyle(
            final Cell cell,
            final String status,
            final WorkbookStyles styles) {

        final String normalised = normalisedStatus(status);
        if ("PASS".equals(normalised)) {
            cell.setCellStyle(styles.pass());
        } else if ("WARNING".equals(normalised)
                || "PROTECTED".equals(normalised)
                || "PASS WITH WARNINGS".equals(normalised)) {
            cell.setCellStyle(styles.warning());
        } else if ("FAIL".equals(normalised) || "ERROR".equals(normalised)) {
            cell.setCellStyle(styles.fail());
        }
    }

    private static CellStyle bodyStyle(final WorkbookStyles styles, final int rowIndex) {
        return rowIndex % 2 == 0 ? styles.normalAlt() : styles.normal();
    }

    private static CellStyle hyperlinkStyle(final WorkbookStyles styles, final int rowIndex) {
        return rowIndex % 2 == 0 ? styles.hyperlinkAlt() : styles.hyperlink();
    }

    private static void finishTabularSheet(
            final Sheet sheet,
            final int rowCount,
            final int columnCount) {

        sheet.createFreezePane(0, 1);
        applyAutoFilter(sheet, 0, Math.max(0, rowCount - 1), 0, columnCount - 1);
    }

    private static void applyAutoFilter(
            final Sheet sheet,
            final int firstRow,
            final int lastRow,
            final int firstColumn,
            final int lastColumn) {

        if (lastRow >= firstRow) {
            sheet.setAutoFilter(new CellRangeAddress(firstRow, lastRow, firstColumn, lastColumn));
        }
    }

    private static void autoSizeColumns(final Sheet sheet, final int... maxWidths) {
        for (int i = 0; i < maxWidths.length; i++) {
            sheet.autoSizeColumn(i);
            final int maxWidth = maxWidths[i] * 256;
            final int minWidth = Math.min(maxWidth, 10 * 256);
            final int currentWidth = sheet.getColumnWidth(i);
            if (currentWidth > maxWidth) {
                sheet.setColumnWidth(i, maxWidth);
            } else if (currentWidth < minWidth) {
                sheet.setColumnWidth(i, minWidth);
            }
        }
    }

    private record CategorySummary(
            String name,
            String status,
            int passed,
            int failed,
            int warning) {
    }

    private record DashboardTotals(
            int totalLinks,
            int totalImages,
            int screenshotsCaptured,
            int brokenLinks,
            int brokenImages,
            int accessibilityWarnings) {
    }

    private record WorkbookStyles(
            CellStyle title,
            CellStyle section,
            CellStyle header,
            CellStyle label,
            CellStyle normal,
            CellStyle normalAlt,
            CellStyle result,
            CellStyle hyperlink,
            CellStyle hyperlinkAlt,
            CellStyle pass,
            CellStyle warning,
            CellStyle fail) {

        private static WorkbookStyles create(final Workbook workbook) {
            final Font bold = workbook.createFont();
            bold.setBold(true);

            final Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 16);

            final Font whiteBold = workbook.createFont();
            whiteBold.setBold(true);
            whiteBold.setColor(IndexedColors.WHITE.getIndex());

            final Font hyperlinkFont = workbook.createFont();
            hyperlinkFont.setColor(IndexedColors.BLUE.getIndex());
            hyperlinkFont.setUnderline(Font.U_SINGLE);

            final CellStyle title = bordered(workbook);
            title.setFont(titleFont);
            title.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            title.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            final CellStyle section = bordered(workbook);
            section.setFont(bold);
            section.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            section.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            final CellStyle header = bordered(workbook);
            header.setFont(whiteBold);
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);

            final CellStyle label = bordered(workbook);
            label.setFont(bold);

            final CellStyle normal = bordered(workbook);
            normal.setWrapText(true);
            normal.setVerticalAlignment(VerticalAlignment.TOP);

            final CellStyle normalAlt = bordered(workbook);
            normalAlt.cloneStyleFrom(normal);
            normalAlt.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            normalAlt.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            final CellStyle result = bordered(workbook);
            result.setFont(titleFont);
            result.setAlignment(HorizontalAlignment.CENTER);

            final CellStyle hyperlink = bordered(workbook);
            hyperlink.setFont(hyperlinkFont);
            hyperlink.setWrapText(true);
            hyperlink.setVerticalAlignment(VerticalAlignment.TOP);

            final CellStyle hyperlinkAlt = bordered(workbook);
            hyperlinkAlt.cloneStyleFrom(hyperlink);
            hyperlinkAlt.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            hyperlinkAlt.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            final CellStyle pass = statusStyle(workbook, IndexedColors.LIGHT_GREEN, bold);
            final CellStyle warning = statusStyle(workbook, IndexedColors.LIGHT_ORANGE, bold);
            final CellStyle fail = statusStyle(workbook, IndexedColors.ROSE, bold);

            return new WorkbookStyles(
                    title,
                    section,
                    header,
                    label,
                    normal,
                    normalAlt,
                    result,
                    hyperlink,
                    hyperlinkAlt,
                    pass,
                    warning,
                    fail);
        }

        private static CellStyle statusStyle(
                final Workbook workbook,
                final IndexedColors color,
                final Font font) {

            final CellStyle style = bordered(workbook);
            style.setFont(font);
            style.setFillForegroundColor(color.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setAlignment(HorizontalAlignment.CENTER);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            return style;
        }

        private static CellStyle bordered(final Workbook workbook) {
            final CellStyle style = workbook.createCellStyle();
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            style.setBorderTop(BorderStyle.THIN);
            style.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setLeftBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setRightBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setTopBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            return style;
        }
    }
}
