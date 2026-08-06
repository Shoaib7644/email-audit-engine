package com.acxiom.emailaudit.gmail;

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * Persists selected Gmail message content as temporary audit input.
 */
public final class GmailMessageDownloader {

    private final GmailHtmlExtractor htmlExtractor;

    public GmailMessageDownloader() {
        this(new GmailHtmlExtractor());
    }

    public GmailMessageDownloader(final GmailHtmlExtractor htmlExtractor) {
        this.htmlExtractor = htmlExtractor;
    }

    public GmailMetadata download(
            final Message message,
            final Path tempRoot) {

        try {
            final Path runDirectory = Files.createDirectories(
                    tempRoot.resolve("gmail-" + System.currentTimeMillis()));
            final Path resourceDirectory = Files.createDirectories(runDirectory.resolve("resources"));
            final GmailHtmlExtractor.ExtractedEmailContent content =
                    htmlExtractor.extract(message, resourceDirectory);

            final String safeSubject = safeFileName(message.getSubject(), "gmail-message");
            final Path htmlFile = runDirectory.resolve(safeSubject + ".html");
            final String html = content.html().isBlank()
                    ? plainTextFallback(content.plainText())
                    : content.html();
            Files.writeString(htmlFile, html, StandardCharsets.UTF_8);

            return new GmailMetadata(
                    message.getSubject(),
                    addresses(message.getFrom()),
                    addressList(message.getRecipients(Message.RecipientType.TO)),
                    addressList(message.getRecipients(Message.RecipientType.CC)),
                    addressList(message.getRecipients(Message.RecipientType.BCC)),
                    addresses(message.getReplyTo()),
                    receivedInstant(message),
                    messageId(message),
                    htmlFile,
                    runDirectory);
        } catch (final IOException | MessagingException ex) {
            throw new GmailException("Unable to download Gmail message: " + ex.getMessage(), ex);
        }
    }

    private static String plainTextFallback(final String plainText) {
        final String escaped = plainText == null ? "" : plainText
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        return "<!doctype html><html><head><meta charset=\"UTF-8\"></head><body><pre>"
                + escaped
                + "</pre></body></html>";
    }

    private static Instant receivedInstant(final Message message) throws MessagingException {
        final Date received = message.getReceivedDate();
        final Date sent = message.getSentDate();
        if (received != null) {
            return received.toInstant();
        }
        if (sent != null) {
            return sent.toInstant();
        }
        return Instant.EPOCH;
    }

    private static String messageId(final Message message) throws MessagingException {
        if (message instanceof MimeMessage mimeMessage) {
            final String id = mimeMessage.getMessageID();
            return id == null ? "" : id;
        }
        return "";
    }

    private static List<String> addressList(final Address[] addresses) {
        if (addresses == null) {
            return List.of();
        }
        return Arrays.stream(addresses)
                .map(GmailMessageDownloader::addressText)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static String addresses(final Address[] addresses) {
        return String.join(", ", addressList(addresses));
    }

    private static String addressText(final Address address) {
        if (address instanceof InternetAddress internetAddress) {
            return internetAddress.toUnicodeString();
        }
        return address == null ? "" : address.toString();
    }

    private static String safeFileName(final String candidate, final String fallback) {
        final String value = candidate == null || candidate.isBlank() ? fallback : candidate.trim();
        final String safe = value.replaceAll("[^A-Za-z0-9._-]+", "_");
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }
}
