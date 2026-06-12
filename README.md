# Email Audit Engine

A high-performance, enterprise-grade Email HTML Validation Framework built with Java, Playwright, Axe-Core, and ExtentReports.

The framework validates marketing emails, CRM templates, transactional emails, and campaign HTML files against accessibility, content quality, email development best practices, and compliance standards.

---

## Overview

Email Audit Engine performs automated validation of HTML email templates and generates detailed audit reports containing:

* Accessibility violations
* Content quality issues
* Link validation results
* Duplicate ID detection
* ALT text validation
* Screenshot evidence
* Detailed Extent HTML reports
* Historical execution tracking

The framework is designed for:

* Marketing Operations Teams
* QA Teams
* Email Developers
* Accessibility Auditors
* CRM Campaign Teams

---

## Key Features

### Accessibility Validation

Powered by Axe-Core.

Checks:

* WCAG accessibility violations
* ARIA issues
* Landmark issues
* Contrast violations (where detectable)
* Form accessibility
* Semantic structure validation

---

### Content Validation

Validates:

* Missing title tags
* Missing meta descriptions
* Empty headings
* Empty buttons
* Empty links
* Empty content blocks
* Placeholder text detection
* Broken content structures

---

### Link Validation

Validates:

* Missing href attributes
* Empty href values
* Invalid URLs
* Broken HTTP/HTTPS links
* Mailto links
* Anchor links
* Redirect handling

---

### Image Validation

#### ALT Text Validation

Detects:

* Missing ALT attributes
* Empty ALT values
* Generic ALT text

Examples:

```html
alt="image"
alt="banner"
alt="photo"
alt="picture"
alt="img"
```

Supports accessibility best practices.

---

### Duplicate ID Validation

Detects duplicate HTML IDs.

Example:

```html
<div id="hero"></div>
<div id="hero"></div>
```

Provides:

* Duplicate ID value
* Occurrence count
* Location details

---

### Screenshot Evidence

Captures full-page screenshots using Playwright.

Features:

* Full-page screenshots
* Timestamped file naming
* Automatic evidence collection
* Audit traceability

---

### HTML Reporting

Generates enterprise reporting using ExtentReports.

Includes:

* File-level results
* Rule-level breakdowns
* Pass/Fail summaries
* Failure leaderboard
* Screenshot evidence
* Execution metrics

---

### Duplicate File Detection

Prevents re-processing of identical files.

Uses:

* SHA-256 hashing
* Historical state tracking
* Duplicate identification

---

### Historical State Registry

Tracks:

* Previously processed files
* File hashes
* Processing status
* Execution timestamps

Benefits:

* Faster incremental executions
* Duplicate prevention
* Audit traceability

---

## Architecture

```text
Email Audit Engine
│
├── Audit Orchestrator
│
├── Rule Executor
│
├── Accessibility Rules
│     └── Axe-Core
│
├── Content Rules
│
├── Link Rules
│
├── Image Rules
│     ├── ALT Text Validation
│     └── Duplicate ID Validation
│
├── Screenshot Service
│
├── Report Manager
│
├── Archive Manager
│
└── State Registry
```

---

## Technology Stack

| Component            | Technology    |
| -------------------- | ------------- |
| Language             | Java 17       |
| Browser Engine       | Playwright    |
| Accessibility Engine | Axe-Core      |
| Reporting            | ExtentReports |
| Logging              | SLF4J         |
| Build Tool           | Maven         |
| State Tracking       | JSON Registry |
| Hashing              | SHA-256       |

---

## Current Validation Rules

| Rule ID                 | Category      | Description                  |
| ----------------------- | ------------- | ---------------------------- |
| ACCESSIBILITY_AXE       | Accessibility | Axe accessibility scan       |
| CONTENT_VALIDATION      | Content       | Content quality checks       |
| LINK_VALIDATION         | Links         | Link validation              |
| ALT_TEXT_VALIDATION     | Accessibility | Image ALT text validation    |
| DUPLICATE_ID_VALIDATION | Accessibility | Duplicate HTML ID validation |

---

## Sample Execution

```bash
mvn clean package

java -jar email-audit-engine.jar
```

---

## Sample Output Structure

```text
target
│
├── audit-reports
│     └── audit-report.html
│
├── audit-screenshots
│     ├── email1_20250611T120101123Z.png
│     ├── email2_20250611T120103456Z.png
│
├── archive
│     ├── passed
│     ├── failed
│
└── state
      └── audit-state.json
```

---

## Example Report

Each HTML file generates:

```text
sample-email.html

PASS
FAIL
ERROR
SKIPPED

Rule Breakdown:
    ACCESSIBILITY_AXE
    CONTENT_VALIDATION
    LINK_VALIDATION
    ALT_TEXT_VALIDATION
    DUPLICATE_ID_VALIDATION

Screenshot Evidence

Execution Metrics
```

---

## Performance Characteristics

Designed for large-scale email auditing.

Capabilities:

* Parallel file processing
* Thread-safe execution
* SHA-256 deduplication
* Incremental processing
* Efficient screenshot capture
* Lightweight reporting

---

## Enterprise Use Cases

### Marketing Campaign Validation

Validate:

* Campaign emails
* Promotional emails
* Newsletters
* Customer journeys

### Accessibility Compliance

Validate:

* WCAG compliance
* Screen-reader compatibility
* Accessibility regressions

### Release Gates

Integrate into:

* GitHub Actions
* Jenkins
* Azure DevOps
* GitLab CI/CD

Prevent deployment of non-compliant emails.

---

## Roadmap

### Completed

* Accessibility Validation (Axe)
* Content Validation
* Link Validation
* Screenshot Evidence
* Extent Reporting
* Historical State Registry
* Duplicate File Detection
* ALT Text Validation
* Duplicate ID Validation

### Planned Enhancements

* Heading Hierarchy Validation
* Color Contrast Validation
* Missing Language Attribute Detection
* Email Subject Length Validation
* Button Accessibility Validation
* Table Accessibility Validation
* Unsubscribe Link Validation
* Email Client Compatibility Checks
* Spam Score Analysis
* Broken Image Detection
* Tracking Pixel Validation
* Responsive Design Validation

---

## Example Validation Findings

### Missing ALT Text

```html
<img src="banner.jpg">
```

Result:

```text
FAIL
Image missing ALT attribute
```

### Duplicate ID

```html
<div id="hero"></div>
<div id="hero"></div>
```

Result:

```text
FAIL
Duplicate ID detected: hero
Occurrences: 2
```

---

## Repository Structure

```text
src
│
├── config
├── orchestration
├── reporting
├── evidence
├── rules
│    ├── accessibility
│    ├── content
│    ├── links
│    └── images
│
├── state
├── archive
└── utils
```

---

## Future Validation Coverage

Planned support includes:

* Accessibility Compliance (WCAG 2.1 AA)
* Email Development Standards
* Content Governance
* Marketing Compliance
* Deliverability Validation
* Responsive Rendering Checks
* Brand Consistency Validation
* Spam Detection Rules
* Email Client Compatibility Validation

---

## License

Internal Enterprise Use

---

## Author

**Shoaib Ahmed**

Lead QA / Test Automation Engineer

Specializations:

* Test Automation Architecture
* Playwright
* Selenium
* Accessibility Testing
* CI/CD Automation
* AI-assisted Quality Engineering

---

## Version

**v1.3**

### Features Included

* Accessibility Validation
* Content Validation
* Link Validation
* Screenshot Evidence
* State Registry
* Duplicate Detection
* ALT Text Validation
* Duplicate ID Validation
* Enterprise Reporting
