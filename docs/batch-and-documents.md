# Dokumente und Batch-Verarbeitung (Arbeitspakete E und F)

Dieses Dokument beschreibt `browser_download`, `browser_document` und `browser_open_many`, die technische
Entscheidung dahinter und die gemessenen Grenzen des Single-Thread-Executors.

## Werkzeuge (nur Profil `expert`)

| Tool | Parameter | Ergebnis |
| --- | --- | --- |
| `browser_download` | `url` (absolut oder relativ zur aktuellen Seite) | `downloadId`, `sourceUrl`, `finalUrl` (nur bei Abweichung), `filename`, `contentType`, `format`, `size`, `expiresAt` |
| `browser_document` | `downloadId`, optional `maxCharacters` (1000–100000, Standard 20000) | Metadaten + `readable`, `text`, `truncated`, `pages` (PDF), `note` |
| `browser_open_many` | `urls` (1–10), optional `maxCharactersPerPage` (200–20000, Standard 4000) | `parallelTabs`, `elapsedMs`, `succeeded`, `results[]` mit einem Eintrag pro URL in Anfragereihenfolge |

Die Werkzeuge werden **nicht** in die neun bisherigen `browser_*`-Werkzeuge aufgenommen. `ArgosApplication`
veröffentlicht sie nur bei `ARGOS_TOOL_PROFILE=expert`. Die endgültige Einordnung übernimmt die Profil-
Integration (`DocumentAndBatchTools.all()` liefert die drei Spezifikationen).

Status pro Batch-Eintrag: `SUCCESS`, `DUPLICATE`, `INVALID_REQUEST`, `NOT_FOUND` (404/410), `BLOCKED`
(401/403/429/451, wird nicht umgangen), `HTTP_ERROR`, `TIMEOUT`, `TOO_LARGE`, `EXTRACTION_FAILED`, `FAILED`.
Fehlermeldungen der Einzelwerkzeuge beginnen mit derselben Kategorie (`TOO_LARGE: …`).

## Stealth: Wie geladen wird

Jeder Download und jede Batch-Adresse wird in einem **Hintergrund-Tab derselben persistenten Session**
geladen (`BackgroundTab`), nie im aktuellen Tab. Ein Download bekommt einen eigenen Tab, der danach geschlossen
wird. Ein Batch öffnet höchstens `parallelTabs` Tabs und lädt weitere Adressen in Tabs, die fertig gelesen sind
(siehe Parallelität); am Ende des Batches werden alle geschlossen.

- Die Bytes stammen aus dem Netzwerkstack des Browsers: dieselben Cookies, Logins, TLS-Fingerprints und
  Request-Header wie bei einem normalen Tab.
- Quelle ist entweder die Navigations-Response (`Response.body()`, unter BiDi der Data Collector von Playwright,
  bis 20 MB) oder – bei `Content-Disposition: attachment` – der echte Browser-Download (`Page.onDownload`).
- **Kein** Preload-/Init-Skript, kein `exposeBinding`, kein In-Page-`fetch()` (Enterprise `FETCH_FUNCTION`
  wurde bewusst nicht übernommen) und kein `APIRequestContext` (eigener HTTP-Client des Treibers mit
  abweichendem TLS-Fingerprint). Text gelesen wird nur über `innerText`/`textContent`-Locator-Abfragen.
- Nicht umgehbar: 401/403/429 werden als `BLOCKED` gemeldet; Paywalls und Logins bleiben, was sie sind.
- Das einzige Skript ist die Frist-Uhr im eigenen `about:blank`-Hilfstab (siehe Temporäre Dateien). Keine
  Website sieht sie.

Bekannte Einschränkung: Ein neuer Tab sendet keinen `Referer`. Server, die Dokumente nur mit Referer ausliefern,
liefern dann 403 (`BLOCKED`). Ein Klick auf den Link in der aktuellen Seite würde den Referer senden, verlässt
aber die Seite; das ist bewusst nicht der Standardweg.

## URL-Richtlinie (eine zentrale Netzwerkpolitik)

Alle Pfade dieses Arbeitspakets verwenden dieselbe `com.aresstack.argos.web.UrlPolicy` wie `web_open`,
`web_follow`, `browser_navigate` und die Recherche. `UrlPolicy` hat keine eigenen Regeln mehr, sondern ruft
`NetworkPolicy.checkUrl` aus PR #11 auf, also dieselben Regeln und dieselbe Auflösung wie der SOCKS5-Proxy:

| Pfad | Vorab-Check | Redirect-Hops | Endadresse |
| --- | --- | --- | --- |
| `browser_download` | ✔ | ✔ (Navigationsanfragen des Haupt-Frames) | ✔ (auch die URL eines Browser-Downloads) |
| `browser_open_many` | ✔ pro URL, abgewiesene URL → `BLOCKED`, die anderen laufen weiter | ✔ | ✔ |

Standardmäßig werden Ziele abgewiesen, die zu Loopback, RFC 1918, Link-Local (auch `169.254.169.254`),
IPv6-ULA, CGNAT oder anderen nicht öffentlichen Bereichen auflösen. **Freigegebene Intranet-Ziele** werden
ausdrücklich erlaubt mit `ARGOS_INTRANET_ALLOW` (Syntax siehe README, Abschnitt Netzwerkgrenze).
`ARGOS_ALLOW_PRIVATE_NETWORKS` gibt es nicht mehr; Argos bricht den Start mit einem Hinweis ab, wenn es
gesetzt ist. `ARGOS_NETWORK_POLICY=off` schaltet Proxy und Vorab-Check gemeinsam ab (nur Fehlersuche).

**Das ist keine Sicherheitsgrenze.** Ein Redirect-Hop wird erst geprüft, wenn der Browser ihn bereits
anfordert; Subrequests der Seite und die DNS-Auflösung des Browsers sieht die Prüfung gar nicht. Der Check
liefert eine frühe, verständliche Fehlermeldung (`BLOCKED: …`) und sorgt dafür, dass Inhalte abgewiesener
Ziele nie im Toolergebnis landen (der Tab wird beim abgewiesenen Hop sofort geschlossen). Durchgesetzt wird
die Richtlinie an der Verbindungsgrenze: lokaler SOCKS5-Proxy mit `NetworkPolicy` (PR #11).

**Entscheidung (Angelo, 2026-10-25):** Es gibt genau eine Sicherheitslogik, die `NetworkPolicy` an der
SOCKS5-Grenze. Sie gilt ohne Ausnahme für alle Profile und Werkzeuge, also auch für `legacy` und
`browser_navigate`, einschließlich Redirects und Subrequests. Einen Legacy-Bypass gibt es nicht. Private und
Loopback-Ziele sind damit auch über die Legacy-Werkzeuge gesperrt. Das ist eine bewusste
Verhaltensänderung. Freigaben laufen nur über `ARGOS_INTRANET_ALLOW`. `ARGOS_NETWORK_POLICY=off` ist ein
bewusstes Debug-Opt-out und kein Standard. Die Signaturen und die Navigations-API der neun Legacy-Werkzeuge
bleiben unverändert, und sie bekommen keine eigene kopierte URL-Prüfung.

Umgesetzt im Integrationsbranch: Der Vorab-Check ruft die zentrale `NetworkPolicy` auf und ist rein
informativ. `LegacyNavigateNetworkPolicyTest` prüft `legacy/browser_navigate` mit drei Fällen: Ein internes
Ziel ist blockiert, ein gezielt erlaubtes funktioniert, ein öffentliches funktioniert (zusätzlich als
Opt-in-Test mit echtem Camoufox und Proxy).

## Dokumentverarbeitung

`DocumentTextExtractor` ist vom Browser unabhängig und läuft **nicht** auf dem Browser-Thread.

| Format | Umsetzung |
| --- | --- |
| PDF | Apache PDFBox 3 (`PDFTextStripper`), höchstens 500 Seiten; ohne Textebene (Scan) → `readable=false` mit Hinweis, kein OCR |
| HTML | jsoup, Titel + Text, ohne Skripte |
| XML/RSS/Atom, JSON, CSV, TXT | Text mit deklariertem Zeichensatz bzw. BOM, begrenzt |
| DOCX, XLSX, PPTX, ODT, ODS, ODP | ZIP + StAX aus dem JDK (DTD/externe Entitäten aus, max. 64 MB entpackt einschließlich ignorierter Einträge, max. 2000 Einträge); XLSX-Zellen bleiben über ihre Zellreferenz in der richtigen Spalte |
| DOC, XLS, PPT, Binärdaten | `readable=false`, „Unsupported document type“ |

Apache POI und Tika (Enterprise `document-tika`) wurden nicht übernommen: Sie würden `argos.jar` um ein
Vielfaches vergrößern, und die modernen Office-Formate sind ohne sie lesbar.

Der Formattyp wird so bestimmt: PDF-Signatur `%PDF-` gewinnt immer, danach ein spezifischer Content-Type,
danach Dateiendung, danach die ersten Bytes. Binärdaten werden nie als Base64 zurückgegeben.

## Temporäre Dateien

`DocumentStore` legt ein Verzeichnis `argos-documents-*` unter `java.io.tmpdir` an (POSIX: `rwx------`).

| Grenze | Wert |
| --- | --- |
| Größe pro Dokument | 20 MB (Content-Length wird vorab geprüft; Batch: 10 MB) |
| Gesamtgröße | 200 MB, ältestes Dokument wird verdrängt |
| Anzahl | 50 Dokumente |
| Lebensdauer | 30 Minuten, danach ist die `downloadId` ungültig |
| Beim Beenden | Verzeichnis wird gelöscht |

Auf der Platte heißen Dateien nach ihrer ID (`doc-1.pdf`), nie nach dem Server-Dateinamen. Der Pfad erscheint
in keinem Toolergebnis. Benutzerinformationen in URLs werden aus den Metadaten entfernt.

Bei echten Browser-Downloads (Attachment) ist die Größe erst nach Abschluss bekannt; Playwright bietet keinen
Fortschritt. Zu große Dateien werden danach verworfen.

**Fristen ohne blockierende Aufrufe.** `Response.body()`, `Download.failure()`, `Download.path()` und
`Download.delete()` warten ohne eigenes Timeout, bis die Übertragung fertig ist. Argos ruft sie deshalb erst
auf, wenn der Browser das Ende gemeldet hat: Der Browser-Thread wartet in 50-ms-Scheiben
(`waitForTimeout`, verarbeitet dabei Playwright-Ereignisse) auf `requestfinished`/`requestfailed` der
Anfrage, höchstens bis zur Frist (Einzeldownload 75 s, Batch 15 s). Danach meldet Argos `TIMEOUT`.

| Fall | CDP (Chromium-Testlauf) | WebDriver BiDi (Camoufox) |
| --- | --- | --- |
| Response-Body (inline PDF, XML, …) | Ende über `requestfinished` | Ende über `requestfinished` |
| Attachment-Download | kein Ende-Ereignis (Anfrage wird sofort als `ERR_ABORTED` gemeldet); stattdessen bricht ein Konsolen-Takt aus einem `about:blank`-Hilfstab den Download nach der Frist per `Download.cancel()` ab | **`Download.cancel()` ist in Playwright 1.62 für BiDi wirkungslos** (`BidiBrowserContext.cancelDownload` ist leer, `coreBundle.js`). Argos wartet auf `requestfinished` der Download-URL; kommt es nicht bis zur Frist, meldet Argos `TIMEOUT` und fasst den Download nicht mehr an (kein `delete()`, das ewig warten würde). Die Übertragung läuft im Browser weiter, bis der Server sie beendet; die temporäre Datei entfernt Playwright beim Browserende. |

Der erste Camoufox-Lauf (Windows, 2c5f7fb) blieb genau an dieser Stelle hängen: Der alte Ablauf rief
`Download.failure()` auf und verließ sich auf `cancel()`. **Offen:** Ob Camoufox über BiDi für eine
abgeschlossene Attachment-Übertragung `requestfinished` meldet, ist nur dort messbar. Meldet es das nicht,
enden Attachment-Downloads in Camoufox immer mit `TIMEOUT` (begrenzt, aber ohne Ergebnis); Inline-Dokumente,
also der Normalfall für PDFs mit `Content-Type: application/pdf`, sind davon nicht betroffen.
`BackgroundTabDeadlineTest` prüft auf CDP beide Modi, auf Camoufox den BiDi-Modus.

## Parallelität: Messung und Grenze des Single-Thread-Executors

Playwright Java ist nicht thread-sicher. Argos führt alle Aufrufe auf **einem** Thread aus
(`CamoufoxBrowser.onBrowser`), und jeder Aufruf blockiert diesen Thread. Ein zweiter Playwright-Thread oder
eine zweite Playwright-Instanz auf demselben Profil ist nicht möglich bzw. nicht zulässig.

Daraus folgt für `browser_open_many`:

1. `page.navigate(url, COMMIT)` blockiert bis zu den Response-Headern. **Die Zeit bis zum ersten Byte
   (Server-Antwortzeit, Weiterleitungen) läuft daher Tab für Tab nacheinander.**
2. Alles danach – Body, Subressourcen, Skripte, Rendering – erledigt der Browser für alle offenen Tabs
   gleichzeitig, während der Thread auf den ältesten Tab wartet (`waitForLoadState(LOAD)`).
3. `BatchOpener` hält bis zu 4 Tabs in einem gleitenden Fenster offen: Sobald der älteste gelesen ist, lädt
   derselbe Tab die nächste Adresse. Ein Tab wird nur wiederverwendet, wenn seine Navigation committet hat
   (auch mit Fehlerstatus) oder ein fertiger Download war und nichts abgewiesen wurde; sonst wird er
   geschlossen und für die nächste Adresse ein neuer geöffnet. Vor der nächsten Adresse lädt der Tab
   `about:blank` (keine Netzwerkanfrage): Skripte der vorigen Seite können danach keinen Download und keine
   Navigation mehr auslösen, und die nächste Adresse ist immer ein neues Dokument (nie nur ein Sprung zu einem
   `#Fragment`). Als Download einer Adresse zählt nur ein Download ihrer eigenen Navigationen; einen Download,
   den die vorige Seite kurz vor dem Verlassen begonnen hat, meldet Firefox erst etwas später, und er wird
   ignoriert (im ungünstigsten Fall bricht er die Navigation der nächsten Adresse ab, die dann als Fehler
   gemeldet wird). Ein `beforeunload`-Dialog beim Verlassen wird
   bestätigt (Handler im Treiber, für die Seite unsichtbar). Die nächste Website sieht in diesem Tab
   `history.length` > 1, wie in jedem Tab, in dem ein Mensch weitersurft.

Gemessen mit `BatchParallelismMeasurementTest` (lokaler Server mit 4 Threads, 4 Adressen, 1500 ms Verzögerung
je Adresse):

| Lauf | ohne Verzögerung, 4 Tabs nacheinander | Body verzögert: sequenziell / 4 Tabs | Header verzögert: sequenziell / 4 Tabs |
| --- | --- | --- | --- |
| Chromium 1243 headless, CDP, Linux | 1249 ms | 6909 / **2096 ms** | 6902 / 7036 ms |
| Camoufox, BiDi, Windows (Angelo, 2c5f7fb, Fixtures über `localhost`) | – | 25 372 / **19 985 ms** | 27 534 / 25 457 ms |
| Camoufox 156.0.1-beta.36 (gepatcht), BiDi, Windows headless (Angelo, 12fb7d6, ohne Tab-Wiederverwendung) | 17 089 ms, davon `newPage()` 15 890 ms | 27 420 / **18 391 ms** | 22 996 / 23 152 ms |
| Camoufox 158.0-beta.40 (gepatcht), BiDi, Linux headless (12fb7d6) | 2021 ms, davon `newPage()` 628 ms | 7608 / **2770 ms** | 7699 / 7585 ms |
| Camoufox 158.0-beta.40 (gepatcht), BiDi, Linux headless, mit Tab-Wiederverwendung (`about:blank` dazwischen) | 1658 ms (ein Tab für 4 Adressen; 4 leere Tabs öffnen: 533 ms) | 7185 / **2571 ms** | 7361 / 7515 ms |

Auswertung:

- Eingespart wird höchstens die Wartezeit **nach** dem Commit, also bei 4 Tabs bis zu 3 × 1500 ms. Chromium
  spart 4813 ms, Camoufox 5387 ms. In beiden Browsern überlappt diese Phase also vollständig.
- Was **vor** dem Commit liegt (Tab öffnen, Verbindung aufbauen, Header abwarten, Tab schließen), bleibt
  seriell. Im Camoufox-Lauf waren das rund 4,8 s pro Tab. Der größte Teil davon ist sehr wahrscheinlich ein
  Artefakt der Testumgebung: `localhost` löst unter Windows zuerst auf `::1` auf, der Testserver hört nur auf
  `127.0.0.1`, und Windows wartet bei einer abgewiesenen Verbindung etwa 2 s vor dem Rückfall auf IPv4. Die
  Fixtures verwenden deshalb jetzt `127.0.0.1`. Die neue Spalte „ohne Verzögerung“ misst die echten
  Grundkosten pro Tab und muss für Camoufox neu erhoben werden.
- Die Testschwelle prüft deshalb nicht mehr ein festes Verhältnis, sondern dass mindestens 60 % der
  überlappbaren Wartezeit tatsächlich eingespart werden. Das hätte auch der Camoufox-Lauf mit 5387 ms erfüllt.

- `context.newPage()` kostet in Camoufox 156 unter Windows rund 4 s pro Tab (Linux, headless und unter Xvfb
  headed: rund 0,2 s). Playwright4J öffnet jeden neuen Tab im Vordergrund (`browsingContext.create` ohne
  `background: true`); die Ursache unter Windows ist noch nicht gemessen. Seit der Tab-Wiederverwendung
  öffnet ein Batch höchstens `parallelTabs` Tabs statt einen pro Adresse; ein einzelner Download zahlt die
  Kosten weiterhin einmal.

**Leistungsversprechen:** `browser_open_many` spart die Lade- und Renderzeit nach dem Commit (typisch der
größte Teil moderner Seiten), nicht die Antwortzeit langsamer Server und nicht die Grundkosten pro Tab. Bei
hohen Grundkosten pro Tab in Camoufox ist der Gewinn gegenüber Einzelaufrufen klein. Der wesentliche Nutzen
bleibt dann: ein Aufruf statt zehn, ein Ergebnis pro URL und eine Fehlerisolation. `browser_open_many` gibt
`parallelTabs` und pro Eintrag `elapsedMs` aus und nennt sich nicht pauschal „parallel“.

Die Grenze liegt in der Java-Client-Schicht von Playwright und nicht am Browser.

Verworfene Alternativen:

- `navigate` mit sehr kurzem Timeout „anstoßen“ und den `TimeoutError` ignorieren: würde auch die Header-Phase
  parallelisieren, verlässt sich aber auf undokumentiertes Verhalten (ob eine abgebrochene Wartephase die
  Navigation weiterlaufen lässt) und meldet echte Timeouts falsch.
- Navigation per `location.href` aus der Seite: Skript in der Seite, Stealth-Stufe schlechter.
- Mehrere Threads auf dieselbe Playwright-Instanz: nicht thread-sicher.

## Tests

```bash
./gradlew test                                       # Unit-Tests (Extraktion, Store, Planung)
./gradlew test -PtestCamoufox=true                   # zusätzlich Browser-Tests mit Camoufox
./gradlew test -PtestChromium=/path/to/chrome        # Browser-Tests mit Chromium (engine-unabhängige Logik)
```

Die Browser-Tests (`ResearchDocumentsBrowserTest`, `BatchParallelismMeasurementTest`) arbeiten nur mit
lokalen HTTP-Fixtures (`FixtureSite`): PDF über Link der aktuellen Seite (Seite bleibt erhalten),
Attachment-Download mit Server-Dateinamen, Größenlimit, 404, Batch mit 404, verweigerter Verbindung, PDF, RSS,
Duplikat und ungültiger Adresse. Der Messbericht landet in `build/reports/argos/`.

**Noch nicht mit Camoufox belegt** (der Chromium-Lauf beweist diese Teile nicht, weil Chromium über CDP statt
WebDriver BiDi läuft):

1. PDF als Navigations-Response: Liefert `Response.body()` unter BiDi/pdf.js die echten PDF-Bytes (Data
   Collector), oder kommt die Datei als Download an? Beides ist implementiert; der Test zeigt, welcher Weg greift.
2. Attachment-Download: Kommt `Page.onDownload` unter BiDi an, und liefert `Download.path()` die Datei?
3. Frist (`BackgroundTabDeadlineTest`): Werden Konsolenmeldungen des Hilfstabs während `Download.failure()`
   bzw. `Response.body()` zugestellt, und bricht `Download.cancel()` den BiDi-Download ab?
4. Redirect-Hop-Erkennung: Meldet BiDi den Redirect-Hop als Navigationsanfrage des Haupt-Frames?

Alle vier laufen mit `./gradlew test -PtestCamoufox=true --tests '*BrowserTest' --tests '*DeadlineTest'
--tests '*Measurement*'`. Schlägt einer fehl, ist das vor der Integration zu beheben.

Für Chromium stellt der Test die eingebaute PDF-Anzeige ab (`plugins.always_open_pdf_externally`), damit die
PDF-Datei als Download ankommt; liefert ein Browser stattdessen seine Viewer-Seite, meldet Argos das als
Fehler statt HTML als PDF auszugeben.
