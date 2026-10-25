# web_search: Port der AskAI-Suchanalyse

Quelle: `Miguel0888/askai-java8`, Branch `arch`, Module `browser-search-analysis`,
`browser-mcp-server-java21` (Sidecar) und `browser-api`. Ziel: `com.aresstack.argos.search`.

## Ablauf

1. `WebSearchService` geht die Suchmaschinen in der konfigurierten Reihenfolge durch
   (`ARGOS_SEARCH_ENGINES`, Standard `duckduckgo,bing,google`). Die erste Maschine mit organischen
   Treffern beendet die Suche (AskAI `FIRST_USABLE`). Endpunkte einer Maschine (DuckDuckGo HTML → Lite)
   sind Ausweichwege desselben Anbieters.
2. Pro Ergebnisseite: HTML über `page.content()` holen, mit jsoup parsen, extrahieren.
3. Nur wenn das nicht zu Treffern führt: Consent-Banner suchen, **einmal** mit echter Eingabe klicken,
   erneut lesen. Ergebnisseiten werden nie angeklickt.
4. Danach erst CAPTCHA/Challenge-Prüfung. Eine Seite *mit* Treffern ist nie eine Challenge (eine Suche
   nach "captcha" darf nicht als Sperre gelten). Eine gesperrte Maschinenfamilie wird 10 Minuten
   übersprungen statt erneut angefragt; die Challenge bleibt nur sichtbar, wenn keine Ausweichmaschine
   mehr folgt.

## Ergebnisse sind ehrlich

| Outcome | Bedeutung | Folge |
|---|---|---|
| `ORGANIC_RESULTS` | Ergebnisblöcke gefunden | Suche endet |
| `NO_ORGANIC_RESULTS` | Seite sagt ausdrücklich "keine Treffer" | weitere Endpunkte derselben Maschine entfallen, nächste Maschine |
| `EXTRACTION_FAILED` | Layout nicht verstanden | nächster Endpunkt, dann nächste Maschine |
| `CHALLENGE_PENDING` | CAPTCHA erkannt oder Familie gesperrt | nächste Maschine |
| `NAVIGATION_FAILED` | Seite nicht erreichbar | nächster Endpunkt |

Nur wenn **keine** Maschine erreichbar war, meldet `web_search` einen Fehler; sonst kommt
"No results." mit den `ATTEMPT:`-Zeilen.

## Ausgabe für kleine Modelle

```
1: WebDriver BiDi - W3C — https://w3.org/TR/webdriver-bidi/
   This document specifies a bidirectional protocol for remote control of user agents.
2: …
PROVIDER: www.bing.com
ATTEMPT: html.duckduckgo.com NO_ORGANIC_RESULTS
ATTEMPT: www.bing.com ORGANIC_RESULTS
```

Format wie in AskAI, nur Treffer zuerst. `BrowserWebSearch` implementiert den Port
`com.aresstack.argos.web.WebSearchPort`, den das Kompakt-Profil nutzt.

## Was übernommen wurde

| AskAI | Argos | Status |
|---|---|---|
| `SearchPageMechanicalAnalyzer`, `SearchPageContainerScorer` | `ResultRegionAnalyzer` | portiert, ohne Geometrie/Farben |
| `SearchResultBlockDetector`, `DomStructureClusterer`, `PrimaryResultLinkResolver`, `SnippetExtractor` | `ResultBlockDetector` | portiert |
| `LegacySearchResultExtractor` (Outcomes, Link-Harvest, KI-Pfad) | `SearchResultExtractor` | portiert, Harvest verschärft |
| `SearchRedirectResolver` (Bing `/ck/`, Google `/url`, DDG `/l/`) | `SearchRedirects` | portiert |
| `SearchPageGuards` (Consent-Priorität Reject → Necessary → Accept → Close, Challenge-Marker) | `SearchPageGuards` | Logik portiert, Ausführung neu |
| `RenderedPageDocumentCapture` (In-Page-Skript) | `SerpDocumentParser` (jsoup) | **neu geschrieben** |
| `AiSearchPageLayoutResolver` | `SearchLayoutResolver` (Port, Standard `NONE`) | nur Vertrag + Validierung |
| `BrowserSearchEngineCatalog` (DDG, Bing) | `SearchEngine` | + Google, + bekannte Ergebnis-Selektoren |

## Bewusste Abweichungen

- **Keine Seiten-Skripte.** AskAI hat die Seite per `page.evaluate` im Hauptkontext vermessen und
  Consent mit `element.click()` geklickt (beides für die Seite beobachtbar, Klick mit
  `isTrusted=false`). Argos liest nur `page.content()`/`title()` und Locator-Abfragen (isolierte
  Playwright-Welt) und klickt mit `page.mouse()` auf die Bounding-Box-Mitte (WebDriver-BiDi-Eingabe).
  Keine Init-/Preload-Skripte, keine Bindings, keine Markierungsattribute.
- **Klick nur über Rohmaus.** Das Element wird per `Locator.count()`/`boundingBox()` nur vermessen; kein
  `Locator.click()` oder `scrollIntoViewIfNeeded()` (deren Actionability-Logik löst seitenseitig sichtbare
  Marker-Events aus, playwright4j #38). Gescrollt wird mit dem Mausrad. Kein `route()`.
- **Sparsame Lesezugriffe.** `page.content()` sendet in Playwright4J `userActivation:true` (#37); pro
  Ergebnisseite wird genau einmal gelesen, nach einem Consent-Klick ein zweites Mal.
- **Ohne Geometrie- und Farbsignale.** Deshalb ist die Mindestkonfidenz 0,3 statt 0,5; die Fixtures
  liegen bei 0,34–0,8. Sichtbarkeit wird aus dem Markup geschätzt (`hidden`, `aria-hidden`, Inline-Style).
- **Adapter-Selektoren zuerst.** Je Endpunkt ein bekannter Ergebnisblock-Selektor (`div.result`,
  `li.b_algo`, `#rso div.g`). Greift er nicht mehr (Layoutänderung), übernimmt die mechanische Analyse.
- **Link-Harvest nur zum Auffüllen.** AskAI erntet auch bei unverstandenem Layout externe Links, weil
  dahinter ein Reranker sitzt. Argos hat keinen; Fremdlinks würden ein kleines Modell als "Treffer"
  in die Irre führen. Ausnahme: DuckDuckGo Lite, das Treffer nur als nackte Links listet.
- **Gewrappte Links zählen nach ihrem Ziel.** AskAI hat Bing-`/ck/`-Links in der Containerstatistik als
  Bing-intern gezählt; Argos zählt das aufgelöste Ziel.
- **KI-Resolver auch bei Region ohne Blöcke**, nicht nur bei niedriger Konfidenz.

## Tests

- `SearchResultExtractorTest`, `SearchPageGuardsTest`, `SearchRedirectsTest`: Fixtures unter
  `src/test/resources/serp/` (nachgebaute DDG-HTML/Lite-, Bing-, Google-, Consent-, CAPTCHA-Seiten).
- `WebSearchServiceTest`: Fallback-Kette, Familien-Sperre, Consent-Klick, Ausgabeformat mit Fake-Browser.
- `CamoufoxWebSearchTest` (opt-in, `-PtestCamoufox=true`): lokale Suchwelt per `HttpServer` hinter
  einer Consent-Wand, echter Camoufox-Klick.
