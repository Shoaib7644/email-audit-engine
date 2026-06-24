#!/bin/bash

INPUT_DIR="./input"
mkdir -p "$INPUT_DIR"

echo "Generating 100 highly diverse HTML files with randomized audit issues..."

for i in {1..100}
do
    UUID=$(uuidgen 2>/dev/null || echo "ID-$RANDOM-$i")
    TIMESTAMP=$(date +"%Y-%m-%dT%H:%M:%S")
    FILE_NAME="$INPUT_DIR/enterprise-newsletter_test_run_${i}.html"

    # --- RANDOMIZE ISSUES ---
    # 0 = No/Clean, 1 = Inject Defect
    FAIL_ACCESSIBILITY=$((RANDOM % 2))
    FAIL_ALT_TEXT=$((RANDOM % 2))
    FAIL_BROKEN_ANCHOR=$((RANDOM % 2))
    FAIL_DUPLICATE_ID=$((RANDOM % 2))
    FAIL_LINK_TEXT=$((RANDOM % 2))
    FAIL_LINK_VALIDATION=$((RANDOM % 2))
    FAIL_HEADING_HIERARCHY=$((RANDOM % 2))
    FAIL_CONTENT=$((RANDOM % 2))

    # Occasionally force a perfectly clean file (1 in 5 chance)
    if [ $((i % 5)) -eq 0 ]; then
        FAIL_ACCESSIBILITY=0; FAIL_ALT_TEXT=0; FAIL_BROKEN_ANCHOR=0; FAIL_DUPLICATE_ID=0
        FAIL_LINK_TEXT=0; FAIL_LINK_VALIDATION=0; FAIL_HEADING_HIERARCHY=0; FAIL_CONTENT=0
    fi

    # --- CONSTRUCT CONDITIONAL HTML ELEMENTS ---
    
    # 1. Accessibility (Missing HTML lang attribute or bad contrast styling)
    HTML_TAG="<html lang=\"en\">"
    BODY_STYLE="font-family: Arial, sans-serif; color: #333;"
    if [ $FAIL_ACCESSIBILITY -eq 1 ]; then
        HTML_TAG="<html>" # Fails Axe core lang check
        BODY_STYLE="font-family: Arial, sans-serif; color: #E0E0E0; background-color: #FFF;" # Poor contrast
    fi

    # 2. Heading Hierarchy
    HEADING_STRUCTURE="<h1>Enterprise Newsletter #${i}</h1>\n<h2>Featured Highlights</h2>"
    if [ $FAIL_HEADING_HIERARCHY -eq 1 ]; then
        HEADING_STRUCTURE="<h1>Enterprise Newsletter #${i}</h1>\n<h3>Skipped Directly to H3</h3>" # Fails Hierarchy
    fi

    # 3. Duplicate IDs
    ID_ATTR_1="id=\"main-content-section\""
    ID_ATTR_2="id=\"footer-legal-notice\""
    if [ $FAIL_DUPLICATE_ID -eq 1 ]; then
        ID_ATTR_1="id=\"duplicate-element-id\""
        ID_ATTR_2="id=\"duplicate-element-id\"" # Fails Duplicate ID validation
    fi

    # 4. Alt Text Validation
    ALT_TEXT="alt=\"A corporate technology banner showing a cloud network architecture\""
    if [ $FAIL_ALT_TEXT -eq 1 ]; then
        ALT_TEXT="" # Fails missing alt text rule
    fi

    # 5. Link Text Validation
    READ_MORE_TEXT="Read our deep dive analysis on AI adoption metrics"
    if [ $FAIL_LINK_TEXT -eq 1 ]; then
        READ_MORE_TEXT="Click here" # Fails generic link text rule
    fi

    # 6. Link Validation (HTTP Status / Host resolution)
    # Using real active URLs for passing scenarios to avoid triggering the 404/UnknownHost tracking
    VALID_LINK_1="https://www.google.com"
    VALID_LINK_2="https://www.github.com"
    if [ $FAIL_LINK_VALIDATION -eq 1 ]; then
        VALID_LINK_1="https://broken-link-demo-${i}23456.com" # Fails UnknownHostException
        VALID_LINK_2="https://www.google.com/non-existent-page-404-error" # Fails 404 Status Check
    fi

    # 7. Broken Anchors (Fragments)
    ANCHOR_LINK="#footer"
    FOOTER_ID="id=\"footer\""
    if [ $FAIL_BROKEN_ANCHOR -eq 1 ]; then
        ANCHOR_LINK="#non-existent-html-snippet-id" # Fails Broken Anchor
    fi

    # 8. Content Validation placeholders
    CONTENT_BODY="Our core strategy this quarter focuses extensively on stabilizing cloud transformation targets."
    if [ $FAIL_CONTENT -eq 1 ]; then
        CONTENT_BODY="Lorem ipsum dolor sit up amet, dynamic placeholder string text [Insert Content Here]." # Fails placeholder content scan
    fi


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
                <a href="https://www.google.com/search?q=unsubscribe&var=${UUID}">Unsubscribe Safely</a>
            </p>
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

echo "Done! 100 unique documents matching multi-rule profiles successfully prepared."