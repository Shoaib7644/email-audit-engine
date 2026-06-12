package com.acxiom.emailaudit.model;

/**
 * Represents the execution state of an individual audit rule
 * or the aggregate status of an entire email document.
 */
public enum AuditStatus {
    PASS,
    FAIL,
    WARNING
}