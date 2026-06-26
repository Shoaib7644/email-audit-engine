package com.acxiom.emailaudit.rules.util;

public record ValidationResult(
        boolean valid,
        String message) {

    public static ValidationResult success() {
        return new ValidationResult(
                true,
                "Link is operational");
    }

    public static ValidationResult failure(
            String message) {

        return new ValidationResult(
                false,
                message);
    }
}