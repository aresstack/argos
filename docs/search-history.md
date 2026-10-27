# Search History

Argos zeichnet je Chat automatisch alle Suchaufrufe, deren Treffer und die gelesenen Webseiten auf. Die Historie
bleibt dauerhaft erhalten, ist mit Lucene im Volltext durchsuchbar und lässt sich als TXT, JSONL oder ZIP
(Single-File-HTML) exportieren. Embeddings, Vektorsuche, KI-Zusammenfassungen und RAG gehören nicht dazu.

## Wann sie aktiv ist

| Variable | Standard | Bedeutung |
|---|---|---|
| `ARGOS_SEARCH_HISTORY` | `true` | Search History an/aus |
| `ARGOS_SEARCH_HISTORY_HTML` | `true` | Single-File-HTML-Archiv je Seiten-Snapshot (cam-tool-Collector) |
| `ARGOS_SEARCH_HISTORY_HTML_TIMEOUT_MS` | `15000` | Höchstdauer des Collectors je Seite (1000–60000) |

Die Search History setzt `ARGOS_PROFILE_ISOLATION=chat` voraus (Standard). Mit `single` gibt es keinen verlässlichen
Chat-Schlüssel. Argos meldet dann beim Start `Search History is disabled: ...`, zeichnet nichts auf und legt **keinen**
globalen Index an.

`search_history` und `search_history_export` werden in den Profilen `compact` und `expert` **immer** veröffentlicht,
damit die Werkzeugliste für ChatGPT stabil bleibt. Ist die Historie aus (`single` oder `ARGOS_SEARCH_HISTORY=false`),
antworten beide mit einem Fehler, der den Grund und die Abhilfe nennt (`ARGOS_PROFILE_ISOLATION=chat`). Im Profil
`legacy` fehlen sie. Nach einem Update muss ChatGPT die Verbindung aktualisieren, damit neue Werkzeuge erscheinen.

## Architektur (`com.aresstack.argos.history`)

| Klasse | Aufgabe |
|---|---|
| `SearchHistory` | Fachliche Schnittstelle eines Chats: Suchereignis speichern, Seitenbesuch speichern, durchsuchen. Kennt weder Browser noch Lucene. |
| `SearchHistoryRepository` | Kanonischer Datenbestand (Quelle der Wahrheit): `events.jsonl`, `pages/`. |
| `LuceneSearchHistoryIndex` | `FSDirectory`, `IndexWriter`, `DirectoryReader`, `IndexSearcher`, `StandardAnalyzer` nach AskAI `LuceneTextPassageIndex`. Wiederaufbaubare Projektion. |
| `SearchHistoryCapture` | Einbindung in die vorhandenen Abläufe, ohne eigene Browser-, Such- oder Research-Logik. |
| `SearchHistoryExporter` | TXT, JSONL, ZIP aus dem kanonischen Bestand (ohne Lucene). |
| `HtmlCollector` + `page-capture.js` | Portierung von cam-tool `kCaptureJs`, nur Inline-Modus (`inlineJsonJs`). |
| `ArchiveSanitizer` | Entfernt vor dem Speichern Formularwerte, Passwörter, Event-Handler, `javascript:`-Links, Meta-Refresh und maskiert geheime URL-Parameter. |
| `SearchHistoryTools` | MCP-Werkzeuge `search_history` und `search_history_export` (Profile `compact` und `expert`). |

Integrationspunkte:

- **Suche**: Decorator um den gemeinsamen `WebSearchPort` (`capturingSearch`). `web_search` und `web_research`
  nutzen denselben Port, daher genau ein Ereignis pro tatsächlichem Suchaufruf. Fehlgeschlagene Suchen erzeugen kein
  Ereignis. Der Suchanbieter stammt aus den `PROVIDER:`-Zeilen des bestehenden Suchpfads.
- **web_\***: `WebSession.onPageRead(...)` wird in `WebSession` nach der URL-Policy-Prüfung aufgerufen, solange die Seite
  noch angezeigt wird. `web_open`/`web_follow`/`web_back` sind neue Besuche. `web_read`/`web_find`/`web_links` lesen
  die angezeigte Seite nur erneut und erzeugen keinen Besuch, solange URL und Inhalt gleich bleiben.
- **browser_navigate** (neuer Besuch) und **browser_snapshot** (erneutes Lesen) in `ArgosTools`, über
  `CamoufoxBrowser.capturePage(tab)` (ein `page.content()` wie bei `web_read`).
- **web_research**: Decorator um `ResearchPageReader` (`CamoufoxResearchPageReader` bleibt unverändert). Er archiviert die
  Seite, während sie noch geladen ist.
- **Kontext**: Die `contexts`-Weiche des Workspaces (`ChatRoutedTools`) setzt zusätzlich den aktiven User-Kontext.
  Er wird nur als Metadatum gespeichert und ist kein Filter.

Fehler beim Aufzeichnen, Archivieren oder Indexieren werden nur mit dem Klassennamen geloggt
(`[argos-history] ...`) und verhindern nie das Ergebnis des Werkzeugs.

Nicht erfasst werden derzeit die Mehrseiten-Werkzeuge (`browser_navigate_pages`, `browser_snapshot_pages`,
`browser_open_many`) und reine Interaktionen (Klick, Tabs, Zurück im Browser). Liest danach `web_read` oder
`browser_snapshot` die neue Seite, wird sie als Besuch erfasst.

## Speicherstruktur

```
~/.argos/profiles/chats/<chat-key>/        <chat-key> = vorhandener SHA-256-Präfix aus ChatProfiles
  profile/                                Camoufox-Profil (unverändert)
  contexts.properties, contexts/          User-Kontexte (unverändert)
  history/
    events.jsonl                          chronologisch, eine JSON-Zeile je Ereignis (seq, type, at, ...)
    pages/<snapshotId>.json               lesbarer Inhalt, Links, Status, contentHash, htmlNote
    pages/<snapshotId>.html               Single-File-HTML (nur wenn der Collector Erfolg hatte)
    lucene/                               Index (jederzeit löschbar, wird neu aufgebaut)
    exports/                              search-history-<UTC-Zeit>.txt|jsonl|zip
```

- Die ChatGPT-Session-ID wird nirgends gespeichert. Pfade und Logs verwenden nur den vorhandenen Hash.
- `contentHash` = SHA-256 über Titel und lesbaren Text (Whitespace normalisiert, ohne Zeitstempel).
  `snapshotId` = SHA-256-Präfix über URL und `contentHash`. Gleiche URL mit gleichem Inhalt verwendet denselben Snapshot.
  Geänderter Inhalt erzeugt einen neuen Snapshot. Jeder Besuch bleibt ein eigenes Ereignis.
- Ein Chat-Index wird erst geöffnet, wenn der Chat schreibt oder sucht. Es gibt keinen Startscan.
  Lucene-Reader und -Writer sind nur während einer Operation geöffnet.
- Indexierung: Der kanonische Datensatz wird synchron geschrieben (`fsync`). Danach indexiert ein gemeinsamer
  Hintergrund-Thread mit begrenzter Warteschlange (64), höchstens eine Aufgabe pro Chat. Der Index speichert in
  seinen Commit-Daten die letzte enthaltene `seq`. Jede Suche holt fehlende Ereignisse synchron nach. Ein
  unlesbarer Index wird gelöscht und aus `events.jsonl` und `pages/` neu aufgebaut.
- Es gibt keine automatische Löschung.

## Download der Exporte

`search_history_export` gibt **keinen lokalen Pfad** mehr zurück, sondern einen Download-Link auf dem vorhandenen
Argos-Server (kein zweiter Server): `GET <PUBLIC_ORIGIN>/api/search-history/exports/<exportId>`. Das Ergebnis enthält
den Link als Markdown-Text (das Modell gibt ihn an den Nutzer weiter) und zusätzlich als MCP `resource_link`
(Name, MIME-Typ, Größe). Der Endpunkt streamt die Datei mit `Content-Disposition: attachment` und passendem
`Content-Type` (`text/plain`, `application/x-ndjson`, `application/zip`) – für alle drei Formate.

- Ohne `PUBLIC_ORIGIN` lautet der Link `http://<ARGOS_HOST>:<Port>/…` (nur lokal brauchbar).
- Der Endpunkt liegt außerhalb des Bearer-Filters, weil ein Browser-Download kein Token mitsendet. Zugangsnachweis
  ist die Export-ID: 256 Zufallsbits, nur im Speicher, gebunden an das `exports/`-Verzeichnis des aufrufenden Chats
  und einen von Argos vergebenen Dateinamen. Die ID enthält keinen Pfad; aufgelöst wird nur eine reguläre Datei
  direkt in diesem Verzeichnis (keine Symlinks). Unbekannte, abgelaufene und ungültige IDs: 404.
- Aufbewahrung: `ARGOS_SEARCH_HISTORY_EXPORT_TTL_MINUTES` (Standard 60, 1–1440). Danach ist der Link ungültig und
  die Datei wird gelöscht; ältere Exporte eines Chats (z. B. aus der Zeit vor einem Neustart) werden beim nächsten
  Export dieses Chats gelöscht. Nach einem Neustart sind alte Links ungültig.

## Exportformate (`search_history_export`)

Exportiert wird immer nur der aufrufende Chat, in dessen `history/exports/`. Der Dateiname wird von Argos vergeben;
Pfade aus Modellargumenten werden nicht übernommen. Alle Exporte lesen `events.jsonl` als Stream.

- `{"format":"txt"}`: eine URL je Zeile (UTF-8), nur tatsächlich besuchte Seiten in Besuchsreihenfolge.
  `"unique": true` gibt jede URL einmal aus, in der Reihenfolge des ersten Auftretens.
  `"include_results": true` nimmt zusätzlich die Treffer-URLs der Suchereignisse auf.
- `{"format":"jsonl"}`: alle Ereignisse chronologisch, eine gültige JSON-Zeile je Ereignis.
  - `search`: `query`, `searchedAt`, `provider`, `contextId`, `results[{rank,title,url,snippet}]`
  - `page_visit`: `url`, `requestedUrl`, `title`, `visitedAt`, `status` (0 = unbekannt), `content`, `links`,
    `contextId`, `contentHash`, `snapshotId`, `htmlArchived`, `source`
  - Fehlende optionale Werte sind `null`. HTML ist nicht enthalten.
- `{"format":"zip"}`: `index.html` (statisch, ohne externe Skripte oder Stylesheets) mit Titel, URL, erstem
  Besuch, Anzahl der Besuche, Status und Archivart. Dazu eine eigenständige `.html` je Snapshot. Dateinamen werden aus
  dem Titel abgeleitet und bereinigt (keine Pfade, keine reservierten Windows-Namen). Kollisionen werden mit `-1`, `-2`
  usw. aufgelöst. Es gibt keine Ressourcenordner. Fehlt ein HTML-Snapshot, enthält die Datei einen deutlich
  markierten **Textbasierten Fallback** mit Grund, lesbarem Text und Links.

`search_history` (`{"query":"..."}`, optional `limit` 1–50) durchsucht Suchanfragen, Treffertitel, -URLs, Snippets,
Seitentitel, finale URLs, lesbaren Text und Links. Base64-Inhalte des HTML-Archivs werden nie indexiert.

## Single-File-HTML und Stealth

Der Collector ist cam-tool `kCaptureJs` (MIT), weitgehend unverändert in `page-capture.js`. Es wurden nur zwei Dinge
geändert:

- Er gibt sein Ergebnis an Playwrights `evaluate()` zurück, statt den Qt-Callback `window[CB]` aufzurufen.
- Er ist ein einzelner Funktionswert und wird mit `Promise.race` auf `ARGOS_SEARCH_HISTORY_HTML_TIMEOUT_MS` begrenzt.

Er läuft einmal je neuem Snapshot, direkt nach dem Lesen der Seite, also solange sie noch angezeigt wird. Er
deklariert keine Globals, fügt dem Live-DOM nichts hinzu (er arbeitet auf einem Klon) und hinterlässt keine Marker.

**Verbleibender Kompromiss:** Anders als der Rest von Argos ist dies eine Auswertung im Seitenkontext. Außerdem lädt
der Collector Stylesheets, CSS-Ressourcen und Bilder per `fetch()` aus der Seite heraus erneut (wie im Original). Eine
Seite, die `fetch`/`FileReader` überwacht, oder ein Server, der doppelte Abrufe auswertet, kann das bemerken.
Wer das für eine Seite oder generell nicht möchte, setzt `ARGOS_SEARCH_HISTORY_HTML=false`. Text- und Suchhistorie
laufen dann unverändert weiter, und der ZIP-Export enthält textbasierte Fallbacks.

Grenzen wie im Original: Nicht abrufbare Ressourcen bleiben externe Verweise. Ressourcen über 8 MiB werden nicht
eingebettet. Das Archiv ist eine bestmögliche Offline-Kopie, keine Garantie für dynamische Seiten.

## Tests

Ohne Browser (`gradlew test`): `history/SearchHistoryTest` (Persistenz, Lucene + Wiederaufbau, Chat-Isolation,
Deduplizierung, Redaktion), `SearchHistoryCaptureTest` (automatische Erfassung über `web_*`, Browser-Tools und
Research-Reader ohne `ARGOS_RESEARCH`), `SearchHistoryExporterTest` (TXT/JSONL/ZIP), `HtmlCollectorTest`
(Portierung und Bereinigung).

Mit Camoufox (§10 Punkt 6, lokal unter Windows):

```bat
cd C:\Projects\argos
set ARGOS_CAMOUFOX_EXECUTABLE=C:\path\to\camoufox\camoufox.exe
set ARGOS_CAMOUFOX_LAUNCH_OPTIONS=C:\path\to\launch.json
gradlew.bat test -PtestCamoufox=true --tests "com.aresstack.argos.SearchHistoryCollectorBrowserTest"
gradlew.bat test --tests "com.aresstack.argos.history.*"
```

Manuelle Abnahme mit ChatGPT: In Unterhaltung A `web_search`, `web_open` eines Treffers, `web_read part=2`, dann
`search_history {"query":"<Wort von der Seite>"}` und `search_history_export {"format":"zip"}`. Unter
`~/.argos/profiles/chats/<A>/history/` liegen danach `events.jsonl` mit 1 Suche und 1 Besuch, `pages/` und die
ZIP-Datei unter `exports/`. In Unterhaltung B liefert `search_history` mit demselben Wort keinen Treffer.

Abnahme Download (ChatGPT, Argos mit `PUBLIC_ORIGIN` gestartet, Tool-Liste neu geladen): in Unterhaltung A nacheinander
`search_history_export` mit `txt`, `jsonl` und `zip` aufrufen lassen. Jede Antwort muss einen anklickbaren Link
`https://<PUBLIC_ORIGIN>/api/search-history/exports/…` enthalten; Klick lädt `search-history-<Zeit>.<ext>` herunter,
die Datei lässt sich öffnen (ZIP: `index.html` im Browser). Gegenproben: denselben Link nach Ablauf der Frist bzw. nach
Neustart öffnen → 404; einen Link mit veränderter ID → 404; `<PUBLIC_ORIGIN>/` ohne Token → weiter 401.
