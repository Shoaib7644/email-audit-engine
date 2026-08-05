package com.acxiom.emailaudit.campaign;

import java.nio.file.Path;
import java.util.List;

/**
 * Format-specific campaign specification loader.
 */
public interface SpreadsheetLoader {

    boolean supports(Path file);

    List<String> worksheetNames(Path file);

    CampaignSpecification load(Path file, String worksheetName);
}
