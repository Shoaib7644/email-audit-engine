package com.acxiom.emailaudit.reporting;

import com.acxiom.emailaudit.core.AuditContext;
import com.acxiom.emailaudit.orchestration.AuditOrchestrator;
import com.acxiom.emailaudit.rules.RuleResult;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.Map;

public final class ExcelExporter {

    private static final String OUTPUT_FILE_NAME =
            "EmailAuditSummary.xlsx";

    private static final String SHEET_NAME =
            "Audit Results";

    private static final int COLUMN_FILE = 0;
    private static final int COLUMN_RULE = 1;
    private static final int COLUMN_SEVERITY = 2;
    private static final int COLUMN_STATUS = 3;
    private static final int COLUMN_BUSINESS_IMPACT = 4;
    private static final int COLUMN_TECHNICAL_DETAILS = 5;
    private static final int TOTAL_COLUMNS = 6;

    private ExcelExporter() {
    }

    public static Path export(
            final AuditOrchestrator.RunSummary summary) {

        try {

            final Path outputFile =
                    Paths.get(
                            "output",
                            OUTPUT_FILE_NAME);

            Files.createDirectories(
                    outputFile.getParent());

            try (XSSFWorkbook workbook =
                         new XSSFWorkbook()) {

                final Sheet sheet =
                        workbook.createSheet(
                                SHEET_NAME);

                createHeader(sheet);

                final Map<String, CellStyle> severityStyles =
                        createSeverityStyles(workbook);

                final Map<String, CellStyle> statusStyles =
                        createStatusStyles(workbook);

                int rowIndex = 1;

                for (AuditContext context :
                        summary.auditResults()) {

                    for (RuleResult rule :
                            context.getRuleResults()) {

                        final Row row =
                                sheet.createRow(
                                        rowIndex++);

                        populateRow(
                                row,
                                context,
                                rule,
                                severityStyles,
                                statusStyles);
                    }
                }

                applySheetFeatures(
                        sheet,
                        rowIndex);

                try (OutputStream outputStream =
                             Files.newOutputStream(outputFile)) {

                    workbook.write(outputStream);
                }
            }

            return outputFile;

        } catch (Exception ex) {

            throw new RuntimeException(
                    "Unable to generate excel report",
                    ex);
        }
    }

    private static void populateRow(
            final Row row,
            final AuditContext context,
            final RuleResult rule,
            final Map<String, CellStyle> severityStyles,
            final Map<String, CellStyle> statusStyles) {

        row.createCell(COLUMN_FILE)
                .setCellValue(
                        context.getFileName());

        row.createCell(COLUMN_RULE)
                .setCellValue(
                        rule.getRuleId());

        final Cell severityCell =
                row.createCell(
                        COLUMN_SEVERITY);

        final String severity =
                rule.getSeverity().name();

        severityCell.setCellValue(
                severity);

        applyStyle(
                severityCell,
                severityStyles.get(severity));

        final Cell statusCell =
                row.createCell(
                        COLUMN_STATUS);

        final String status =
                rule.getStatus().name();

        statusCell.setCellValue(
                status);

        applyStyle(
                statusCell,
                statusStyles.get(status));

        final String findings =
                String.join(
                        "; ",
                        rule.getFindings());

        row.createCell(COLUMN_BUSINESS_IMPACT)
                .setCellValue(
                        FindingSummarizer.summarize(findings));

        row.createCell(COLUMN_TECHNICAL_DETAILS)
                .setCellValue(
                        findings);
    }

    private static void createHeader(
            final Sheet sheet) {

        final Row header =
                sheet.createRow(0);

        header.createCell(COLUMN_FILE)
                .setCellValue("File");

        header.createCell(COLUMN_RULE)
                .setCellValue("Rule");

        header.createCell(COLUMN_SEVERITY)
                .setCellValue("Severity");

        header.createCell(COLUMN_STATUS)
                .setCellValue("Status");

        header.createCell(COLUMN_BUSINESS_IMPACT)
                .setCellValue("Business Impact");

        header.createCell(COLUMN_TECHNICAL_DETAILS)
                .setCellValue("Technical Details");
    }

    private static Map<String, CellStyle> createSeverityStyles(
            final Workbook workbook) {

        final Map<String, CellStyle> styles =
                new java.util.HashMap<>();

        styles.put(
                "CRITICAL",
                createColorStyle(
                        workbook,
                        IndexedColors.DARK_RED));

        styles.put(
                "HIGH",
                createColorStyle(
                        workbook,
                        IndexedColors.RED));

        styles.put(
                "MEDIUM",
                createColorStyle(
                        workbook,
                        IndexedColors.ORANGE));

        styles.put(
                "LOW",
                createColorStyle(
                        workbook,
                        IndexedColors.YELLOW));

        styles.put(
                "INFO",
                createColorStyle(
                        workbook,
                        IndexedColors.LIGHT_CORNFLOWER_BLUE));

        return styles;
    }

    private static Map<String, CellStyle> createStatusStyles(
            final Workbook workbook) {

        final Map<String, CellStyle> styles =
                new java.util.HashMap<>();

        styles.put(
                "PASS",
                createColorStyle(
                        workbook,
                        IndexedColors.BRIGHT_GREEN));

        styles.put(
                "FAIL",
                createColorStyle(
                        workbook,
                        IndexedColors.RED));

        styles.put(
                "SKIPPED",
                createColorStyle(
                        workbook,
                        IndexedColors.GREY_25_PERCENT));

        styles.put(
                "ERROR",
                createColorStyle(
                        workbook,
                        IndexedColors.DARK_RED));

        return styles;
    }

    /**
     * Creates a reusable style instance.
     * Apache POI recommends reusing styles rather than
     * creating them per row/cell.
     */
    private static CellStyle createColorStyle(
            final Workbook workbook,
            final IndexedColors color) {

        final CellStyle style =
                workbook.createCellStyle();

        style.setFillForegroundColor(
                color.getIndex());

        style.setFillPattern(
                FillPatternType.SOLID_FOREGROUND);

        return style;
    }

    private static void applyStyle(
            final Cell cell,
            final CellStyle style) {

        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    private static void applySheetFeatures(
            final Sheet sheet,
            final int totalRows) {

        sheet.createFreezePane(
                0,
                1);

        if (totalRows > 1) {

            sheet.setAutoFilter(
                    new CellRangeAddress(
                            0,
                            totalRows - 1,
                            0,
                            TOTAL_COLUMNS - 1));
        }

        for (int column = 0;
             column < TOTAL_COLUMNS;
             column++) {

            sheet.autoSizeColumn(column);
        }
    }
}