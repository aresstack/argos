# Convenience-Profil

`ARGOS_TOOL_PROFILE=convenience` ist das Standardprofil, auch wenn die Variable fehlt oder leer ist. Es ist ein eigener, klar getrennter Pfad (`com.aresstack.argos.convenience`, `search.ParallelWebSearch`, `ConvenienceTools`). `legacy`, `compact` und `expert` bleiben unverändert, ebenso die sequenzielle Suche (`WebSearchService.search`) dieser Profile.

## Werkzeuge

Es gibt genau diese neun Werkzeuge, dazu `search_history` und `search_history_export`, wenn die Search History aktiv ist. Expert-Browser- und Netzwerkwerkzeuge sind nicht dabei, und `ResearchTools` fehlen auch mit `ARGOS_RESEARCH=true`.

| Werkzeug | Parameter |
| --- | --- |
| `web_search` | `query` |
| `web_select_results` | `searchId`, `resultIds[]` (höchstens 10) |
| `web_select_links` | `links[{tabId, linkId}]` (höchstens 10) |
| `web_follow_link` | `tabId`, `linkId` |
| `web_read_tab` | `tabId`, `part?` |
| `web_back` | `tabId` |
| `web_open_url` | `url` (nur vom Nutzer genannte URLs) |
| `web_get_url` | `referenceId` (`r-…` oder `l-…`) |
| `web_close_tabs` | `tabIds[]` |

Die MCP-Serverinstruktion (nur in diesem Profil) lautet: *Every page response includes its available links with IDs. Navigate using those IDs. Never reconstruct, modify, or pass link URLs. Use URL lookup only when an actual URL is explicitly needed.*

## IDs

- **Suchen:** `s1`, `s2`, … Je Chat bleiben die letzten 10 Suchen gültig.
- **Ergebnisse:** `r-s1-1`, `r-s1-2`, … Sie gehören fest zu ihrer Suche.
- **Tabs:** `t1`, `t2`, … Die Nummern kommen vom Browser und werden nie wiederverwendet.
- **Links:** `l-t21-4` gehört zu Tab `t21`. Der Zähler pro Tab läuft weiter: Eine neue Seite im selben Tab bekommt neue Nummern, und die IDs der früheren Seite werden als veraltet abgelehnt.
- **Abgelehnt werden:** unbekannte IDs, IDs geschlossener Tabs und IDs anderer Chats. Ein Tab eines anderen Benutzerkontexts wird ebenfalls abgelehnt.
- **URLs:** Antworten enthalten nur Domains. Die exakte URL bleibt intern gespeichert, inklusive Query-Parametern wie `_sg`/`_tp` und Fragment, und wird unverändert zur Navigation verwendet. Ausgegeben wird sie nur mit `web_get_url`.

## web_search

1. Alle konfigurierten Suchmaschinen laufen gleichzeitig, jede in einem neuen Tab. `ARGOS_SEARCH_ENGINES` gibt die Menge an (Standard `duckduckgo,bing,google`), keine Fallback-Reihenfolge, und keine Suchmaschine wird übersprungen.
2. Die erste Navigation aller Suchmaschinen ist ein gemeinsamer paralleler Ladevorgang (Budget 20 s). Danach wertet jede Suchmaschine ihre Seite mit dem unveränderten `WebSearchService` aus, beschränkt auf diese eine Suchmaschine (Budget 15 s): Extraktion, Consent-Klick über echte BiDi-Eingabe und Challenge-Erkennung.
3. DuckDuckGo Lite ist nur der Wiederholungsversuch innerhalb des DuckDuckGo-Jobs und läuft im DuckDuckGo-Tab.
4. Status je Suchmaschine: `OK`, `NO_RESULTS`, `EXTRACTION_FAILED`, `CHALLENGE`, `CONSENT_REQUIRED`, `TIMEOUT` oder `NAVIGATION_FAILED`.
5. Bei `EXTRACTION_FAILED`, `CHALLENGE` und `CONSENT_REQUIRED` enthält `fallbackPage` den sichtbaren Text (höchstens 4000 Zeichen, mit `truncated`) und bis zu 40 Links mit IDs.
6. Die Gesamtsuche hat den Status `COMPLETE`, `PARTIAL` oder `FAILED`.
7. Die Ergebnisse werden deterministisch verschränkt: zuerst Rang 1 jeder Suchmaschine in Konfigurationsreihenfolge, dann Rang 2 und so weiter. Es gibt keinen Re-Ranker.
8. Doppelte werden nur bei exakt gleicher URL-Zeichenkette zusammengeführt. Jedes Ergebnis trägt `sources[{engine, rank}]`.
9. `ARGOS_CONVENIENCE_RESULTS_PER_ENGINE` legt die Zahl der Ergebnisse je Suchmaschine fest (Standard 10, höchstens 30).
10. Zu Beginn der nächsten Suche schließen sich die Suchmaschinen-Tabs der vorherigen Suche.
11. Search History: Jeder `web_search`-Aufruf erzeugt genau ein Ereignis. Es enthält alle Suchmaschinen mit Status als Provider und die verschränkte Trefferliste. Zusätzliche Seitenanfragen gibt es dafür nicht.

```json
{"searchId":"s1","query":"…","status":"PARTIAL",
 "engines":[{"id":"duckduckgo","status":"OK","tabId":"t4","resultCount":10},
            {"id":"bing","status":"EXTRACTION_FAILED","tabId":"t5","resultCount":0,
             "fallbackPage":{"title":"…","text":"…","truncated":false,"links":[{"linkId":"l-t5-1","text":"…","domain":"bing.com"}],"linksTruncated":false}}],
 "results":[{"resultId":"r-s1-1","title":"…","snippet":"…","domain":"example.org","sources":[{"engine":"duckduckgo","rank":1}]}]}
```

## Seiten öffnen

`web_select_results`, `web_select_links` und `web_open_url` öffnen jede Seite in einem neuen Tab und antworten so:

```json
{"opened":[{"resultId":"r-s1-3","tabId":"t21","title":"…","domain":"example.org","status":"OK","httpStatus":200,
            "contentType":"text/html","text":"…","part":1,"parts":2,"truncated":true,
            "links":[{"linkId":"l-t21-1","text":"…","domain":"example.org"}],"linksTruncated":false}],
 "skipped":[{"resultId":"r-s1-4","reason":"already open","tabId":"t9"}],
 "errors":[{"resultId":"r-s1-5","error":"HTTP 429 (access limited)","domain":"…","retryAfterSeconds":120}]}
```

- Die Reihenfolge in `opened` entspricht der Auswahl. Text und Links kommen immer zusammen.
- Begrenzungen:
  - Ein Teil umfasst 12 000 Zeichen.
  - Alle Seiten eines Aufrufs zusammen haben höchstens 40 000 Zeichen Text, mindestens 2000 je Seite.
  - Je Seite gibt es höchstens 150 Links, im Batch zusammen 300, mindestens 20 je Seite.
  - Gekürzte Antworten tragen `truncated` bzw. `linksTruncated`. Weitere Teile liefert `web_read_tab`.
- `skipped` enthält:
  - doppelt genannte IDs,
  - gleiche Ziel-URLs im selben Aufruf,
  - Ziele, die schon in einem Tab offen sind (mit `tabId`),
  - Ziele über dem Tab-Limit von 20 Tabs je Chat (mit dem Hinweis, Tabs mit `web_close_tabs` zu schließen).
- `errors` enthält:
  - unbekannte oder veraltete IDs,
  - von der `NetworkPolicy` gesperrte Ziele (vor der Navigation und nach Weiterleitungen),
  - HTTP-Status ab 400 sowie 429/403 und Challenges (gemeldet, nicht umgangen),
  - Downloads, Zeitüberschreitungen und Fehler.
- Ein fehlgeschlagener neuer Tab wird wieder geschlossen.
- `web_follow_link` navigiert im angegebenen Tab. Schlägt das fehl, behält der Tab seine Seite.
- `web_back` geht im Tab eine Seite zurück. Auch dafür wird der Domain-Slot belegt.
- `web_close_tabs` schließt nur die genannten Tabs.

## Domain-Koordination

`DomainCoordinator.shared()` gilt prozessweit, also über alle Aufrufe, Chats und Browser hinweg.

- Je registrierbarer Domain läuft höchstens eine Navigation gleichzeitig. Die Domain bestimmt die Public Suffix List (Guava `InternetDomainName`), also `docs.example.co.uk` → `example.co.uk`. IP-Adressen und `localhost` stehen für sich.
- Insgesamt laufen höchstens 4 Navigationen gleichzeitig, und ein Aufruf enthält höchstens 10 URLs.
- Die nächste URL derselben Domain startet, sobald die vorige fertig ist. Es gibt keine feste Pause.
- Nach 429, 403 oder einer Challenge pausiert die Domain 60 s. Bei Wiederholung verdoppelt sich die Pause, höchstens bis 15 min. Ein `Retry-After` der Site (Sekunden oder HTTP-Datum, höchstens 60 min) hat Vorrang. Während der Pause meldet Argos die Domain, ohne Anfragen zu senden.
- Threading:
  - Alle Ladevorgänge eines Aufrufs laufen in einer einzigen Aufgabe auf dem Playwright-Treiber-Thread (`CamoufoxBrowser.loadTabs`).
  - Jede Navigation wird mit `waitUntil=commit` (höchstens 2 s) gestartet. Danach werden die Ladezustände aller Tabs reihum mit kurzen Timeouts abgefragt.
  - Echte Überlappung entsteht im Browser. Die Slots werden nicht blockierend vergeben (`tryAcquire`).
  - Gleichzeitige Aufrufe anderer Chats laufen in deren eigenen Browsern und teilen sich nur den Koordinator.

## Tests

Die Unit-Tests laufen im Container: `ConvenienceSessionTest` gegen lokale HTTP-Server mit Intervall-Log, `ConvenienceToolsTest`, `ProfileSnapshotTest` und `ToolProfileTest`. Abgedeckt sind:

- Überlappung der Suchmaschinen,
- eine langsame Suchmaschine,
- `NO_RESULTS` vs. `EXTRACTION_FAILED` vs. `CONSENT_REQUIRED` mit Fallback-Seite,
- Dedup mit `_sg`/`_tp`/Fragment,
- Domain-Serialisierung über gleichzeitige Aufrufe und zwei Chats,
- globales Limit, 429 mit `Retry-After`,
- Kürzung und Teile, veraltete IDs, Schließen von Tabs, Tab-Limit, gesperrte Ziele,
- ein History-Ereignis pro Suche,
- Profil-Default und unveränderte Profile.

Den Browser-Test führt Angelo lokal aus (Camoufox 156):

```
.\gradlew.bat test -PtestCamoufox=true --tests "*ConvenienceBrowserTest*"
```

Er prüft:

- paralleles Laden in neuen Tabs auf echtem Camoufox,
- dass dieselbe Domain wartet (`localhost` vs. `127.0.0.1`),
- dass alle Slots wieder freigegeben werden,
- Auswahl, Folgen, Zurück, Lesen und Schließen über IDs.

Echte Suchmaschinen prüft nur die Abnahme über ChatGPT.

## Bekannte Grenzen

- Die gemeinsame erste Navigation endet erst, wenn alle Suchmaschinen geladen haben oder ihr Budget (20 s) abgelaufen ist. Die Auswertung danach hat ein eigenes Budget von 15 s. Ein Aufruf dauert deshalb höchstens etwa 35 s.
- Ein laufender Browser-Schritt einer abgelaufenen Suchmaschine endet erst mit seinem eigenen Timeout. Ein Netzwerkfehler nach dem Commit-Fenster wird als `TIMEOUT` gemeldet.
- Hintergrund-Tabs können von Firefox gedrosselt werden.
- Argos vergibt den nächsten Slot einer Domain sofort nach der Freigabe (gemessen: ~30 ms). Danach braucht Firefox im Browsertest unter Windows für den dritten neuen Tab teils 2–3 s zusätzlich: Mal kommt die Anfrage später beim Server an, mal committet Firefox eine schon vollständig empfangene Antwort später. Vermutete, noch unbestätigte Ursache: Site-Isolation (Fission) startet je Site einen neuen Content-Prozess. Argos ändert dafür keine Firefox-Prefs, weil das auch die anderen Profile beträfe.
- Anders als `web_read` gibt es kein zweites Lesen von „Loading…“-Seiten (Single-Page-Apps).
- Ein Consent-Klick in einem Hintergrund-Tab ist auf echtem Camoufox noch nicht geprüft. Er geschieht nur mit `ARGOS_SEARCH_CONSENT_ACCEPT=true` oder bei „Ablehnen“-Schaltflächen.
- Die Tab-, Such- und Link-Register leben im Speicher des Chat-Workspaces und gehen bei Idle-Shutdown oder Neustart verloren. Alte IDs werden dann als unbekannt abgelehnt.
