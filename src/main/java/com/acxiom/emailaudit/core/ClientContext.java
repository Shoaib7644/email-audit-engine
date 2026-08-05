package com.acxiom.emailaudit.core;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stores the client selected for the current audit execution.
 */
public final class ClientContext {

    public static final String DEFAULT_CLIENT = "General";

    private static final List<String> SUPPORTED_CLIENTS =
            List.of(DEFAULT_CLIENT, "GM", "AT&T", "Citi");

    private static final AtomicReference<String> SELECTED_CLIENT =
            new AtomicReference<>(DEFAULT_CLIENT);

    private ClientContext() {
        throw new UnsupportedOperationException(
                "Utility class should not be instantiated");
    }

    public static List<String> supportedClients() {
        return SUPPORTED_CLIENTS;
    }

    public static String selectedClient() {
        return SELECTED_CLIENT.get();
    }

    public static void setSelectedClient(final String client) {
        if (client == null || client.isBlank()) {
            SELECTED_CLIENT.set(DEFAULT_CLIENT);
            return;
        }

        SELECTED_CLIENT.set(client.trim());
    }
}
