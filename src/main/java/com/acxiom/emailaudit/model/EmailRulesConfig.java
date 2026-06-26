package com.acxiom.emailaudit.model;

public class EmailRulesConfig {

    private RuleDefinition privacyLink;
    private RuleDefinition viewOnline;
    private RuleDefinition replyToDisclaimer;

    public RuleDefinition getPrivacyLink() {
        return privacyLink;
    }

    public void setPrivacyLink(RuleDefinition privacyLink) {
        this.privacyLink = privacyLink;
    }

    public RuleDefinition getViewOnline() {
        return viewOnline;
    }

    public void setViewOnline(RuleDefinition viewOnline) {
        this.viewOnline = viewOnline;
    }

    public RuleDefinition getReplyToDisclaimer() {
        return replyToDisclaimer;
    }

    public void setReplyToDisclaimer(
            RuleDefinition replyToDisclaimer) {
        this.replyToDisclaimer = replyToDisclaimer;
    }
}