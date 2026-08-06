package com.acxiom.emailaudit.reporting.dashboard;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Dashboard representation of a complete audit run.
 */
public record RunAuditData(
        String client,
        String validationMode,
        String inputSource,
        int totalFiles,
        int passedFiles,
        int failedFiles,
        int erroredFiles,
        int skippedFiles,
        long executionTimeMs,
        Instant generatedAt,
        EmailMetadataData emailMetadata,
        List<FileAuditData> files) {

    public RunAuditData {
        client = client == null || client.isBlank() ? "General" : client.trim();
        validationMode = validationMode == null || validationMode.isBlank() ? "PRE_SEND" : validationMode.trim();
        inputSource = inputSource == null || inputSource.isBlank() ? "HTML Folder" : inputSource.trim();
        Objects.requireNonNull(generatedAt, "generatedAt must not be null");
        emailMetadata = emailMetadata == null ? EmailMetadataData.notAvailable() : emailMetadata;
        Objects.requireNonNull(files,       "files must not be null");

        if (totalFiles    < 0) throw new IllegalArgumentException("totalFiles cannot be negative");
        if (passedFiles   < 0) throw new IllegalArgumentException("passedFiles cannot be negative");
        if (failedFiles   < 0) throw new IllegalArgumentException("failedFiles cannot be negative");
        if (erroredFiles  < 0) throw new IllegalArgumentException("erroredFiles cannot be negative");
        if (skippedFiles  < 0) throw new IllegalArgumentException("skippedFiles cannot be negative");
        if (executionTimeMs < 0) throw new IllegalArgumentException("executionTimeMs cannot be negative");

        files = List.copyOf(files);
    }
}
