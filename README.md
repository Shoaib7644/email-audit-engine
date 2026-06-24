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
* Business-friendly Excel summary reports
* Interactive, centralized web dashboards
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

### HTML Reporting & Interactive Dashboard

Generates enterprise reporting using ExtentReports and an advanced interactive web dashboard (`dashboard.html`).

Includes:

* Centralized file-level results and toggle views
* Interactive navigation sidebar with pass/fail file badges
* Rule-level breakdowns with impact metrics
* Fail, error, and skip threshold counters
* Dynamic search and filter modules
* Embedded screenshot evidence and technical trace logs
* System execution context timestamps

---

### Business-Friendly Excel Summaries

Compiles high-level data metrics into a business-oriented workbook tracker format (`EmailAuditSummary.xlsx`).

Provides:

* Tabular file breakdown matrices
* Severity classifications (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`)
* Rule ID matching references
* **Business Impact Statements** for marketing compliance stakeholders
* Deep-dive **Technical Details** strings for engineering remediation paths

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

| Component | Technology |
| --- | --- |
| Language | Java 17 |
| Browser Engine | Playwright |
| Accessibility Engine | Axe-Core |
| Reporting | ExtentReports & HTML5 |
| Spreadsheet Engine | Apache POI |
| Logging | SLF4J |
| Build Tool | Maven |
| State Tracking | JSON Registry |
| Hashing | SHA-256 |

---

## Current Validation Rules

| Rule ID | Category | Description |
| --- | --- | --- |
| **`ACCESSIBILITY_AXE`** | Accessibility | Comprehensive Axe-Core automated accessibility violation scan. |
| **`ALT_TEXT_VALIDATION`** | Accessibility | Evaluates presence, emptiness, and generic string patterns in image alt attributes. |
| **`BROKEN_ANCHOR`** | Structural | Validates internal hash fragment jump links against companion destination DOM IDs. |
| **`CTA_VALIDATION`** | Marketing Quality | Assesses structural properties, contrast rules, and visibility profiles of key action targets. |
| **`CONTENT_VALIDATION`** | Content Integrity | Scans body text properties, unparsed dynamic template tags, and placeholder copy. |
| **`DUPLICATE_ID`** | DOM Standards | Flags identical HTML element IDs across the active document tree structure. |
| **`LINK_TEXT_VALIDATION`** | Usability | Reviews contextual visibility profiles of anchor copy strings (e.g., catching "Click Here"). |
| **`LINK_VALIDATION`** | Connectivity | Extracts absolute URLs and dispatches remote HTTP status confirmation lookups. |
| **`HEADING_HIERARCHY`** | Accessibility | Audits logical sequence ordering across structurally nested heading layers (`<h1>` to `<h6>`). |

---

## Execution Methods

The platform provides two execution paradigms: an automated CLI build workflow and a unified graphical console panel interface.

### Method 1: Headless CLI Execution

To execute via standard command-line tools:

```bash
mvn clean package

java -jar email-audit-engine.jar

```

### Method 2: Interactive UI Execution

The app includes a graphic interface console layout displaying runtime paths, configurations, interactive action triggers, visual progress meters, and integrated terminal streaming log outputs.

#### **Executing on Windows**

Launch the graphical engine console utilizing the target platform command script wrapper:

1. Open your project root folder path inside File Explorer.
2. Double-click the file named **`RunForWindows.bat`**.

* *Alternative command line method:*
```cmd
RunForWindows.bat

```



#### **Executing on macOS**

Launch the console interface framework via native terminal execution:

1. Open the **Terminal** app and move to your project root workspace directory folder.
2. Grant executing permissions to the shell script asset if needed:
```bash
chmod +x RunForMac.command

```


3. Initialize the application container engine context:
```bash
./RunForMac.command

```



---

## Workspace Directory Structure

The operational directories (`input` and `output`) reside at the absolute root of the project workspace alongside your build files. Drop the target raw campaign source files inside the root `input` folder prior to execution.

```text
email-audit-engine (Project Root)
│
├── input
│     ├── sample-promo-email.html
│     └── transactional-welcome.html
│
├── output
│     ├── archive
│     │     ├── failed
│     │     └── passed
│     │
│     ├── audit-reports
│     │     ├── audit-report.html
│     │     └── dashboard.html
│     │
│     ├── audit-screenshots
│     │     ├── sample-promo-email_20260617T120101123Z.png
│     │     └── transactional-welcome_20260617T120103456Z.png
│     │
│     ├── reports
│     │     └── EmailAuditSummary.xlsx
│     │
│     └── state
│           └── registry.json
│
├── src
├── pom.xml
├── RunForMac.command
└── RunForWindows.bat

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
    ALT_TEXT_VALIDATION
    BROKEN_ANCHOR
    CTA_VALIDATION
    CONTENT_VALIDATION
    DUPLICATE_ID
    LINK_TEXT_VALIDATION
    LINK_VALIDATION
    HEADING_HIERARCHY

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
* Centralized Web Dashboard View
* Excel Business Summary Exportation
* Historical State Registry
* Duplicate File Detection
* ALT Text Validation
* Duplicate ID Validation
* Heading Hierarchy Validation
* Unsubscribe Link Validation
* Broken Image Detection

### Planned Enhancements

* Email Subject Length Validation
* Button Accessibility Validation
* Table Accessibility Validation
* Email Client Compatibility Checks
* Spam Score Analysis
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

## Repository Source Structure

```text
src
│
├── main
│    ├── java
│    │    └── com
│    │         └── acxiom
│    │              └── emailaudit
│    │                   ├── bootstrap
│    │                   ├── config
│    │                   ├── evidence
│    │                   ├── gui
│    │                   ├── orchestration
│    │                   ├── reporting
│    │                   ├── rules
│    │                   └── utils
│    └── resources
│        └── styles

```

---


## License

Internal Enterprise Use

---

## Author

**Shoaib Ahmed**

Lead QA / Test Automation Engineer

Specializations:

* Test Automation Architecture
* Playwright, Java, Maven
* Selenium, C#, .Net
* Accessibility Testing
* Docker, CI/CD Automation
* AI-assisted Quality Engineering

---

## Version

***v1.3***

### Features Included

* Accessibility Validation
* Content Validation
* Link Validation
* Screenshot Evidence
* State Registry
* Duplicate Detection
* ALT Text Validation
* Duplicate ID Validation
* Interactive HTML5 Dashboard Component
* Business-Friendly Excel Summary Matrix Exports
* Enterprise Reporting



```