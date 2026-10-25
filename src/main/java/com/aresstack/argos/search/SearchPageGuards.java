package com.aresstack.argos.search;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Selector;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Consent and challenge recognition for search pages (port of AskAI's SearchPageGuards). AskAI ran
 * these as page scripts and clicked with {@code element.click()}, which pages can observe. Argos only
 * inspects serialized HTML in the JVM and returns a CSS selector; the browser adapter then clicks the
 * element's bounding-box centre with trusted WebDriver BiDi input. Nothing is injected into the page.
 */
final class SearchPageGuards {
    enum ConsentAction { REJECT_ALL, ONLY_NECESSARY, ACCEPT_ALL, CLOSE }

    /** The control to click: chosen with minimal consent first (reject, necessary only, accept, close). */
    record ConsentControl(ConsentAction action, String selector, String evidence) {
    }

    /** A blocking challenge (CAPTCHA, "unusual traffic") with the evidence that identified it. */
    record Challenge(String evidence) {
    }

    private static final List<String> REJECT_SELECTORS = List.of(
            "#onetrust-reject-all-handler", "#CybotCookiebotDialogBodyLevelButtonLevelOptinDeclineAll",
            "#CybotCookiebotDialogBodyButtonDecline", "#uc-btn-deny-banner", "#bnp_btn_reject",
            ".qc-cmp2-summary-buttons button[mode=secondary]", "button[data-action=reject]",
            "button[data-action=reject-all]", "button[data-action=rejectAll]",
            "button[data-testid=reject-all-button]", "[class*=cookie] button[class*=reject]",
            "[class*=consent] button[class*=reject]", "[id*=cookie] button[id*=reject]",
            "[id*=consent] button[id*=reject]");
    private static final List<String> REJECT_TEXTS = List.of(
            "reject all", "decline all", "deny all", "reject cookies", "decline cookies",
            "continue without accepting", "do not accept", "alle ablehnen", "cookies ablehnen",
            "weiter ohne zustimmung", "nicht zustimmen");
    private static final List<String> ONLY_NECESSARY_TEXTS = List.of(
            "only necessary", "necessary only", "use necessary only", "essential only",
            "only essential cookies", "accept only necessary", "nur notwendige", "nur erforderliche",
            "nur essenzielle", "nur essentielle");
    private static final List<String> ACCEPT_SELECTORS = List.of(
            "#onetrust-accept-btn-handler", "#CybotCookiebotDialogBodyLevelButtonLevelOptinAllowAll",
            ".qc-cmp2-summary-buttons button[mode=primary]", "#uc-btn-accept-banner", "#bnp_btn_accept",
            "[class*=cookie] button[class*=accept]", "[class*=consent] button[class*=accept]",
            "[id*=cookie] button[id*=accept]", "[id*=consent] button[id*=accept]",
            "button[data-action=accept]", "button[data-action=accept-all]", "button[data-action=acceptAll]");
    private static final List<String> ACCEPT_TEXTS = List.of(
            "accept all", "allow all", "i agree", "alle akzeptieren", "alle annehmen", "alle zulassen",
            "zustimmen", "akzeptieren");
    private static final List<String> CLOSE_SELECTORS = List.of(
            "[class*=cookie] [aria-label=Close]", "[class*=consent] [aria-label=Close]",
            "[id*=cookie] [aria-label=Close]", "[id*=consent] [aria-label=Close]",
            "[class*=cookie] button[class*=close]", "[class*=consent] button[class*=close]");
    private static final String BUTTONS =
            "button, a[role=button], [role=button], input[type=button], input[type=submit], [class*=btn]";

    private static final List<String> CHALLENGE_SELECTORS = List.of(
            "iframe[src*=captcha]", "iframe[title*=challenge]", "#challenge-form", "iframe[src*=turnstile]",
            "#captcha-form", "form#captcha-form", "[class*=captcha]", "[id*=captcha]", ".anomaly-modal",
            "#b_rrsr");
    private static final List<String> CHALLENGE_TEXTS = List.of(
            "noch ein letzter schritt", "one last step", "verify you are human", "unusual traffic",
            "ungewöhnlichen datenverkehr", "complete the challenge", "bots use duckduckgo too",
            "bestätigen sie, dass sie ein mensch sind", "i'm not a robot", "ich bin kein roboter");

    private SearchPageGuards() {
    }

    static Optional<ConsentControl> findConsentControl(Document document) {
        List<Element> buttons = visible(document.select(BUTTONS));
        Optional<ConsentControl> found = bySelector(document, REJECT_SELECTORS, ConsentAction.REJECT_ALL)
                .or(() -> byText(buttons, REJECT_TEXTS, ConsentAction.REJECT_ALL))
                .or(() -> byText(buttons, ONLY_NECESSARY_TEXTS, ConsentAction.ONLY_NECESSARY))
                .or(() -> bySelector(document, ACCEPT_SELECTORS, ConsentAction.ACCEPT_ALL))
                .or(() -> byText(buttons, ACCEPT_TEXTS, ConsentAction.ACCEPT_ALL))
                .or(() -> bySelector(document, CLOSE_SELECTORS, ConsentAction.CLOSE));
        return found;
    }

    /**
     * Only consulted when a page yielded no organic results: a results page about CAPTCHAs must not be
     * mistaken for a challenge. URL evidence (Google's /sorry/ interstitial) is decisive on its own.
     */
    static Optional<Challenge> findChallenge(Document document, String url, String visibleText) {
        String lowerUrl = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (lowerUrl.contains("/sorry/") || lowerUrl.contains("/captcha") || lowerUrl.contains("challenges.cloudflare.com")) {
            return Optional.of(new Challenge("url:" + DomainNames.hostOf(url)));
        }
        for (String selector : CHALLENGE_SELECTORS) {
            for (Element element : safeSelect(document, selector)) {
                if (!hiddenInMarkup(element)) return Optional.of(new Challenge("selector:" + selector));
            }
        }
        String text = visibleText == null ? "" : visibleText.toLowerCase(Locale.ROOT);
        for (String marker : CHALLENGE_TEXTS) {
            if (text.contains(marker)) return Optional.of(new Challenge("text:" + marker));
        }
        return Optional.empty();
    }

    private static Optional<ConsentControl> bySelector(Document document, List<String> selectors, ConsentAction action) {
        for (String selector : selectors) {
            for (Element element : safeSelect(document, selector)) {
                if (!hiddenInMarkup(element)) {
                    return Optional.of(new ConsentControl(action, element.cssSelector(), selector));
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<ConsentControl> byText(List<Element> buttons, List<String> phrases, ConsentAction action) {
        for (Element button : buttons) {
            String text = SerpDocumentParser.clean(button.text().isEmpty() ? button.attr("value") : button.text())
                    .toLowerCase(Locale.ROOT);
            if (text.isEmpty() || text.length() > 60) continue;
            for (String phrase : phrases) {
                if (text.equals(phrase) || text.startsWith(phrase)) {
                    return Optional.of(new ConsentControl(action, button.cssSelector(), "text:" + text));
                }
            }
        }
        return Optional.empty();
    }

    private static List<Element> visible(List<Element> elements) {
        return elements.stream().filter(element -> !hiddenInMarkup(element)).limit(200).toList();
    }

    /** Hidden by its own markup or an ancestor's (hidden, aria-hidden, inline display/visibility). */
    static boolean hiddenInMarkup(Element element) {
        for (Element current = element; current != null; current = current.parent()) {
            if (SerpDocumentParser.isHidden(current)) return true;
        }
        return false;
    }

    private static List<Element> safeSelect(Document document, String selector) {
        try {
            return document.select(selector);
        } catch (Selector.SelectorParseException invalid) {
            return List.of();
        }
    }
}
