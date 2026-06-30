#!/bin/bash

INPUT_DIR="./input"
mkdir -p "$INPUT_DIR"

echo "Generating 5 highly diverse HTML files with randomized audit issues..."

# --- KEYWORD ARRAYS FROM JSON ---
PRIVACY_KEYWORDS=("privacy" "privacy policy" "privacy notice")
VIEW_ONLINE_KEYWORDS=("view online" "view in browser" "open in browser" "web version")
DISCLAIMER_KEYWORDS=("reply-to" "do not reply" "please do not reply" "this mailbox is not monitored" "unmonitored mailbox")

for i in {1..5}
do
    UUID=$(uuidgen 2>/dev/null || echo "ID-$RANDOM-$i")
    TIMESTAMP=$(date +"%Y-%m-%dT%H:%M:%S")
    FILE_NAME="$INPUT_DIR/enterprise-newsletter_test_run_${i}.html"

    # --- RANDOMIZE ISSUES ---
    # 0 = No/Clean (Passes), 1 = Inject Defect (Fails)
    FAIL_ACCESSIBILITY=$((RANDOM % 2))
    FAIL_ALT_TEXT=$((RANDOM % 2))
    FAIL_BROKEN_ANCHOR=$((RANDOM % 2))
    FAIL_DUPLICATE_ID=$((RANDOM % 2))
    FAIL_LINK_TEXT=$((RANDOM % 2))
    FAIL_LINK_VALIDATION=$((RANDOM % 2))
    FAIL_HEADING_HIERARCHY=$((RANDOM % 2))
    FAIL_CONTENT=$((RANDOM % 2))
    
    FAIL_PRIVACY=$((RANDOM % 2))
    FAIL_DISCLAIMER=$((RANDOM % 2))

    # Advanced View Online Scenarios:
    # 0 = Pass (Valid Enterprise URL)
    # 1 = Fail: Missing Keyword/Link Entirely
    # 2 = Fail: Broken Link / 404 Pattern (Relative URL)
    # 3 = Fail: Blank White Page Mock (Triggers 200 OK but empty body parser rule)
    # 4 = Fail: Redirect Loop Mock Pattern
    FAIL_VIEW_ONLINE=$((RANDOM % 5))

    # Occasionally force a perfectly clean file (1 in 5 chance)
    if [ $((i % 5)) -eq 0 ]; then
        FAIL_ACCESSIBILITY=0; FAIL_ALT_TEXT=0; FAIL_BROKEN_ANCHOR=0; FAIL_DUPLICATE_ID=0
        FAIL_LINK_TEXT=0; FAIL_LINK_VALIDATION=0; FAIL_HEADING_HIERARCHY=0; FAIL_CONTENT=0
        FAIL_PRIVACY=0; FAIL_DISCLAIMER=0; FAIL_VIEW_ONLINE=0
    fi

    # --- CONSTRUCT CONDITIONAL HTML ELEMENTS ---
    
    # 1. Accessibility
    HTML_TAG="<html lang=\"en\">"
    BODY_STYLE="font-family: Arial, sans-serif; color: #333;"
    if [ $FAIL_ACCESSIBILITY -eq 1 ]; then
        HTML_TAG="<html>"
        BODY_STYLE="font-family: Arial, sans-serif; color: #E0E0E0; background-color: #FFF;"
    fi

    # 2. Heading Hierarchy
    HEADING_STRUCTURE="<h1>Enterprise Newsletter #${i}</h1>\n<h2>Featured Highlights</h2>"
    if [ $FAIL_HEADING_HIERARCHY -eq 1 ]; then
        HEADING_STRUCTURE="<h1>Enterprise Newsletter #${i}</h1>\n<h3>Skipped Directly to H3</h3>"
    fi

    # 3. Duplicate IDs
    ID_ATTR_1="id=\"main-content-section\""
    ID_ATTR_2="id=\"footer-legal-notice\""
    if [ $FAIL_DUPLICATE_ID -eq 1 ]; then
        ID_ATTR_1="id=\"duplicate-element-id\""
        ID_ATTR_2="id=\"duplicate-element-id\""
    fi

    # 4. Alt Text Validation
    ALT_TEXT="alt=\"A corporate technology banner showing a cloud network architecture\""
    if [ $FAIL_ALT_TEXT -eq 1 ]; then
        ALT_TEXT=""
    fi

    # 5. Link Text Validation
    READ_MORE_TEXT="Read our deep dive analysis on AI adoption metrics"
    if [ $FAIL_LINK_TEXT -eq 1 ]; then
        READ_MORE_TEXT="Click here"
    fi

    # 6. Link Validation (HTTP Status / Host resolution)
    VALID_LINK_1="https://www.google.com"
    VALID_LINK_2="https://www.github.com"
    if [ $FAIL_LINK_VALIDATION -eq 1 ]; then
        VALID_LINK_1="https://broken-link-demo-${i}23456.com"
        VALID_LINK_2="https://www.google.com/non-existent-page-404-error"
    fi

    # 7. Broken Anchors
    ANCHOR_LINK="#footer"
    FOOTER_ID="id=\"footer\""
    if [ $FAIL_BROKEN_ANCHOR -eq 1 ]; then
        ANCHOR_LINK="#non-existent-html-snippet-id"
    fi

    # 8. Content Validation placeholders
    CONTENT_BODY="Our core strategy this quarter focuses extensively on stabilizing cloud transformation targets."
    if [ $FAIL_CONTENT -eq 1 ]; then
        CONTENT_BODY="Lorem ipsum dolor sit up amet, dynamic placeholder string text [Insert Content Here]."
    fi

    # --- KEYWORD AND OPERATIONAL CONDITIONALS ---
    
    # Randomly pick unique test keyword terms
    RAND_PRIVACY=${PRIVACY_KEYWORDS[$((RANDOM % ${#PRIVACY_KEYWORDS[@]}))]}
    RAND_VIEW_ONLINE=${VIEW_ONLINE_KEYWORDS[$((RANDOM % ${#VIEW_ONLINE_KEYWORDS[@]}))]}
    RAND_DISCLAIMER=${DISCLAIMER_KEYWORDS[$((RANDOM % ${#DISCLAIMER_KEYWORDS[@]}))]}

    # Handle Privacy Policy Link
    if [ $FAIL_PRIVACY -eq 1 ]; then
        PRIVACY_SECTION="<a href=\"/legal\">Click to read corporate details</a>"
    else
        PRIVACY_SECTION="<a href=\"/privacy-policy\">Read our corporate ${RAND_PRIVACY}</a>"
    fi

    # Handle Disclaimer Text
    if [ $FAIL_DISCLAIMER -eq 1 ]; then
        DISCLAIMER_SECTION="<p>Standard footer notice text with completely normal variables.</p>"
    else
        DISCLAIMER_SECTION="<p>Please note: This is an automated notification via an ${RAND_DISCLAIMER}. Outbound messages are unmonitored.</p>"
    fi

    # --- NEW: ADVANCED OPERATIONAL VIEW ONLINE LINK SCENARIOS ---
    case $FAIL_VIEW_ONLINE in
        0)
            # PASSING: Alternates realistic Salesforce, Adobe, and Acoustic Enterprise tracking URL mirrors
            MODULO=$((i % 3))
            if [ $MODULO -eq 0 ]; then
                # Salesforce Marketing Cloud Example
                VIEW_ONLINE_SECTION="<p style=\"font-size: 11px; text-align: right;\"><a href=\"https://view.email.company.com/?qs=abc123hash${i}\">${RAND_VIEW_ONLINE}</a></p>"
            elif [ $MODULO -eq 1 ]; then
                # Adobe Campaign Example
                VIEW_ONLINE_SECTION="<p style=\"font-size: 11px; text-align: right;\"><a href=\"https://news.company.com/mirror?id=id123456_${i}\">${RAND_VIEW_ONLINE}</a></p>"
            else
                # Acoustic Campaign Example
                VIEW_ONLINE_SECTION="<p style=\"font-size: 11px; text-align: right;\"><a href=\"https://mirror.company.com/acoustic-render-${i}\">${RAND_VIEW_ONLINE}</a></p>"
            fi
            ;;
        1)
            # FAIL: Keyword/Link completely missing from code framework
            VIEW_ONLINE_SECTION=""
            ;;
        2)
            # FAIL: Broken Production Link (Relative paths causing systemic 404s)
            VIEW_ONLINE_SECTION="<p style=\"font-size: 11px; text-align: right;\"><a href=\"/view-online\">${RAND_VIEW_ONLINE}</a></p>"
            ;;
        3)
            # FAIL: Blank White Page (Returns HTTP 200, but payload content/body is empty text)
            # Added unique query parameter '?render=blank' so your automation test runner can simulate a blank page hook
            VIEW_ONLINE_SECTION="<p style=\"font-size: 11px; text-align: right;\"><a href=\"https://view.email.company.com/?qs=blankmock&instance=${i}\">${RAND_VIEW_ONLINE}</a></p>"
            ;;
        4)
            # FAIL: Redirect Loop Scenario
            # Appends distinct loop parameter to simulate infinite 302 hops down the pipeline
            VIEW_ONLINE_SECTION="<p style=\"font-size: 11px; text-align: right;\"><a href=\"https://news.company.com/mirror?redirect_loop=true&run=${i}\">${RAND_VIEW_ONLINE}</a></p>"
            ;;
    esac


    # --- WRITE HTML GENERATION BLOCK ---
    cat << EOF > "$FILE_NAME"
<!DOCTYPE html>
${HTML_TAG}
<head>
    <title>Newsletter Edition ${i} - Variation Run</title>
    <meta charset="UTF-8">
    </head>
<body style="${BODY_STYLE}">

<table width="700" align="center" border="0">
    <tr>
        <td ${ID_ATTR_1}>
            ${VIEW_ONLINE_SECTION}
            <span style="color: #777; font-size: 11px;">Run Ref: ${TIMESTAMP} | Instance: ${i}</span>
            
            ${HEADING_STRUCTURE}

            <img src="https://picsum.photos/700/250?random=${i}" ${ALT_TEXT} width="700">

            <p>
                ${CONTENT_BODY}
            </p>

            <h2>Resource List</h2>
            <ul>
                <li>
                    <a href="${VALID_LINK_1}">
                        ${READ_MORE_TEXT}
                    </a>
                </li>
                <li>
                    <a href="${VALID_LINK_2}">
                        Secondary Reference Materials for Infrastructure
                    </a>
                </li>
                <li>
                    <a href="${ANCHOR_LINK}">
                        Jump to Legal Disclaimers Section
                    </a>
                </li>
            </ul>

            <hr>

            <p ${ID_ATTR_2}>
                Questions? <a href="mailto:support-engine-${i}@example.com">Contact Support Team</a>
            </p>
            <p>
                <a href="https://www.google.com/search?q=unsubscribe&var=${UUID}">Unsubscribe Safely</a> | ${PRIVACY_SECTION}
            </p>
            
            <div style="margin-top: 20px; border-top: 1px dashed #ccc; padding-top: 10px; font-size: 11px; color: #666;">
                ${DISCLAIMER_SECTION}
            </div>
        </td>
    </tr>
</table>

<div ${FOOTER_ID}>
    <p style="font-size:10px; color:#999;">Document Instance Track Profile ID: ${UUID}</p>
</div>

</body>
</html>
EOF

done

echo "Done! 5 unique documents matching multi-rule profiles successfully prepared."