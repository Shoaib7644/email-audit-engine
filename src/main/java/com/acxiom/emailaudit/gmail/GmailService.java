package com.acxiom.emailaudit.gmail;

import javax.mail.Store;
import java.time.Duration;

/**
 * Facade for Phase 1 Gmail post-send retrieval.
 */
public final class GmailService {

    private final GmailConfiguration configuration;
    private final GmailAuthenticator authenticator;
    private final GmailSearchService searchService;
    private final GmailMessageDownloader downloader;

    public GmailService() {
        this(
                GmailConfiguration.fromConfig(),
                new GmailAuthenticator(),
                new GmailSearchService(),
                new GmailMessageDownloader());
    }

    public GmailService(final String username) {
        this(
                GmailConfiguration.fromConfig().withUsername(username),
                new GmailAuthenticator(),
                new GmailSearchService(),
                new GmailMessageDownloader());
    }

    public GmailService(
            final GmailConfiguration configuration,
            final GmailAuthenticator authenticator,
            final GmailSearchService searchService,
            final GmailMessageDownloader downloader) {

        this.configuration = configuration;
        this.authenticator = authenticator;
        this.searchService = searchService;
        this.downloader = downloader;
    }

    public GmailMetadata downloadNewestMatchingEmail(
            final String subject,
            final String folder,
            final Duration receivedWithin) {

        Store store = null;
        try {
            store = authenticator.connect(configuration);
            try (GmailSearchService.SearchResult result =
                         searchService.findNewestBySubject(
                                 store,
                                 folder == null || folder.isBlank()
                                         ? configuration.folder()
                                         : folder,
                                 subject,
                                 receivedWithin)) {

                return downloader.download(
                        result.message(),
                        configuration.tempDirectory());
            }
        } catch (final GmailException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new GmailException("Gmail retrieval failed: " + ex.getMessage(), ex);
        } finally {
            closeQuietly(store);
        }
    }

    private static void closeQuietly(final Store store) {
        if (store == null || !store.isConnected()) {
            return;
        }
        try {
            store.close();
        } catch (final Exception ignored) {
            // Nothing useful to do during cleanup.
        }
    }
}
