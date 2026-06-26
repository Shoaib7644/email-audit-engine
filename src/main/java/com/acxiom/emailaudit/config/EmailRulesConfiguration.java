package com.acxiom.emailaudit.config;

import com.acxiom.emailaudit.model.EmailRulesConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;

public final class EmailRulesConfiguration {

    private static volatile EmailRulesConfig instance;

    private EmailRulesConfiguration() {
    }

    public static EmailRulesConfig get() {

        if (instance == null) {

            synchronized (EmailRulesConfiguration.class) {

                if (instance == null) {

                    instance = load();
                }
            }
        }

        return instance;
    }

    private static EmailRulesConfig load() {

        try {

            String path =
                    ConfigurationManager
                            .getInstance()
                            .getRulesConfigPath();

            InputStream stream =
                    EmailRulesConfiguration.class
                            .getClassLoader()
                            .getResourceAsStream(path);

            return new ObjectMapper()
                    .readValue(stream, EmailRulesConfig.class);

        } catch (Exception e) {

            throw new RuntimeException(
                    "Unable to load email rules configuration",
                    e);
        }
    }
}