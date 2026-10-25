# Argos 2.0 – Bestandsaufnahme und Architekturentscheidung (Phase 0)

Stand: 2026-10-25. Untersuchte Revisionen:

| Repository | Branch / Commit | Rolle |
|---|---|---|
| `aresstack/argos` | `main` @ `cdb21f4` | Zielprojekt |
| `Miguel0888/askai-java8` | `arch` @ `a6d23f6` | Referenz Suche, Recherche, kleine Modelle |
| `aresstack/enterprise-ai-client` | `main` @ `a64d004` | Referenz Tabs, Controls, Dokumente, Batch |
| `aresstack/playwright4j` | `1c11fe9` (in Argos-CI gepinnt) | Unterbau, nur gelesen wegen Tarnungsfragen |

Dieses Dokument ist die gemeinsame Referenz für die parallel laufenden Arbeitspakete A–H. Es ändert keinen
Code. Klassen- und Methodennamen sind belegt; Pfade sind relativ zum jeweiligen Repository.

---

## 0. Kurzfassung der Entscheidungen

1. **Architektur bleibt:** ein Gradle-Modul, Java 21, Playwright Java 1.62 + Playwright4J, Camoufox über BiDi,
   MCP-SDK 0.18.4 über Jetty, OAuth-Filter, `argos.jar`. Kein Code aus `browser-firefox` (Enterprise) und kein
   Playwright4J 0.1.0 (AskAI).
2. **Tarnung hat Vorrang vor Bequemlichkeit.** Jede neue Funktion wird einer *Stealth-Stufe* zugeordnet
   (Abschnitt 3). **Verboten sind Preload-/Init-Scripts (`addInitScript`), `exposeBinding`, DOM-Markierungen
   und Globals** – also alles, was vor den Seitenskripten läuft oder in der Seite bleibt. Bevorzugt werden
   `page.content()` (Utility-Welt) mit Java-seitigem Parsen und Klicks über Bounding-Box + echte
   BiDi-Eingaben. Flüchtige, nur lesende `page.evaluate`-Aufrufe sind erlaubt, wo das nicht reicht.
3. **Drei Werkzeugprofile** über `ARGOS_TOOL_PROFILE` / `tools.profile`: `legacy` (Standard, heutige neun
   `browser_*`), `compact` (AskAI-Sechs), `expert` (Obermenge). Abschnitt 5.
4. **Wiederverwendung:** AskAI `browser-api` (Teile) und `browser-search-analysis` sind framework-freies Java 8
   und werden **als Quellcode nach Argos übernommen** (MIT, gleicher Rechteinhaber AresStack), nicht als
   Gradle-Modul. Die in AskAI per `page.evaluate` laufende Seitenerfassung (`RenderedPageDocumentCapture`)
   wird durch einen **Java-seitigen Builder über `page.content()` + jsoup** ersetzt.
5. **Erfahrung des Entwicklers (2026-10-25):** Camoufox setzt `navigator.webdriver` gerade **nicht** auf
   `true`; genau deshalb darf Argos die Tarnung nicht durch eigene Spuren verraten. Ein früheres
   **Preload-Script war auffällig und führte auf einigen Seiten zur Blockierung.** Skriptauswertung zur
   Laufzeit (`page.evaluate`) war bisher unproblematisch. Daraus folgt: Preload-/Init-Scripts und alles,
   was dauerhaft in der Seite bleibt (Globals, Bindings, DOM-Markierungen), sind das Hauptrisiko und
   verboten; flüchtige Laufzeit-Auswertung ist erlaubt, wird aber sparsam eingesetzt (Abschnitt 3).
   Eine Regressionsmessung mit `BrowserSignalsProbe` sichert das ab (Abschnitt 7, R1).

---

## 1. Ist-Zustand Argos (belegt)

| Bereich | Implementierung | Befund |
|---|---|---|
| Einstieg | `ArgosApplication.main` | `--verify-runtime` startet den Playwright4J-Treiber aus der JAR; MCP über `HttpServletStreamableServerTransportProvider` an `/`, `/health`, `/.well-known/oauth-protected-resource`. |
| Root-Servlet | `ArgosRootMcpServlet` | GET → 405, POST/DELETE → Transport. Pyloros-kompatibel. Unverändert lassen. |
| Auth | `ArgosMcpAuthorizationFilter` | JWT via AnAuthentia (`JwtAccessTokenValidator.forRemoteJwks`), Scope-Prüfung, kein Fallback. Fehlertext enthält kein Token. |
| Konfiguration | `ArgosConfig.fromEnvironment` | Env vor `~/.argos/argos.properties`; liest Camoufox-Pfade aus `~/.browser-event-hub/desktop/camoufox.properties`, nie dessen Profil. Loopback-Pflicht für `SERVER_HOST`. Hier kommt `ARGOS_TOOL_PROFILE` hinzu. |
| Werkzeuge | `ArgosTools.all()` | **9 Werkzeuge**: `browser_status`, `browser_navigate`, `browser_snapshot`, `browser_inspect`, `browser_click`, `browser_fill`, `browser_evaluate`, `browser_network`, `browser_screenshot`. Ergebnisse als JSON-Text (`ObjectMapper.writeValueAsString`), Fehler als `isError` mit `getMessage()`. |
| Browser | `CamoufoxBrowser` | Eine `Page` (`activePage()`), `launchPersistentContext` mit `channel=moz-firefox`, `setViewportSize(null)` und weiteren `null`-Emulationen (gut: echte Fenstergeometrie). Fingerprint-Env nur `CAMOU_*`/`FONTCONFIG*` (`activePage`). |
| Threading | `CamoufoxBrowser.onBrowser` | `newSingleThreadExecutor`, 90 s Timeout pro Aufruf. Kein Event-Pump zwischen Aufrufen (Netzwerk-Events kommen nur während Playwright-Aufrufen an). |
| Navigation | `CamoufoxBrowser.navigate` | `WaitUntilState.DOMCONTENTLOADED`, 45 s; nur http(s) mit Host; **kein Redirect-Ziel-Check, keine Netzwerkrichtlinie.** |
| Snapshot | `CamoufoxBrowser.snapshot` | `p.locator("body").innerText()` → wartet bei XML/RSS ohne `body` bis zum Timeout (Springer-Fehler). Kein `contentType`, kein `status`. |
| Inspect | `CamoufoxBrowser.inspect` | **`page.evaluate` in der Hauptwelt** (`querySelectorAll`). Stealth-Stufe 3. |
| Klick/Füllen | `CamoufoxBrowser.click/fill` | `locator.click()` / `locator.fill()`: Aktionsprüfungen in Playwrights Utility-Welt, Eingabe über `input.performActions` → vertrauenswürdige Events. Stufe 1. |
| Evaluate | `CamoufoxBrowser.evaluate` | Hauptwelt, beliebiger Code. Stufe 3, nur `expert`/`legacy`. |
| Netzwerk | `CamoufoxBrowser.record` | `onRequest/onResponse/onRequestFailed`, Ringpuffer 200 (`MAX_NETWORK_EVENTS`), Query und UserInfo entfernt, keine IDs, keine Dauer, keine Bodies. |
| Screenshot | `CamoufoxBrowser.screenshot` | Viewport-PNG, `browsingContext.captureScreenshot`; unsichtbar. |
| Tests | `ArgosToolsTest` (zählt 9 Werkzeuge, Reihenfolge `browser_status` zuerst), `CamoufoxBrowserTest` (opt-in `-PtestCamoufox=true`, lokaler `HttpServer`), `ArgosMcpAuthorizationFilterTest` | Profil-Einführung muss `ArgosToolsTest` für `legacy` unverändert grün lassen. |
| CI | `.github/workflows/build-release.yml` | Baut Playwright4J `1c11fe9` selbst, `test argosZip`, Linux-Health + `GET / → 405`, Windows-Smoke prüft `tools/list` auf `browser_navigate` und `browser_screenshot`. **Bei `ARGOS_TOOL_PROFILE=compact` würde der Windows-Smoke fehlschlagen → CI läuft mit Standardprofil `legacy`; zusätzlicher Smoke für `compact` empfohlen.** |
| Packaging | `bootJar` → `argos.jar`, `verifyStandaloneRuntime`, `packageArgos`, `argosZip` | Neue Abhängigkeiten (jsoup, PDFBox) sind normale JARs in `BOOT-INF/lib`, unkritisch. |

Bekannte Beobachtungen 1–7 aus dem Auftrag sind alle bestätigt. Zusätzlich gefunden:

- 8. `browser_navigate` prüft das Ziel nach Redirects nicht (AskAI und Enterprise tun das).
- 9. `activePage()` registriert die Netzwerk-Listener nur beim Wechsel der Seite, nicht pro Tab → bei Tabs
  muss das Recording pro `Page` registriert werden.
- 10. Fehlermeldungen (`onBrowser`) reichen `e.getMessage()` der Playwright-Ausnahme durch; diese enthalten
  mehrzeilige Call-Logs mit URLs inkl. Query. Für die neuen Werkzeuge erste Zeile + Redaction (wie AskAI
  `Playwright4jDriver.firstLine`).

---

## 2. Was Playwright/Playwright4J für die Seite sichtbar macht (belegt)

Quelle: `playwright4j/docs/browser-signals.md` und `problems.md` (Messung Firefox 158, Oktober 2026).

| Mechanismus | Für Seitenskripte sichtbar? | Konsequenz für Argos |
|---|---|---|
| `page.exposeBinding` / `exposeFunction` | **Ja**: `__playwright__binding__`, `__playwright__binding__controller__` in jeder Seite | Nie verwenden (AskAI-HUD nutzt es → nicht übernehmen). |
| `addInitScript` | **Ja** (Preload-Script in der Hauptwelt) | Nie verwenden. |
| `page.evaluate`, `locator.evaluate`, `evaluateAll`, `waitForFunction` | Code läuft in der **Hauptwelt** (Stack-Traces, überschriebene Prototypen, Timing). Zusätzlich setzt Playwright `userActivation: true` → `navigator.userActivation.hasBeenActive` ohne Eingabe | Stufe 3: flüchtig und nur lesend erlaubt, wenn Stufe 0/1 nicht reicht; in Argos bisher unauffällig. |
| `locator.innerText/textContent/getAttribute/boundingBox/ariaSnapshot/isVisible`, `page.content()`, Aktionsprüfungen | Laufen in Playwrights **Utility-Welt** (BiDi-Sandbox-Realm); keine Globals in der Seite | Standardweg zum Lesen. Restrisiko: `userActivation` (R3). |
| `mouse.move/click`, `keyboard.type/press`, `locator.click/fill` | Echte BiDi-Eingaben (`input.performActions`), `isTrusted=true` | Standardweg zum Bedienen. |
| `browsingContext.captureScreenshot`, Netzwerk-Events, `response.body()` (Data-Collector bis 20 MB) | Nein | Unbedenklich. |
| `TestUtils`-Global | In Playwright4J abgeschaltet (Abweichung vom offiziellen Treiber) | Profil-Prefs nie wieder einschalten. |
| `navigator.webdriver` | In normalem Firefox über BiDi `true`; **Camoufox setzt es laut Entwickler nicht** | Nichts tun, was den Vorteil zunichtemacht; als Regressionstest messen (R1). |
| `setViewportSize(null)` | Echte Fenstergeometrie | Argos macht das bereits; neue Tabs/Kontexte ebenso. |

Daraus folgt die verbindliche Regel: **Argos-Code darf in der Hauptwelt keine bleibenden Spuren
hinterlassen und nichts vor den Seitenskripten installieren.** Praxis in Argos: Preload-Scripts haben
bereits zu Blockierungen geführt, flüchtige Laufzeit-Auswertungen bisher nicht.
AskAI und Enterprise verletzen das an vielen Stellen (Abschnitt 4, Spalte „Bewusst nicht übernehmen“).

---

## 3. Stealth-Stufen (verbindlich für alle Arbeitspakete)

| Stufe | Mittel | Erlaubt in |
|---|---|---|
| **0 – unsichtbar** | Navigation, Netzwerk-Events, `response.body()`/Header, Screenshots, Tab-Verwaltung (`context.newPage`, `page.close`, `bringToFront`), Java-seitige Verarbeitung | allen Profilen, automatischen Abläufen |
| **1 – Utility-Welt / echte Eingabe** | `page.content()`, `locator(...).innerText/boundingBox/ariaSnapshot/getAttribute/isVisible`, `mouse`/`keyboard`, `locator.click/fill/press` | allen Profilen, automatischen Abläufen |
| **2 – eigenes, unsichtbares Firefox-Add-on** (geplant) | Content-Script in isolierter Welt, Kommunikation über Hintergrundseite ↔ Loopback-WebSocket mit Token, Anzeige nur im Browser-Chrome (Sidebar/Popup), keine `web_accessible_resources`, keine DOM-Änderung | nach Messung (R5), für HUD und geometrische Seitenerfassung |
| **3 – flüchtige Laufzeit-Auswertung in der Hauptwelt** | `page.evaluate`, `locator.evaluate*` mit reinen Lesefunktionen, die nichts in Seite, `window` oder DOM zurücklassen und keine Prototypen ändern; kein `waitForFunction`-Polling | In der Praxis bisher unproblematisch. Erlaubt, wenn Stufe 0/1 das Ergebnis nicht oder nur mit unverhältnismäßigem Aufwand liefert (z. B. Geometrie/Farben für die SERP-Analyse, Sichtbarkeit vieler Elemente in einem Durchgang). Jede Verwendung im Code begründen; `browser_evaluate` bleibt frei für `expert`/`legacy`. |
| **verboten** | **Preload-/Init-Scripts** (`addInitScript`, `script.addPreloadScript` – hat in Argos bereits zu Blockierungen geführt), `exposeBinding`/`exposeFunction`, DOM-Overlays, `data-*`-Markierungen, `window.__*`-Globals, `route()`-basiertes Umschreiben von Antworten, Requests aus einem anderen TLS-Stack (`context.request()`) an Zielseiten | nirgends |

**Regeln für Stufe 3 (flüchtige Auswertung).** Ein `page.evaluate` ist nur zulässig, wenn nach seinem Ende
nichts davon in der Seite übrig ist:

- keine neuen oder geänderten Globals (`window.__…`, `document.__…`), keine neuen Funktionen, Klassen oder
  Symbole, die die Seite erreichen kann;
- keine geänderten Prototypen oder überschriebenen nativen Funktionen (`Function.prototype.toString`,
  `Element.prototype.*`, `fetch`, `XMLHttpRequest`, `console` …);
- keine Event-Listener, `MutationObserver`, `IntersectionObserver`, Timer (`setTimeout`/`setInterval`),
  `requestAnimationFrame`-Schleifen, Promises, die nach Rückgabe weiterlaufen;
- keine DOM-Änderungen: keine Attribute (`data-*`), Klassen, Elemente, Styles, kein `scrollIntoView`
  (Scrollen nur über echte Mausrad-Eingaben), kein `focus()`/`click()`/`dispatchEvent`;
- keine Netzwerkanfragen (`fetch`, `sendBeacon`, Bilder);
- eine einzige, in sich geschlossene Funktion, die Werte liest und als einfache Daten (Strings, Zahlen,
  Arrays) zurückgibt; kein Zustand zwischen zwei Aufrufen in der Seite.

Neue oder bisher ungetestete Mechanismen gelten als riskant, auch wenn sie flüchtig wirken. Sie werden
erst nach dem Signal-Regressionstest (R1) eingesetzt. Jede Stufe-3-Stelle im Code bekommt einen
Kommentar mit Begründung, und ein Unit-Test prüft das Skript mindestens auf verbotene Muster
(`window.`-Zuweisungen, `addEventListener`, `Observer`, `setAttribute`, `dataset`, `prototype`,
`scrollIntoView`, `fetch`).

Konsequenzen für typische Aufgaben:

- **Klicken:** Element über Utility-Welt finden (`locator`/`getByRole`), `boundingBox()`, sichtbaren Mittelpunkt
  bestimmen, Verdeckung über `locator.click(new Locator.ClickOptions().setTrial(true))` prüfen, dann
  `HumanPointer`-Pfad über `page.mouse()`. Kein JS-Fallback-Klick (AskAI `Playwright4jDriver.tryDismissConsent`
  hat einen → nicht übernehmen).
- **Warten auf SPA-Inhalte:** `waitForLoadState`, Polling von `page.content()`-Länge/Hash bzw.
  `locator(sel).waitFor()`; **kein MutationObserver** (Enterprise `OBSERVE`) und kein `waitForFunction`.
- **Screenshot-Markierungen:** Java-seitig mit `Graphics2D` auf das PNG zeichnen (Boxen aus `boundingBox()`),
  nicht als DOM-Ebene (Enterprise `SHOT_PREPARE_FUNCTION`).
- **Downloads:** im eigenen Tab navigieren und `response.body()` bzw. `page.waitForDownload` nutzen (Firefox-
  Netzwerkstack, echte Cookies). Kein In-Page-`fetch` (Enterprise `FETCH_FUNCTION`), kein `APIRequestContext`
  (anderer TLS-Fingerabdruck gegenüber der Zielseite).
- **HUD/Anzeige:** nicht in die Seite (AskAI `ResearchHudOverlay` + `exposeBinding`), sondern später über das
  Add-on (Stufe 2) oder gar nicht.

---

## 4. Funktionsmatrix

Legende Spalten: **A** = in Argos vorhanden, **K** = in AskAI (`arch`), **E** = in Enterprise. Entscheidung:
**W** direkt wiederverwendbar (Quelltext übernehmen), **AN** Anpassung erforderlich, **NEU** neu entwickeln,
**NICHT** bewusst nicht übernehmen. AP = Arbeitspaket des Auftrags.

### 4.1 Seiten, Dokumenttypen, Bereitschaft (AP A)

| Funktion | A | K | E | Entscheidung | Belege und Begründung |
|---|---|---|---|---|---|
| Navigation http(s) | ✔ `CamoufoxBrowser.navigate` | ✔ `Playwright4jDriver.open` | ✔ `BidiBrowserConnection.navigate` | behalten | – |
| URL-Richtlinie vor und nach Redirect | ✘ | ✔ `UrlSafetyPolicy.check`, `PlaywrightBrowserSession.checkedSnapshot` | ✔ `BrowserAccessPolicy`, `ChatBrowserRuntime.checkFinal` | **AN** | AskAI-Policy blockiert privat pauschal; Auftrag verlangt Intranet per Richtlinie → Enterprise-Regelsyntax (`example.org`, `*.example.org`, Blockliste gewinnt) mit AskAI-Prüfpunkten kombinieren. Dialog „Benutzer fragen“ (Enterprise `UNKNOWN`) entfällt, Server-Konfiguration statt Rückfrage. |
| Dokumenttyp-abhängige Textgewinnung (HTML/XML/RSS/JSON/text/binär) | ✘ (nur `body.innerText`) | ✘ (`page.innerText("body")` in `Playwright4jDriver.state`) | teilweise (`isDocumentPath` per Dateiendung) | **NEU** | Content-Type aus `Response.headerValue("content-type")` der Navigation (Stufe 0), Body aus `response.body()`; HTML über `page.content()` + jsoup bzw. `locator("body").innerText()` nur wenn `body` existiert (`locator("body").count()`). XML/RSS: Java-seitige Text-/Item-Darstellung; JSON: Pretty-Print begrenzt; Binär → Verweis auf Dokumentwerkzeug. |
| Konfigurierbare Seitenbereitschaft | ✘ (DOMContentLoaded) | ✔ teilweise: `BrowserPageReadiness`, `HeuristicPageReadinessJudge`, `RenderedPageDocumentCapture` (Fingerprint vorher/nachher, `MAX_RECAPTURES`, `SETTLE_MILLIS`) | ✔ `BidiBrowserConnection.isLoadSettled`, `QUIET_MILLIS`, `settleRedirect` | **AN** | Konzept „Dokument geladen → ruhig für X ms → Inhalt vorhanden → Ladeindikator weg → Deadline“ aus beiden. Ruhe-Messung **ohne** MutationObserver: Hash von `page.content()` bzw. Textlänge in Intervallen (Stufe 1) plus Netzwerk-Ruhe aus den Request-Events (Stufe 0). |
| `browser_wait(selector,state,timeoutMs)` | ✘ | ✘ | ✘ | **NEU** (expert) | `locator.waitFor(state)`; Utility-Welt. |
| Einheitlicher Snapshot (`url,title,contentType,status,text,truncated`) | ✘ | ✔ teilweise `BrowserPageSnapshot` (ohne Typ/Status) | ✔ `BrowserPage` | **AN** | Neuer Record `PageSnapshot` in Argos; AskAI-Felder + `contentType`, `status`. |
| Consent-Erkennung | ✘ | ✔ `SearchPageGuards.consentReportScript/consentLocateScript/consentDismissScript`, Priorität REJECT_ALL → ONLY_NECESSARY → ACCEPT_ALL → CLOSE | ✔ `BidiBrowserConnection.PROBE_SCRIPT` | **AN** | Regeln (Selektor-/Textlisten, Priorität „minimal consent“) übernehmen; **Ausführung umbauen**: Suche über `page.content()`/jsoup bzw. `getByRole("button", name)`, Klick über Bounding-Box + echte Maus. AskAI-JS-Fallback-Klick entfällt. Auftrag: nur konfigurierte, eindeutig erlaubte Aktionen → ACCEPT_ALL standardmäßig aus. |
| CAPTCHA-/Challenge-Erkennung | ✘ | ✔ `SearchPageGuards.challengeDetectScript` (visible/hidden-Unterscheidung), `AccessBlockSignals` (Cloudflare 1020) | ✔ `PROBE_SCRIPT` | **AN** | Marker übernehmen, Erkennung Java-seitig (HTML + Sichtbarkeit über `locator.isVisible()`). Kein Umgehen; Ergebnis `CHALLENGE_REQUIRED`. Parken in eigenem Tab (`Playwright4jDriver.parkChallenge`) ist sinnvoll und Stufe 0. |

### 4.2 Websuche (AP B)

| Funktion | A | K | E | Entscheidung | Belege und Begründung |
|---|---|---|---|---|---|
| `web_search(query)` mit nummerierter Textausgabe | ✘ | ✔ `BrowserMcpSidecarMain.registerTools` (`PROVIDER:`, `id: title — url`, Snippet, `CHALLENGE:`, `ATTEMPT:`-Zeilen) | ✘ | **W** (Format) | Format beibehalten, für kleine Modelle getestet. Auftrag wünscht `1. Titel\n   URL\n   Snippet` – kompatibel machen, `ATTEMPT:` nur bei Fehlern ausgeben. |
| Suchmaschinen-Navigation mit Endpunkt-Alternativen, Seitenzahl, Pausen, Familien-Sperre | ✘ | ✔ `PlaywrightBrowserSession.navigateAndCaptureSearchEngines`, `BrowserSearchEngineCatalog` (DuckDuckGo html/lite, Bing), `EngineAcquisitionMode.FIRST_USABLE`, `paceBeforeRepeatRequest` | ✘ | **AN** | Algorithmus übernehmen, gegen `CamoufoxBrowser`-Port statt `PlaywrightDriver`. **Google fehlt in AskAI → NEU** als eigener Engine-Eintrag (`/search?q=`, Redirect `/url?q=` kennt `SearchRedirectResolver` bereits). |
| Strukturierte Seitenerfassung | ✘ | ✔ `RenderedPageDocumentCapture` (ein `page.evaluate` misst Container, Geometrie, Farben) | ✘ | **AN** | Das Skript ist eine flüchtige Lesefunktion (laut Javadoc „writes nothing into the page“) → Stufe 3, zulässig. Bevorzugt: Struktur **Java-seitig aus `page.content()` mit jsoup**, Geometrie/Farbe nur für die Top-N-Container per `boundingBox()` oder, falls das zu langsam/ungenau ist, mit dem übernommenen AskAI-Messskript in einem einzigen `page.evaluate`. Vor der Übernahme gegen die Stufe-3-Regeln prüfen (Abschnitt 3). |
| Ergebnisbereich, wiederholte Container, Primärlink, Snippet, Dedup | ✘ | ✔ `SearchPageMechanicalAnalyzer.analyze`, `SearchPageContainerScorer.score`, `DomStructureClusterer.cluster`, `SearchResultBlockDetector.detect/detectExplicit`, `PrimaryResultLinkResolver.resolve`, `SnippetExtractor.extract`, `LegacySearchResultExtractor.extract` | ✘ | **W** | Framework-freies Java 8 (`browser-search-analysis/build.gradle`: nur `:browser-api`). Quelltext + Tests nach `com.aresstack.argos.search.analysis` übernehmen; JUnit4-Tests auf JUnit 5 heben. |
| Ehrliche Ergebnisarten `ORGANIC_RESULTS` / `NO_ORGANIC_RESULTS` / `EXTRACTION_FAILED` | ✘ | ✔ `SearchResultExtractionResult`, `PlaywrightBrowserSession.CapturedPageVerdict` | ✘ | **W** | Kerninvariante „kein rohes Anker-Dumping“ (`search`: „HARD INVARIANT“). |
| Redirect-Auflösung Bing/Google/DDG | ✘ | ✔ `SearchRedirectResolver.resolve` | ✘ | **W** | Rein statisch. |
| Domain-Familien (Public Suffix) | ✘ | ✔ `PublicSuffixDomainKeyResolver`, `domain/public-suffix-snapshot.txt` | ✘ | **W** | |
| URL-Normalisierung (utm, gclid …) | ✘ | ✔ `research-agent-runtime …/search/SearchUrlNormalizer` | ✔ `ChatBrowserRuntime.withoutFragment` | **W** | |
| Settings-Katalog/Validator für Suche | ✘ | ✔ `LegacyBrowserSearchSettings`, `…Defaults`, `…Codec`, `DefaultLegacyBrowserSearchSettingsValidator` | ✘ | **AN** | Umfangreich; nur die von Analyse/Navigation gelesenen Felder übernehmen, Rest weglassen. |
| KI-Layout-Reparatur | ✘ | ✔ `AiSearchPageLayoutResolver`, `SearchPageLayoutDecisionValidator`, `SearchLayoutRepairTools` (`web_search_prepare/apply_layout/discard_repair`) | ✘ | **AN** (optional, später) | Port `SearchPageLayoutResolver` + Validator übernehmen; Standard „kein Resolver“ → `EXTRACTION_FAILED`. Die drei Reparatur-Werkzeuge **nicht** in `compact`. |
| API-Suchanbieter (Brave, BrightData, DataForSEO, SearxNG, Tavily …) | ✘ | ✔ `web-search-providers`, `research-agent-runtime …/search/provider/*` | ✘ | **NICHT** (jetzt) | Kostenpflichtig, Schlüsselverwaltung (`AesGcmSecretCipher`), AsyncHttpClient-Abhängigkeit. Später optional hinter dem Port `WebSearchProvider`, nie Standard. SearxNG (selbst gehostet) wäre der erste Kandidat. |

### 4.3 Navigation, Links, Interaktion, Tabs (AP C)

| Funktion | A | K | E | Entscheidung | Belege und Begründung |
|---|---|---|---|---|---|
| `web_open`, `web_read` | teilweise (`browser_navigate` + `browser_snapshot`) | ✔ `BrowserSession.open/currentPage`, Rendering `BrowserMcpSidecarMain.render` | ✔ `ChatBrowser.navigate/read` | **W** (Vertrag), **AN** (Umsetzung über Snapshot aus 4.1) | |
| `web_links` mit stabilen IDs pro Snapshot | ✘ | ✔ `PlaywrightBrowserSession.checkedSnapshot` (`link-1..n`, Invalidierung bei Navigation) | ✔ `ChatBrowser.links(tab,max,filter)`, Region (main/nav/footer) in `LINKS_SCRIPT` | **AN** | AskAI-IDs + Enterprise-Filter und Region. Anker **aus `page.content()` mit jsoup** statt `page.evaluate("querySelectorAll('a[href]')")` (`Playwright4jDriver.state`). |
| `web_follow(id)` | ✘ | ✔ `PlaywrightBrowserSession.follow` (öffnet URL) | ✔ `ChatBrowser.follow` | **W** | Navigation per URL ist Stufe 0. Option später: echter Klick auf den Link, falls Seiten Referrer/Klickpfad verlangen. |
| `web_back` | ✘ | ✔ `Playwright4jDriver.back` (`page.goBack`) | ✔ eigene Historie `ChatBrowserRuntime.push/remember` (Ziel wieder durch Guard) | **AN** | Browser-History (`goBack`) und Recherche-Historie (Liste besuchter URLs) getrennt halten, wie Auftrag C4. |
| Controls-Liste mit IDs (`web_controls`) | ✘ | ✘ | ✔ `ChatBrowser.controls`, `CONTROLS_SCRIPT` | **AN** | Enterprise markiert Elemente mit `data-eai-control` und Globals → **verboten**. Ersatz: `page.locator("body").ariaSnapshot()` bzw. `getByRole` (Utility-Welt) → Liste aus Rolle + Name; ID = Index in diesem Snapshot, Auflösung später über `getByRole(role, name).nth(k)`. |
| Semantischer Locator `role=button name="…"`, `label="…"` | ✘ | ✘ | ✔ `ControlLocator.find` (exakt vor teilweise, Mehrdeutigkeit = Fehler mit Namen) | **W** (Algorithmus) | Reine Java-Logik über einer Kontrollenliste; passt direkt auf die ARIA-Liste. Alternativ Playwright `getByRole/getByLabel` mit `strict`. |
| Klick/Füllen als echte Eingabe | ✔ `locator.click/fill` | ✔ `HumanPointer.moveAndClick`, `HumanInteractionPolicy` (Pfade, Pausen, Tippen; Env `ASKAI_HUMAN_*`) | ✔ `InputActions.click/typeReplacing`, `HumanInput`, `actReally` (Bounding-Box, Verdeckungsprüfung, `performActions`) | **W** (`HumanInteractionPolicy`, `HumanPointer`), **AN** (Bounding-Box aus `locator.boundingBox()` statt `PREPARE_FUNCTION`) | Kein Skript-Fallback (`ACT_FUNCTION` setzt `value` per Prototyp-Setter und feuert synthetische Events → `isTrusted=false`, detektierbar). |
| Tabs auflisten/öffnen/aktivieren/schließen/navigieren/lesen | ✘ (eine `Page`) | teilweise (Challenge-Tab) | ✔ `ChatBrowser.tabs/newTab/selectTab/closeTab/navigate(tabId)/read(tabId)`, chat-lokale IDs | **AN** | API-Gedanke übernehmen, Umsetzung über `BrowserContext.pages()`/`newPage()`/`Page.close()`/`bringToFront()`. IDs `tab-1..n`, nie wiederverwendet. |
| Screenshot mit Markierungen | ✔ (ohne) | ✘ | ✔ `screenshot(tab, controlId, marks)` per DOM-Overlay | **AN** | Markierungen Java-seitig zeichnen; Passwortfelder ebenfalls Java-seitig schwärzen (Boxen aus `locator("input[type=password]").boundingBox()`). |

### 4.4 Extraktion, Dokumente, Batch, Netzwerk (AP D–G)

| Funktion | A | K | E | Entscheidung | Belege und Begründung |
|---|---|---|---|---|---|
| `browser_inspect` (CSS) | ✔ Hauptwelt | ✘ | ✘ | behalten (`legacy`/`expert`), **AN** | Auf Utility-Welt umstellen ist möglich (`locator(sel).all()` + `innerText/getAttribute`), dann Stufe 1. |
| `browser_extract(selector, fields)` | ✘ | ✘ | ✘ | **NEU** | Java-seitig mit jsoup über `page.content()` (CSS-Selektoren, `a@href` → absolut gegen Basis-URL), harte Grenzen. Stufe 1. |
| `browser_evaluate` | ✔ | ✘ | ✘ | behalten | Stufe 3; Beschreibung um Tarnungswarnung ergänzen. |
| Dokument-Download | ✘ | ✘ (Downloads abgelehnt, `Browser.NewContextOptions.setAcceptDownloads(false)`) | ✔ `ChatBrowser.download`, `BrowserDocument.MAX_BYTES`, `FETCH_FUNCTION` (In-Page-fetch) | **AN** | Konzept + Metadaten übernehmen; technisch über eigenen Tab + `response.body()` oder `waitForDownload`, aktueller Tab bleibt. Temp-Verzeichnis unter `~/.argos/downloads`, TTL, Größenlimit. |
| Dokument → Text | ✘ | ✘ | ✔ `AttachmentTextExtractor` → `DocumentAttachmentTextExtractor`, `document-tika` (Tika-Standardparser) | **AN** | Tika-Standardparser sind sehr groß. Empfehlung: PDFBox für PDF, Text/CSV/XML/HTML Java-seitig, Office über Apache POI nur falls Größe akzeptabel; Port `DocumentTextExtractor` damit spätere Tika-Nutzung austauschbar bleibt. |
| Batch `browser_open_many` | ✘ | ✘ | ✔ `ChatBrowserRuntime.openMany/loadWave`, `BidiBrowserConnection.navigateAll` (`ReadinessState.NONE` + Polling), Zufallspause `pauseBeforeTab`, Host einmal pro Batch prüfen, Ergebnis pro URL | **AN** | Echte Parallelität mit **einem** Playwright-Thread ist nur möglich, wenn Navigationen ohne Blockieren gestartet werden. `page.navigate` blockiert bis mindestens `COMMIT`. Messen (R6), sonst ehrlich sequenziell. |
| Netzwerkprotokoll mit IDs, Dauer, MIME, Headern, Body, HAR | teilweise (`record`, 200 Ereignisse) | ✘ | ✘ | **NEU** | `Request.timing()`, `Response.allHeaders()`, `Response.body()` (BiDi-Data-Collector) – Stufe 0. Redaction-Liste (Authorization, Cookie, Set-Cookie, Token-Muster). Bodies nur auf Anforderung und begrenzt. |
| Fehlerkategorien `SUCCESS … INTERNAL_ERROR` | ✘ | teilweise (`LegacySearchAttemptOutcome`, `BROWSER_CLOSED_MESSAGE`, `PlaywrightReadiness.Status`) | ✔ `BrowserException.Kind` (`INVALID_REQUEST`, `BROWSER_CLOSED`, `FAILED` …) | **AN** | Gemeinsames `ArgosOutcome`-Enum in Argos; erste Zeile in jedem Werkzeug-Ergebnis, z. B. `BLOCKED: …`. |

### 4.5 Laufzeit, Recherche, Betrieb (AP H, Querschnitt)

| Funktion | A | K | E | Entscheidung | Belege und Begründung |
|---|---|---|---|---|---|
| Einzel-Owner-Thread | ✔ `onBrowser` | ✔ `BrowserSessionActor` (+ `ThreadOwnershipGuard`, Event-Pump `Playwright4jDriver.pumpEvents`) | ✔ Monitor pro Chat | **AN** | Argos-Executor behalten; **Idle-Pump** aus AskAI übernehmen, damit Netzwerk-/Popup-Events auch zwischen MCP-Aufrufen ankommen (`page.waitForCondition` in Zeitscheiben). |
| Erkennen „Benutzer hat Browser geschlossen“, Wiederanlauf | ✘ | ✔ `Playwright4jDriver.browserClosedByUser`, `BROWSER_CLOSED_MESSAGE` | ✔ `ChatBrowserRuntime.recover/ensureConnected`, Lesen einmal wiederholen | **AN** | Für Abnahme „Browser-Neustart“. |
| Popups sofort schließen | ✘ | ✔ `Playwright4jDriver.popupCloser` | ✘ | **AN** | In `expert` lieber als neuer Tab melden statt schließen. |
| Research-Loop, Budgets, Stop-Gründe | ✘ | ✔ `ResearchLoop`, `ResearchRunBudget` (30/20/8/3/10 min), `ResearchStopReason`, `WebSearchApplicationService` (1867 Zeilen) | ✘ | **AN** (später, getrennt) | Nicht in den Browserkern. Wenn überhaupt: eigenes Paket `com.aresstack.argos.research`, nur über die eigenen Werkzeug-Ports. |
| Reranking BM25 | ✘ | ✔ `Bm25CandidateReranker` | ✘ | **W** (optional) | Klein, modellfrei; nützlich für `web_search`-Sortierung oder `web_links`-Filter. |
| Readiness-Urteil, Ähnlichkeit Seite ↔ Snippet | ✘ | ✔ `HeuristicPageReadinessJudge`, `PageContentSimilarity` (NaN = keine Meinung) | ✘ | **W** (Heuristik) | Modellvariante nur als Port. |
| Research-HUD in der Seite | ✘ | ✔ `ResearchHudOverlay` + `exposeBinding("__askaiHudCommand")` | ✘ | **NICHT** | Verletzt Stufe „verboten“. Ersatz: Add-on (Stufe 2). |
| Request-Interception gegen SSRF | ✘ | ✔ `context.route("**/*")` in `Playwright4jDriver.launch` | ✘ | **NICHT** (vorerst) | Route-Interception verzögert jeden Request messbar und hängt am Event-Pump. Für Argos genügt die Richtlinie vor/nach Navigation; Subrequests der Seite nicht filtern. |
| Low-Level-BiDi (WD4J, `de.bund.zrb.*`) | ✘ | ✘ | ✔ `browser-firefox` | **NICHT** | Auftrag; Argos bleibt bei Playwright4J. |
| Capability-Probe mit spezifischem Status | teilweise (`--verify-runtime`) | ✔ `PlaywrightCapabilityProbe`, `PlaywrightReadiness.Status` | ✘ | **AN** | `browser_status` um Status (READY/BROWSER_NOT_CONFIGURED/START_FAILED) erweitern. |
| Parent-Death-Watchdog | ✘ | ✔ `ParentDeathWatchdog` | ✘ | **NICHT** | Argos wird vom Webserver verwaltet, nicht per stdin. |

---

## 5. Architekturvorschlag

### 5.1 Paketstruktur (ein Gradle-Modul)

```text
com.aresstack.argos
├── ArgosApplication, ArgosConfig, ArgosRootMcpServlet, ArgosMcpAuthorizationFilter   (unverändert, Config +profile)
├── mcp/            ToolProfile (LEGACY|COMPACT|EXPERT), ToolCatalog, LegacyTools (= heutiges ArgosTools),
│                   CompactTools, ExpertTools, ToolText (nummerierte Ausgabe, Outcome-Zeile)
├── browser/        CamoufoxBrowser (Executor, Lebenszyklus), BrowserTabs, PageSnapshot, PageReadiness,
│                   DocumentKind/ContentReader, HumanInput (aus AskAI HumanInteractionPolicy/HumanPointer),
│                   NetworkLog, UrlPolicy
├── page/           HtmlPage (jsoup über page.content()), LinkSnapshot, ControlSnapshot, ControlLocator (Enterprise),
│                   Extractor (browser_extract)
├── search/         SearchEngine/Catalog, EngineNavigation, RedirectResolver, ConsentGuard, ChallengeGuard,
│   └── analysis/   übernommener AskAI-Quelltext (Analyzer, Scorer, Clusterer, BlockDetector, …)
├── document/       DocumentStore (Temp, TTL, Limit), DocumentTextExtractor (Port) + PdfBoxExtractor
└── research/       (später, optional) Budgets, Stop-Gründe, BM25
```

Ports-and-Adapters ohne zusätzliche Module: Die Werkzeugklassen sprechen nur mit `CamoufoxBrowser`-Methoden
mit fachlichen Namen (`openPage`, `readCurrentPage`, `listLinks`, `followLink`, `goBack`, `search`), die
Analyse-Pakete kennen Playwright nicht (testbar ohne Browser über HTML-Fixtures).

### 5.2 Werkzeugprofile

Konfiguration: `ARGOS_TOOL_PROFILE` (Env) bzw. `tools.profile` (Properties), Werte `legacy` (Standard),
`compact`, `expert`. Ungültiger Wert → Startfehler. `serverInfo` meldet das Profil im Versionsstring nicht
(Clients cachen), aber `browser_status`/`web_status` zeigt es.

| Werkzeug | Parameter | legacy | compact | expert | Stufe |
|---|---|:-:|:-:|:-:|:-:|
| `browser_status` | – | ✔ | – | ✔ | 0 |
| `browser_navigate` | `url` | ✔ | – | ✔ | 0 |
| `browser_snapshot` | `maxCharacters` | ✔ (mit XML-Fix) | – | ✔ | 1 |
| `browser_inspect` | `selector`, `limit` | ✔ | – | ✔ | 3 → 1 |
| `browser_click` / `browser_fill` | `selector`(, `text`) | ✔ | – | ✔ | 1 |
| `browser_evaluate` | `expression` | ✔ | – | ✔ | 3 |
| `browser_network` | – | ✔ | – | ✔ (+ `browser_network_detail(id)`) | 0 |
| `browser_screenshot` | – | ✔ | – | ✔ (+ `marks`) | 0 |
| `web_search` | `query` | – | ✔ | ✔ | 0/1 |
| `web_open` | `url` | – | ✔ | ✔ | 0/1 |
| `web_read` | – | – | ✔ | ✔ | 1 |
| `web_links` | (`filter` optional) | – | ✔ | ✔ | 1 |
| `web_follow` | `link_id` | – | ✔ | ✔ | 0 |
| `web_back` | – | – | ✔ | ✔ | 0 |
| `web_controls`, `web_click(id)`, `web_fill(id,text)` | 1–2 | – | **erst nach Modelltest** | ✔ | 1 |
| `browser_wait` | `selector`, `state`, `timeoutMs` | – | – | ✔ | 1 |
| `browser_tabs`, `browser_tab_new(url)`, `browser_tab_select(id)`, `browser_tab_close(id)` | ≤ 1 | – | – | ✔ | 0 |
| `browser_act(locator, action, value)` | 3 | – | – | ✔ | 1 |
| `browser_extract(selector, fields)` | 2 | – | – | ✔ | 1 |
| `browser_document(link_id \| url)` | 1 | – | – (Kandidat) | ✔ | 0 |
| `browser_open_many(urls)` | 1 | – | – | ✔ | 0/1 |

Regeln für `compact`: höchstens zwei Parameter, englische Kurzbeschreibung mit Beispiel
(`Example: web_search {"query":"camoufox webdriver bidi"}`), Textausgabe statt JSON, erste Zeile ist bei
Fehlern immer `<OUTCOME>: <kurzer Satz mit nächstem Schritt>`, z. B.
`CHALLENGE_REQUIRED: bing.com shows a CAPTCHA. Try web_search again later or open another result.`
`legacy` bleibt bitgenau die heutige Liste (`ArgosToolsTest` unverändert).

### 5.3 Ablauf `web_open` (Referenz für AP A/C)

1. `UrlPolicy.check(url)` → `BLOCKED`/`INVALID_REQUEST`.
2. `page.navigate(url, waitUntil=COMMIT)` → `Response` (Status, Content-Type).
3. `UrlPolicy.check(page.url())` nach Redirect; sonst `about:blank` und `BLOCKED`.
4. Nicht-HTML: `response.body()` begrenzt lesen → `ContentReader` je Typ (XML/RSS/JSON/Text/Binär).
5. HTML: `PageReadiness.await(profile)` (load-Event, Netzwerk-Ruhe, Inhalts-Ruhe über `page.content()`-Hash,
   optional Ladeindikator weg, Deadline). Keine festen Sleeps.
6. Challenge/Consent prüfen (Java-seitig über HTML); Consent nur nach konfigurierter Regel per echter Maus.
7. `PageSnapshot` aus HTML (Text, Links mit IDs), Ausgabe `URL/TITLE/TYPE/STATUS` + Text.

---

## 6. Wiederverwendungskandidaten (konkret)

| Quelle | Klassen | Ziel in Argos | Art |
|---|---|---|---|
| AskAI `browser-search-analysis` | `SearchPageMechanicalAnalyzer`, `SearchPageContainerScorer`, `DomStructureClusterer`, `SearchResultBlockDetector`, `PrimaryResultLinkResolver`, `SnippetExtractor`, `LegacySearchResultExtractor`, `SearchPageRegionClassification`, `HeuristicScoreBreakdown`, `HeuristicSignal`, `SignalFamily`, `DetectedResultBlock`, `SearchPageLayoutResolution` | `search.analysis` | Quelltext kopieren, Herkunft im Klassenkommentar, Tests mitnehmen |
| AskAI `browser-api` | `render/*` (`RenderedPageDocument`, `RenderedContainerDescriptor`, `RenderedLinkDescriptor`, `DomStructureSignature`, `LinkRedirectResolution` …), `search/SearchResultCandidate`, `SearchResultExtractionResult`, `SearchPageAnalysisSettings`, `SearchResultExtractionSettings`, `domain/*`, `input/HumanInteractionPolicy`, `input/HumanPointer`, `BrowserPageReadiness` | `search.analysis`, `browser` | kopieren, Settings auf benötigte Felder kürzen |
| AskAI `browser-mcp-server-java21` | `SearchRedirectResolver`, `SearchPageGuards` (nur Listen/Prioritäten), `PlaywrightBrowserSession.navigateAndCaptureSearchEngines`, `BrowserMcpSidecarMain.registerTools` (Texte, Format) | `search`, `mcp` | anpassen (kein `page.evaluate`) |
| AskAI `browser-mcp-server-java21` | `RenderedPageDocumentCapture` | – | **neu schreiben** als `HtmlRenderedPageBuilder` (jsoup) mit gleichem Ausgabevertrag |
| AskAI `research-agent-runtime` | `SearchUrlNormalizer`, `Bm25CandidateReranker`, `AccessBlockSignals`, `HeuristicPageReadinessJudge`, `ResearchStopReason`, `ResearchRunBudget` | `search`, `research` | kopieren (später) |
| Enterprise `browser-runtime` | `ControlLocator.find`, Batch-Regeln aus `ChatBrowserRuntime.openMany` (Dedup, Host einmal prüfen, Ergebnis pro URL, Zufallspause), `usableUrl`, `isDocumentPath`, `fileNameOf` | `page`, `browser` | Algorithmus übernehmen |
| Enterprise `browser-api` | `ChatBrowser` (Tab-API), `BrowserBatchPage`, `BrowserDocument`, `BrowserAccessPolicy` (Regelsyntax) | `browser`, `document` | Verträge als Vorlage |
| Enterprise `browser-firefox` | `BidiBrowserConnection.isLoadSettled/isRedirectSettled` (reine Entscheidungsfunktionen), `QUIET_MILLIS`-Idee | `browser.PageReadiness` | Logik übernehmen, Messung anders |
| Playwright4J Contract-Tests | `BrowserSignalsProbe` | `src/test/.../StealthSignalsTest` (opt-in) | Messverfahren übernehmen |

Neue Abhängigkeiten: `org.jsoup:jsoup` (HTML-Parsing, MIT), `org.apache.pdfbox:pdfbox` (AP E). Beide klein
genug für `argos.jar`.

---

## 7. Risikoanalyse

| # | Risiko | Wirkung | Gegenmaßnahme | Wer |
|---|---|---|---|---|
| R1 | Argos verrät die Automatisierung durch eigene Spuren, obwohl Camoufox `navigator.webdriver` verbirgt (bekanntes Beispiel: früheres Preload-Script → Blockierungen) | Seiten wie ResearchGate blocken | Preload-/Init-Scripts, Bindings, Globals, DOM-Markierungen verboten (Abschnitt 3); Code-Review-Regel. **Regressionstest** nach `BrowserSignalsProbe`-Verfahren (opt-in, lokal): `navigator.webdriver`, Globals mit Automatisierungsnamen, `TestUtils`, `userActivation`, Fenstergeometrie – vor und nach jedem Arbeitspaket gleich. | laufend |
| R2 | `browser_evaluate` erlaubt dem Modell beliebigen Code, der auch bleibende Spuren setzen kann | Tarnung hängt vom Modell ab | in `legacy`/`expert` belassen; Beschreibung bittet um reine Lesefunktionen. `browser_inspect` ist flüchtig und darf bleiben. | AP D |
| R3 | Auch Utility-Welt-Aufrufe von Playwright senden `userActivation: true` (laut Playwright4J nur der `1+1`-Aufruf entschärft) | `navigator.userActivation.hasBeenActive` ohne echte Eingabe nach reinem Lesen | messen; ggf. Playwright4J-Issue „userActivation=false für Utility-Lesezugriffe“. Kein Argos-Workaround. | mit R1 |
| R4 | Seitenerfassung ohne Geometrie verschlechtert die SERP-Analyse | mehr `EXTRACTION_FAILED` | Fixtures von DDG/Bing/Google mit und ohne Geometrie vergleichen; Geometrie für Top-N-Container über `boundingBox()` nachreichen; ehrliches `EXTRACTION_FAILED` ist akzeptabel | AP B |
| R5 | Eigenes Add-on könnte über Ressourcen/Timing erkennbar sein; unsignierte Add-ons brauchen Pref | Tarnung | kein `web_accessible_resources`, keine DOM-Änderungen, Content-Script nur auf Anforderung; Camoufox-Add-on-Mechanismus nutzen; vor Einsatz mit R1-Verfahren messen | später |
| R6 | Parallelität mit Single-Thread-Executor | `browser_open_many` wäre nur sequenziell | Messung mit verzögertem lokalem Server: N Tabs, `navigate(COMMIT)` nacheinander, dann Bereitschaft je Tab; Laufzeit < N × Einzelzeit nachweisen. Sonst sequenziell und so dokumentieren. Kein zweiter Playwright-Thread. | AP F |
| R7 | Kein Idle-Event-Pump | Netzwerk-Events/Popups stauen sich zwischen Aufrufen | Pump aus `BrowserSessionActor` übernehmen, Zeitscheiben ≤ 250 ms | AP A/G |
| R8 | Fehlertexte aus Playwright enthalten URLs mit Tokens | Geheimnisse im Werkzeugergebnis | zentrale `ErrorText.safe(e)`: erste Zeile, Query-Redaction, keine Header | Querschnitt |
| R9 | CI-Smoke prüft feste Werkzeugnamen | Profilwechsel bricht CI | Standard `legacy`; zusätzlicher Smoke-Schritt mit `ARGOS_TOOL_PROFILE=compact` prüft `web_search` | Profil-Thread |
| R10 | Google-SERP reagiert empfindlich (CAPTCHA, Consent `consent.google.com`) | Suchausfälle | Google nicht an erste Stelle der Standardreihenfolge (DDG html → Bing → Google), Pausen pro Engine (`delayMillisFor`), `CHALLENGE_REQUIRED` statt Wiederholung | AP B |
| R11 | Mehrere Threads arbeiten parallel an `ArgosTools`/`CamoufoxBrowser` | Merge-Konflikte | Profil-Thread führt `mcp/`-Pakete ein; andere Pakete ergänzen nur Methoden in `CamoufoxBrowser` mit fachlichen Namen und eigene Klassen; `legacy` nicht anfassen | alle |
| R12 | `argos.jar`-Größe durch Parser | langsamer Start, große Releases | kein Tika-Standardpaket; PDFBox reicht zunächst | AP E |
| R13 | Playwright4J-Issue #34 (zwei Fenster bei persistentem BiDi-Start unter Windows) | sichtbares Fremdfenster, Tab-Liste unvollständig | bei Tab-Verwaltung nur `context.pages()` als Wahrheit; Fix aus Playwright4J abwarten | AP C |

---

## 8. Reihenfolge der Umsetzung

| Schritt | Inhalt | Abhängig von | Abnahme |
|---|---|---|---|
| 0a | Stealth-Regressionstest (R1, R3) als opt-in Test | – | Bericht mit `navigator.webdriver`, Globals, `userActivation`, `TestUtils`; Basiswert für alle Pakete |
| 0b | `ToolProfile` + `mcp/`-Gerüst, `legacy` = heutige Liste | – | `ArgosToolsTest` grün, neuer Test je Profil |
| 1 | **AP A**: `PageSnapshot`, Content-Type-abhängiges Lesen, `PageReadiness`, Redirect-Policy, `ErrorText` | 0b | Fixtures HTML/XML/RSS/JSON/text/SPA ohne Timeout |
| 2 | **compact**: `web_open/read/links/follow/back` über AP A | 1 | MCP-Test über lokalen Server |
| 3 | **AP B**: Analyse-Quelltext übernehmen, `HtmlRenderedPageBuilder`, Engine-Navigation DDG/Bing/Google, Consent/Challenge, `web_search` | 1, (0a) | Fixtures je Engine + unbekanntes Layout → `EXTRACTION_FAILED` |
| 4 | **AP C/D**: Tabs, ARIA-Controls + `ControlLocator`, `browser_act`, `browser_wait`, `browser_extract`, Historie | 1 | 3 Tabs unabhängig; Mehrdeutigkeit → Fehler |
| 5 | **AP E/F**: Dokumente (Tab + `response.body()`, PDFBox), Batch nach Parallelitätsmessung | 4 | PDF lesbar, aktuelle Seite bleibt; Batch mit defekter URL |
| 6 | **AP G**: Netzwerkprotokoll mit IDs, Redaction, Detail-Werkzeug | 1 | Secrets maskiert |
| 7 | Add-on-Prototyp (HUD/Geometrie), nur wenn 0a es rechtfertigt | 0a, 3 | Messung zeigt keine neuen Signale |
| 8 | **AP H** optional + Modellvergleich (kleine/große Modelle, Protokollfelder aus Auftrag §13) | 2–6 | Protokoll, keine Verschlechterung für `compact` |

---

## 9. Offene Fragen an den Entwickler

1. ~~BiDi oder Juggler?~~ Beantwortet (2026-10-25): Camoufox verbirgt `navigator.webdriver` selbst;
   Laufzeit-Auswertung war unproblematisch, Preload-Scripts führten zu Blockierungen. Eingearbeitet in
   Abschnitt 0, 2, 3 und 7.
2. Soll `compact` später der Standard werden, sobald der ChatGPT-Connector umgestellt ist?
3. Darf Argos ein eigenes (unsigniertes) Firefox-Add-on im Argos-Profil installieren?
