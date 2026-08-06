package com.acxiom.emailaudit.gmail;

import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import java.io.IOException;
import java.nio.file.Path;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extracts HTML/plain text content from MIME messages.
 */
public final class GmailHtmlExtractor {

    private final GmailAttachmentDownloader attachmentDownloader;

    public GmailHtmlExtractor() {
        this(new GmailAttachmentDownloader());
    }

    public GmailHtmlExtractor(final GmailAttachmentDownloader attachmentDownloader) {
        this.attachmentDownloader = attachmentDownloader;
    }

    public ExtractedEmailContent extract(
            final Message message,
            final Path resourceDirectory) {

        try {
            final ExtractedContentBuilder builder = new ExtractedContentBuilder();
            collect(message, resourceDirectory, builder);
            final String html = rewriteCidReferences(builder.html(), builder.inlineResources());
            final String text = builder.text();

            if (html.isBlank() && text.isBlank()) {
                throw new GmailException("Selected Gmail message did not contain HTML or plain text content.");
            }

            return new ExtractedEmailContent(html, text);
        } catch (final IOException | MessagingException ex) {
            throw new GmailException("Unable to extract Gmail HTML: " + ex.getMessage(), ex);
        }
    }

    private void collect(
            final Part part,
            final Path resourceDirectory,
            final ExtractedContentBuilder builder)
            throws MessagingException, IOException {

        if (part.isMimeType("text/html")) {
            if (builder.html().isBlank()) {
                builder.html(String.valueOf(part.getContent()));
            }
            return;
        }

        if (part.isMimeType("text/plain")) {
            if (builder.text().isBlank()) {
                builder.text(String.valueOf(part.getContent()));
            }
            return;
        }

        if (part.isMimeType("multipart/*")) {
            final Multipart multipart = (Multipart) part.getContent();
            for (int index = 0; index < multipart.getCount(); index++) {
                final BodyPart bodyPart = multipart.getBodyPart(index);
                if (attachmentDownloader.isInlineResource(bodyPart)) {
                    final GmailAttachmentDownloader.InlineResource resource =
                            attachmentDownloader.saveInlinePart(bodyPart, resourceDirectory);
                    builder.inlineResource(
                            resource.contentId(),
                            relativeResourcePath(resource.savedPath()));
                }
                collect(bodyPart, resourceDirectory, builder);
            }
        }
    }

    private static String relativeResourcePath(final Path savedPath) {
        if (savedPath == null || savedPath.getFileName() == null) {
            return "";
        }
        return Path.of("resources", savedPath.getFileName().toString())
                .toString()
                .replace('\\', '/');
    }

    private static String rewriteCidReferences(
            final String html,
            final Map<String, String> inlineResources) {

        String rewritten = html == null ? "" : html;
        for (Map.Entry<String, String> entry : inlineResources.entrySet()) {
            if (entry.getKey().isBlank() || entry.getValue().isBlank()) {
                continue;
            }
            for (String alias : cidAliases(entry.getKey())) {
                rewritten = rewritten.replace("cid:" + alias, entry.getValue());
                rewritten = rewritten.replace("CID:" + alias, entry.getValue());
            }
        }
        return rewritten;
    }

    private static Iterable<String> cidAliases(final String contentId) {
        final String normalized = contentId.replace("<", "").replace(">", "").trim();
        return java.util.List.of(
                normalized,
                URLEncoder.encode(normalized, StandardCharsets.UTF_8).replace("+", "%20"));
    }

    public record ExtractedEmailContent(String html, String plainText) {
        public ExtractedEmailContent {
            html = html == null ? "" : html;
            plainText = plainText == null ? "" : plainText;
        }
    }

    private static final class ExtractedContentBuilder {
        private String html = "";
        private String text = "";
        private final Map<String, String> inlineResources = new LinkedHashMap<>();

        String html() {
            return html;
        }

        void html(final String value) {
            html = value == null ? "" : value;
        }

        String text() {
            return text;
        }

        void text(final String value) {
            text = value == null ? "" : value;
        }

        Map<String, String> inlineResources() {
            return inlineResources;
        }

        void inlineResource(final String contentId, final String relativePath) {
            if (contentId != null && !contentId.isBlank()
                    && relativePath != null && !relativePath.isBlank()) {
                inlineResources.put(contentId, relativePath);
            }
        }
    }
}
