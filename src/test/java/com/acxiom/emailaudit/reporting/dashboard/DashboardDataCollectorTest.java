package com.acxiom.emailaudit.reporting.dashboard;

import com.acxiom.emailaudit.core.AuditContext;
import com.acxiom.emailaudit.orchestration.AuditOrchestrator;
import com.acxiom.emailaudit.rules.AuditRule;
import com.acxiom.emailaudit.rules.LinkAuditEntry;
import com.acxiom.emailaudit.rules.RuleResult;
import com.microsoft.playwright.Page;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.List;

import static org.testng.Assert.assertEquals;

public class DashboardDataCollectorTest {

    @Test
    public void failedChecksExcludeSkippedAndSectionsAggregateAllFailures() {
        final AuditRule content = rule("CONTENT_VALIDATION", AuditRule.RuleCategory.CONTENT);
        final AuditRule preheader = rule("PREHEADER_TRIM_VALIDATION", AuditRule.RuleCategory.CONTENT);
        final AuditRule linkText = rule("LINK_TEXT_VALIDATION", AuditRule.RuleCategory.LINKS);
        final AuditRule linkValidation = rule("LINK_VALIDATION", AuditRule.RuleCategory.LINKS);
        final AuditRule privacy = rule("PRIVACY_LINK", AuditRule.RuleCategory.LINKS);

        final List<RuleResult> results = List.of(
                RuleResult.pass(content, System.currentTimeMillis()),
                RuleResult.skipped(preheader, "No preheader found"),
                RuleResult.fail(linkText, System.currentTimeMillis(), List.of("generic text")),
                RuleResult.fail(linkValidation, System.currentTimeMillis(), List.of("template one", "template two")),
                RuleResult.fail(privacy, System.currentTimeMillis(), List.of("privacy failed"))
        );

        final AuditContext context = AuditContext.builder(Path.of("sample.html"))
                .withRuleResults(results)
                .withStatus(AuditContext.AuditStatus.FAILED)
                .completedNow()
                .build();

        final RunAuditData data = DashboardDataCollector.collect(
                new AuditOrchestrator.RunSummary(
                        1,
                        1,
                        0,
                        0,
                        1,
                        0,
                        null,
                        null,
                        List.of(context),
                        25));

        final FileAuditData file = data.files().getFirst();
        assertEquals(file.totalChecks(), 5);
        assertEquals(file.passedChecks(), 1);
        assertEquals(file.failedChecks(), 3);

        final SectionCheckResult links = section(file, "Links");
        assertEquals(links.status(), "FAIL");
        assertEquals(links.findingCount(), 4);

        final SectionCheckResult contentSection = section(file, "Content");
        assertEquals(contentSection.status(), "SKIPPED");
        assertEquals(contentSection.findingCount(), 0);
    }

    @Test
    public void collectIncludesStructuredLinkInventoryFromRuleMetadata() {
        final AuditRule linkValidation = rule("LINK_VALIDATION", AuditRule.RuleCategory.LINKS);
        final RuleResult result = RuleResult.builder(
                        linkValidation,
                        RuleResult.Status.PASS,
                        System.currentTimeMillis())
                .withMetadata("links", List.of(new LinkAuditEntry(
                        "GM Privacy Statement",
                        "https://t.delivery.generalmotors.com/r/?id=abc",
                        "https://www.gm.com/privacy",
                        "HTTP",
                        "",
                        "PASS",
                        "Redirect completed successfully",
                        "GM Privacy Statement | General Motors",
                        200,
                        "OK",
                        1,
                        List.of(
                                "https://t.delivery.generalmotors.com/r/?id=abc",
                                "https://www.gm.com/privacy"),
                        1250L,
                        "/tmp/link-shot.png",
                        "Text Link",
                        "Privacy policy",
                        "GM Privacy Statement",
                        "_blank",
                        4,
                        "10,20 140x24")))
                .build();

        final AuditContext context = AuditContext.builder(Path.of("sample.html"))
                .withRuleResults(List.of(result))
                .withStatus(AuditContext.AuditStatus.SUCCESS)
                .completedNow()
                .build();

        final RunAuditData data = DashboardDataCollector.collect(
                new AuditOrchestrator.RunSummary(
                        1,
                        1,
                        1,
                        0,
                        0,
                        0,
                        null,
                        null,
                        List.of(context),
                        25));

        final FileAuditData file = data.files().getFirst();
        assertEquals(file.links().size(), 1);

        final LinkAuditData link = file.links().getFirst();
        assertEquals(link.visibleText(), "GM Privacy Statement");
        assertEquals(link.originalUrl(), "https://t.delivery.generalmotors.com/r/?id=abc");
        assertEquals(link.finalUrl(), "https://www.gm.com/privacy");
        assertEquals(link.linkType(), "HTTP");
        assertEquals(link.validationStatus(), "PASS");
        assertEquals(link.reason(), "Redirect completed successfully");
        assertEquals(link.pageTitle(), "GM Privacy Statement | General Motors");
        assertEquals(link.httpStatus(), Integer.valueOf(200));
        assertEquals(link.statusText(), "OK");
        assertEquals(link.redirectCount(), Integer.valueOf(1));
        assertEquals(link.redirectChain().size(), 2);
        assertEquals(link.responseTimeMs(), Long.valueOf(1250L));
        assertEquals(link.screenshotPath(), "/tmp/link-shot.png");
        assertEquals(link.element(), "Text Link");
        assertEquals(link.ariaLabel(), "Privacy policy");
        assertEquals(link.title(), "GM Privacy Statement");
        assertEquals(link.target(), "_blank");
        assertEquals(link.domIndex(), Integer.valueOf(4));
        assertEquals(link.bounds(), "10,20 140x24");
    }

    private static SectionCheckResult section(final FileAuditData file, final String name) {
        return file.sections().stream()
                .filter(section -> section.sectionName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static AuditRule rule(
            final String ruleId,
            final AuditRule.RuleCategory category) {

        return new AuditRule() {
            @Override
            public String ruleId() {
                return ruleId;
            }

            @Override
            public String description() {
                return ruleId;
            }

            @Override
            public RuleCategory category() {
                return category;
            }

            @Override
            public RuleSeverity severity() {
                return RuleSeverity.HIGH;
            }

            @Override
            public String passImpact() {
                return ruleId + " passed";
            }

            @Override
            public String failImpact() {
                return ruleId + " failed";
            }

            @Override
            public RuleResult execute(final Page page) {
                throw new UnsupportedOperationException("Test stub");
            }
        };
    }
}
