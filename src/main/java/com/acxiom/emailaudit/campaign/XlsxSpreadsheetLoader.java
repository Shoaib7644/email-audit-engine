package com.acxiom.emailaudit.campaign;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XLSX implementation of the campaign specification loader.
 */
final class XlsxSpreadsheetLoader implements SpreadsheetLoader {

    private static final DataFormatter FORMATTER = new DataFormatter();
    private static final Pattern ATTRIBUTE_PATTERN =
            Pattern.compile("(?i)(?:^|\\s)(href|_label|_category)\\s*=\\s*\"([^\"]*)\"");

    @Override
    public boolean supports(final Path file) {
        return file != null
                && file.getFileName() != null
                && supportsExtension(file.getFileName().toString());
    }

    private static boolean supportsExtension(final String fileName) {
        final String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xlsx") || lower.endsWith(".xlsm");
    }

    @Override
    public List<String> worksheetNames(final Path file) {
        try (InputStream input = Files.newInputStream(file);
             Workbook workbook = new XSSFWorkbook(input)) {

            final List<String> names = new ArrayList<>(workbook.getNumberOfSheets());
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                names.add(workbook.getSheetName(index));
            }
            return names;
        } catch (final Exception ex) {
            throw new IllegalArgumentException("Unable to read campaign specification workbook: "
                    + ex.getMessage(), ex);
        }
    }

    @Override
    public CampaignSpecification load(final Path file, final String worksheetName) {
        try (InputStream input = Files.newInputStream(file);
             Workbook workbook = new XSSFWorkbook(input)) {

            final Sheet sheet = workbook.getSheet(worksheetName);
            if (sheet == null) {
                throw new IllegalArgumentException("Worksheet not found: " + worksheetName);
            }

            final FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            final Row headerRow = findHeaderRow(sheet, evaluator);
            if (headerRow == null) {
                throw new IllegalArgumentException("Worksheet has no header row: " + worksheetName);
            }

            final List<String> headers = readHeaders(headerRow, evaluator);
            if (headers.isEmpty()) {
                throw new IllegalArgumentException("Worksheet has no usable headers: " + worksheetName);
            }

            final List<CampaignSpecificationRow> entries = new ArrayList<>();
            for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                final Row row = sheet.getRow(rowIndex);
                final Map<String, String> rawColumns = readRow(row, headers, evaluator);
                if (rawColumns.values().stream().allMatch(String::isBlank)) {
                    continue;
                }

                final String finalCode = findFirst(rawColumns,
                        "final code for adobe integration",
                        "final code for adobe intergration",
                        "developers column for final code final code for adobe integration do not edit this column",
                        "developers column for final code final code for adobe intergration do not edit this column");
                final Map<String, String> codeAttributes = parseFinalCode(finalCode);
                final String itemNumber = findFirst(rawColumns, "item#", "item number", "item");
                final String taxonomy = findFirst(rawColumns,
                        "long form taxonomy category",
                        "long form taxonomy",
                        "link description",
                        "taxonomy",
                        "category");
                final String shortLabel = findFirst(rawColumns,
                        "short form taxonomy label",
                        "short form taxonomy",
                        "short label",
                        "label");
                final String expectedHref = firstNonBlank(
                        codeAttributes.get("href"),
                        findFirst(rawColumns, "url", "expected url", "destination url", "link url", "href"));
                final String expectedLabel = firstNonBlank(
                        codeAttributes.get("_label"),
                        findFirst(rawColumns, "_label", "tracking label", "tracking_label"));
                final String expectedCategory = firstNonBlank(
                        codeAttributes.get("_category"),
                        taxonomy,
                        findFirst(rawColumns, "_category", "tracking category", "tracking_category"));

                entries.add(new CampaignSpecificationRow(
                        rowIndex + 1,
                        itemNumber,
                        taxonomy,
                        shortLabel,
                        expectedLabel,
                        expectedCategory,
                        expectedHref,
                        trackingParameters(rawColumns, expectedHref),
                        expectedElementType(rawColumns, taxonomy, expectedHref),
                        finalCode,
                        rawColumns));
            }

            return new CampaignSpecification(file, worksheetName, headers, entries);
        } catch (final RuntimeException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalArgumentException("Unable to load campaign specification: "
                    + ex.getMessage(), ex);
        }
    }

    private static Row findHeaderRow(final Sheet sheet, final FormulaEvaluator evaluator) {
        Row fallback = null;
        for (int index = sheet.getFirstRowNum(); index <= sheet.getLastRowNum(); index++) {
            final Row row = sheet.getRow(index);
            if (row != null) {
                int score = 0;
                for (int cellIndex = row.getFirstCellNum(); cellIndex < row.getLastCellNum(); cellIndex++) {
                    final Cell cell = row.getCell(cellIndex);
                    final String value = cellText(cell, evaluator);
                    final String normalised = normaliseHeader(value);
                    if (cell != null && !value.isBlank() && fallback == null) {
                        fallback = row;
                    }
                    if (normalised.contains("item")) {
                        score++;
                    }
                    if (normalised.contains("taxonomy") || normalised.contains("category")) {
                        score++;
                    }
                    if (normalised.contains("final code") && normalised.contains("adobe")) {
                        score += 3;
                    }
                    if ("url".equals(normalised)) {
                        score++;
                    }
                }
                if (score >= 4) {
                    return row;
                }
            }
        }
        return fallback;
    }

    private static List<String> readHeaders(final Row headerRow, final FormulaEvaluator evaluator) {
        final List<String> headers = new ArrayList<>();
        for (int index = 0; index < headerRow.getLastCellNum(); index++) {
            final String value = cellText(headerRow.getCell(index), evaluator);
            headers.add(value.isBlank() ? "Column " + (index + 1) : value);
        }
        return headers;
    }

    private static Map<String, String> readRow(
            final Row row,
            final List<String> headers,
            final FormulaEvaluator evaluator) {
        final Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            final Cell cell = row == null ? null : row.getCell(index);
            values.put(headers.get(index), cellText(cell, evaluator));
        }
        return values;
    }

    private static String cellText(final Cell cell, final FormulaEvaluator evaluator) {
        return cell == null ? "" : FORMATTER.formatCellValue(cell, evaluator).trim();
    }

    private static String findFirst(final Map<String, String> values, final String... aliases) {
        final List<String> normalisedAliases = Arrays.stream(aliases)
                .map(XlsxSpreadsheetLoader::normaliseHeader)
                .toList();

        for (final Map.Entry<String, String> entry : values.entrySet()) {
            final String header = normaliseHeader(entry.getKey());
            if (normalisedAliases.stream().anyMatch(alias ->
                    header.equals(alias) || header.contains(alias) || alias.contains(header))
                    && entry.getValue() != null
                    && !entry.getValue().isBlank()) {
                return entry.getValue();
            }
        }
        return "";
    }

    private static List<String> trackingParameters(final Map<String, String> values, final String expectedHref) {
        final List<String> parameters = new ArrayList<>();
        final String configured = findFirst(values,
                "tracking parameters",
                "tracking params",
                "required parameters",
                "required params",
                "parameters",
                "params",
                "evar");
        if (!configured.isBlank()) {
            Arrays.stream(configured.split("[,;\\n]"))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(value -> value.replace("=", "").trim())
                    .filter(value -> !value.isBlank())
                    .forEach(parameters::add);
        }

        parameters.addAll(queryParameterNames(expectedHref));

        return parameters.stream()
                .filter(XlsxSpreadsheetLoader::isTrackingParameter)
                .distinct()
                .toList();
    }

    private static Map<String, String> parseFinalCode(final String finalCode) {
        if (finalCode == null || finalCode.isBlank()) {
            return Map.of();
        }

        final Map<String, String> attributes = new LinkedHashMap<>();
        final Matcher matcher = ATTRIBUTE_PATTERN.matcher(finalCode);
        while (matcher.find()) {
            attributes.put(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2).trim());
        }
        return attributes;
    }

    private static List<String> queryParameterNames(final String url) {
        if (url == null || url.isBlank()) {
            return List.of();
        }

        final int queryStart = url.indexOf('?');
        if (queryStart < 0 || queryStart == url.length() - 1) {
            return List.of();
        }

        final String query = url.substring(queryStart + 1);
        return Arrays.stream(query.split("&"))
                .map(pair -> {
                    final int equals = pair.indexOf('=');
                    return equals >= 0 ? pair.substring(0, equals) : pair;
                })
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static String expectedElementType(
            final Map<String, String> rawColumns,
            final String taxonomy,
            final String expectedHref) {

        final String explicit = findFirst(rawColumns, "type", "link type", "element type", "asset type");
        final String haystack = (explicit + " " + taxonomy + " " + expectedHref).toLowerCase(Locale.ROOT);
        if (haystack.contains("mirror") || haystack.contains("viewinbrowser") || haystack.contains("view in browser")) {
            return "Mirror Page";
        }
        if (haystack.contains("optout") || haystack.contains("opt out") || haystack.contains("unsubscribe")) {
            return "Opt-Out";
        }
        if (haystack.startsWith("mailto") || haystack.contains("mailto:")) {
            return "Mailto";
        }
        if (haystack.startsWith("tel") || haystack.contains("tel:") || haystack.contains("phone")) {
            return "Telephone";
        }
        if (haystack.contains("image")) {
            return "Image";
        }
        if (haystack.contains("button")) {
            return "Button";
        }
        if (haystack.contains("cta")) {
            return "CTA";
        }
        return explicit.isBlank() ? "Text" : explicit;
    }

    private static boolean isTrackingParameter(final String value) {
        final String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lower.startsWith("evar")
                || lower.startsWith("cmp")
                || lower.startsWith("hid")
                || lower.startsWith("utm_")
                || lower.endsWith("_label")
                || lower.endsWith("_category");
    }

    private static String firstNonBlank(final String... values) {
        if (values == null) {
            return "";
        }
        for (final String value : values) {
            if (value != null && !value.trim().isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String normaliseHeader(final String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ");
    }
}
