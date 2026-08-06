package com.acxiom.emailaudit.gmail;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Metadata for the Gmail message selected for Post-Send validation.
 */
public record GmailMetadata(
        String subject,
        String from,
        List<String> to,
        List<String> cc,
        List<String> bcc,
        String replyTo,
        Instant receivedDate,
        String messageId,
        Path htmlFile,
        Path tempDirectory) {

    public GmailMetadata {
        subject = clean(subject);
        from = clean(from);
        to = to == null ? List.of() : List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
        bcc = bcc == null ? List.of() : List.copyOf(bcc);
        replyTo = clean(replyTo);
        messageId = clean(messageId);
    }

    private static String clean(final String value) {
        return value == null ? "" : value.trim();
    }
}
