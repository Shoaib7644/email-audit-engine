package com.acxiom.emailaudit.gmail;

import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Store;
import javax.mail.search.AndTerm;
import javax.mail.search.ComparisonTerm;
import javax.mail.search.ReceivedDateTerm;
import javax.mail.search.SearchTerm;
import javax.mail.search.SubjectTerm;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.Optional;

/**
 * Searches Gmail folders for the newest matching message.
 */
public final class GmailSearchService {

    public SearchResult findNewestBySubject(
            final Store store,
            final String folderName,
            final String subject,
            final Duration receivedWithin) {

        if (subject == null || subject.isBlank()) {
            throw new GmailException("Subject is required for Post-Send validation.");
        }

        Folder folder = null;
        try {
            folder = store.getFolder(normalizeFolder(folderName));
            if (folder == null || !folder.exists()) {
                throw new GmailException("Gmail folder not found: " + folderName);
            }

            folder.open(Folder.READ_ONLY);

            final SearchTerm subjectTerm = new SubjectTerm(subject.trim());
            final SearchTerm searchTerm = receivedWithin == null || receivedWithin.isZero() || receivedWithin.isNegative()
                    ? subjectTerm
                    : new AndTerm(
                            subjectTerm,
                            new ReceivedDateTerm(
                                    ComparisonTerm.GE,
                                    Date.from(Instant.now().minus(receivedWithin))));

            final Message[] matches = folder.search(searchTerm);
            final Optional<Message> newest = Arrays.stream(matches)
                    .max(Comparator.comparing(GmailSearchService::receivedInstantSafe));

            if (newest.isEmpty()) {
                throw new GmailException("No Gmail message found for subject: " + subject);
            }

            return new SearchResult(folder, newest.get());
        } catch (final MessagingException ex) {
            closeQuietly(folder);
            throw new GmailException("Unable to search Gmail: " + ex.getMessage(), ex);
        } catch (final RuntimeException ex) {
            closeQuietly(folder);
            throw ex;
        }
    }

    private static Instant receivedInstantSafe(final Message message) {
        try {
            final Date received = message.getReceivedDate();
            final Date sent = message.getSentDate();
            if (received != null) {
                return received.toInstant();
            }
            if (sent != null) {
                return sent.toInstant();
            }
        } catch (final MessagingException ignored) {
            // Fall through to epoch.
        }
        return Instant.EPOCH;
    }

    private static String normalizeFolder(final String folderName) {
        if (folderName == null || folderName.isBlank()) {
            return "INBOX";
        }
        if ("inbox".equals(folderName.trim().toLowerCase(Locale.ROOT))) {
            return "INBOX";
        }
        return folderName.trim();
    }

    private static void closeQuietly(final Folder folder) {
        if (folder == null || !folder.isOpen()) {
            return;
        }
        try {
            folder.close(false);
        } catch (final MessagingException ignored) {
            // Nothing useful to do while unwinding.
        }
    }

    public record SearchResult(Folder folder, Message message) implements AutoCloseable {
        @Override
        public void close() {
            closeQuietly(folder);
        }
    }
}
