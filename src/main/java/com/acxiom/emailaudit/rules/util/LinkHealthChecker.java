package com.acxiom.emailaudit.rules.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.stream.Collectors;

public final class LinkHealthChecker {

    private LinkHealthChecker() {
    }

    public static ValidationResult validate(String url) {

        if (url == null || url.isBlank()) {
            return ValidationResult.failure("URL is empty");
        }

        try {

            URL target = new URL(url);

            URLConnection rawConnection =
                    target.openConnection();

            /*
             * Local file links
             */
            if ("file".equalsIgnoreCase(target.getProtocol())) {

                File file =
                        new File(target.toURI());

                if (!file.exists()) {

                    return ValidationResult.failure(
                            "Referenced file does not exist");
                }

                if (file.length() < 50) {

                    return ValidationResult.failure(
                            "Page loaded but contains little or no content");
                }

                return ValidationResult.success();
            }

            /*
             * HTTP / HTTPS links
             */
            if (!(rawConnection instanceof HttpURLConnection connection)) {

                return ValidationResult.failure(
                        "Unsupported protocol: "
                                + target.getProtocol());
            }

            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int status =
                    connection.getResponseCode();

            if (status < 200 || status >= 400) {

                return ValidationResult.failure(
                        "HTTP " + status);
            }

            String body;

            try (BufferedReader reader =
                         new BufferedReader(
                                 new InputStreamReader(
                                         connection.getInputStream()))) {

                body =
                        reader.lines()
                                .collect(Collectors.joining());
            }

            String content =
                    body.replaceAll("<[^>]*>", "")
                            .trim();

            if (content.length() < 50) {

                return ValidationResult.failure(
                        "Page loaded but contains little or no content");
            }

            return ValidationResult.success();

        } catch (Exception ex) {

            return ValidationResult.failure(
                    ex.getClass().getSimpleName()
                            + ": "
                            + ex.getMessage());
        }
    }
}