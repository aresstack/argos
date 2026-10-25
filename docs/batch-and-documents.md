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

Jeder Download und jede Batch-Adresse wird in einem **eigenen Hintergrund-Tab derselben persistenten Session**
geöffnet (`BackgroundTab`), danach wird der Tab geschlossen. Die aktuelle Seite wird nicht navigiert.

- Die Bytes stammen aus dem Netzwerkstack des Browsers: dieselben Cookies, Logins, TLS-Fingerprints und
  Request-Header wie bei einem normalen Tab.
- Quelle ist entweder die Navigations-Response (`Response.body()`, unter BiDi der Data Collector von Playwright,
  bis 20 MB) oder – bei `Content-Disposition: attachment` – der echte Browser-Download (`Page.onDownload`).
- **Kein** Preload-/Init-Skript, kein `exposeBinding`, kein In-Page-`fetch()` (Enterprise `FETCH_FUNCTION`
  wurde bewusst nicht übernommen) und kein `APIRequestContext` (eigener HTTP-Client des Treibers mit
  abweichendem TLS-Fingerprint). Text gelesen wird nur über `innerText`/`textContent`-Locator-Abfragen.
- Nicht umgehbar: 401/403/429 werden als `BLOCKED` gemeldet; Paywalls und Logins bleiben, was sie sind.

Bekannte Einschränkung: Ein neuer Tab sendet keinen `Referer`. Server, die Dokumente nur mit Referer ausliefern,
liefern dann 403 (`BLOCKED`). Ein Klick auf den Link in der aktuellen Seite würde den Referer senden, verlässt
aber die Seite; das ist bewusst nicht der Standardweg.

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
Fortschritt. Zu große Dateien werden danach verworfen. Damit ein hängender Server den einzigen Browser-Thread
nicht blockiert, haben Download-Abschluss und `Response.body()` eine eigene Frist (Einzeldownload 75 s, Batch
15 s): Ein Hilfstab mit `about:blank` (nie eine Website) erzeugt per Laufzeit-`evaluate` alle 250 ms eine
Konsolenmeldung. Playwright Java verarbeitet Ereignisse auch während der blockierenden Wartezeit, und nach Ablauf
der Frist bricht der Handler den Download ab (`Download.cancel()`) bzw. schließt den Tab. Ergebnis ist `TIMEOUT`.
Getestet mit einem Server, der nach dem ersten Block nichts mehr sendet (`BackgroundTabDeadlineTest`).

## Parallelität: Messung und Grenze des Single-Thread-Executors

Playwright Java ist nicht thread-sicher. Argos führt alle Aufrufe auf **einem** Thread aus
(`CamoufoxBrowser.onBrowser`), und jeder Aufruf blockiert diesen Thread. Ein zweiter Playwright-Thread oder
eine zweite Playwright-Instanz auf demselben Profil ist nicht möglich bzw. nicht zulässig.

Daraus folgt für `browser_open_many`:

1. `page.navigate(url, COMMIT)` blockiert bis zu den Response-Headern. **Die Zeit bis zum ersten Byte
   (Server-Antwortzeit, Weiterleitungen) läuft daher Tab für Tab nacheinander.**
2. Alles danach – Body, Subressourcen, Skripte, Rendering – erledigt der Browser für alle offenen Tabs
   gleichzeitig, während der Thread auf den ältesten Tab wartet (`waitForLoadState(LOAD)`).
3. `BatchOpener` hält bis zu 4 Tabs in einem gleitenden Fenster offen: Sobald der älteste gelesen und
   geschlossen ist, wird die nächste Adresse geöffnet.

Gemessen mit `BatchParallelismMeasurementTest` (lokaler Server mit 4 Threads, 4 Adressen, 1500 ms Verzögerung
je Adresse), Chromium 1243 headless über Playwright4J/CDP, Oktober 2026:

| Verzögerung | sequenziell (1 Tab) | gleitendes Fenster (4 Tabs) |
| --- | --- | --- |
| nach dem Commit (Body kommt verzögert) | 7068 ms | **2228 ms** |
| vor dem Commit (Header kommen verzögert) | 6886 ms | 6870 ms (kein Gewinn) |

Ergebnis: Die Parallelität ist echt, aber nur für die Ladephase nach dem Commit. Für Seiten, deren Zeit
überwiegend in Skripten, Nachladen und Rendering steckt (die meisten modernen Seiten), ist der Gewinn
erheblich; bei langsamen Servern bleibt es sequenziell. `browser_open_many` meldet das nicht als „parallel“,
sondern liefert `parallelTabs` und pro Eintrag `elapsedMs`.

Die Grenze liegt in der Java-Client-Schicht von Playwright und nicht am Browser. Der Lauf mit Camoufox ist
derselbe Test mit `-PtestCamoufox=true` (siehe unten) und sollte vor einem Release einmal lokal ausgeführt
und hier ergänzt werden. Im Cloud-Container dieses Arbeitspakets stand kein Camoufox/Firefox zur Verfügung.

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

Für Chromium stellt der Test die eingebaute PDF-Anzeige ab (`plugins.always_open_pdf_externally`), damit die
PDF-Datei als Download ankommt; liefert ein Browser stattdessen seine Viewer-Seite, meldet Argos das als
Fehler statt HTML als PDF auszugeben.
