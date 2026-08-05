package com.acxiom.emailaudit.rules;

import com.acxiom.emailaudit.campaign.CampaignSpecification;
import com.acxiom.emailaudit.campaign.CampaignSpecificationModule;
import com.acxiom.emailaudit.campaign.CampaignValidationResult;
import com.acxiom.emailaudit.campaign.CampaignValidator;
import com.microsoft.playwright.Page;

import java.util.List;

/**
 * Optional rule that compares the rendered email against an uploaded campaign
 * specification.
 */
public final class CampaignValidationRule implements ContextAwareAuditRule {

    public static final String RULE_ID = "CAMPAIGN_VALIDATION";
    private static final String METADATA_KEY = "campaignValidation";

    private final CampaignValidator validator;

    public CampaignValidationRule() {
        this(new CampaignValidator());
    }

    CampaignValidationRule(final CampaignValidator validator) {
        this.validator = validator;
    }

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "Campaign Validation";
    }

    @Override
    public RuleCategory category() {
        return RuleCategory.CUSTOM;
    }

    @Override
    public RuleSeverity severity() {
        return RuleSeverity.HIGH;
    }

    @Override
    public String passImpact() {
        return "Rendered email matches the uploaded campaign specification.";
    }

    @Override
    public String failImpact() {
        return "Rendered email does not match the uploaded campaign specification.";
    }

    @Override
    public RuleResult execute(final Page page) {
        return execute(page, List.of());
    }

    @Override
    public RuleResult execute(final Page page, final List<RuleResult> previousResults) {
        final long startMs = System.currentTimeMillis();
        final CampaignSpecification specification = CampaignSpecificationModule
                .activeSpecification()
                .orElse(null);

        if (specification == null) {
            final CampaignValidationResult result = CampaignValidationResult.noSpecification();
            return RuleResult.builder(this, RuleResult.Status.SKIPPED, startMs)
                    .withBusinessImpact("No campaign specification selected. Upload a specification to compare campaign metadata against the email.")
                    .withErrorMessage(result.message())
                    .withMetadata(METADATA_KEY, result)
                    .build();
        }

        try {
            final CampaignValidationResult result = validator.validate(specification, page, previousResults);
            final List<String> findings = result.rows()
                    .stream()
                    .filter(row -> !"PASS".equalsIgnoreCase(row.validation()))
                    .map(row -> row.identifier() + ": " + row.notes())
                    .toList();

            final RuleResult.Status status = result.failed() > 0
                    ? RuleResult.Status.FAIL
                    : RuleResult.Status.PASS;

            return RuleResult.builder(this, status, startMs)
                    .withFindings(findings)
                    .withMetadata(METADATA_KEY, result)
                    .build();
        } catch (final RuntimeException ex) {
            final CampaignValidationResult result =
                    CampaignValidationResult.notExecuted(ex.getClass().getSimpleName()
                            + ": " + ex.getMessage());
            return RuleResult.builder(this, RuleResult.Status.ERROR, startMs)
                    .withErrorMessage(result.message())
                    .withMetadata(METADATA_KEY, result)
                    .build();
        }
    }
}
