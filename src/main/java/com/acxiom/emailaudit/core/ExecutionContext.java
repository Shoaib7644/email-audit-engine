package com.acxiom.emailaudit.core;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Stores reporting metadata for the current audit execution.
 */
public final class ExecutionContext {

    public static final String DEFAULT_INPUT_SOURCE = "HTML Folder";

    private static final AtomicReference<ValidationMode> VALIDATION_MODE =
            new AtomicReference<>(ValidationMode.PRE_SEND);

    private static final AtomicReference<String> INPUT_SOURCE =
            new AtomicReference<>(DEFAULT_INPUT_SOURCE);

    private ExecutionContext() {
        throw new UnsupportedOperationException(
                "Utility class should not be instantiated");
    }

    public static void configure(
            final ValidationMode mode,
            final String inputSource) {

        VALIDATION_MODE.set(mode == null ? ValidationMode.PRE_SEND : mode);
        INPUT_SOURCE.set(inputSource == null || inputSource.isBlank()
                ? DEFAULT_INPUT_SOURCE
                : inputSource.trim());
    }

    public static ValidationMode validationMode() {
        return VALIDATION_MODE.get();
    }

    public static String inputSource() {
        return INPUT_SOURCE.get();
    }
}
