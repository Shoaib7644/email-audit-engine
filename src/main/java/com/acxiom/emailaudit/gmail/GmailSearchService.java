package com.acxiom.emailaudit.gmail;

import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Store;
import javax.mail.search.ComparisonTerm;
import javax.mail.search.ReceivedDateTerm;
import javax.mail.search.SearchTerm;
import javax.mail.search.SubjectTerm;
import javax.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
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

    private static final Logger log = LoggerFactory.getLogger(GmailSearchService.class);

    private final Clock clock;

    public GmailSearchService() {
        this(Clock.systemDefaultZone());
    }

    GmailSearchService(final Clock clock) {
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    public SearchResult findNewestBySubject(
            final Store store,
            final String folderName,
            final String subject,
            final Duration receivedWithin) {

        return findNewest(store, folderName, subject, "", receivedWithin);
    }

    SearchResult findNewest(
            final Store store,
            final String folderName,
            final String subject,
            final String messageId,
            final Duration receivedWithin) {

        if (subject == null || subject.isBlank()) {
            throw new GmailException("Subject is required for Post-Send validation.");
        }

        Folder folder = null;
        try {
            final String mailbox = normalizeFolder(folderName);
            folder = store.getFolder(mailbox);
            if (folder == null || !folder.exists()) {
                throw new GmailException("Gmail folder not found: " + folderName);
            }

            folder.open(Folder.READ_ONLY);

            final SearchTerm searchTerm = serverSearchTerm(receivedWithin);
            logSearch(mailbox, subject, messageId, receivedWithin, searchTerm);

            final Message[] candidates = searchTerm == null
                    ? folder.getMessages()
                    : folder.search(searchTerm);
            final Optional<Message> newest = Arrays.stream(candidates)
                    .filter(message -> isWithinReceivedWindow(message, receivedWithin))
                    .filter(message -> subjectMatches(message, subject))
                    .filter(message -> messageIdMatches(message, messageId))
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

    private SearchTerm serverSearchTerm(final Duration receivedWithin) {
        if (receivedWithin == null || receivedWithin.isZero() || receivedWithin.isNegative()) {
            return null;
        }
        return new ReceivedDateTerm(
                ComparisonTerm.GE,
                Date.from(Instant.now(clock).minus(receivedWithin)));
    }

    private void logSearch(
            final String mailbox,
            final String subject,
            final String messageId,
            final Duration receivedWithin,
            final SearchTerm searchTerm) {

        log.debug(
                "Gmail IMAP search criteria - mailbox='{}', subject='{}', messageId='{}', "
                        + "receivedWithin='{}', searchCriteria='{}', searchTermClass='{}'",
                mailbox,
                safeLogValue(subject),
                safeLogValue(messageId),
                receivedWithin == null ? "" : receivedWithin,
                searchTerm == null
                        ? "ALL_MESSAGES_CANDIDATES_THEN_LOCAL_SUBJECT_FILTER"
                        : "RECEIVED_DATE_PREFILTER_THEN_LOCAL_SUBJECT_FILTER",
                searchTerm == null ? "NONE" : searchTerm.getClass().getName());
    }

    private static boolean subjectMatches(final Message message, final String subject) {
        final String requested = subject == null ? "" : subject.trim();
        if (requested.isBlank()) {
            return false;
        }
        final SubjectTerm subjectTerm = new SubjectTerm(requested);
        if (subjectTerm.match(message)) {
            return true;
        }
        try {
            final String actual = message.getSubject();
            return normalizeSubject(actual).contains(normalizeSubject(requested));
        } catch (final MessagingException ex) {
            return false;
        }
    }

    private static boolean messageIdMatches(final Message message, final String messageId) {
        final String requested = normalizeMessageId(messageId);
        if (requested.isBlank()) {
            return true;
        }
        return requested.equals(normalizeMessageId(messageIdSafe(message)));
    }

    private boolean isWithinReceivedWindow(
            final Message message,
            final Duration receivedWithin) {

        if (receivedWithin == null || receivedWithin.isZero() || receivedWithin.isNegative()) {
            return true;
        }
        return !receivedInstantSafe(message).isBefore(
                Instant.now(clock).minus(receivedWithin));
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

    private static String messageIdSafe(final Message message) {
        try {
            if (message instanceof MimeMessage mimeMessage) {
                final String id = mimeMessage.getMessageID();
                return id == null ? "" : id;
            }
            final String[] headers = message.getHeader("Message-ID");
            return headers == null || headers.length == 0 || headers[0] == null
                    ? ""
                    : headers[0];
        } catch (final MessagingException ex) {
            return "";
        }
    }

    private static String normalizeSubject(final String value) {
        return value == null
                ? ""
                : value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeMessageId(final String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("<") && normalized.endsWith(">") && normalized.length() > 2) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String safeLogValue(final String value) {
        if (value == null) {
            return "";
        }
        final String cleaned = value
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.length() > 160 ? cleaned.substring(0, 160) + "..." : cleaned;
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
