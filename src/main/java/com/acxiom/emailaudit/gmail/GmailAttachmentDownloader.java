package com.acxiom.emailaudit.gmail;

import javax.mail.BodyPart;
import javax.mail.MessagingException;
import javax.mail.Part;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Saves inline Gmail MIME resources to disk.
 */
public final class GmailAttachmentDownloader {

    public InlineResource saveInlinePart(
            final BodyPart part,
            final Path outputDirectory) {

        try {
            Files.createDirectories(outputDirectory);
            final String contentId = normalizeContentId(firstHeader(part, "Content-ID"));
            final String fileName = contentId.isBlank()
                    ? safeFileName(part.getFileName(), "inline-resource")
                    : safeFileName(contentId, "inline-resource");
            final Path outputPath = outputDirectory.resolve(fileName);

            try (InputStream input = part.getInputStream()) {
                Files.copy(input, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return new InlineResource(contentId, outputPath);
        } catch (final IOException | MessagingException ex) {
            throw new GmailException("Unable to save inline Gmail resource: " + ex.getMessage(), ex);
        }
    }

    public boolean isInlineResource(final Part part) {
        try {
            final String disposition = part.getDisposition();
            final String contentId = firstHeader(part, "Content-ID");
            return Part.INLINE.equalsIgnoreCase(disposition)
                    || (contentId != null && !contentId.isBlank());
        } catch (final MessagingException ex) {
            return false;
        }
    }

    private static String firstHeader(final Part part, final String name)
            throws MessagingException {

        final String[] values = part.getHeader(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    private static String normalizeContentId(final String contentId) {
        if (contentId == null) {
            return "";
        }
        return contentId.replace("<", "").replace(">", "").trim();
    }

    private static String safeFileName(final String candidate, final String fallback) {
        final String value = candidate == null || candidate.isBlank() ? fallback : candidate.trim();
        return value.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    public record InlineResource(String contentId, Path savedPath) {
        public InlineResource {
            contentId = contentId == null ? "" : contentId;
        }
    }
}
