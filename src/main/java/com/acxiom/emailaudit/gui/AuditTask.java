package com.acxiom.emailaudit.gui;

import com.acxiom.emailaudit.bootstrap.ApplicationLauncher;
import com.acxiom.emailaudit.core.ExecutionContext;
import com.acxiom.emailaudit.core.ValidationMode;
import com.acxiom.emailaudit.orchestration.AuditOrchestrator;
import com.acxiom.emailaudit.output.ExecutionOutputManager;
import javafx.concurrent.Task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class AuditTask extends Task<AuditOrchestrator.RunSummary> {

    private final ValidationMode validationMode;
    private final String inputSource;
    private final Path inputDirectory;
    private final boolean cleanupInputDirectory;
    private final InputDirectoryProvider inputDirectoryProvider;

    public AuditTask() {
        this(ValidationMode.PRE_SEND, ExecutionContext.DEFAULT_INPUT_SOURCE);
    }

    public AuditTask(
            final ValidationMode validationMode,
            final String inputSource) {

        this(validationMode, inputSource, null, false, null);
    }

    public AuditTask(
            final ValidationMode validationMode,
            final String inputSource,
            final Path inputDirectory,
            final boolean cleanupInputDirectory) {

        this(validationMode, inputSource, inputDirectory, cleanupInputDirectory, null);
    }

    public AuditTask(
            final ValidationMode validationMode,
            final String inputSource,
            final InputDirectoryProvider inputDirectoryProvider,
            final boolean cleanupInputDirectory) {

        this(validationMode, inputSource, null, cleanupInputDirectory, inputDirectoryProvider);
    }

    private AuditTask(
            final ValidationMode validationMode,
            final String inputSource,
            final Path inputDirectory,
            final boolean cleanupInputDirectory,
            final InputDirectoryProvider inputDirectoryProvider) {

        this.validationMode = validationMode == null ? ValidationMode.PRE_SEND : validationMode;
        this.inputSource = inputSource == null || inputSource.isBlank()
                ? ExecutionContext.DEFAULT_INPUT_SOURCE
                : inputSource.trim();
        this.inputDirectory = inputDirectory;
        this.cleanupInputDirectory = cleanupInputDirectory;
        this.inputDirectoryProvider = inputDirectoryProvider;
    }

    @Override
    protected AuditOrchestrator.RunSummary call() throws Exception {

        updateMessage("Starting audit...");
        ExecutionContext.configure(validationMode, inputSource);
        ExecutionOutputManager.startNewExecution();

        Path resolvedInputDirectory = inputDirectory;
        try {
            if (resolvedInputDirectory == null && inputDirectoryProvider != null) {
                updateMessage("Retrieving post-send email...");
                resolvedInputDirectory = inputDirectoryProvider.resolve();
                updateMessage("Email HTML downloaded. Starting shared audit pipeline...");
            }

            AuditOrchestrator.RunSummary summary =
                    ApplicationLauncher.runAuditUsingCurrentOutput(
                            validationMode,
                            inputSource,
                            resolvedInputDirectory);

            updateProgress(100,100);

            updateMessage("Completed");

            return summary;
        } finally {
            if (cleanupInputDirectory && resolvedInputDirectory != null) {
                deleteDirectoryQuietly(resolvedInputDirectory);
            }
        }
    }

    private void deleteDirectoryQuietly(final Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (final IOException ignored) {
                            // Temporary post-send input cleanup should not fail the audit.
                        }
                    });
        } catch (final IOException ignored) {
            // Temporary post-send input cleanup should not fail the audit.
        }
    }

    @FunctionalInterface
    public interface InputDirectoryProvider {
        Path resolve();
    }
}
