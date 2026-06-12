package com.acxiom.emailaudit.rendering;

import com.acxiom.emailaudit.exceptions.EmailAuditException;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure wrapper layer managing lifecycle routines for the Playwright engine instance.
 * Completely decoupled from specific DOM validations or reporting operations.
 */
public class PlaywrightManager {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightManager.class);

    private Playwright playwright;
    private Browser browser;

    /**
     * Instantiates a specialized, thread-isolated Playwright instance and launches a headless browser.
     */
    public void createPlaywrightInstance() {
        try {
            log.info("Initializing fundamental Playwright automation environment...");
            playwright = Playwright.create();

            log.info("Launching backend Chromium headless instances...");
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true));
        } catch (Exception e) {
            log.error("Fatal failure initializing automation browser layers: {}", e.getMessage(), e);
            throw new EmailAuditException("Failed to construct local Playwright execution engine", e);
        }
    }

    /**
     * Exposes the active, managed browser instance to external test handlers.
     */
    public Browser getBrowser() {
        if (browser == null) {
            throw new EmailAuditException("Browser target requested prior to initialization. Verify createPlaywrightInstance loop context.");
        }
        return browser;
    }

    /**
     * Cleanly terminates the browser instance process.
     */
    public void closeBrowser() {
        if (browser != null) {
            try {
                log.info("Tearing down running Chromium browser engine instance gracefully...");
                browser.close();
                browser = null;
            } catch (Exception e) {
                log.warn("Non-breaking exception observed while shutting down browser process instance context: {}", e.getMessage());
            }
        }
    }

    /**
     * Core cleanup routine ensuring the fundamental Playwright process stack is freed.
     */
    public void closePlaywright() {
        closeBrowser();
        if (playwright != null) {
            try {
                log.info("Terminating global underlying Playwright execution framework processes...");
                playwright.close();
                playwright = null;
            } catch (Exception e) {
                log.warn("Exception encountered while releasing underlying Playwright engine processes: {}", e.getMessage());
            }
        }
    }
}