package com.acxiom.emailaudit.campaign;

/**
 * Calculates the row result from mandatory campaign checks.
 */
public final class CampaignResultCalculator {

    public String calculate(
            final String elementStatus,
            final String urlStatus,
            final String trackingStatus,
            final String labelStatus,
            final String categoryStatus,
            final String typeStatus) {

        final String[] mandatory = {
                elementStatus,
                urlStatus,
                trackingStatus,
                labelStatus,
                categoryStatus,
                typeStatus
        };

        boolean warning = false;
        for (final String status : mandatory) {
            if ("FAIL".equalsIgnoreCase(status)) {
                return "FAIL";
            }
            if ("WARNING".equalsIgnoreCase(status)) {
                warning = true;
            }
        }
        return warning ? "WARNING" : "PASS";
    }
}
