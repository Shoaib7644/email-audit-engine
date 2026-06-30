#!/usr/bin/env bash
###############################################################################
# generate-test-emails.sh
#
# Deterministic enterprise regression-suite generator for an Email Audit
# Engine. Produces a fixed, reproducible set of HTML email fixtures under
# ./input/, each tagged with inline metadata describing which audit rules it
# is expected to PASS and which it is expected to FAIL, plus a single
# scenario-manifest.json describing the whole suite.
#
# This script is intentionally NOT randomized. Re-running it always produces
# byte-identical output (modulo any timestamp fields, which are fixed
# constants below rather than `date`/`$RANDOM`).
###############################################################################
set -euo pipefail

# -----------------------------------------------------------------------------
# Global, deterministic constants (no `date`, no `$RANDOM`)
# -----------------------------------------------------------------------------
readonly OUT_DIR="./input"
readonly MANIFEST_FILE="${OUT_DIR}/scenario-manifest.json"
readonly FIXED_TIMESTAMP="2026-01-15T09:30:00Z"
readonly CAMPAIGN_ID="CMP-2026-Q1-00042"
readonly COMPANY_NAME="Acxiom Global Holdings, Inc."
readonly COMPANY_ADDRESS="4720 Commerce Park Drive, Suite 300, Conway, AR 72032, USA"
readonly SUPPORT_EMAIL="support@acxiom-demo.com"
readonly PRIVACY_URL_OK="https://www.acxiom-demo.com/legal/privacy"
readonly PRIVACY_URL_404="https://www.acxiom-demo.com/legal/privacy-removed"
readonly PRIVACY_URL_DNS="https://privacy.does-not-exist-acxiom-demo.invalid/"
readonly VIEW_ONLINE_URL_OK="https://view.acxiom-demo.com/e/abc123"
readonly VIEW_ONLINE_URL_404="https://view.acxiom-demo.com/e/missing"
readonly UNSUB_URL="https://unsub.acxiom-demo.com/u/abc123?cid=${CAMPAIGN_ID}"

# Manifest accumulation (newline-delimited JSON objects, joined at the end)
MANIFEST_ENTRIES=()

# -----------------------------------------------------------------------------
# mkdir -p target dir, fresh each run for true reproducibility
# -----------------------------------------------------------------------------
rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

###############################################################################
# HELPER FUNCTIONS — reusable HTML building blocks
###############################################################################

# create_logo - returns an <img> logo block. $1=alt_mode (ok|missing|empty|decorative)
create_logo() {
    local alt_mode="${1:-ok}"
    case "${alt_mode}" in
        ok)         echo '<img src="https://cdn.acxiom-demo.com/logo.png" width="160" height="40" alt="Acxiom Global Holdings logo" style="display:block;border:0;">' ;;
        missing)    echo '<img src="https://cdn.acxiom-demo.com/logo.png" width="160" height="40" style="display:block;border:0;">' ;;
        empty)      echo '<img src="https://cdn.acxiom-demo.com/logo.png" width="160" height="40" alt="" style="display:block;border:0;">' ;;
        decorative) echo '<img src="https://cdn.acxiom-demo.com/divider.png" width="600" height="4" alt="" role="presentation" style="display:block;border:0;">' ;;
        broken404)  echo '<img src="https://cdn.acxiom-demo.com/logo-removed-404.png" width="160" height="40" alt="Acxiom Global Holdings logo" style="display:block;border:0;">' ;;
    esac
}

# create_header - top banner table row with logo. $1=alt_mode for logo
create_header() {
    local alt_mode="${1:-ok}"
    cat <<EOF
    <tr>
      <td style="padding:24px 32px;background:#0b2545;" align="left">
        $(create_logo "${alt_mode}")
      </td>
    </tr>
EOF
}

# create_privacy - privacy policy anchor. $1=mode (ok|404|dns|missing)
create_privacy() {
    local mode="${1:-ok}"
    case "${mode}" in
        ok)      echo "<a href=\"${PRIVACY_URL_OK}\" style=\"color:#9fb3c8;\">Privacy Policy</a>" ;;
        404)     echo "<a href=\"${PRIVACY_URL_404}\" style=\"color:#9fb3c8;\">Privacy Policy</a>" ;;
        dns)     echo "<a href=\"${PRIVACY_URL_DNS}\" style=\"color:#9fb3c8;\">Privacy Policy</a>" ;;
        missing) echo "" ;;
    esac
}

# create_disclaimer - legal/regulatory disclaimer block. $1=mode (ok|missing|hidden|commented)
create_disclaimer() {
    local mode="${1:-ok}"
    local text="This message and any attachments are confidential and intended solely for the addressee. Acxiom Global Holdings, Inc. is not liable for unauthorized use of this communication. Past performance is not indicative of future results."
    case "${mode}" in
        ok)        echo "<p style=\"font-size:11px;color:#7a8aa0;\">${text}</p>" ;;
        missing)   echo "" ;;
        hidden)    echo "<p style=\"font-size:11px;color:#7a8aa0;display:none;\">${text}</p>" ;;
        commented) echo "<!-- <p style=\"font-size:11px;color:#7a8aa0;\">${text}</p> -->" ;;
        multiple)  echo "<p style=\"font-size:11px;color:#7a8aa0;\">${text}</p><p style=\"font-size:11px;color:#7a8aa0;\">${text}</p>" ;;
    esac
}

# create_cta - a call-to-action button/link. $1=mode (ok|missing-href|hidden|opacity0|displaynone|visibilityhidden|empty|button-element|js-href|generic-text|empty-text)
create_cta() {
    local mode="${1:-ok}"
    case "${mode}" in
        ok)               echo '<a href="https://shop.acxiom-demo.com/spring-sale" style="background:#1d6fd6;color:#fff;padding:12px 28px;border-radius:4px;text-decoration:none;display:inline-block;">Shop the Spring Collection</a>' ;;
        missing-href)     echo '<a style="background:#1d6fd6;color:#fff;padding:12px 28px;border-radius:4px;text-decoration:none;display:inline-block;">Shop Now</a>' ;;
        hidden)           echo '<a href="https://shop.acxiom-demo.com/spring-sale" style="background:#1d6fd6;color:#fff;padding:12px 28px;border-radius:4px;text-decoration:none;display:inline-block;visibility:hidden;">Shop Now</a>' ;;
        opacity0)         echo '<a href="https://shop.acxiom-demo.com/spring-sale" style="background:#1d6fd6;color:#fff;padding:12px 28px;border-radius:4px;text-decoration:none;display:inline-block;opacity:0;">Shop Now</a>' ;;
        displaynone)      echo '<a href="https://shop.acxiom-demo.com/spring-sale" style="background:#1d6fd6;color:#fff;padding:12px 28px;border-radius:4px;text-decoration:none;display:none;">Shop Now</a>' ;;
        visibilityhidden) echo '<a href="https://shop.acxiom-demo.com/spring-sale" style="background:#1d6fd6;color:#fff;padding:12px 28px;border-radius:4px;text-decoration:none;display:inline-block;visibility:hidden;">Shop Now</a>' ;;
        empty)            echo '<a href="https://shop.acxiom-demo.com/spring-sale" style="background:#1d6fd6;color:#fff;padding:12px 28px;border-radius:4px;text-decoration:none;display:inline-block;"></a>' ;;
        button-element)   echo '<button style="background:#1d6fd6;color:#fff;padding:12px 28px;border-radius:4px;border:0;">Shop Now</button>' ;;
        js-href)          echo '<a href="javascript:void(0)" style="background:#1d6fd6;color:#fff;padding:12px 28px;border-radius:4px;text-decoration:none;display:inline-block;">Shop Now</a>' ;;
        generic-click)    echo '<a href="https://shop.acxiom-demo.com/spring-sale" style="color:#1d6fd6;">Click Here</a>' ;;
        generic-more)     echo '<a href="https://shop.acxiom-demo.com/spring-sale" style="color:#1d6fd6;">More</a>' ;;
        generic-readmore) echo '<a href="https://shop.acxiom-demo.com/spring-sale" style="color:#1d6fd6;">Read More</a>' ;;
        generic-open)     echo '<a href="https://shop.acxiom-demo.com/spring-sale" style="color:#1d6fd6;">Open</a>' ;;
        generic-visit)    echo '<a href="https://shop.acxiom-demo.com/spring-sale" style="color:#1d6fd6;">Visit</a>' ;;
        generic-go)       echo '<a href="https://shop.acxiom-demo.com/spring-sale" style="color:#1d6fd6;">Go</a>' ;;
        generic-learnmore) echo '<a href="https://shop.acxiom-demo.com/spring-sale" style="color:#1d6fd6;">Learn More</a>' ;;
        empty-text)       echo '<a href="https://shop.acxiom-demo.com/spring-sale" style="color:#1d6fd6;"></a>' ;;
    esac
}

# create_view_online - view-in-browser link. $1=mode (ok|404|missing|redirect|dns)
create_view_online() {
    local mode="${1:-ok}"
    case "${mode}" in
        ok)       echo "<a href=\"${VIEW_ONLINE_URL_OK}\" style=\"color:#9fb3c8;\">View this email in your browser</a>" ;;
        404)      echo "<a href=\"${VIEW_ONLINE_URL_404}\" style=\"color:#9fb3c8;\">View this email in your browser</a>" ;;
        redirect) echo "<a href=\"https://view.acxiom-demo.com/r/redirect-loop\" style=\"color:#9fb3c8;\">View this email in your browser</a>" ;;
        dns)      echo "<a href=\"https://view.does-not-exist-acxiom-demo.invalid/\" style=\"color:#9fb3c8;\">View this email in your browser</a>" ;;
        missing)  echo "" ;;
    esac
}

# create_footer - full enterprise footer: unsubscribe, privacy, address, social, disclaimer
# $1=privacy_mode $2=view_online_mode $3=disclaimer_mode
create_footer() {
    local privacy_mode="${1:-ok}"
    local view_online_mode="${2:-ok}"
    local disclaimer_mode="${3:-ok}"
    cat <<EOF
    <tr>
      <td style="padding:24px 32px;background:#f4f6f9;border-top:1px solid #e2e8f0;" align="center">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0">
          <tr>
            <td align="center" style="padding-bottom:8px;">
              <a href="https://twitter.com/acxiomdemo" style="margin:0 6px;">Twitter</a>
              <a href="https://linkedin.com/company/acxiomdemo" style="margin:0 6px;">LinkedIn</a>
              <a href="https://facebook.com/acxiomdemo" style="margin:0 6px;">Facebook</a>
            </td>
          </tr>
          <tr>
            <td align="center" style="font-size:12px;color:#475569;padding-bottom:6px;">
              ${COMPANY_NAME}<br>
              ${COMPANY_ADDRESS}
            </td>
          </tr>
          <tr>
            <td align="center" style="font-size:12px;padding-bottom:6px;">
              <a href="${UNSUB_URL}" style="color:#9fb3c8;">Unsubscribe</a> &nbsp;|&nbsp;
              <a href="https://prefs.acxiom-demo.com/p/abc123" style="color:#9fb3c8;">Email Preferences</a> &nbsp;|&nbsp;
              $(create_privacy "${privacy_mode}") &nbsp;|&nbsp;
              $(create_view_online "${view_online_mode}")
            </td>
          </tr>
          <tr>
            <td align="center" style="font-size:11px;color:#94a3b8;padding-bottom:6px;">
              Contact us: <a href="mailto:${SUPPORT_EMAIL}" style="color:#9fb3c8;">${SUPPORT_EMAIL}</a>
            </td>
          </tr>
          <tr>
            <td align="center">
              $(create_disclaimer "${disclaimer_mode}")
            </td>
          </tr>
        </table>
      </td>
    </tr>
EOF
}

# create_layout - main body content table row. $1=layout_style $2=body_extra_html
create_layout() {
    local style="${1:-single-column}"
    local extra="${2:-}"
    case "${style}" in
        single-column)
            cat <<EOF
    <tr>
      <td style="padding:32px;">
        <h1 style="color:#0b2545;font-size:24px;">Spring Collection Has Arrived</h1>
        <p style="color:#334155;font-size:15px;line-height:1.5;">We've refreshed our catalog with new arrivals curated for the season. Explore styles handpicked by our merchandising team.</p>
        ${extra}
      </td>
    </tr>
EOF
            ;;
        two-column)
            cat <<EOF
    <tr>
      <td style="padding:32px;">
        <h1 style="color:#0b2545;font-size:24px;">This Week's Highlights</h1>
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0">
          <tr>
            <td width="50%" valign="top" style="padding-right:16px;">
              <h2 style="font-size:18px;color:#0b2545;">Feature One</h2>
              <p style="color:#334155;font-size:14px;">Our newest integration helps teams move faster across every channel.</p>
            </td>
            <td width="50%" valign="top" style="padding-left:16px;">
              <h2 style="font-size:18px;color:#0b2545;">Feature Two</h2>
              <p style="color:#334155;font-size:14px;">Real-time reporting now available across every dashboard view.</p>
            </td>
          </tr>
        </table>
        ${extra}
      </td>
    </tr>
EOF
            ;;
        hero-image)
            cat <<EOF
    <tr>
      <td style="padding:0;">
        <img src="https://cdn.acxiom-demo.com/hero-spring.jpg" width="600" height="280" alt="Models wearing the new spring collection outdoors" style="display:block;width:100%;border:0;">
      </td>
    </tr>
    <tr>
      <td style="padding:32px;">
        <h1 style="color:#0b2545;font-size:24px;">New Season, New Styles</h1>
        <p style="color:#334155;font-size:15px;line-height:1.5;">Discover pieces built for warmer days ahead.</p>
        ${extra}
      </td>
    </tr>
EOF
            ;;
        cards)
            cat <<EOF
    <tr>
      <td style="padding:32px;">
        <h1 style="color:#0b2545;font-size:24px;">Featured Products</h1>
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0">
          <tr>
            <td width="33%" style="padding:8px;background:#f8fafc;border-radius:6px;" valign="top">
              <img src="https://cdn.acxiom-demo.com/product-a.jpg" width="160" height="120" alt="Linen blazer in sand colour" style="display:block;border:0;width:100%;">
              <p style="font-size:13px;color:#334155;">Linen Blazer — \$129</p>
            </td>
            <td width="33%" style="padding:8px;background:#f8fafc;border-radius:6px;" valign="top">
              <img src="https://cdn.acxiom-demo.com/product-b.jpg" width="160" height="120" alt="Canvas tote bag" style="display:block;border:0;width:100%;">
              <p style="font-size:13px;color:#334155;">Canvas Tote — \$48</p>
            </td>
            <td width="33%" style="padding:8px;background:#f8fafc;border-radius:6px;" valign="top">
              <img src="https://cdn.acxiom-demo.com/product-c.jpg" width="160" height="120" alt="Suede loafers in tan" style="display:block;border:0;width:100%;">
              <p style="font-size:13px;color:#334155;">Suede Loafers — \$96</p>
            </td>
          </tr>
        </table>
        ${extra}
      </td>
    </tr>
EOF
            ;;
        feature-grid)
            cat <<EOF
    <tr>
      <td style="padding:32px;">
        <h1 style="color:#0b2545;font-size:24px;">Why Teams Choose Us</h1>
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0">
          <tr>
            <td width="50%" style="padding:12px;" valign="top"><h2 style="font-size:16px;">Reliability</h2><p style="font-size:13px;color:#334155;">99.99% platform uptime, audited quarterly.</p></td>
            <td width="50%" style="padding:12px;" valign="top"><h2 style="font-size:16px;">Security</h2><p style="font-size:13px;color:#334155;">SOC 2 Type II certified infrastructure.</p></td>
          </tr>
          <tr>
            <td width="50%" style="padding:12px;" valign="top"><h2 style="font-size:16px;">Support</h2><p style="font-size:13px;color:#334155;">24/7 enterprise support with dedicated CSM.</p></td>
            <td width="50%" style="padding:12px;" valign="top"><h2 style="font-size:16px;">Scale</h2><p style="font-size:13px;color:#334155;">Built to handle billions of monthly events.</p></td>
          </tr>
        </table>
        ${extra}
      </td>
    </tr>
EOF
            ;;
        sidebar)
            cat <<EOF
    <tr>
      <td style="padding:32px;">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0">
          <tr>
            <td width="70%" valign="top" style="padding-right:16px;">
              <h1 style="color:#0b2545;font-size:22px;">Quarterly Release Notes</h1>
              <p style="color:#334155;font-size:14px;">This release focuses on performance and reliability across the platform.</p>
            </td>
            <td width="30%" valign="top" style="background:#f8fafc;padding:12px;border-radius:6px;">
              <h2 style="font-size:14px;">In This Issue</h2>
              <p style="font-size:12px;color:#475569;">Performance, Security, Roadmap</p>
            </td>
          </tr>
        </table>
        ${extra}
      </td>
    </tr>
EOF
            ;;
        responsive-table)
            cat <<EOF
    <tr>
      <td style="padding:32px;" class="stack-on-mobile">
        <h1 style="color:#0b2545;font-size:24px;">Your Monthly Statement</h1>
        <table role="presentation" width="100%" cellpadding="8" cellspacing="0" style="border-collapse:collapse;">
          <tr style="background:#f1f5f9;"><td style="font-size:12px;font-weight:bold;">Item</td><td style="font-size:12px;font-weight:bold;">Amount</td></tr>
          <tr><td style="font-size:12px;border-bottom:1px solid #e2e8f0;">Subscription Fee</td><td style="font-size:12px;border-bottom:1px solid #e2e8f0;">\$49.00</td></tr>
          <tr><td style="font-size:12px;border-bottom:1px solid #e2e8f0;">Usage Overage</td><td style="font-size:12px;border-bottom:1px solid #e2e8f0;">\$12.40</td></tr>
        </table>
        ${extra}
      </td>
    </tr>
EOF
            ;;
        nested-tables)
            cat <<EOF
    <tr>
      <td style="padding:32px;">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0">
          <tr><td>
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0">
              <tr><td>
                <h1 style="color:#0b2545;font-size:22px;">Order Confirmation</h1>
                <table role="presentation" width="100%" cellpadding="6" cellspacing="0">
                  <tr><td style="font-size:13px;">Order #88291 confirmed and processing.</td></tr>
                </table>
              </td></tr>
            </table>
          </td></tr>
        </table>
        ${extra}
      </td>
    </tr>
EOF
            ;;
        outlook-vml)
            cat <<EOF
    <tr>
      <td style="padding:32px;">
        <h1 style="color:#0b2545;font-size:24px;">Welcome Aboard</h1>
        <p style="color:#334155;font-size:14px;">Get started with your new account below.</p>
        <!--[if mso]>
        <v:roundrect xmlns:v="urn:schemas-microsoft-com:vml" href="https://app.acxiom-demo.com/start" style="height:44px;v-text-anchor:middle;width:220px;" arcsize="10%" fillcolor="#1d6fd6">
          <center style="color:#ffffff;font-size:15px;font-weight:bold;">Get Started</center>
        </v:roundrect>
        <![endif]-->
        <!--[if !mso]><!-- -->
        <a href="https://app.acxiom-demo.com/start" style="background:#1d6fd6;color:#fff;padding:12px 28px;border-radius:4px;text-decoration:none;display:inline-block;">Get Started</a>
        <!--<![endif]-->
        ${extra}
      </td>
    </tr>
EOF
            ;;
    esac
}

###############################################################################
# INJECTOR FUNCTIONS — failure-specific HTML fragments inserted into ${extra}
###############################################################################

inject_alt_failure() {
    local mode="${1:-missing}"
    case "${mode}" in
        missing)    echo '<img src="https://cdn.acxiom-demo.com/promo.jpg" width="300" height="200" style="display:block;border:0;">' ;;
        empty)      echo '<img src="https://cdn.acxiom-demo.com/promo.jpg" width="300" height="200" alt="" style="display:block;border:0;">' ;;
        duplicate)  echo '<img src="https://cdn.acxiom-demo.com/promo-a.jpg" width="150" height="100" alt="Spring sale banner" style="display:block;border:0;"><img src="https://cdn.acxiom-demo.com/promo-b.jpg" width="150" height="100" alt="Spring sale banner" style="display:block;border:0;">' ;;
        decorative) echo '<img src="https://cdn.acxiom-demo.com/spacer.gif" width="600" height="1" alt="" role="presentation" style="display:block;border:0;">' ;;
    esac
}

inject_duplicate_ids() {
    echo '<div id="promo-block">First promo block</div><div id="promo-block">Duplicate-id promo block</div><div id="promo-block">A third duplicate-id block</div>'
}

inject_heading_failure() {
    local mode="${1:-missing-h1}"
    case "${mode}" in
        missing-h1)   echo '<h2 style="font-size:20px;">Section heading without a preceding H1</h2><p style="font-size:14px;">Body copy follows a heading-less page.</p>' ;;
        skipped)      echo '<h1 style="font-size:24px;">Main Title</h1><h4 style="font-size:14px;">Skipped from H1 directly to H4</h4>' ;;
        multiple-h1)  echo '<h1 style="font-size:24px;">First H1</h1><h1 style="font-size:24px;">Second, duplicate H1</h1>' ;;
        h3-after-h1)  echo '<h1 style="font-size:24px;">Main Title</h1><h3 style="font-size:16px;">Direct H3 child, skipping H2</h3>' ;;
        h5-first)     echo '<h5 style="font-size:12px;">Document opens at H5 with no H1</h5>' ;;
        only-divs)    echo '<div style="font-size:24px;font-weight:bold;">Looks like a heading but is only a styled div</div>' ;;
    esac
}

inject_link_failure() {
    local mode="${1:-404}"
    case "${mode}" in
        200)        echo '<a href="https://shop.acxiom-demo.com/in-stock">Shop In-Stock Items</a>' ;;
        301)        echo '<a href="https://shop.acxiom-demo.com/old-redirect-301">Shop Legacy Catalog</a>' ;;
        302)        echo '<a href="https://shop.acxiom-demo.com/seasonal-302">Shop Seasonal Picks</a>' ;;
        307)        echo '<a href="https://shop.acxiom-demo.com/temp-307">Shop Temporary Offer</a>' ;;
        308)        echo '<a href="https://shop.acxiom-demo.com/perm-308">Shop Permanent Offer</a>' ;;
        401)        echo '<a href="https://portal.acxiom-demo.com/account">View Your Account</a>' ;;
        403)        echo '<a href="https://internal.acxiom-demo.com/restricted">Internal Resources</a>' ;;
        404)        echo '<a href="https://shop.acxiom-demo.com/removed-product-404">Shop the Discontinued Line</a>' ;;
        410)        echo '<a href="https://shop.acxiom-demo.com/gone-410">Shop the Archived Sale</a>' ;;
        500)        echo '<a href="https://shop.acxiom-demo.com/checkout-500">Proceed to Checkout</a>' ;;
        dns)        echo '<a href="https://shop.does-not-exist-acxiom-demo.invalid/">Shop Our New Site</a>' ;;
        timeout)    echo '<a href="https://slow.acxiom-demo.com/never-responds">Shop Flash Sale</a>' ;;
        ssl)        echo '<a href="https://expired-cert.acxiom-demo.invalid/">Shop Secure Checkout</a>' ;;
        relative)   echo '<a href="/catalog/spring">Shop Spring Catalog</a>' ;;
        js-void)    echo '<a href="javascript:void(0)">Open Product Modal</a>' ;;
        ftp)        echo '<a href="ftp://files.acxiom-demo.com/catalog.pdf">Download Catalog (FTP)</a>' ;;
        mailto)     echo '<a href="mailto:sales@acxiom-demo.com">Email Our Sales Team</a>' ;;
        tel)        echo '<a href="tel:+18005551234">Call Customer Support</a>' ;;
        localhost)  echo '<a href="http://localhost:3000/preview">Preview Draft (dev link left in)</a>' ;;
        malformed)  echo '<a href="htp:/broken-url..acxiom demo .com">Shop Now</a>' ;;
    esac
}

inject_anchor_failure() {
    local mode="${1:-missing-target}"
    case "${mode}" in
        missing-target) echo '<a href="#section-pricing">Jump to Pricing</a><p>Note: no element on this page has id="section-pricing".</p>' ;;
        duplicate-target) echo '<a href="#section-faq">Jump to FAQ</a><div id="section-faq">FAQ block one</div><div id="section-faq">FAQ block two (duplicate id)</div>' ;;
        wrong-id)       echo '<a href="#section-Faq">Jump to FAQ (case mismatch)</a><div id="section-faq">FAQ block, id is lowercase only</div>' ;;
        nested)         echo '<a href="#outer"><span><a href="#inner">Nested anchor inside anchor</a></span></a><div id="outer">Outer target</div>' ;;
    esac
}

inject_content_failure() {
    local mode="${1:-lorem}"
    case "${mode}" in
        lorem)        echo '<p>Lorem ipsum dolor sit amet, consectetur adipiscing elit. Placeholder copy left in by the design team.</p>' ;;
        todo)         echo '<p>TODO: insert finalized legal copy here before this campaign ships.</p>' ;;
        xxxx)         echo '<p>Subject line A/B variant: XXXX — replace before send.</p>' ;;
        replace-me)   echo '<p>[Replace Me] with the customer first name token before sending.</p>' ;;
        insert)       echo '<p>{{Insert Content}} — marketing copy pending approval.</p>' ;;
        coming-soon)  echo '<p>Coming Soon — this section is still under construction.</p>' ;;
        dummy)        echo '<p>Dummy text used for layout testing purposes only, not for production use.</p>' ;;
    esac
}

inject_accessibility_failure() {
    local mode="${1:-lang}"
    case "${mode}" in
        lang)      echo "MISSING_LANG" ;;
        invalid-lang) echo "INVALID_LANG" ;;
        contrast)  echo '<p style="color:#d8d8d8;background:#ffffff;font-size:13px;">Low-contrast disclaimer text intended to fail WCAG AA contrast checks.</p>' ;;
        landmark)  echo "NO_LANDMARK" ;;
        main)      echo "NO_MAIN" ;;
        aria)      echo '<div role="button">Clickable div with no accessible name (missing aria-label)</div>' ;;
        scope)     echo '<table role="presentation"><tr><th>Plan</th><th>Price</th></tr><tr><td>Pro</td><td>$49/mo</td></tr></table>' ;;
    esac
}

inject_image_failure() {
    local mode="${1:-404}"
    case "${mode}" in
        404)            echo '<img src="https://cdn.acxiom-demo.com/missing-image-404.jpg" width="300" height="200" alt="Product photo that returns a 404" style="display:block;border:0;">' ;;
        missing-width)  echo '<img src="https://cdn.acxiom-demo.com/promo.jpg" height="200" alt="Promo banner missing explicit width" style="display:block;border:0;">' ;;
        missing-height) echo '<img src="https://cdn.acxiom-demo.com/promo.jpg" width="300" alt="Promo banner missing explicit height" style="display:block;border:0;">' ;;
        broken)         echo '<img src="https://cdn.acxiom-demo.com/corrupted-file.jpg" width="300" height="200" alt="Corrupted image file" style="display:block;border:0;">' ;;
        cid)            echo '<img src="cid:promo-banner-001" width="300" height="200" alt="Inline CID-referenced promo banner" style="display:block;border:0;">' ;;
        base64)         echo '<img src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBTAA7" width="1" height="1" alt="Tracking pixel" style="display:block;border:0;">' ;;
        svg)            echo '<img src="https://cdn.acxiom-demo.com/icon.svg" width="32" height="32" alt="Vector icon illustrating shipping" style="display:block;border:0;">' ;;
        gif)            echo '<img src="https://cdn.acxiom-demo.com/animated.gif" width="300" height="200" alt="Animated countdown timer graphic" style="display:block;border:0;">' ;;
        webp)           echo '<img src="https://cdn.acxiom-demo.com/photo.webp" width="300" height="200" alt="Product photo in WebP format" style="display:block;border:0;">' ;;
    esac
}

inject_html_failure() {
    local mode="${1:-unclosed}"
    case "${mode}" in
        unclosed)        echo '<p style="font-size:14px;">This paragraph tag is never closed and bleeds into the next block.<div>Nested div inside an unclosed paragraph</div>' ;;
        broken-nesting)  echo '<b><i>Bold-italic text with </b>mismatched closing order</i>' ;;
        invalid-table)   echo '<table><tr><td>Cell without a closing row or table tag' ;;
        nested-forms)    echo '<form action="/subscribe"><input type="email" name="email"><form action="/inner"><input type="text" name="inner"></form></form>' ;;
    esac
}

###############################################################################
# CORE EMAIL ASSEMBLY
###############################################################################

# build_html - assembles a complete HTML document.
# Args: title, lang_mode(ok|missing|invalid), header_alt, layout_style, body_extra,
#       privacy_mode, view_online_mode, disclaimer_mode, landmark_mode(ok|missing-landmark|missing-main)
build_html() {
    local title="$1" lang_mode="$2" header_alt="$3" layout_style="$4" body_extra="$5"
    local privacy_mode="$6" view_online_mode="$7" disclaimer_mode="$8" landmark_mode="${9:-ok}"

    local lang_attr=' lang="en"'
    [ "${lang_mode}" = "missing" ] && lang_attr=""
    [ "${lang_mode}" = "invalid" ] && lang_attr=' lang="zz-ZZ-not-a-real-locale"'

    local open_main close_main
    case "${landmark_mode}" in
        ok)              open_main='<main role="main">'; close_main='</main>' ;;
        no-landmark)     open_main='<div>'; close_main='</div>' ;;
        no-main)         open_main='<div role="main">'; close_main='</div>' ;;
        *)               open_main='<main role="main">'; close_main='</main>' ;;
    esac

    # Resolve sentinel accessibility tokens emitted by inject_accessibility_failure
    if [ "${body_extra}" = "MISSING_LANG" ]; then lang_attr=""; body_extra=""; fi
    if [ "${body_extra}" = "INVALID_LANG" ]; then lang_attr=' lang="zz-ZZ-not-a-real-locale"'; body_extra=""; fi
    if [ "${body_extra}" = "NO_LANDMARK" ]; then open_main='<div>'; close_main='</div>'; body_extra=""; fi
    if [ "${body_extra}" = "NO_MAIN" ]; then open_main='<div role="main">'; close_main='</div>'; body_extra=""; fi

    cat <<EOF
<!DOCTYPE html>
<html${lang_attr}>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${title}</title>
<style>
  body { margin:0; padding:0; background:#eef2f6; font-family:Arial, Helvetica, sans-serif; }
  @media (prefers-color-scheme: dark) {
    body { background:#0f172a !important; }
  }
  @media screen and (max-width: 600px) {
    .stack-on-mobile { display:block !important; width:100% !important; }
  }
</style>
</head>
<body>
${open_main}
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#eef2f6;">
  <tr>
    <td align="center">
      <table role="presentation" width="600" cellpadding="0" cellspacing="0" style="background:#ffffff;margin:24px 0;">
$(create_header "${header_alt}")
$(create_layout "${layout_style}" "${body_extra}")
$(create_footer "${privacy_mode}" "${view_online_mode}" "${disclaimer_mode}")
      </table>
    </td>
  </tr>
</table>
${close_main}
<!-- tracking-pixel: cid=${CAMPAIGN_ID} ts=${FIXED_TIMESTAMP} -->
</body>
</html>
EOF
}

# metadata_comment - produces the inline HTML comment block required for every scenario.
metadata_comment() {
    local id="$1" name="$2" purpose="$3" pass_rules="$4" fail_rules="$5" result="$6"
    cat <<EOF
<!--
Scenario ID: ${id}
Scenario Name: ${name}
Purpose: ${purpose}
Expected Pass Rules: ${pass_rules}
Expected Fail Rules: ${fail_rules}
Expected Result: ${result}
-->
EOF
}

# write_scenario - writes one HTML file + records one manifest entry.
# Args: filename, scenario_name, purpose, pass_rules, fail_rules, overall_status, html_body
write_scenario() {
    local filename="$1" name="$2" purpose="$3" pass_rules="$4" fail_rules="$5" status="$6" body="$7"
    local path="${OUT_DIR}/${filename}"

    {
        metadata_comment "${filename%.html}" "${name}" "${purpose}" "${pass_rules}" "${fail_rules}" "${status}"
        printf '%s\n' "${body}"
    } > "${path}"

    MANIFEST_ENTRIES+=("$(cat <<EOF
  {
    "scenarioId": "${filename%.html}",
    "filename": "${filename}",
    "purpose": $(json_escape "${purpose}"),
    "expectedPassRules": [$(rules_to_json_array "${pass_rules}")],
    "expectedFailRules": [$(rules_to_json_array "${fail_rules}")],
    "expectedOverallStatus": "${status}"
  }
EOF
)")
}

# json_escape - wraps a plain string in JSON double quotes, escaping internal quotes.
json_escape() {
    local s="$1"
    s="${s//\\/\\\\}"
    s="${s//\"/\\\"}"
    printf '"%s"' "${s}"
}

# rules_to_json_array - converts a comma-separated rule list into JSON string items.
rules_to_json_array() {
    local list="$1"
    [ -z "${list}" ] && return 0
    local IFS=','
    local -a parts=(${list})
    local out=""
    local first=1
    for p in "${parts[@]}"; do
        p="$(echo "${p}" | sed 's/^ *//;s/ *$//')"
        [ -z "${p}" ] && continue
        if [ "${first}" -eq 1 ]; then out="\"${p}\""; first=0; else out="${out}, \"${p}\""; fi
    done
    printf '%s' "${out}"
}

###############################################################################
# SCENARIO GENERATION
###############################################################################

ALL_PASS_RULES="LINK_VALIDATION,VIEW_ONLINE_LINK,LINK_TEXT_VALIDATION,CTA_VALIDATION,PRIVACY_LINK,BROKEN_ANCHOR,DUPLICATE_ID,CONTENT_VALIDATION,ALT_TEXT,HEADING_STRUCTURE,ACCESSIBILITY,HTML_VALIDITY"

echo "Generating golden (perfect) emails..."

write_scenario "001-perfect-enterprise.html" "Perfect Enterprise Email" \
  "Golden baseline: full enterprise newsletter layout that must pass every rule." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "Q1 Enterprise Update" ok ok single-column "$(create_cta ok)" ok ok ok ok)"

write_scenario "002-perfect-newsletter.html" "Perfect Newsletter" \
  "Golden baseline newsletter using a two-column layout." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "Weekly Product Newsletter" ok ok two-column "$(create_cta ok)" ok ok ok ok)"

write_scenario "003-perfect-promotional.html" "Perfect Promotional Email" \
  "Golden baseline promotional email with hero image layout." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "Spring Sale Is Live" ok ok hero-image "$(create_cta ok)" ok ok ok ok)"

write_scenario "004-perfect-transactional.html" "Perfect Transactional Email" \
  "Golden baseline transactional (order confirmation) email." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "Your Order Has Shipped" ok ok nested-tables "$(create_cta ok)" ok ok ok ok)"

echo "Generating ALT TEXT failure scenarios..."

write_scenario "010-missing-alt.html" "Missing Alt Attribute" \
  "Image with no alt attribute at all." \
  "LINK_VALIDATION,PRIVACY_LINK" "ALT_TEXT" "FAIL" \
  "$(build_html "Missing Alt Test" ok ok single-column "$(inject_alt_failure missing)" ok ok ok ok)"

write_scenario "011-empty-alt.html" "Empty Alt Attribute" \
  "Image with alt=\"\" on a non-decorative content image." \
  "LINK_VALIDATION,PRIVACY_LINK" "ALT_TEXT" "FAIL" \
  "$(build_html "Empty Alt Test" ok ok single-column "$(inject_alt_failure empty)" ok ok ok ok)"

write_scenario "012-decorative-alt.html" "Decorative Image Alt" \
  "Decorative spacer image correctly using alt=\"\" with role=presentation; should PASS alt-text rule." \
  "ALT_TEXT,LINK_VALIDATION,PRIVACY_LINK" "" "PASS" \
  "$(build_html "Decorative Alt Test" ok ok single-column "$(inject_alt_failure decorative)" ok ok ok ok)"

write_scenario "013-duplicate-alt.html" "Duplicate Alt Text" \
  "Two distinct images sharing identical, non-descriptive alt text." \
  "LINK_VALIDATION,PRIVACY_LINK" "ALT_TEXT" "FAIL" \
  "$(build_html "Duplicate Alt Test" ok ok single-column "$(inject_alt_failure duplicate)" ok ok ok ok)"

echo "Generating BROKEN ANCHOR failure scenarios..."

write_scenario "020-broken-anchor.html" "Broken Anchor - General" \
  "Fragment link pointing at a non-existent target id." \
  "LINK_VALIDATION,PRIVACY_LINK" "BROKEN_ANCHOR" "FAIL" \
  "$(build_html "Broken Anchor Test" ok ok single-column "$(inject_anchor_failure missing-target)" ok ok ok ok)"

write_scenario "021-missing-anchor-target.html" "Missing Anchor Target" \
  "Same as 020 but isolated as its own dedicated regression case." \
  "LINK_VALIDATION,PRIVACY_LINK" "BROKEN_ANCHOR" "FAIL" \
  "$(build_html "Missing Anchor Target Test" ok ok single-column "$(inject_anchor_failure missing-target)" ok ok ok ok)"

write_scenario "022-duplicate-anchor-target.html" "Duplicate Anchor Target" \
  "Fragment link target id exists twice on the page." \
  "LINK_VALIDATION,PRIVACY_LINK" "BROKEN_ANCHOR,DUPLICATE_ID" "FAIL" \
  "$(build_html "Duplicate Anchor Target Test" ok ok single-column "$(inject_anchor_failure duplicate-target)" ok ok ok ok)"

write_scenario "023-wrong-id-anchor.html" "Anchor Case Mismatch" \
  "Fragment link uses different case than the actual target id (ids are case-sensitive)." \
  "LINK_VALIDATION,PRIVACY_LINK" "BROKEN_ANCHOR" "FAIL" \
  "$(build_html "Anchor Case Mismatch Test" ok ok single-column "$(inject_anchor_failure wrong-id)" ok ok ok ok)"

write_scenario "024-nested-anchor.html" "Nested Anchor Elements" \
  "Anchor element nested inside another anchor element — invalid HTML structure." \
  "LINK_VALIDATION,PRIVACY_LINK" "BROKEN_ANCHOR,HTML_VALIDITY" "FAIL" \
  "$(build_html "Nested Anchor Test" ok ok single-column "$(inject_anchor_failure nested)" ok ok ok ok)"

echo "Generating LINK VALIDATION scenarios..."

write_scenario "030-link-404.html" "Link Returns 404" \
  "Body link resolves to an HTTP 404 Not Found." \
  "PRIVACY_LINK,VIEW_ONLINE_LINK" "LINK_VALIDATION" "FAIL" \
  "$(build_html "Link 404 Test" ok ok single-column "$(inject_link_failure 404)" ok ok ok ok)"

write_scenario "031-link-timeout.html" "Link Times Out" \
  "Body link points at a host that never responds." \
  "PRIVACY_LINK,VIEW_ONLINE_LINK" "LINK_VALIDATION" "FAIL" \
  "$(build_html "Link Timeout Test" ok ok single-column "$(inject_link_failure timeout)" ok ok ok ok)"

write_scenario "032-link-dns.html" "Link DNS Failure" \
  "Body link points at a non-resolving hostname." \
  "PRIVACY_LINK,VIEW_ONLINE_LINK" "LINK_VALIDATION" "FAIL" \
  "$(build_html "Link DNS Test" ok ok single-column "$(inject_link_failure dns)" ok ok ok ok)"

write_scenario "033-link-500.html" "Link Returns 500" \
  "Body link resolves to an HTTP 500 server error." \
  "PRIVACY_LINK,VIEW_ONLINE_LINK" "LINK_VALIDATION" "FAIL" \
  "$(build_html "Link 500 Test" ok ok single-column "$(inject_link_failure 500)" ok ok ok ok)"

write_scenario "034-link-relative.html" "Relative URL Link" \
  "Body link uses a relative path instead of an absolute URL, which is invalid in email." \
  "PRIVACY_LINK,VIEW_ONLINE_LINK" "LINK_VALIDATION" "FAIL" \
  "$(build_html "Relative URL Test" ok ok single-column "$(inject_link_failure relative)" ok ok ok ok)"

write_scenario "035-link-malformed.html" "Malformed URL" \
  "Body link href contains a syntactically invalid URL." \
  "PRIVACY_LINK,VIEW_ONLINE_LINK" "LINK_VALIDATION" "FAIL" \
  "$(build_html "Malformed URL Test" ok ok single-column "$(inject_link_failure malformed)" ok ok ok ok)"

write_scenario "036-link-301.html" "Link 301 Redirect" \
  "Body link resolves via a permanent redirect; flagged for follow-up." \
  "PRIVACY_LINK,VIEW_ONLINE_LINK" "LINK_VALIDATION" "FAIL" \
  "$(build_html "Link 301 Test" ok ok single-column "$(inject_link_failure 301)" ok ok ok ok)"

write_scenario "037-link-302.html" "Link 302 Redirect" \
  "Body link resolves via a temporary redirect; flagged for follow-up." \
  "PRIVACY_LINK,VIEW_ONLINE_LINK" "LINK_VALIDATION" "FAIL" \
  "$(build_html "Link 302 Test" ok ok single-column "$(inject_link_failure 302)" ok ok ok ok)"

write_scenario "038-link-ssl.html" "Link SSL Failure" \
  "Body link points at a host with an invalid/expired TLS certificate." \
  "PRIVACY_LINK,VIEW_ONLINE_LINK" "LINK_VALIDATION" "FAIL" \
  "$(build_html "Link SSL Test" ok ok single-column "$(inject_link_failure ssl)" ok ok ok ok)"

write_scenario "039-link-localhost.html" "Leftover Localhost Link" \
  "Body link still points at a local development URL left in by mistake." \
  "PRIVACY_LINK,VIEW_ONLINE_LINK" "LINK_VALIDATION" "FAIL" \
  "$(build_html "Localhost Link Test" ok ok single-column "$(inject_link_failure localhost)" ok ok ok ok)"

echo "Generating LINK TEXT scenarios..."

write_scenario "040-generic-link-text.html" "Generic Link Text" \
  "Body link uses non-descriptive 'Click Here' anchor text." \
  "LINK_VALIDATION,PRIVACY_LINK" "LINK_TEXT_VALIDATION" "FAIL" \
  "$(build_html "Generic Link Text Test" ok ok single-column "$(inject_link_failure 200) $(create_cta generic-click)" ok ok ok ok)"

write_scenario "041-empty-link-text.html" "Empty Link Text" \
  "Body link has an href but no visible text." \
  "LINK_VALIDATION,PRIVACY_LINK" "LINK_TEXT_VALIDATION" "FAIL" \
  "$(build_html "Empty Link Text Test" ok ok single-column "$(create_cta empty-text)" ok ok ok ok)"

write_scenario "042-link-text-variants.html" "Generic Link Text Variants" \
  "Multiple low-quality anchor phrases (More, Read More, Open, Visit, Go, Learn More) in one page." \
  "LINK_VALIDATION,PRIVACY_LINK" "LINK_TEXT_VALIDATION" "FAIL" \
  "$(build_html "Link Text Variants Test" ok ok single-column "$(create_cta generic-more) $(create_cta generic-readmore) $(create_cta generic-open) $(create_cta generic-visit) $(create_cta generic-go) $(create_cta generic-learnmore)" ok ok ok ok)"

echo "Generating HEADING scenarios..."

write_scenario "050-missing-h1.html" "Missing H1" \
  "Page has no H1 element at all." \
  "LINK_VALIDATION,PRIVACY_LINK" "HEADING_STRUCTURE" "FAIL" \
  "$(build_html "Missing H1 Test" ok ok single-column "$(inject_heading_failure missing-h1)" ok ok ok ok)"

write_scenario "051-skipped-heading.html" "Skipped Heading Level" \
  "Heading order jumps directly from H1 to H4, skipping H2/H3." \
  "LINK_VALIDATION,PRIVACY_LINK" "HEADING_STRUCTURE" "FAIL" \
  "$(build_html "Skipped Heading Test" ok ok single-column "$(inject_heading_failure skipped)" ok ok ok ok)"

write_scenario "052-multiple-h1.html" "Multiple H1 Elements" \
  "Two H1 elements present on the same page." \
  "LINK_VALIDATION,PRIVACY_LINK" "HEADING_STRUCTURE" "FAIL" \
  "$(build_html "Multiple H1 Test" ok ok single-column "$(inject_heading_failure multiple-h1)" ok ok ok ok)"

write_scenario "053-h3-after-h1.html" "H3 Directly After H1" \
  "Heading order skips H2, going from H1 straight to H3." \
  "LINK_VALIDATION,PRIVACY_LINK" "HEADING_STRUCTURE" "FAIL" \
  "$(build_html "H3 After H1 Test" ok ok single-column "$(inject_heading_failure h3-after-h1)" ok ok ok ok)"

write_scenario "054-h5-first.html" "H5 As First Heading" \
  "Document's first and only heading is an H5, with no H1 present." \
  "LINK_VALIDATION,PRIVACY_LINK" "HEADING_STRUCTURE" "FAIL" \
  "$(build_html "H5 First Test" ok ok single-column "$(inject_heading_failure h5-first)" ok ok ok ok)"

write_scenario "055-only-divs.html" "Only Styled Divs, No Headings" \
  "Page uses styled <div> elements that look like headings but uses no real heading tags." \
  "LINK_VALIDATION,PRIVACY_LINK" "HEADING_STRUCTURE" "FAIL" \
  "$(build_html "Only Divs Test" ok ok single-column "$(inject_heading_failure only-divs)" ok ok ok ok)"

echo "Generating DUPLICATE ID scenario..."

write_scenario "060-duplicate-id.html" "Duplicate HTML IDs" \
  "Three elements share the same id attribute value." \
  "LINK_VALIDATION,PRIVACY_LINK" "DUPLICATE_ID" "FAIL" \
  "$(build_html "Duplicate ID Test" ok ok single-column "$(inject_duplicate_ids)" ok ok ok ok)"

echo "Generating CONTENT scenarios..."

write_scenario "070-content-placeholder.html" "Lorem Ipsum Placeholder Content" \
  "Body copy still contains Lorem Ipsum filler text." \
  "LINK_VALIDATION,PRIVACY_LINK" "CONTENT_VALIDATION" "FAIL" \
  "$(build_html "Content Placeholder Test" ok ok single-column "$(inject_content_failure lorem)" ok ok ok ok)"

write_scenario "071-content-template.html" "Un-replaced Template Tokens" \
  "Body copy contains un-replaced merge-field style tokens." \
  "LINK_VALIDATION,PRIVACY_LINK" "CONTENT_VALIDATION" "FAIL" \
  "$(build_html "Content Template Test" ok ok single-column "$(inject_content_failure insert)" ok ok ok ok)"

write_scenario "072-content-todo.html" "TODO Placeholder Left In" \
  "Body copy contains a literal TODO note intended for internal review only." \
  "LINK_VALIDATION,PRIVACY_LINK" "CONTENT_VALIDATION" "FAIL" \
  "$(build_html "Content TODO Test" ok ok single-column "$(inject_content_failure todo)" ok ok ok ok)"

write_scenario "073-content-comingsoon.html" "Coming Soon Placeholder" \
  "Body copy still reads 'Coming Soon' rather than finished content." \
  "LINK_VALIDATION,PRIVACY_LINK" "CONTENT_VALIDATION" "FAIL" \
  "$(build_html "Content Coming Soon Test" ok ok single-column "$(inject_content_failure coming-soon)" ok ok ok ok)"

echo "Generating PRIVACY scenarios..."

write_scenario "080-privacy-missing.html" "Privacy Link Missing" \
  "Footer omits the privacy policy link entirely." \
  "LINK_VALIDATION,VIEW_ONLINE_LINK" "PRIVACY_LINK" "FAIL" \
  "$(build_html "Privacy Missing Test" ok ok single-column "$(create_cta ok)" missing ok ok ok)"

write_scenario "081-privacy-404.html" "Privacy Link 404" \
  "Privacy policy link resolves to HTTP 404." \
  "LINK_VALIDATION,VIEW_ONLINE_LINK" "PRIVACY_LINK" "FAIL" \
  "$(build_html "Privacy 404 Test" ok ok single-column "$(create_cta ok)" 404 ok ok ok)"

write_scenario "082-privacy-dns.html" "Privacy Link DNS Failure" \
  "Privacy policy link points at a non-resolving hostname." \
  "LINK_VALIDATION,VIEW_ONLINE_LINK" "PRIVACY_LINK" "FAIL" \
  "$(build_html "Privacy DNS Test" ok ok single-column "$(create_cta ok)" dns ok ok ok)"

write_scenario "083-privacy-pass.html" "Privacy Link Valid" \
  "Privacy policy link resolves successfully — dedicated PASS regression case." \
  "PRIVACY_LINK,LINK_VALIDATION,VIEW_ONLINE_LINK" "" "PASS" \
  "$(build_html "Privacy Pass Test" ok ok single-column "$(create_cta ok)" ok ok ok ok)"

echo "Generating VIEW ONLINE scenarios..."

write_scenario "090-view-online-missing.html" "View Online Link Missing" \
  "Footer omits the 'view in browser' link entirely." \
  "LINK_VALIDATION,PRIVACY_LINK" "VIEW_ONLINE_LINK" "FAIL" \
  "$(build_html "View Online Missing Test" ok ok single-column "$(create_cta ok)" ok missing ok ok)"

write_scenario "091-view-online-404.html" "View Online Link 404" \
  "'View in browser' link resolves to HTTP 404." \
  "LINK_VALIDATION,PRIVACY_LINK" "VIEW_ONLINE_LINK" "FAIL" \
  "$(build_html "View Online 404 Test" ok ok single-column "$(create_cta ok)" ok 404 ok ok)"

write_scenario "092-view-online-redirect.html" "View Online Link Redirect Loop" \
  "'View in browser' link enters a redirect loop." \
  "LINK_VALIDATION,PRIVACY_LINK" "VIEW_ONLINE_LINK" "FAIL" \
  "$(build_html "View Online Redirect Test" ok ok single-column "$(create_cta ok)" ok redirect ok ok)"

write_scenario "093-view-online-pass.html" "View Online Link Valid" \
  "'View in browser' link resolves successfully — dedicated PASS regression case." \
  "VIEW_ONLINE_LINK,LINK_VALIDATION,PRIVACY_LINK" "" "PASS" \
  "$(build_html "View Online Pass Test" ok ok single-column "$(create_cta ok)" ok ok ok ok)"

echo "Generating DISCLAIMER scenarios..."

write_scenario "100-disclaimer-missing.html" "Disclaimer Missing" \
  "Mandatory legal disclaimer block is absent from the footer." \
  "LINK_VALIDATION,PRIVACY_LINK,VIEW_ONLINE_LINK" "CONTENT_VALIDATION" "FAIL" \
  "$(build_html "Disclaimer Missing Test" ok ok single-column "$(create_cta ok)" ok ok missing ok)"

write_scenario "101-disclaimer-pass.html" "Disclaimer Present" \
  "Mandatory legal disclaimer present and visible — dedicated PASS regression case." \
  "CONTENT_VALIDATION,LINK_VALIDATION,PRIVACY_LINK,VIEW_ONLINE_LINK" "" "PASS" \
  "$(build_html "Disclaimer Pass Test" ok ok single-column "$(create_cta ok)" ok ok ok ok)"

write_scenario "102-disclaimer-hidden.html" "Disclaimer Hidden via CSS" \
  "Disclaimer text is present in markup but hidden with display:none." \
  "LINK_VALIDATION,PRIVACY_LINK,VIEW_ONLINE_LINK" "CONTENT_VALIDATION" "FAIL" \
  "$(build_html "Disclaimer Hidden Test" ok ok single-column "$(create_cta ok)" ok ok hidden ok)"

write_scenario "103-disclaimer-commented.html" "Disclaimer Commented Out" \
  "Disclaimer markup exists only inside an HTML comment, never rendered." \
  "LINK_VALIDATION,PRIVACY_LINK,VIEW_ONLINE_LINK" "CONTENT_VALIDATION" "FAIL" \
  "$(build_html "Disclaimer Commented Test" ok ok single-column "$(create_cta ok)" ok ok commented ok)"

echo "Generating CTA scenarios..."

write_scenario "110-cta-missing-href.html" "CTA Missing Href" \
  "Primary CTA button has no href attribute." \
  "LINK_VALIDATION,PRIVACY_LINK" "CTA_VALIDATION" "FAIL" \
  "$(build_html "CTA Missing Href Test" ok ok single-column "$(create_cta missing-href)" ok ok ok ok)"

write_scenario "111-cta-hidden.html" "CTA Hidden via Visibility" \
  "Primary CTA is present but visibility:hidden, invisible to recipients." \
  "LINK_VALIDATION,PRIVACY_LINK" "CTA_VALIDATION" "FAIL" \
  "$(build_html "CTA Hidden Test" ok ok single-column "$(create_cta hidden)" ok ok ok ok)"

write_scenario "112-cta-empty.html" "CTA Empty Text" \
  "Primary CTA has a valid href but no visible label text." \
  "LINK_VALIDATION,PRIVACY_LINK" "CTA_VALIDATION,LINK_TEXT_VALIDATION" "FAIL" \
  "$(build_html "CTA Empty Test" ok ok single-column "$(create_cta empty)" ok ok ok ok)"

write_scenario "113-cta-pass.html" "CTA Valid" \
  "Primary CTA is fully valid — descriptive text, visible, working href." \
  "CTA_VALIDATION,LINK_VALIDATION,LINK_TEXT_VALIDATION,PRIVACY_LINK" "" "PASS" \
  "$(build_html "CTA Pass Test" ok ok single-column "$(create_cta ok)" ok ok ok ok)"

write_scenario "114-cta-opacity-zero.html" "CTA Opacity Zero" \
  "Primary CTA rendered with opacity:0, technically present but invisible." \
  "LINK_VALIDATION,PRIVACY_LINK" "CTA_VALIDATION" "FAIL" \
  "$(build_html "CTA Opacity Zero Test" ok ok single-column "$(create_cta opacity0)" ok ok ok ok)"

write_scenario "115-cta-display-none.html" "CTA Display None" \
  "Primary CTA rendered with display:none, removed from the visual layout." \
  "LINK_VALIDATION,PRIVACY_LINK" "CTA_VALIDATION" "FAIL" \
  "$(build_html "CTA Display None Test" ok ok single-column "$(create_cta displaynone)" ok ok ok ok)"

write_scenario "116-cta-button-element.html" "CTA As Button Element" \
  "CTA implemented as a non-anchor <button> with no client-side handler — clicking does nothing in email clients." \
  "LINK_VALIDATION,PRIVACY_LINK" "CTA_VALIDATION" "FAIL" \
  "$(build_html "CTA Button Element Test" ok ok single-column "$(create_cta button-element)" ok ok ok ok)"

write_scenario "117-cta-js-href.html" "CTA Javascript Href" \
  "CTA href uses a javascript: scheme instead of a real destination URL." \
  "LINK_VALIDATION,PRIVACY_LINK" "CTA_VALIDATION" "FAIL" \
  "$(build_html "CTA JS Href Test" ok ok single-column "$(create_cta js-href)" ok ok ok ok)"

echo "Generating ACCESSIBILITY scenarios..."

write_scenario "120-accessibility-lang.html" "Missing Lang Attribute" \
  "Root <html> element has no lang attribute." \
  "LINK_VALIDATION,PRIVACY_LINK" "ACCESSIBILITY" "FAIL" \
  "$(build_html "Accessibility Lang Test" ok ok single-column "$(inject_accessibility_failure lang)" ok ok ok ok)"

write_scenario "121-accessibility-color.html" "Low Contrast Text" \
  "Disclaimer-style text rendered with insufficient color contrast against its background." \
  "LINK_VALIDATION,PRIVACY_LINK" "ACCESSIBILITY" "FAIL" \
  "$(build_html "Accessibility Color Test" ok ok single-column "$(inject_accessibility_failure contrast)" ok ok ok ok)"

write_scenario "122-accessibility-landmark.html" "Missing Landmark Region" \
  "Page content is wrapped in a plain <div> instead of a semantic landmark." \
  "LINK_VALIDATION,PRIVACY_LINK" "ACCESSIBILITY" "FAIL" \
  "$(build_html "Accessibility Landmark Test" ok ok single-column "$(inject_accessibility_failure landmark)" ok ok ok no-landmark)"

write_scenario "123-accessibility-pass.html" "Accessibility Valid" \
  "Page includes lang attribute, landmark region, sufficient contrast, and ARIA labeling — dedicated PASS case." \
  "ACCESSIBILITY,LINK_VALIDATION,PRIVACY_LINK" "" "PASS" \
  "$(build_html "Accessibility Pass Test" ok ok single-column "$(create_cta ok)" ok ok ok ok)"

write_scenario "124-accessibility-main.html" "Missing Main Landmark" \
  "Page wraps content in a div[role=main] rather than a real <main> element." \
  "LINK_VALIDATION,PRIVACY_LINK" "ACCESSIBILITY" "FAIL" \
  "$(build_html "Accessibility Main Test" ok ok single-column "$(inject_accessibility_failure main)" ok ok ok no-main)"

write_scenario "125-accessibility-aria.html" "Missing ARIA Label" \
  "Interactive div with role=button has no accessible name." \
  "LINK_VALIDATION,PRIVACY_LINK" "ACCESSIBILITY" "FAIL" \
  "$(build_html "Accessibility ARIA Test" ok ok single-column "$(inject_accessibility_failure aria)" ok ok ok ok)"

write_scenario "126-accessibility-invalid-lang.html" "Invalid Lang Value" \
  "Root <html> lang attribute is set to a non-existent locale code." \
  "LINK_VALIDATION,PRIVACY_LINK" "ACCESSIBILITY" "FAIL" \
  "$(build_html "Accessibility Invalid Lang Test" invalid ok single-column "$(inject_accessibility_failure invalid-lang)" ok ok ok ok)"

echo "Generating IMAGE scenarios..."

write_scenario "130-image-missing-alt.html" "Image Missing Alt" \
  "Content image entirely lacking an alt attribute (duplicate of 010, scoped to IMAGE category)." \
  "LINK_VALIDATION,PRIVACY_LINK" "ALT_TEXT" "FAIL" \
  "$(build_html "Image Missing Alt Test" ok ok cards "$(inject_alt_failure missing)" ok ok ok ok)"

write_scenario "131-image-broken.html" "Broken Image File" \
  "Image src points at a corrupted/unreadable file." \
  "LINK_VALIDATION,PRIVACY_LINK" "LINK_VALIDATION" "FAIL" \
  "$(build_html "Image Broken Test" ok ok cards "$(inject_image_failure broken)" ok ok ok ok)"

write_scenario "132-image-pass.html" "Image Valid" \
  "Image has alt text, explicit width/height, and resolves successfully — dedicated PASS case." \
  "ALT_TEXT,LINK_VALIDATION,PRIVACY_LINK" "" "PASS" \
  "$(build_html "Image Pass Test" ok ok cards "$(inject_image_failure svg)" ok ok ok ok)"

write_scenario "133-image-404.html" "Image 404" \
  "Image src resolves to HTTP 404." \
  "LINK_VALIDATION,PRIVACY_LINK" "LINK_VALIDATION" "FAIL" \
  "$(build_html "Image 404 Test" ok ok cards "$(inject_image_failure 404)" ok ok ok ok)"

write_scenario "134-image-missing-width.html" "Image Missing Width" \
  "Image has alt text but no explicit width attribute, risking layout shift." \
  "ALT_TEXT,LINK_VALIDATION,PRIVACY_LINK" "HTML_VALIDITY" "FAIL" \
  "$(build_html "Image Missing Width Test" ok ok cards "$(inject_image_failure missing-width)" ok ok ok ok)"

write_scenario "135-image-missing-height.html" "Image Missing Height" \
  "Image has alt text but no explicit height attribute, risking layout shift." \
  "ALT_TEXT,LINK_VALIDATION,PRIVACY_LINK" "HTML_VALIDITY" "FAIL" \
  "$(build_html "Image Missing Height Test" ok ok cards "$(inject_image_failure missing-height)" ok ok ok ok)"

write_scenario "136-image-cid.html" "CID Inline Image" \
  "Image referenced via cid: scheme, typical of inline-attached email assets." \
  "ALT_TEXT,PRIVACY_LINK" "LINK_VALIDATION" "FAIL" \
  "$(build_html "Image CID Test" ok ok cards "$(inject_image_failure cid)" ok ok ok ok)"

write_scenario "137-image-base64.html" "Base64 Inline Image" \
  "Image embedded as a base64 data URI tracking pixel." \
  "ALT_TEXT,LINK_VALIDATION,PRIVACY_LINK" "" "PASS" \
  "$(build_html "Image Base64 Test" ok ok cards "$(inject_image_failure base64)" ok ok ok ok)"

write_scenario "138-image-gif.html" "Animated GIF Image" \
  "Animated GIF used as content imagery, valid alt text present." \
  "ALT_TEXT,LINK_VALIDATION,PRIVACY_LINK" "" "PASS" \
  "$(build_html "Image GIF Test" ok ok cards "$(inject_image_failure gif)" ok ok ok ok)"

write_scenario "139-image-webp.html" "WebP Image" \
  "WebP-format content image, valid alt text present." \
  "ALT_TEXT,LINK_VALIDATION,PRIVACY_LINK" "" "PASS" \
  "$(build_html "Image WebP Test" ok ok cards "$(inject_image_failure webp)" ok ok ok ok)"

echo "Generating HTML structural scenarios..."

write_scenario "140-broken-html.html" "Broken/Invalid Nesting" \
  "Bold and italic tags closed in the wrong order, producing invalid nesting." \
  "LINK_VALIDATION,PRIVACY_LINK" "HTML_VALIDITY" "FAIL" \
  "$(build_html "Broken HTML Test" ok ok single-column "$(inject_html_failure broken-nesting)" ok ok ok ok)"

write_scenario "141-unclosed-tags.html" "Unclosed Tags" \
  "Paragraph tag is left unclosed and swallows the following block element." \
  "LINK_VALIDATION,PRIVACY_LINK" "HTML_VALIDITY" "FAIL" \
  "$(build_html "Unclosed Tags Test" ok ok single-column "$(inject_html_failure unclosed)" ok ok ok ok)"

write_scenario "142-invalid-table.html" "Invalid Table Structure" \
  "Table is missing closing </tr> and </table> tags." \
  "LINK_VALIDATION,PRIVACY_LINK" "HTML_VALIDITY" "FAIL" \
  "$(build_html "Invalid Table Test" ok ok single-column "$(inject_html_failure invalid-table)" ok ok ok ok)"

write_scenario "143-nested-forms.html" "Nested Form Elements" \
  "A <form> element is nested inside another <form> element, which is invalid HTML." \
  "LINK_VALIDATION,PRIVACY_LINK" "HTML_VALIDITY" "FAIL" \
  "$(build_html "Nested Forms Test" ok ok single-column "$(inject_html_failure nested-forms)" ok ok ok ok)"

echo "Generating combined/multi-failure and stress scenarios..."

write_scenario "150-multi-failure.html" "Multiple Simultaneous Failures" \
  "Single email intentionally combining missing alt, broken anchor, duplicate id, generic CTA text, missing privacy link, and lorem ipsum content." \
  "VIEW_ONLINE_LINK" "ALT_TEXT,BROKEN_ANCHOR,DUPLICATE_ID,LINK_TEXT_VALIDATION,PRIVACY_LINK,CONTENT_VALIDATION" "FAIL" \
  "$(build_html "Multi Failure Test" ok ok single-column "$(inject_alt_failure missing) $(inject_anchor_failure missing-target) $(inject_duplicate_ids) $(create_cta generic-click) $(inject_content_failure lorem)" missing ok ok ok)"

# 160-stress-large-email.html: generate ~1000 links and ~500 images programmatically.
build_stress_body() {
    local body=""
    local i
    for ((i = 1; i <= 500; i++)); do
        body="${body}<a href=\"https://shop.acxiom-demo.com/catalog/item-${i}\">View Item ${i}</a> "
    done
    for ((i = 1; i <= 500; i++)); do
        body="${body}<a href=\"https://shop.acxiom-demo.com/catalog/item-${i}/details\">Item ${i} Details</a> "
    done
    for ((i = 1; i <= 500; i++)); do
        body="${body}<img src=\"https://cdn.acxiom-demo.com/catalog/item-${i}.jpg\" width=\"60\" height=\"60\" alt=\"Product thumbnail ${i}\" style=\"display:inline-block;border:0;\"> "
    done
    printf '%s' "${body}"
}

echo "Generating stress-test email (this produces a large file)..."
write_scenario "160-stress-large-email.html" "Large Stress Test Email" \
  "Synthetic large email containing 1000 links and 500 images to validate engine performance and pagination under load." \
  "ALT_TEXT,PRIVACY_LINK,VIEW_ONLINE_LINK" "LINK_VALIDATION" "FAIL" \
  "$(build_html "Stress Test Email" ok ok single-column "$(build_stress_body)" ok ok ok ok)"

echo "Generating marketing-platform-inspired layout scenarios..."

write_scenario "170-salesforce-layout.html" "Salesforce Marketing Cloud Style Layout" \
  "Layout structured similarly to a Salesforce Marketing Cloud journey email (nested tables, VML-safe buttons)." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "SFMC-Style Journey Email" ok ok outlook-vml "$(create_cta ok)" ok ok ok ok)"

write_scenario "171-adobe-layout.html" "Adobe Campaign Style Layout" \
  "Layout structured similarly to an Adobe Campaign transactional template, two-column feature blocks." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "Adobe Campaign-Style Email" ok ok two-column "$(create_cta ok)" ok ok ok ok)"

write_scenario "172-acoustic-layout.html" "Acoustic Style Layout" \
  "Layout structured similarly to an Acoustic (Watson Marketing) responsive table newsletter." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "Acoustic-Style Newsletter" ok ok responsive-table "$(create_cta ok)" ok ok ok ok)"

write_scenario "173-mailchimp-layout.html" "Mailchimp Style Layout" \
  "Layout structured similarly to a Mailchimp campaign template using card-based product grids." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "Mailchimp-Style Campaign" ok ok cards "$(create_cta ok)" ok ok ok ok)"

write_scenario "174-hubspot-layout.html" "HubSpot Style Layout" \
  "Layout structured similarly to a HubSpot marketing email with sidebar summary block." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "HubSpot-Style Email" ok ok sidebar "$(create_cta ok)" ok ok ok ok)"

write_scenario "175-braze-layout.html" "Braze Style Layout" \
  "Layout structured similarly to a Braze lifecycle/engagement email with feature-grid content." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "Braze-Style Lifecycle Email" ok ok feature-grid "$(create_cta ok)" ok ok ok ok)"

write_scenario "176-marketo-layout.html" "Marketo Style Layout" \
  "Layout structured similarly to a Marketo nurture-stream email, single-column with strong CTA emphasis." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "Marketo-Style Nurture Email" ok ok single-column "$(create_cta ok)" ok ok ok ok)"

write_scenario "177-eloqua-layout.html" "Oracle Eloqua Style Layout" \
  "Layout structured similarly to an Oracle Eloqua program email, hero-image driven." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "Eloqua-Style Program Email" ok ok hero-image "$(create_cta ok)" ok ok ok ok)"

echo "Generating final golden/perfect baseline scenarios..."

write_scenario "180-perfect-accessible.html" "Perfect Accessible Email" \
  "Golden baseline emphasizing full WCAG-aligned accessibility: lang, landmarks, contrast, alt text." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "Fully Accessible Update" ok ok feature-grid "$(create_cta ok)" ok ok ok ok)"

write_scenario "181-perfect-minimal.html" "Perfect Minimal Email" \
  "Golden baseline using the simplest possible valid single-column layout." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "Minimal Account Notice" ok ok single-column "<p style=\"font-size:14px;color:#334155;\">Your account settings were updated successfully.</p>" ok ok ok ok)"

write_scenario "182-perfect-responsive.html" "Perfect Responsive Email" \
  "Golden baseline using a fully responsive table layout with media queries." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "Responsive Statement Email" ok ok responsive-table "$(create_cta ok)" ok ok ok ok)"

echo "Generating enterprise email-type coverage scenarios..."

write_scenario "190-invoice.html" "Invoice Email" \
  "Realistic billing/invoice transactional email layout, fully passing." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "Invoice #88291 Available" ok ok responsive-table "$(create_cta ok)" ok ok ok ok)"

write_scenario "191-password-reset.html" "Password Reset Email" \
  "Security-sensitive password reset transactional email, fully passing." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "Reset Your Password" ok ok single-column "$(create_cta ok)" ok ok ok ok)"

write_scenario "192-welcome-email.html" "Welcome Email" \
  "New-user welcome/onboarding email using an Outlook-compatible VML button." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "Welcome to Acxiom" ok ok outlook-vml "$(create_cta ok)" ok ok ok ok)"

write_scenario "193-product-launch.html" "Product Launch Email" \
  "Product-launch marketing email using a hero-image layout." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "Introducing Our Newest Release" ok ok hero-image "$(create_cta ok)" ok ok ok ok)"

write_scenario "194-corporate-announcement.html" "Corporate Announcement Email" \
  "Company-wide announcement email using a sidebar layout." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "Corporate Announcement" ok ok sidebar "$(create_cta ok)" ok ok ok ok)"

write_scenario "195-security-notification.html" "Security Notification Email" \
  "Account security alert transactional email." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "Security Alert: New Sign-In Detected" ok ok single-column "$(create_cta ok)" ok ok ok ok)"

write_scenario "196-support-case.html" "Support Case Update Email" \
  "Customer support case status-update transactional email." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "Your Support Case Has Been Updated" ok ok nested-tables "$(create_cta ok)" ok ok ok ok)"

write_scenario "197-feature-release.html" "Feature Release Email" \
  "Product feature-release announcement using a feature-grid layout." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "New Features Now Available" ok ok feature-grid "$(create_cta ok)" ok ok ok ok)"

write_scenario "198-maintenance-notice.html" "Maintenance Notice Email" \
  "Scheduled-maintenance operational notice email." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "Scheduled Maintenance Window" ok ok single-column "$(create_cta ok)" ok ok ok ok)"

echo "Generating dark-mode / media-query coverage scenario..."

write_scenario "199-dark-mode-newsletter.html" "Dark Mode Aware Newsletter" \
  "Newsletter with dark-mode CSS media queries and mobile-responsive stacking, fully passing." \
  "${ALL_PASS_RULES}" "" "PASS" \
  "$(build_html "Dark Mode Newsletter" ok ok two-column "$(create_cta ok)" ok ok ok ok)"

###############################################################################
# MANIFEST WRITE-OUT
###############################################################################

echo "Writing scenario manifest..."
{
    echo "{"
    echo "  \"generatedAt\": \"${FIXED_TIMESTAMP}\","
    echo "  \"campaignId\": \"${CAMPAIGN_ID}\","
    echo "  \"totalScenarios\": ${#MANIFEST_ENTRIES[@]},"
    echo "  \"scenarios\": ["
    local_count="${#MANIFEST_ENTRIES[@]}"
    idx=0
    for entry in "${MANIFEST_ENTRIES[@]}"; do
        idx=$((idx + 1))
        if [ "${idx}" -lt "${local_count}" ]; then
            printf '%s,\n' "${entry}"
        else
            printf '%s\n' "${entry}"
        fi
    done
    echo "  ]"
    echo "}"
} > "${MANIFEST_FILE}"

echo ""
echo "Done. Generated ${#MANIFEST_ENTRIES[@]} email fixtures into ${OUT_DIR}/"
echo "Manifest written to ${MANIFEST_FILE}"