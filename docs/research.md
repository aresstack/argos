# web_research: Rechercheorchestrierung (Arbeitspaket H)

Quelle: `Miguel0888/askai-java8`, Branch `arch`, Modul `research-agent-runtime`:
`loop.ResearchLoop`, `acquire.WebSearchApplicationService`, `loop.ResearchRunBudget`,
`loop.ResearchStopReason`, `acquire.WebAcquisitionText`. Ziel: `com.aresstack.argos.research`.

## Einordnung

Die Recherche ist **optional** und vom Browserkern getrennt. Sie hängt nur an Ports, nicht an
Playwright:

| Port | Standard-Adapter | Austauschbar gegen |
|---|---|---|
| `WebSearchPort` (aus `web`) | `BrowserWebSearch` (web_search-Kette) | jede andere Suche, auch eine API (nie automatisch kostenpflichtig) |
| `ResearchPageReader` | `CamoufoxResearchPageReader` (`loadHtml` + jsoup) | z. B. Tab-/Dokument-Leser aus anderen Arbeitspaketen |
| `RelevanceScorer` | `LexicalRelevanceScorer` (Begriffsabdeckung) | Modell-Reranker, z. B. AskAIs Cross-Encoder |
| `QueryPlanner` | `QueryPlanner.VERBATIM` (Frage unverändert) | Query Expansion durch ein Modell |
| `ResearchListener` | MCP-Progress-Notifications in `ResearchTools` | Logging, UI |

Ohne `ARGOS_RESEARCH=true` wird nichts davon instanziiert; Argos verhält sich wie bisher.
Kein Modell ist nötig, keine bezahlte Such-API wird angesprochen.

## Ablauf eines Laufs (`ResearchLoop`)

1. **Suchplanung:** `QueryPlanner` liefert Suchanfragen, höchstens `maxSearches`. Schlägt die Planung
   fehl, wird die Frage selbst gesucht (Notiz `PLAN: …`).
2. **Kandidaten:** Treffer werden auf Titel+Snippet bewertet; relevante zuerst (bei Gleichstand gilt
   der Suchmaschinenrang), die übrigen dahinter. Die niedrigste Bewertung der relevanten Treffer ist
   die **Messlatte für Links** (AskAI `seedSerpRelevanceFloor`).
3. **Zentrales Budget-Tor vor jeder Suche und jedem Seitenaufruf** (AskAI `stopReasonNow`):
   Abbruch → ausreichende Evidenz → Quellen → Seiten → Fehler in Folge → Laufzeit.
4. **Seite lesen** über den Port; danach:
   - Weiterleitung auf eine schon gelesene Seite → Duplikat. Kanonische Form ignoriert Schema,
     `www.`, abschließenden Slash, Fragment und Tracking-Parameter.
   - `BLOCKED`/`CHALLENGE` (HTTP 401/403/407/429/451, CAPTCHA-URL, kurze Seite mit CAPTCHA-Text) →
     die ganze Site-Familie wird für den Lauf gesperrt, nie erneut geöffnet, CAPTCHAs nie gelöst.
   - Suchmaschinen-Seiten (`PROVIDER:`-Familien) sind Transit, nie Quelle.
   - Nicht relevante Seite → abgelehnt und **nicht** nach Links durchsucht (AskAI: eine Seite, die
     nicht zum Thema gehört, ist keine Brücke dorthin).
   - Gleicher Text unter anderer Adresse (Spiegel) → Duplikat.
5. **Quelle annehmen** mit kurzem Zitat (Satzfenster mit den meisten Fragebegriffen).
6. **Begrenzte Linkverfolgung** (AskAI `selectLinksToFollow`): nur Ankertext zählt, nie die URL;
   höchstens 60 Links bewertet, die besten `maxLinksPerPage` oberhalb der Messlatte kommen in die
   Frontier, bis `maxLinkDepth`. Dokument-Links (PDF, Office, CSV, ZIP …) werden **gelistet, nicht
   geöffnet**, damit die Dokument-Werkzeuge sie gezielt holen können.

## Stop-Gründe (`ResearchStopReason`)

| Grund | Bedeutung |
|---|---|
| `SUFFICIENT_EVIDENCE` | mind. `minimumSources` Quellen auf mind. `minimumDistinctHosts` Sites |
| `PAGE_BUDGET_EXHAUSTED`, `SOURCE_BUDGET_EXHAUSTED`, `TIME_BUDGET_EXHAUSTED`, `ERROR_BUDGET_EXHAUSTED` | Grenze erreicht, Teilergebnis |
| `NO_RELEVANT_PATHS` | alle Kandidaten gelesen oder verworfen |
| `NO_SEARCH_RESULTS` | Suche lief, ehrlich keine Treffer |
| `CHALLENGE_REQUIRED` | Suchmaschinen zeigen CAPTCHA, nichts gesucht |
| `SEARCH_FAILED` | Suche technisch fehlgeschlagen (≠ leere Suche) |
| `CANCELLED` | `web_research_stop` oder Unterbrechung |

## Ausgabe für kleine Modelle

```
Research: camoufox fingerprint rotation
Stopped: SUFFICIENT_EVIDENCE (3 sources from 3 sites, 4 pages read, 1 search, 21 s)
1: Camoufox fingerprint rotation — https://a.example/one
   Camoufox rotates its fingerprint per session. …
2: …
Documents (not opened):
- https://a.example/paper.pdf
SEARCH: "camoufox fingerprint rotation" 10 hits
SKIPPED: 1 not relevant, 0 blocked, 0 duplicates
NEXT: web_open a source number's url to read it in full.
```

Jeder Stop-Grund hat eine `NEXT:`-Zeile, die dem Modell den nächsten sinnvollen Schritt nennt.

## Konfiguration

| Variable | Standard | Bedeutung |
|---|---|---|
| `ARGOS_RESEARCH` | `false` | `true` veröffentlicht `web_research` und `web_research_stop` |
| `ARGOS_RESEARCH_MAX_SEARCHES` | 2 | Suchanfragen pro Lauf (1–5) |
| `ARGOS_RESEARCH_MAX_PAGES` | 8 | Seitenaufrufe pro Lauf (1–50) |
| `ARGOS_RESEARCH_MAX_SOURCES` | 5 | Quellen, bei denen der Lauf in jedem Fall endet |
| `ARGOS_RESEARCH_MIN_SOURCES` | 3 | Quellen für `SUFFICIENT_EVIDENCE` |
| `ARGOS_RESEARCH_MIN_SITES` | 2 | verschiedene Sites für `SUFFICIENT_EVIDENCE` |
| `ARGOS_RESEARCH_MAX_SECONDS` | 120 | Laufzeit |
| `ARGOS_RESEARCH_LINKS_PER_PAGE` | 3 | verfolgte Links pro relevanter Seite (0–10) |
| `ARGOS_RESEARCH_LINK_DEPTH` | 2 | 0 = nur Suchtreffer |

Die Standards sind kleiner als AskAIs 30 Tool-Calls / 20 Seiten / 10 Minuten, weil ein Lauf **ein**
MCP-Toolaufruf ist und MCP-Clients Zeitlimits haben. Fortschritt geht als MCP-Progress-Notification
an Clients, die ein `progressToken` mitschicken.

## Stealth

Nur Stufe 0/1 aus `docs/argos-optimization-analysis.md`: Navigation und `page.content()`; Text und
Links werden in der JVM mit jsoup gewonnen. Kein `evaluate`, keine Init-/Preload-Skripte, keine
Bindings, keine DOM-Markierungen, keine Klicks. Gesperrte Sites werden nicht erneut angefragt.

## Bewusst nicht übernommen

- Host-Zustandsmaschine, ACP, `source_accept`/`finding_add`, HUD (Pause/Skip/Delay), Scope-Control:
  gehören zu AskAIs Research-Workspace, nicht zu einem Browser-MCP-Server.
- Pflicht-Reranker mit typisierten Reranker-Stop-Gründen: in Argos optional hinter `RelevanceScorer`.
- Warten auf vom Benutzer gelöste CAPTCHAs innerhalb des Laufs: blockiert einen MCP-Aufruf
  minutenlang. Stattdessen `CHALLENGE_REQUIRED` + Hinweis, die Challenge im Argos-Fenster zu lösen.
- Seitenbereitschafts-Wiederholungen und Consent-Klicks auf Zielseiten: Seitenbereitschaft liefert
  Arbeitspaket A; Consent-Klicks bleiben auf Suchseiten beschränkt.

## Grenzen und Folgearbeiten

- Ein Lauf steuert die einzige Browserseite; parallel ist immer nur ein Lauf möglich
  (`ResearchBusyException`). Mit Tabs (Paket C) und Batch (Paket F) kann der `ResearchPageReader`
  später Hintergrund-Tabs nutzen, ohne die aktive Seite des Modells zu verlieren.
- Die lexikalische Bewertung kennt keine Synonyme. Ein Modell-Reranker oder Query Expansion kommen
  über `RelevanceScorer`/`QueryPlanner` dazu.
- Im Profil-Schema (PR #3) gehört `web_research` in `compact` und `expert`, sobald es gegen kleine
  Modelle getestet ist (Phase 7).

## Tests

- `ResearchServiceTest`: Ablauf gegen eine In-Memory-Welt mit manueller Uhr (Evidenz, Linkverfolgung,
  Duplikate/Weiterleitungen/Spiegel, gesperrte Sites, Suchmaschinen-Transit, alle Budgets, Such-
  fehler vs. leere Suche vs. CAPTCHA, Dokumente, Query-Planung, Abbruch, Parallelsperre, Fortschritt).
- `HtmlResearchPagesTest`, `ResearchUrlsTest`, `LexicalRelevanceScorerTest`, `ResearchSettingsTest`,
  `ResearchToolsTest`, `ArgosToolsTest` (Werkzeuge nur bei `ARGOS_RESEARCH=true`).
- `CamoufoxResearchTest` (opt-in, `-PtestCamoufox=true`): Lauf über echtes Camoufox gegen einen
  lokalen `HttpServer`.
