# Search History

Argos zeichnet je Chat automatisch alle Suchaufrufe, deren Treffer, die gelesenen Webseiten und ausgewählte, vom
Browser **tatsächlich empfangene** HTTP-Antworten (insbesondere per `fetch()`/XHR nachgeladene JSON-, XML- und
HTML-Daten) auf. Die Historie bleibt dauerhaft erhalten, ist mit Lucene im Volltext durchsuchbar und lässt sich als
TXT, JSONL oder ZIP exportieren. Die Aufzeichnung löst **keine zusätzlichen HTTP-Anfragen** aus und führt kein
Seitenskript aus. Ein Single-File-HTML-Archiv entsteht nur durch den ausdrücklichen Aufruf von
`browser_archive_html`. Embeddings, Vektorsuche, KI-Zusammenfassungen und RAG gehören nicht dazu.

## Wann sie aktiv ist

| Variable | Standard | Bedeutung |
|---|---|---|
| `ARGOS_SEARCH_HISTORY` | `true` | Search History an/aus |
| `ARGOS_SEARCH_HISTORY_CAPTURE_IMAGES` | `false` | zusätzlich `image/*` einschließlich SVG |
| `ARGOS_SEARCH_HISTORY_CAPTURE_FONTS` | `false` | zusätzlich `font/*` und ältere Font-Typen (`application/font-woff`, `application/x-font-ttf`, `application/vnd.ms-fontobject`, …) |
| `ARGOS_SEARCH_HISTORY_CAPTURE_AUDIO` | `false` | zusätzlich `audio/*` |
| `ARGOS_SEARCH_HISTORY_CAPTURE_VIDEO` | `false` | zusätzlich `video/*` |
| `ARGOS_SEARCH_HISTORY_CAPTURE_SCRIPTS` | `false` | zusätzlich JavaScript/ECMAScript (`text/javascript`, `application/javascript`, `application/x-javascript`, `text/ecmascript`, …) |
| `ARGOS_SEARCH_HISTORY_CAPTURE_STYLES` | `false` | zusätzlich `text/css` |
| `ARGOS_SEARCH_HISTORY_CAPTURE_PDF` | `false` | zusätzlich `application/pdf` |
| `ARGOS_SEARCH_HISTORY_CAPTURE_OTHER` | `false` | zusätzlich alle nicht klassifizierten Typen, auch fehlender/ungültiger `Content-Type` |
| `ARGOS_SEARCH_HISTORY_HTML_TIMEOUT_MS` | `15000` | Höchstdauer des Collectors von `browser_archive_html` (1000–60000); **kein** Aktivierungsschalter |

Die `CAPTURE_*`-Werte akzeptieren nur `true`/`false` (Groß-/Kleinschreibung egal); jeder andere Wert, auch ein leerer,
bricht den Start mit einer Meldung ab, die die Variable nennt. Jede Variable schaltet genau eine Kategorie;
`OTHER=true` bedeutet nicht `IMAGES=true`. Sie sind die Startwerte jedes Chats, der sie mit
`search_history_capture_settings` für sich ändern kann (siehe unten).

`ARGOS_SEARCH_HISTORY_HTML` ist **wirkungslos** (beim Start erscheint ein Hinweis, falls sie gesetzt ist). Es gibt
keine Variable, die die HTML-Archivierung automatisch einschaltet. `ARGOS_NETWORK_BODIES` steuert weiterhin nur die
Diagnose von `browser_network*` und hat keinen Einfluss auf die Search History.

Die Search History setzt `ARGOS_PROFILE_ISOLATION=chat` voraus (Standard). Mit `single` gibt es keinen verlässlichen
Chat-Schlüssel. Argos meldet dann beim Start `Search History is disabled: ...`, zeichnet nichts auf und legt **keinen**
globalen Index an.

`search_history`, `search_history_export`, `search_history_capture_settings` und `browser_archive_html` werden in den
Profilen `compact` und `expert` **immer** veröffentlicht, damit die Werkzeugliste für ChatGPT stabil bleibt. Ist die
Historie aus (`single` oder `ARGOS_SEARCH_HISTORY=false`), antworten alle mit einem Fehler, der den Grund und die Abhilfe nennt (`ARGOS_PROFILE_ISOLATION=chat`). Im Profil
`legacy` fehlen sie. Nach einem Update muss ChatGPT die Verbindung aktualisieren, damit neue Werkzeuge erscheinen.

## Architektur (`com.aresstack.argos.history`)

| Klasse | Aufgabe |
|---|---|
| `SearchHistory` | Fachliche Schnittstelle eines Chats: Suchereignis speichern, Seitenbesuch speichern, durchsuchen. Kennt weder Browser noch Lucene. |
| `SearchHistoryRepository` | Kanonischer Datenbestand (Quelle der Wahrheit): `events.jsonl`, `pages/`, `resources/`. |
| `ResourceCategory` | MIME-Klassifikation der Antworten (Standard-Allowlist und optionale Kategorien). |
| `CaptureSettings` | Wirksame Kategorien eines Chats: Überschreibung in `history/capture-settings.json`, sonst Umgebungsvariable, sonst `false`. |
| `LuceneSearchHistoryIndex` | `FSDirectory`, `IndexWriter`, `DirectoryReader`, `IndexSearcher`, `StandardAnalyzer` nach AskAI `LuceneTextPassageIndex`. Wiederaufbaubare Projektion. |
| `SearchHistoryCapture` | Einbindung in die vorhandenen Abläufe, ohne eigene Browser-, Such- oder Research-Logik. |
| `SearchHistoryExporter` | TXT, JSONL, ZIP aus dem kanonischen Bestand (ohne Lucene). |
| `HtmlCollector` + `page-capture.js` | Portierung von cam-tool `kCaptureJs`, nur Inline-Modus (`inlineJsonJs`); läuft nur bei `browser_archive_html`. |
| `ArchiveSanitizer` | Entfernt vor dem Speichern Formularwerte, Passwörter, Event-Handler, `javascript:`-Links, Meta-Refresh und maskiert geheime URL-Parameter. |
| `SearchHistoryTools` | MCP-Werkzeuge `search_history`, `search_history_export`, `search_history_capture_settings`, `browser_archive_html` (Profile `compact` und `expert`). |
| `network.ResponseContentSink` | Schmale Schnittstelle, über die der vorhandene `NetworkRecorder` empfangene Bodies weitergibt; der Recorder kennt die History nicht. |

Integrationspunkte:

- **Suche**: Decorator um den gemeinsamen `WebSearchPort` (`capturingSearch`). `web_search` und `web_research`
  nutzen denselben Port, daher genau ein Ereignis pro tatsächlichem Suchaufruf. Fehlgeschlagene Suchen erzeugen kein
  Ereignis. Der Suchanbieter stammt aus den `PROVIDER:`-Zeilen des bestehenden Suchpfads.
- **web_\***: `WebSession.onPageRead(...)` wird in `WebSession` nach der URL-Policy-Prüfung aufgerufen, solange die Seite
  noch angezeigt wird. `web_open`/`web_follow`/`web_back` sind neue Besuche. `web_read`/`web_find`/`web_links` lesen
  die angezeigte Seite nur erneut und erzeugen keinen Besuch, solange URL und Inhalt gleich bleiben.
- **browser_navigate** (neuer Besuch) und **browser_snapshot** (erneutes Lesen) in `ArgosTools`, über
  `CamoufoxBrowser.capturePage(tab)` (ein `page.content()` wie bei `web_read`).
- **web_research**: Decorator um `ResearchPageReader` (`CamoufoxResearchPageReader` bleibt unverändert).
- **Netzwerkantworten**: `SearchHistoryCapture.networkContent()` hängt am vorhandenen `NetworkRecorder` jedes
  Chat-Browsers (`CamoufoxBrowser.contentSink`, Registrierung in `adopt(Page)` unverändert). Siehe unten.
- **Kontext**: Die `contexts`-Weiche des Workspaces (`ChatRoutedTools`) setzt zusätzlich den aktiven User-Kontext.
  Er wird nur als Metadatum gespeichert und ist kein Filter.

Keiner dieser Pfade startet den HTML-Collector, auch nicht `search_history_export`. Fehler beim Aufzeichnen,
Bereinigen, Speichern oder Indexieren werden nur mit dem Klassennamen geloggt
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
    pages/<snapshotId>.html               Single-File-HTML (nur nach browser_archive_html)
    resources/<resourceId>.json           Metadaten eines gespeicherten Antwort-Bodys (MIME, Kategorie, Hash, Größe)
    resources/<resourceId>.body           der Body: bereinigter UTF-8-Text oder die empfangenen Bytes
    capture-settings.json                 Überschreibungen dieses Chats (nur wenn geändert)
    lucene/                               Index (jederzeit löschbar, wird neu aufgebaut)
    exports/                              search-history-<UTC-Zeit>.txt|jsonl|zip, page-archive-<UTC-Zeit>.html
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

## Netzwerkinhalte

**Standard (immer, sobald die History aktiv ist):** `text/html`, `application/xhtml+xml`, `application/json`,
`application/*+json` (z. B. `ld+json`), `application/xml`, `text/xml`, `application/*+xml`, `text/plain`,
`text/markdown`, `text/csv`. Klassifiziert wird der normalisierte MIME-Typ (Parameter wie `charset` entfernt),
nie die Dateiendung. Bekannte optionale Kategorien werden **vorher** geprüft: `text/css`, JavaScript und
`image/svg+xml` gelten nie als Text/XML. Fehlt der `Content-Type` oder ist er ungültig, ist das „Sonstige“ und wird nie
als Text gelesen.

**Ablauf** (vorhandene BiDi-Netzwerkereignisse, kein zweiter Listener-Satz):

1. `NetworkRecorder.requestFinished` fragt die Sink nur anhand der Metadaten (URL, Status, MIME, Ressourcenart,
   `Content-Length`, `Content-Range`), ohne Protokollaufruf im Event-Handler. Nicht erfasst werden fehlgeschlagene/
   abgebrochene Requests, `HEAD`, Status < 200, 204, 304, 3xx und Nicht-HTTP(S)-URLs.
2. Gewünschte Antworten kommen in eine begrenzte Warteschlange (64 je Browser; voll = neue Antworten werden
   ausgelassen und gezählt, nie gepuffert).
3. Nach jeder Browseroperation (`onBrowser`, außerhalb der Event-Handler) liest der Recorder die Bodies mit
   `Response.body()`, also die bereits empfangenen Daten aus dem Browser. Es entsteht **keine neue Anfrage**; es gibt
   kein `fetch()`/XHR im Seitenkontext. Budget je Operation: 16 Bodies, 2,5 s, 48 MiB. Der Rest wartet auf die nächste
   Operation. Antworten, die erst nach der letzten Operation fertig werden, werden mit der nächsten gelesen.
4. Ein Hintergrund-Schreiber (Warteschlange höchstens 64 MiB über alle Chats, sonst ausgelassen) bereinigt und
   speichert; dann wird indexiert. Ein nicht mehr lesbarer, fehlender oder übergroßer Body wird ausgelassen; die
   Browseroperation schlägt nie deshalb fehl.

**Grenzen:**

| Grenze | Wert |
|---|---|
| Text-Body (Standard, Skripte, Styles) | 8 MiB |
| Binär-Body (Bilder, Fonts, Audio, Video, PDF, Sonstige) | 32 MiB |
| Voraussetzung für jedes Auslesen | bekannte, gültige, **unkodierte** `Content-Length` innerhalb der Grenze |
| Bodies je Browseroperation | 16, höchstens 2,5 s und 48 MiB (die angekündigte Größe wird **vor** dem Lesen reserviert; was nicht mehr passt, wartet auf die nächste Operation) |
| Warteschlange je Browser / Schreiber | 64 Antworten / 64 MiB |
| Ressourcen-Volumen je Chat | 2 GiB, danach werden neue Bodies ausgelassen (Log-Hinweis) |
| Indexierter Text je Ressource | 2 000 000 Zeichen (gespeichert wird der ganze Body) |

`Response.body()` liefert immer den vollständigen Body als Byte-Array, ohne Streaming und ohne Größenbegrenzung.
Darum wird eine Antwort ohne verlässliche Größe (fehlende oder ungültige `Content-Length`, oder ein
`Content-Encoding`, bei dem die Länge nur die komprimierte Größe ist) **nie** gelesen, egal welcher Typ. Bewusste
Abwägung: chunked oder komprimiert ausgelieferte Antworten (z. B. chunked JSON) werden derzeit nicht erfasst.
Die Diagnosegrenzen des Recorders (200 Einträge, 32 000 Zeichen) gelten für die History nicht: gespeichert werden die
Bytes, nie der gekürzte Diagnosetext.

**Teilantworten:** Range-Antworten (206 oder `Content-Range`) werden mit `complete=false` und ihrem `contentRange`
gespeichert. Argos setzt daraus keine Mediendatei zusammen und behauptet keine Vollständigkeit. Ebenso
`complete=false`, wenn weniger Bytes als angekündigt ankamen.

**Bereinigung:** HTML mit `ArchiveSanitizer` (Skripte, Formularwerte, Event-Handler, `javascript:`, Meta-Refresh,
geheime URL-Parameter), JSON und übriger Text mit `SecretMasker.body` (JSON-Schlüsselmaskierung, Token-Muster). URLs
werden maskiert. `masked=true` heißt: Der gespeicherte Text ist nicht die unveränderte Originalantwort. Binärdaten
lassen sich nicht textuell bereinigen und werden unverändert gespeichert, deshalb nur bei ausdrücklich
eingeschalteter Kategorie. Request-Bodies, Cookies, Authorization- und andere Header werden nie gespeichert.

**Deduplizierung:** `resourceId` = SHA-256-Präfix der gespeicherten Bytes. Identische Bodies (auch unter anderen URLs)
liegen einmal vor und ergeben einen Suchtreffer; jede Beobachtung bleibt ein eigenes `network_resource`-Ereignis.
Ein geänderter Body derselben URL ist eine neue Ressource. MIME-Typ, Kategorie und `masked` gehören zur jeweiligen
Beobachtung (Ereignis/Manifest), nicht zur geteilten Ressource. Der Dateiname im ZIP (`resources/<resourceId>.<ext>`,
Endung nach dem MIME-Typ der ersten Speicherung) ist für alle Beobachtungen derselbe und gleich dem JSONL-`bodyRef`. Das HTML-Dokument einer Seite, die schon als Seiten-Snapshot
gefunden wird, erscheint nicht zusätzlich als Ressourcentreffer.

**Ereignis** `network_resource` in `events.jsonl`: `seq`, `at`, `url`, `pageUrl`, `status`, `mimeType`, `category`,
`resourceType`, `contextId`, `resourceId`, `contentHash`, `size`, `complete`, `masked`, `contentRange`. Ältere
`search`- und `page_visit`-Zeilen bleiben unverändert lesbar; unbekannte Typen werden übersprungen.

### Einstellungen pro Chat (`search_history_capture_settings`)

- `{}` zeigt die wirksamen Werte und ihre Herkunft („changed in this chat“ oder „server setting <Variable>“).
- `{"set": {"images": true, "pdf": false}}` ändert einzelne Kategorien (`images`, `fonts`, `audio`, `video`,
  `scripts`, `styles`, `pdf`, `other`). `content` lässt sich nicht abschalten.
- `{"reset": true}` löscht `history/capture-settings.json`; danach gelten wieder die Umgebungsvariablen.

Priorität: Überschreibung des Chats, dann Umgebungsvariable, dann `false`. Die Datei liegt im `history/` des
aufrufenden Chats, gilt für alle seine User-Kontexte und für keinen anderen Chat. Änderungen wirken nur auf
künftige Antworten; nichts wird nachgeladen. Das HTML-Archiv gehört nicht zu diesen Einstellungen.

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
  - `network_resource`: `url`, `pageUrl`, `capturedAt`, `status`, `mimeType`, `category`, `resourceType`,
    `contextId`, `resourceId`, `contentHash`, `size`, `complete`, `masked`, `contentRange`, `bodyRef`
    (Pfad im ZIP) und bei Text bis 1 MiB `body` (bereinigter Text). Binärdaten werden **nie** als Base64 eingebettet:
    JSONL ersetzt für große Binärdaten nicht das Medienarchiv, dafür ist der ZIP-Export da.
  - Fehlende optionale Werte sind `null`. Seiten-HTML ist nicht enthalten.
- `{"format":"zip"}`: `index.html` (statisch, ohne externe Skripte oder Stylesheets) mit Titel, URL, erstem
  Besuch, Anzahl der Besuche, Status und Archivart. Dazu eine eigenständige `.html` je Snapshot. Dateinamen werden aus
  dem Titel abgeleitet und bereinigt (keine Pfade, keine reservierten Windows-Namen). Kollisionen werden mit `-1`, `-2`
  usw. aufgelöst. Liegt ein mit `browser_archive_html` erzeugtes Archiv vor, wird es verwendet; sonst enthält die Datei
  einen deutlich markierten **Textbasierten Fallback** mit Grund, lesbarem Text und Links. Die Seiten-HTML-Dateien
  brauchen keinen Ressourcenordner. Zusätzlich `resources/<resourceId>.<ext>` je gespeichertem Body und
  `resources/manifest.json` (je Beobachtung: `file`, `url`, `pageUrl`, `mimeType`, `category`, `resourceType`,
  `status`, `size`, `sha256`, `capturedAt`, `complete`, `masked`, `contentRange`). Das ist ein Archiv der tatsächlich
  beobachteten Antworten, keine Rekonstruktion der Webseite.
- TXT nimmt Ressourcen-URLs nie auf (sonst füllte ein Seitenbesuch die Liste mit hunderten Asset-URLs).
- Kein Export startet den HTML-Collector oder greift auf Seiten zu; er ist deterministisch.

`search_history` (`{"query":"..."}`, optional `limit` 1–50) durchsucht Suchanfragen, Treffertitel, -URLs, Snippets,
Seitentitel, finale URLs, lesbaren Text, Links und gespeicherte Netzwerkantworten. Treffer sind als `PAGE`, `SEARCH`
oder `RESOURCE` (mit MIME-Typ, URL, Zeitpunkt, Ausschnitt) gekennzeichnet. Indexiert werden: HTML-Antworten als
lesbarer Text (jsoup, Java-seitig), JSON/XML/Text/Markdown/CSV als Text, optionale Skripte und Styles in einem
schwach gewichteten Feld (sie verdrängen keine Seiteninhalte). Binärressourcen nur über URL und MIME-Typ, nie als
Volltext. Base64-Inhalte des HTML-Archivs werden nie indexiert. Ein Index je Chat, kein Startscan, jederzeit aus dem
kanonischen Bestand wiederaufbaubar.

## Single-File-HTML: `browser_archive_html`

Das Archiv entsteht **nur**, wenn der Agent `browser_archive_html` aufruft – laut Werkzeugbeschreibung nur auf
ausdrücklichen Wunsch des Nutzers. Normale Navigationen, Snapshots, Suchen, `web_research`, die Netzwerkerfassung
und `search_history_export` starten den Collector nie.

- `{}` archiviert die aktuell angezeigte HTTP(S)-Seite des aktuellen Tabs, `{"tab":"t2"}` einen anderen Tab. Das
  Werkzeug navigiert nie; eine andere Seite muss vorher mit den Browserwerkzeugen geöffnet werden.
- Ablauf: Seitentext als Snapshot sichern (falls noch nicht vorhanden; ein späterer Collector-Fehler kostet die
  Texthistorie nichts), Collector einmal ausführen, Ergebnis mit `ArchiveSanitizer` bereinigen, als
  `pages/<snapshotId>.html` beim passenden Snapshot ablegen (spätere ZIP-Exporte verwenden es) und zusätzlich als
  `exports/page-archive-<UTC-Zeit>.html` schreiben.
- Rückgabe: Download-Link über den bestehenden Endpunkt (`/api/search-history/exports/<id>`, gleiche Zufalls-ID,
  Frist und Chat-Bindung wie die Exporte, `Content-Type: text/html; charset=utf-8`) als Markdown-Text und als MCP
  `resource_link`; nie ein lokaler Pfad.

Der Collector ist cam-tool `kCaptureJs` (MIT), weitgehend unverändert in `page-capture.js`. Geändert wurden nur zwei
Dinge: Er gibt sein Ergebnis an Playwrights `evaluate()` zurück statt den Qt-Callback `window[CB]` aufzurufen, und er
wird mit `Promise.race` auf `ARGOS_SEARCH_HISTORY_HTML_TIMEOUT_MS` begrenzt. Er deklariert keine Globals und arbeitet
auf einem DOM-Klon.

**Bekannte Einschränkung:** Er ist **nicht unauffällig**. Er läuft im Seitenkontext und lädt Stylesheets,
CSS-Ressourcen und Bilder per `fetch()` aus der Seite heraus erneut (sichtbar z. B. als Performance-Einträge, in
CSP-Berichten, über `Sec-Fetch-*`-Header oder als doppelte Abrufe am Server). Deshalb ist er aus allen automatischen
Pfaden entfernt. Nicht abrufbare Ressourcen bleiben externe Verweise, Ressourcen über 8 MiB werden nicht eingebettet;
das Archiv ist eine bestmögliche Offline-Kopie.

## Tests

Ohne Browser (`gradlew test`): `history/SearchHistoryTest` (Persistenz, Lucene + Wiederaufbau, Chat-Isolation,
Deduplizierung, Redaktion, explizites Archiv), `SearchHistoryCaptureTest` (automatische Erfassung ohne Collector),
`SearchHistoryExporterTest` (TXT/JSONL/ZIP), `HtmlCollectorTest`, `ResourceCategoryTest` (MIME-Klassifikation,
Variablen), `SearchHistoryResourcesTest` (Tests A–J auf Speicherseite: Allowlist, optionale Kategorien einzeln,
dynamische Daten, Grenzen und Teilantworten, Chat-Isolation, Index-Wiederaufbau, Exporte mit Manifest,
`browser_archive_html`-Download, `search_history_capture_settings`), `network/NetworkContentCaptureTest` (Queue,
Budget, Body-Abfrage außerhalb der Event-Handler, unabhängig von `ARGOS_NETWORK_BODIES`).

Mit Camoufox (lokal unter Windows):

```bat
cd C:\Projects\argos
set ARGOS_CAMOUFOX_EXECUTABLE=C:\path\to\camoufox\camoufox.exe
set ARGOS_CAMOUFOX_LAUNCH_OPTIONS=C:\path\to\launch.json
gradlew.bat test -PtestCamoufox=true --tests "com.aresstack.argos.SearchHistoryNetworkBrowserTest" --tests "com.aresstack.argos.SearchHistoryCollectorBrowserTest"
gradlew.bat test --tests "com.aresstack.argos.history.*" --tests "com.aresstack.argos.network.*"
```

`SearchHistoryNetworkBrowserTest`: Testserver zählt alle Requests; Seite lädt JSON per `fetch()` und XML per XHR plus
CSS/JS/Bild. Prüft Speicherung und Suche der Daten (nicht im sichtbaren Text), keine optionalen Typen ohne
Einschaltung, Bild nach `images=true` nur für neue Antworten, keine zusätzlichen Requests durch Erfassung,
Indexierung und Exporte, kein Collector-Aufruf außer bei `archiveHtml`.

Manuelle Abnahme mit ChatGPT: In Unterhaltung A `web_search`, `web_open` eines Treffers, `web_read part=2`, dann
`search_history {"query":"<Wort von der Seite>"}` und `search_history_export {"format":"zip"}`. Unter
`~/.argos/profiles/chats/<A>/history/` liegen danach `events.jsonl` mit 1 Suche, 1 Besuch und
`network_resource`-Zeilen, `pages/` (nur `.json`), `resources/` und die ZIP-Datei unter `exports/`. Danach
„Archiviere die Seite als HTML“ → `browser_archive_html` liefert einen Link auf `page-archive-<Zeit>.html`. In Unterhaltung B liefert `search_history` mit demselben Wort keinen Treffer.

Abnahme Download (ChatGPT, Argos mit `PUBLIC_ORIGIN` gestartet, Tool-Liste neu geladen): in Unterhaltung A nacheinander
`search_history_export` mit `txt`, `jsonl` und `zip` aufrufen lassen. Jede Antwort muss einen anklickbaren Link
`https://<PUBLIC_ORIGIN>/api/search-history/exports/…` enthalten; Klick lädt `search-history-<Zeit>.<ext>` herunter,
die Datei lässt sich öffnen (ZIP: `index.html` im Browser). Gegenproben: denselben Link nach Ablauf der Frist bzw. nach
Neustart öffnen → 404; einen Link mit veränderter ID → 404; `<PUBLIC_ORIGIN>/` ohne Token → weiter 401.
