package com.acxiom.emailaudit.model;

import java.util.List;

public class RuleDefinition {

    private boolean enabled;
    private List<String> keywords;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords;
    }
}