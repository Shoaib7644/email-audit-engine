package com.acxiom.emailaudit.campaign;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Optional campaign specification module facade.
 */
public final class CampaignSpecificationModule {

    private static final List<SpreadsheetLoader> LOADERS =
            List.of(new XlsxSpreadsheetLoader());

    private static volatile CampaignSpecification activeSpecification;

    private CampaignSpecificationModule() {
    }

    public static List<String> worksheetNames(final Path file) {
        return loaderFor(file).worksheetNames(file);
    }

    public static CampaignSpecification load(final Path file, final String worksheetName) {
        return loaderFor(file).load(file, worksheetName);
    }

    public static void setActiveSpecification(final CampaignSpecification specification) {
        activeSpecification = specification;
    }

    public static void clearActiveSpecification() {
        activeSpecification = null;
    }

    public static Optional<CampaignSpecification> activeSpecification() {
        return Optional.ofNullable(activeSpecification);
    }

    private static SpreadsheetLoader loaderFor(final Path file) {
        if (file == null) {
            throw new IllegalArgumentException("Specification file must not be null");
        }
        return LOADERS.stream()
                .filter(loader -> loader.supports(file))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported campaign specification format: " + extension(file)));
    }

    private static String extension(final Path file) {
        final String name = file.getFileName() == null
                ? file.toString()
                : file.getFileName().toString();
        final int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }
}
