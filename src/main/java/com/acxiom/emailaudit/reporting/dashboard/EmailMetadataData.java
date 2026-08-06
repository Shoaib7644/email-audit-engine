package com.acxiom.emailaudit.reporting.dashboard;

import java.time.Instant;
import java.util.List;

/**
 * Dashboard payload for Post-Send email metadata.
 */
public record EmailMetadataData(
        boolean available,
        String subject,
        String from,
        List<String> to,
        List<String> cc,
        List<String> bcc,
        String replyTo,
        Instant receivedDate,
        String messageId) {

    public EmailMetadataData {
        subject = subject == null ? "" : subject;
        from = from == null ? "" : from;
        to = to == null ? List.of() : List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
        bcc = bcc == null ? List.of() : List.copyOf(bcc);
        replyTo = replyTo == null ? "" : replyTo;
        messageId = messageId == null ? "" : messageId;
    }

    public static EmailMetadataData notAvailable() {
        return new EmailMetadataData(
                false,
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                "",
                null,
                "");
    }
}
