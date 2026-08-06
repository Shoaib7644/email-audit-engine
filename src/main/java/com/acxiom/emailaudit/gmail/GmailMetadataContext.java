package com.acxiom.emailaudit.gmail;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds metadata for the Gmail message selected for the current Post-Send run.
 */
public final class GmailMetadataContext {

    private static final AtomicReference<GmailMetadata> CURRENT =
            new AtomicReference<>();

    private GmailMetadataContext() {
        throw new UnsupportedOperationException(
                "Utility class should not be instantiated");
    }

    public static void set(final GmailMetadata metadata) {
        CURRENT.set(metadata);
    }

    public static Optional<GmailMetadata> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void clear() {
        CURRENT.set(null);
    }
}
