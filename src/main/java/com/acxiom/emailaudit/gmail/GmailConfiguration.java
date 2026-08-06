package com.acxiom.emailaudit.gmail;

import com.acxiom.emailaudit.config.ConfigurationManager;
import com.acxiom.emailaudit.output.ExecutionOutputManager;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Gmail-specific configuration resolved through the shared configuration facade.
 */
public record GmailConfiguration(
        boolean enabled,
        String username,
        String authentication,
        String appPassword,
        String host,
        int port,
        String folder,
        Path tempDirectory) {

    private static final String KEY_ENABLED = "gmail.enabled";
    private static final String KEY_USERNAME = "gmail.username";
    private static final String KEY_AUTHENTICATION = "gmail.authentication";
    private static final String KEY_APP_PASSWORD = "gmail.appPassword";
    private static final String KEY_HOST = "gmail.host";
    private static final String KEY_PORT = "gmail.port";
    private static final String KEY_FOLDER = "gmail.folder";
    private static final String KEY_TEMP_DIR = "gmail.temp.dir";

    public GmailConfiguration {
        username = clean(username);
        authentication = clean(authentication).isBlank() ? "app-password" : clean(authentication);
        appPassword = clean(appPassword);
        host = clean(host).isBlank() ? "imap.gmail.com" : clean(host);
        folder = clean(folder).isBlank() ? "INBOX" : clean(folder);
        tempDirectory = tempDirectory == null
                ? ExecutionOutputManager.ensureCurrentExecution().postSendTempDir()
                : tempDirectory;
    }

    public static GmailConfiguration fromConfig() {
        final ConfigurationManager config = ConfigurationManager.getInstance();
        return new GmailConfiguration(
                config.getBoolean(KEY_ENABLED, false),
                config.getOrDefault(KEY_USERNAME, ""),
                config.getOrDefault(KEY_AUTHENTICATION, "app-password"),
                config.getOrDefault(KEY_APP_PASSWORD, ""),
                config.getOrDefault(KEY_HOST, "imap.gmail.com"),
                config.getInt(KEY_PORT, 993),
                config.getOrDefault(KEY_FOLDER, "INBOX"),
                resolveTempDirectory(config.getOrDefault(KEY_TEMP_DIR, "")));
    }

    private static Path resolveTempDirectory(final String configured) {
        if (ExecutionOutputManager.isManagedGmailTempDir(configured)) {
            return ExecutionOutputManager.ensureCurrentExecution().postSendTempDir();
        }
        return Paths.get(configured);
    }

    public GmailConfiguration withUsername(final String usernameOverride) {
        if (usernameOverride == null || usernameOverride.isBlank()) {
            return this;
        }
        return new GmailConfiguration(
                enabled,
                usernameOverride.trim(),
                authentication,
                appPassword,
                host,
                port,
                folder,
                tempDirectory);
    }

    public void validateForConnection() {
        if (!enabled) {
            throw new GmailException("Gmail post-send validation is disabled. Set gmail.enabled=true.");
        }
        if (username.isBlank()) {
            throw new GmailException("Gmail username is not configured. Set gmail.username.");
        }
        if ("app-password".equalsIgnoreCase(authentication) && appPassword.isBlank()) {
            throw new GmailException("Gmail app password is not configured. Set gmail.appPassword.");
        }
        if (!"app-password".equalsIgnoreCase(authentication)
                && !"oauth".equalsIgnoreCase(authentication)) {
            throw new GmailException("Unsupported Gmail authentication mode: " + authentication);
        }
        if ("oauth".equalsIgnoreCase(authentication)) {
            throw new GmailException("Gmail OAuth authentication is not implemented yet. Use gmail.authentication=app-password for Phase 1.");
        }
    }

    private static String clean(final String value) {
        return value == null ? "" : value.trim();
    }
}
