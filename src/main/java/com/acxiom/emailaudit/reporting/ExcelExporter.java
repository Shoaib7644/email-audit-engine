package com.acxiom.emailaudit.reporting;

import com.acxiom.emailaudit.campaign.CampaignSpecification;
import com.acxiom.emailaudit.campaign.CampaignSpecificationModule;
import com.acxiom.emailaudit.campaign.CampaignValidationResult;
import com.acxiom.emailaudit.campaign.CampaignValidationRow;
import com.acxiom.emailaudit.config.ConfigurationManager;
import com.acxiom.emailaudit.core.AuditContext;
import com.acxiom.emailaudit.orchestration.AuditOrchestrator;
import com.acxiom.emailaudit.output.ExecutionOutputManager;
import com.acxiom.emailaudit.reporting.dashboard.DashboardDataCollector;
import com.acxiom.emailaudit.reporting.dashboard.FileAuditData;
import com.acxiom.emailaudit.reporting.dashboard.ImageAuditData;
import com.acxiom.emailaudit.reporting.dashboard.LinkAuditData;
import com.acxiom.emailaudit.reporting.dashboard.RuleAuditData;
import com.acxiom.emailaudit.reporting.dashboard.RunAuditData;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.SpreadsheetVersion;
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
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFTable;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Exports a concise, business-friendly Excel workbook from the same structured
 * audit data that powers the HTML dashboard.
 */
public final class ExcelExporter {

    private static final Logger log = LoggerFactory.getLogger(ExcelExporter.class);

    private static final String OUTPUT_FILE_NAME = "EmailAuditSummary.xlsx";

    private static final String SHEET_AUDIT_SUMMARY = "Audit Summary";
    private static final String SHEET_LINKS = "Links";
    private static final String SHEET_IMAGES = "Images";
    private static final String SHEET_CAMPAIGN_VALIDATION = "Campaign Validation";

    private static final DateTimeFormatter AUDIT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.US)
                    .withZone(ZoneId.systemDefault());

    private ExcelExporter() {
    }

    public static Path outputPath() {
        return ExecutionOutputManager.ensureCurrentExecution().summaryWorkbookPath();
    }

    public static Path export(final AuditOrchestrator.RunSummary summary) {
        try {
            final Path outputFile = outputPath();
            Files.createDirectories(outputFile.getParent());

            final RunAuditData dashboardData = DashboardDataCollector.collect(summary);
            final ConfigurationManager config = ConfigurationManager.getInstance();
            final ExcelMetadata metadata = ExcelMetadata.from(dashboardData, summary);
            final int campaignRows = campaignRowCount(dashboardData);
            final boolean campaignValidationEnabled = campaignRows > 0;

            log.info("""
                    
                    ========== EXCEL EXPORT ==========
                    Campaign Validation Enabled : {}
                    Campaign Rows : {}""",
                    campaignValidationEnabled,
                    campaignRows);

            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                final WorkbookStyles styles = WorkbookStyles.create(workbook);

                buildAuditSummarySheet(workbook, dashboardData, summary, config, styles, metadata);
                buildLinksSheet(workbook, dashboardData, styles, metadata);
                buildImagesSheet(workbook, dashboardData, styles, metadata);
                if (campaignValidationEnabled) {
                    log.info("Creating Campaign Sheet...");
                    try {
                        buildCampaignValidationSheet(workbook, dashboardData, styles, metadata);
                    } catch (final RuntimeException ex) {
                        log.error("Campaign Validation Excel sheet generation failed; continuing workbook export.", ex);
                        buildCampaignValidationExportFallbackSheet(workbook, styles, metadata, ex);
                    }
                } else {
                    log.info("Skipping Campaign Sheet...");
                    buildCampaignValidationNotSelectedSheet(workbook, styles, metadata);
                }

                try (OutputStream outputStream = Files.newOutputStream(outputFile)) {
                    workbook.write(outputStream);
                    log.info("Workbook Written Successfully");
                }
            }

            log.info("""
                    Workbook Closed Successfully
                    =================================""");
            return outputFile;
        } catch (final Exception ex) {
            throw new RuntimeException("Unable to generate excel report", ex);
        }
    }

    private static void buildAuditSummarySheet(
            final Workbook workbook,
            final RunAuditData data,
            final AuditOrchestrator.RunSummary summary,
            final ConfigurationManager config,
            final WorkbookStyles styles,
            final ExcelMetadata metadata) {

        final Sheet sheet = workbook.createSheet(SHEET_AUDIT_SUMMARY);
        int rowIndex = 0;

        titleRow(sheet, rowIndex++, "EMAIL AUDIT EXECUTION SUMMARY", styles);
        rowIndex++;
        rowIndex = writeWorkbookMetadata(sheet, rowIndex, metadata, styles, true);
        rowIndex++;

        final int emailHeaderRow = rowIndex;
        headerRow(sheet, rowIndex++, List.of(
                "Email Name",
                "Links",
                "Images",
                "Campaign Validation",
                "Accessibility",
                "Overall Result"), styles);

        for (final FileAuditData file : data.files()) {
            final Row row = sheet.createRow(rowIndex++);
            final CellStyle rowStyle = bodyStyle(styles, rowIndex);
            final EmailSummary emailSummary = emailSummary(file);
            writeCell(row, 0, file.fileName(), rowStyle);
            writeCell(row, 1, emailSummary.linksLabel(), rowStyle);
            writeCell(row, 2, emailSummary.imagesLabel(), rowStyle);
            final Cell campaignCell = writeCell(row, 3, emailSummary.campaignLabel(), rowStyle);
            applyStatusStyle(campaignCell, emailSummary.campaignStatus(), styles);
            final Cell accessibilityCell = writeCell(row, 4, emailSummary.accessibilityLabel(), rowStyle);
            applyStatusStyle(accessibilityCell, emailSummary.accessibilityStatus(), styles);
            final Cell resultCell = writeCell(row, 5, emailSummary.overallResult(), rowStyle);
            applyStatusStyle(resultCell, emailSummary.overallResult(), styles);
        }
        createExcelTable(sheet, emailHeaderRow, Math.max(emailHeaderRow, rowIndex - 1), 0, 5, "AuditSummaryTable");
        rowIndex += 2;

        sectionRow(sheet, rowIndex++, "EXECUTION TOTALS", styles);
        final DashboardTotals totals = totals(data);
        rowIndex = keyValueRow(sheet, rowIndex, "Total Emails Executed", String.valueOf(data.files().size()), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Total Links", String.valueOf(totals.totalLinks()), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Passed Links", String.valueOf(totals.passedLinks()), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Failed Links", String.valueOf(totals.failedLinks()), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Skipped Links", String.valueOf(totals.skippedLinks()), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Total Images", String.valueOf(totals.totalImages()), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Passed Images", String.valueOf(totals.passedImages()), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Failed Images", String.valueOf(totals.failedImages()), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Accessibility Warnings", String.valueOf(totals.accessibilityWarnings()), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Execution Start Time", formatInstant(executionStart(summary.auditResults())), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Execution End Time", formatInstant(executionEnd(summary.auditResults())), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Execution Duration", formatDuration(data.executionTimeMs()), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Browser", browserLabel(config), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Execution Mode", config.isHeadless() ? "Headless" : "Headed", styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Overall Execution Result", overallResult(data), styles);
        applyStatusStyle(sheet.getRow(rowIndex - 1).getCell(1), overallResult(data), styles);
        rowIndex++;

        sheet.createFreezePane(0, emailHeaderRow + 1);
        autoSizeColumns(sheet, 34, 16, 16, 24, 22, 22);
    }

    private static void buildLinksSheet(
            final Workbook workbook,
            final RunAuditData data,
            final WorkbookStyles styles,
            final ExcelMetadata metadata) {

        final Sheet sheet = workbook.createSheet(SHEET_LINKS);
        final List<String> headers = List.of(
                "Email Name",
                "#",
                "Visible Text",
                "Destination URL",
                "Destination Title",
                "Validation",
                "Screenshot",
                "Notes");

        int rowIndex = writeWorksheetMetadata(sheet, 0, metadata, styles);
        final int headerRowIndex = rowIndex;
        headerRow(sheet, rowIndex++, headers, styles);

        for (final FileAuditData file : data.files()) {
            int count = 1;
            for (final LinkAuditData link : file.links()) {
                final Row row = sheet.createRow(rowIndex++);
                final CellStyle rowStyle = bodyStyle(styles, rowIndex);
                final CellStyle urlStyle = hyperlinkStyle(styles, rowIndex);
                writeCell(row, 0, file.fileName(), rowStyle);
                writeNumber(row, 1, count++, rowStyle);
                writeCell(row, 2, displayText(link.visibleText(), "(No Visible Text)"), rowStyle);
                writeHyperlink(row, 3, destinationUrl(link), urlStyle, workbook.getCreationHelper());
                writeCell(row, 4, displayText(link.pageTitle(), "-"), rowStyle);

                final Cell validationCell = writeCell(row, 5, statusOrDash(link.validationStatus()), rowStyle);
                applyStatusStyle(validationCell, link.validationStatus(), styles);

                writeCell(row, 6, hasScreenshot(link.screenshotPath()) ? "Captured" : "No Screenshot", rowStyle);
                writeCell(row, 7, displayText(link.reason(), link.validationNote()), rowStyle);
            }
        }

        finishTabularSheet(sheet, rowIndex, headers.size(), headerRowIndex);
        createExcelTable(sheet, headerRowIndex, Math.max(headerRowIndex, rowIndex - 1), 0, headers.size() - 1, "LinksTable");
        autoSizeColumns(sheet, 28, 8, 28, 60, 36, 16, 18, 48);
    }

    private static void buildImagesSheet(
            final Workbook workbook,
            final RunAuditData data,
            final WorkbookStyles styles,
            final ExcelMetadata metadata) {

        final Sheet sheet = workbook.createSheet(SHEET_IMAGES);
        final List<String> headers = List.of(
                "Email Name",
                "#",
                "Alt Text",
                "Image URL",
                "HTTP Status",
                "Validation",
                "Screenshot",
                "Notes");

        int rowIndex = writeWorksheetMetadata(sheet, 0, metadata, styles);
        final int headerRowIndex = rowIndex;
        headerRow(sheet, rowIndex++, headers, styles);

        for (final FileAuditData file : data.files()) {
            int count = 1;
            for (final ImageAuditData image : file.images()) {
                final Row row = sheet.createRow(rowIndex++);
                final CellStyle rowStyle = bodyStyle(styles, rowIndex);
                final CellStyle urlStyle = hyperlinkStyle(styles, rowIndex);
                writeCell(row, 0, file.fileName(), rowStyle);
                writeNumber(row, 1, count++, rowStyle);
                writeCell(row, 2, displayText(image.altText(), "(No Alt Text)"), rowStyle);
                writeHyperlink(row, 3, image.imageUrl(), urlStyle, workbook.getCreationHelper());
                writeCell(row, 4, httpStatus(image.httpStatus(), ""), rowStyle);

                final Cell validationCell = writeCell(row, 5, statusOrDash(image.validationStatus()), rowStyle);
                applyStatusStyle(validationCell, image.validationStatus(), styles);

                writeCell(row, 6, hasScreenshot(image.screenshotPath()) ? "Captured" : "No Screenshot", rowStyle);
                writeCell(row, 7, displayText(image.notes(), image.validationStatus()), rowStyle);
            }
        }

        finishTabularSheet(sheet, rowIndex, headers.size(), headerRowIndex);
        createExcelTable(sheet, headerRowIndex, Math.max(headerRowIndex, rowIndex - 1), 0, headers.size() - 1, "ImagesTable");
        autoSizeColumns(sheet, 28, 8, 34, 64, 18, 16, 18, 48);
    }

    private static void buildCampaignValidationSheet(
            final Workbook workbook,
            final RunAuditData data,
            final WorkbookStyles styles,
            final ExcelMetadata metadata) {

        final Sheet sheet = workbook.createSheet(SHEET_CAMPAIGN_VALIDATION);
        final List<String> headers = List.of(
                "Email",
                "Item #",
                "Taxonomy",
                "Expected Type",
                "Actual Type",
                "Expected URL",
                "Actual URL",
                "Element Exists",
                "URL",
                "Tracking",
                "Label",
                "Category",
                "Type",
                "Screenshot",
                "Overall Result",
                "Notes");

        int rowIndex = writeWorksheetMetadata(sheet, 0, metadata, styles);
        final int headerRowIndex = rowIndex;
        headerRow(sheet, rowIndex++, headers, styles);
        int rowsWritten = 0;
        final int rowsToExport = campaignRowCount(data);

        log.info("========== CAMPAIGN EXCEL EXPORT ==========");
        log.info("Rows to export : {}", rowsToExport);

        for (final FileAuditData file : data.files()) {
            final CampaignValidationResult campaign = file.campaignValidation();
            if (campaign == null || campaign.rows().isEmpty()) {
                continue;
            }

            for (final CampaignValidationRow campaignRow : campaign.rows()) {
                log.info("""
                        
                        Writing row:
                        Taxonomy:
                        {}
                        Overall:
                        {}
                        ------------------------------------------""",
                        campaignRow.identifier(),
                        statusOrDash(campaignRow.validation()));

                final Row row = sheet.createRow(rowIndex++);
                final CellStyle rowStyle = bodyStyle(styles, rowIndex);
                final CellStyle urlStyle = hyperlinkStyle(styles, rowIndex);
                int column = 0;
                writeCell(row, column++, file.fileName(), rowStyle);
                writeCell(row, column++, campaignItem(campaignRow), rowStyle);
                writeCell(row, column++, displayText(campaignRow.identifier(), "-"), rowStyle);
                writeCell(row, column++, displayText(campaignRow.type(), "-"), rowStyle);
                writeCell(row, column++, displayText(campaignRow.actualType(), "-"), rowStyle);
                writeHyperlink(row, column++, campaignRow.expectedUrl(), urlStyle, workbook.getCreationHelper());
                writeHyperlink(row, column++, campaignRow.actualUrl(), urlStyle, workbook.getCreationHelper());
                writeStatusCell(row, column++, campaignRow.elementStatus(), rowStyle, styles);
                writeStatusCell(row, column++, campaignRow.urlStatus(), rowStyle, styles);
                writeStatusCell(row, column++, campaignRow.trackingStatus(), rowStyle, styles);
                writeStatusCell(row, column++, campaignRow.labelStatus(), rowStyle, styles);
                writeStatusCell(row, column++, campaignRow.categoryStatus(), rowStyle, styles);
                writeStatusCell(row, column++, campaignRow.typeStatus(), rowStyle, styles);
                writeCell(row, column++, hasScreenshot(campaignRow.screenshotPath()) ? "Captured" : "Missing", rowStyle);
                final Cell validationCell = writeCell(row, column++, statusOrDash(campaignRow.validation()), rowStyle);
                applyStatusStyle(validationCell, campaignRow.validation(), styles);
                writeCell(row, column, displayText(campaignRow.notes(), "-"), rowStyle);
                rowsWritten++;
            }
        }

        log.info("Rows written : {}", rowsWritten);

        finishTabularSheet(sheet, rowIndex, headers.size(), headerRowIndex);
        createExcelTable(sheet, headerRowIndex, Math.max(headerRowIndex, rowIndex - 1), 0, headers.size() - 1, "CampaignValidationTable");
        autoSizeColumns(sheet, 28, 10, 34, 18, 18, 60, 60, 18, 16, 18, 16, 16, 16, 18, 18, 56);
    }

    private static void buildCampaignValidationNotSelectedSheet(
            final Workbook workbook,
            final WorkbookStyles styles,
            final ExcelMetadata metadata) {

        final Sheet sheet = workbook.createSheet(SHEET_CAMPAIGN_VALIDATION);
        int rowIndex = 0;
        titleRow(sheet, rowIndex++, "CAMPAIGN VALIDATION", styles);
        rowIndex++;
        rowIndex = writeWorksheetMetadata(sheet, rowIndex, metadata, styles);
        sectionRow(sheet, rowIndex++, "STATUS", styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Campaign Validation", "No Campaign Specification Selected", styles);
        keyValueRow(sheet, rowIndex, "Message", "Campaign validation was skipped because no campaign specification was loaded.", styles);
        autoSizeColumns(sheet, 28, 80);
    }

    private static void buildCampaignValidationExportFallbackSheet(
            final Workbook workbook,
            final WorkbookStyles styles,
            final ExcelMetadata metadata,
            final RuntimeException ex) {

        if (workbook.getSheet(SHEET_CAMPAIGN_VALIDATION) != null) {
            return;
        }

        final Sheet sheet = workbook.createSheet(SHEET_CAMPAIGN_VALIDATION);
        titleRow(sheet, 0, "CAMPAIGN VALIDATION", styles);
        final int rowIndex = writeWorksheetMetadata(sheet, 2, metadata, styles);
        sectionRow(sheet, rowIndex, "EXPORT STATUS", styles);
        keyValueRow(sheet, rowIndex + 1, "Status", "Not available in Excel export", styles);
        keyValueRow(sheet, rowIndex + 2, "Reason", ex.getMessage(), styles);
        autoSizeColumns(sheet, 28, 80);
    }

    private static int writeWorkbookMetadata(
            final Sheet sheet,
            int rowIndex,
            final ExcelMetadata metadata,
            final WorkbookStyles styles,
            final boolean includeCampaignSpecification) {

        rowIndex = keyValueRow(sheet, rowIndex, "Client", metadata.client(), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Validation Mode", metadata.validationMode(), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Input Source", metadata.inputSource(), styles);
        if (includeCampaignSpecification) {
            rowIndex = keyValueRow(sheet, rowIndex, "Campaign Specification File", metadata.campaignSpecificationFile(), styles);
            rowIndex = keyValueRow(sheet, rowIndex, "Worksheet", metadata.worksheet(), styles);
        }
        rowIndex = keyValueRow(sheet, rowIndex, "Execution Date/Time", metadata.executionDateTime(), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Total HTML Files", metadata.totalHtmlFiles(), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Processed", metadata.processed(), styles);
        return keyValueRow(sheet, rowIndex, "Execution Time", metadata.executionTime(), styles);
    }

    private static int writeWorksheetMetadata(
            final Sheet sheet,
            int rowIndex,
            final ExcelMetadata metadata,
            final WorkbookStyles styles) {

        rowIndex = keyValueRow(sheet, rowIndex, "Client", metadata.client(), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Validation Mode", metadata.validationMode(), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Input Source", metadata.inputSource(), styles);
        rowIndex = keyValueRow(sheet, rowIndex, "Execution", metadata.executionDateTime(), styles);
        return rowIndex + 1;
    }

    private static EmailSummary emailSummary(final FileAuditData file) {
        final int totalLinks = file.links().size();
        final int passedLinks = (int) file.links()
                .stream()
                .filter(link -> "PASS".equals(normalisedStatus(link.validationStatus())))
                .count();

        final int totalImages = file.images().size();
        final int passedImages = (int) file.images()
                .stream()
                .filter(image -> "PASS".equals(normalisedStatus(image.validationStatus())))
                .count();

        final int accessibilityWarnings = accessibilityWarningCount(file);
        final boolean accessibilityError = findRule(file, "ACCESSIBILITY_AXE")
                .stream()
                .anyMatch(rule -> "ERROR".equals(normalisedStatus(rule.status())));
        final String accessibilityLabel = accessibilityError
                ? "FAIL"
                : accessibilityWarnings > 0 ? "Warning (" + accessibilityWarnings + ")" : "PASS";
        final String accessibilityStatus = accessibilityError
                ? "FAIL"
                : accessibilityWarnings > 0 ? "WARNING" : "PASS";
        final CampaignValidationResult campaign = file.campaignValidation();
        final String campaignLabel;
        final String campaignStatus;
        if (campaign == null || !campaign.specificationSelected()) {
            campaignLabel = "Not Selected";
            campaignStatus = "SKIPPED";
        } else if (campaign.failed() > 0) {
            campaignLabel = "FAIL (" + campaign.failed() + ")";
            campaignStatus = "FAIL";
        } else if (campaign.warnings() > 0) {
            campaignLabel = "Warning (" + campaign.warnings() + ")";
            campaignStatus = "WARNING";
        } else {
            campaignLabel = "PASS";
            campaignStatus = "PASS";
        }

        return new EmailSummary(
                passedLinks + "/" + totalLinks,
                passedImages + "/" + totalImages,
                campaignLabel,
                campaignStatus,
                accessibilityLabel,
                accessibilityStatus,
                fileOverallResult(file));
    }

    private static int campaignRowCount(final RunAuditData data) {
        int rows = 0;
        for (final FileAuditData file : data.files()) {
            final CampaignValidationResult campaign = file.campaignValidation();
            if (campaign != null && campaign.rows() != null) {
                rows += campaign.rows().size();
            }
        }
        return rows;
    }

    private static DashboardTotals totals(final RunAuditData data) {
        int totalLinks = 0;
        int passedLinks = 0;
        int failedLinks = 0;
        int skippedLinks = 0;
        int totalImages = 0;
        int passedImages = 0;
        int failedImages = 0;
        int accessibilityWarnings = 0;

        for (final FileAuditData file : data.files()) {
            totalLinks += file.links().size();
            totalImages += file.images().size();

            for (final LinkAuditData link : file.links()) {
                final String status = normalisedStatus(link.validationStatus());
                if ("PASS".equals(status)) {
                    passedLinks++;
                } else if ("FAIL".equals(status)) {
                    failedLinks++;
                } else if ("SKIPPED".equals(status)) {
                    skippedLinks++;
                }
            }

            for (final ImageAuditData image : file.images()) {
                final String status = normalisedStatus(image.validationStatus());
                if ("PASS".equals(status)) {
                    passedImages++;
                } else if ("FAIL".equals(status)) {
                    failedImages++;
                }
            }

            accessibilityWarnings += accessibilityWarningCount(file);
        }

        return new DashboardTotals(
                totalLinks,
                passedLinks,
                failedLinks,
                skippedLinks,
                totalImages,
                passedImages,
                failedImages,
                accessibilityWarnings);
    }

    private static int accessibilityWarningCount(final FileAuditData file) {
        return findRule(file, "ACCESSIBILITY_AXE")
                .stream()
                .mapToInt(rule -> rule.findings().size())
                .sum();
    }

    private static List<RuleAuditData> findRule(final FileAuditData file, final String ruleId) {
        return file.rules()
                .stream()
                .filter(rule -> rule.ruleId().equalsIgnoreCase(ruleId))
                .toList();
    }

    private static String overallResult(final RunAuditData data) {
        if (data.files().stream().map(ExcelExporter::fileOverallResult).anyMatch("FAIL"::equals)) {
            return "FAIL";
        }
        if (data.files().stream().map(ExcelExporter::fileOverallResult).anyMatch("PASS WITH WARNINGS"::equals)) {
            return "PASS WITH WARNINGS";
        }

        return "PASS";
    }

    private static String fileOverallResult(final FileAuditData file) {
        final boolean failedLinks = file.links()
                .stream()
                .anyMatch(link -> "FAIL".equals(normalisedStatus(link.validationStatus())));
        final boolean failedImages = file.images()
                .stream()
                .anyMatch(image -> "FAIL".equals(normalisedStatus(image.validationStatus())));
        final boolean failedCampaign = file.campaignValidation() != null
                && file.campaignValidation().specificationSelected()
                && file.campaignValidation().failed() > 0;
        final boolean nonAccessibilityFailure = file.rules()
                .stream()
                .filter(rule -> !"ACCESSIBILITY_AXE".equalsIgnoreCase(rule.ruleId()))
                .anyMatch(rule -> "FAIL".equals(normalisedStatus(rule.status()))
                        || "ERROR".equals(normalisedStatus(rule.status())));

        if (failedLinks || failedImages || failedCampaign || nonAccessibilityFailure) {
            return "FAIL";
        }

        final boolean hasWarnings = accessibilityWarningCount(file) > 0
                || (file.campaignValidation() != null
                && file.campaignValidation().specificationSelected()
                && file.campaignValidation().warnings() > 0)
                || file.links()
                .stream()
                .anyMatch(link -> "WARNING".equals(normalisedStatus(link.validationStatus()))
                        || "PROTECTED".equals(normalisedStatus(link.validationStatus())))
                || file.images()
                .stream()
                .anyMatch(image -> image.warning()
                        || "WARNING".equals(normalisedStatus(image.validationStatus())));

        if (hasWarnings) {
            return "PASS WITH WARNINGS";
        }

        return "PASS";
    }

    private static String browserLabel(final ConfigurationManager config) {
        final String channel = config.getBrowserChannel();
        if (channel == null || channel.isBlank()) {
            return config.getBrowser();
        }
        return config.getBrowser() + " (" + channel + ")";
    }

    private static Instant executionStart(final List<AuditContext> contexts) {
        return contexts.stream()
                .map(AuditContext::getStartTime)
                .filter(instant -> instant != null)
                .min(Instant::compareTo)
                .orElse(null);
    }

    private static Instant executionEnd(final List<AuditContext> contexts) {
        return contexts.stream()
                .map(AuditContext::getEndTime)
                .filter(instant -> instant != null)
                .max(Instant::compareTo)
                .orElse(null);
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

    private static String pathFileName(final Path path) {
        if (path == null || path.getFileName() == null) {
            return "-";
        }
        return path.getFileName().toString();
    }

    private static String htmlMetadataStatus(final CampaignValidationRow row) {
        return "Label " + statusOrDash(row.labelStatus())
                + "; Category " + statusOrDash(row.categoryStatus());
    }

    private static String campaignItem(final CampaignValidationRow row) {
        for (final var entry : row.rawColumns().entrySet()) {
            final String key = entry.getKey() == null
                    ? ""
                    : entry.getKey().trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
            if ((key.equals("item") || key.equals("item number"))
                    && entry.getValue() != null
                    && !entry.getValue().isBlank()) {
                return entry.getValue();
            }
        }
        return String.valueOf(row.index());
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

    private static String formatInstant(final Instant instant) {
        return instant == null ? "-" : AUDIT_DATE_FORMAT.format(instant);
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

        try {
            final Hyperlink hyperlink = creationHelper.createHyperlink(HyperlinkType.URL);
            hyperlink.setAddress(url);
            cell.setHyperlink(hyperlink);
        } catch (final RuntimeException ex) {
            log.debug("Skipping Excel hyperlink attachment for non-standard URL '{}': {}",
                    url,
                    ex.getMessage());
        }
    }

    private static void writeStatusCell(
            final Row row,
            final int column,
            final String status,
            final CellStyle rowStyle,
            final WorkbookStyles styles) {

        final Cell cell = writeCell(row, column, statusOrDash(status), rowStyle);
        applyStatusStyle(cell, status, styles);
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
            final int columnCount,
            final int headerRowIndex) {

        sheet.createFreezePane(0, headerRowIndex + 1);
        applyAutoFilter(sheet, headerRowIndex, Math.max(headerRowIndex, rowCount - 1), 0, columnCount - 1);
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

    private static void createExcelTable(
            final Sheet sheet,
            final int firstRow,
            final int lastRow,
            final int firstColumn,
            final int lastColumn,
            final String tableName) {

        if (!(sheet instanceof XSSFSheet xssfSheet)) {
            return;
        }

        final AreaReference areaReference = new AreaReference(
                new CellReference(firstRow, firstColumn),
                new CellReference(lastRow, lastColumn),
                SpreadsheetVersion.EXCEL2007);
        final XSSFTable table = xssfSheet.createTable(areaReference);
        table.setName(tableName);
        table.setDisplayName(tableName);
        table.setStyleName("TableStyleMedium2");
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

    private record EmailSummary(
            String linksLabel,
            String imagesLabel,
            String campaignLabel,
            String campaignStatus,
            String accessibilityLabel,
            String accessibilityStatus,
            String overallResult) {
    }

    private record DashboardTotals(
            int totalLinks,
            int passedLinks,
            int failedLinks,
            int skippedLinks,
            int totalImages,
            int passedImages,
            int failedImages,
            int accessibilityWarnings) {
    }

    private record ExcelMetadata(
            String client,
            String validationMode,
            String inputSource,
            String campaignSpecificationFile,
            String worksheet,
            String executionDateTime,
            String totalHtmlFiles,
            String processed,
            String executionTime) {

        private static ExcelMetadata from(
                final RunAuditData data,
                final AuditOrchestrator.RunSummary summary) {

            final CampaignSpecification specification =
                    CampaignSpecificationModule.activeSpecification()
                            .orElse(null);

            return new ExcelMetadata(
                    displayText(data.client(), "General"),
                    displayText(data.validationMode(), "PRE_SEND"),
                    displayText(data.inputSource(), "HTML Folder"),
                    specification == null ? "Not Selected" : pathFileName(specification.sourceFile()),
                    specification == null ? "-" : displayText(specification.worksheetName(), "-"),
                    formatInstant(data.generatedAt()),
                    String.valueOf(summary.totalDiscovered()),
                    String.valueOf(summary.processed()),
                    formatDuration(data.executionTimeMs()));
        }
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
