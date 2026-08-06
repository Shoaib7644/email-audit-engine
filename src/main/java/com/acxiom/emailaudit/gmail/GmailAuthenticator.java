package com.acxiom.emailaudit.gmail;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;
import java.util.Properties;

/**
 * Creates authenticated Gmail IMAP stores.
 */
public final class GmailAuthenticator {

    public Store connect(final GmailConfiguration configuration) {
        configuration.validateForConnection();

        final Properties properties = new Properties();
        properties.put("mail.store.protocol", "imaps");
        properties.put("mail.imaps.host", configuration.host());
        properties.put("mail.imaps.port", String.valueOf(configuration.port()));
        properties.put("mail.imaps.ssl.enable", "true");
        properties.put("mail.imaps.auth", "true");

        try {
            final Session session = Session.getInstance(properties);
            final Store store = session.getStore("imaps");
            store.connect(
                    configuration.host(),
                    configuration.port(),
                    configuration.username(),
                    configuration.appPassword());
            return store;
        } catch (final MessagingException ex) {
            throw new GmailException("Unable to connect to Gmail: " + ex.getMessage(), ex);
        }
    }
}
