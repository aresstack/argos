# Argos

**Argos** ist ein eigenständiger Java-21-MCP-Server zur interaktiven Untersuchung von Webseiten. Er verwendet ausschließlich den gepatchten **Camoufox**-Browser über **Playwright4J / WebDriver BiDi**, ohne Node.js als eigenen Serverprozess.

Das Repository ist unabhängig von GPT Desktop, Pyloros und dem AresStack Webserver. Argos kann als **Managed JAR Application** vom AresStack Webserver gestartet, überwacht und bei Abstürzen neu gestartet werden.

## Architektur

```text
ChatGPT / MCP-Client
       │ HTTPS + zentrale OAuth-Authentifizierung
       ▼
AresStack Webserver (Caddy-Reverse-Proxy)
       │ HTTP nur nach 127.0.0.1
       ▼
Argos :SERVER_PORT ─── GET /health
       └─────── / (offizieller MCP Java SDK Streamable HTTP)
                   │
                   ▼
             CamoufoxBrowser
                   │ Java Playwright API / Playwright4J
                   ▼
            Camoufox / Firefox BiDi
                   └── eigenes persistentes Browserprofil
```

Die MCP-Implementierung nutzt `io.modelcontextprotocol.sdk:mcp:0.18.4` und eingebettetes Jetty 12. Es wird **kein MCP-Protokoll selbst implementiert**. Der Browser startet erst beim ersten Browseraufruf, nicht beim Health-Check oder bei `tools/list`. Alle Playwright-Aufrufe werden auf einem einzelnen Browser-Thread serialisiert.

## Bauen (Windows)

Voraussetzung: Java 21 sowie **Playwright4J `0.3.0`**, basierend auf Commit [`2e889ce9fdfa7cc360c1c3c4434d926e30f79784`](https://github.com/aresstack/playwright4j/commit/2e889ce9fdfa7cc360c1c3c4434d926e30f79784). Diese Version enthält die geprüfte Unicode-Tastatureingabe aus #73. Der lokale Build löst `com.aresstack:playwright4j:0.3.0` über Maven Local auf; nach Veröffentlichung ist Maven Central ebenfalls verfügbar. Die CI baut genau diesen Playwright4J-Commit und veröffentlicht ihn vor dem Argos-Build in das Maven-Local-Repository des Runners. Für Integrationstests gegen einen anderen Playwright4J-Quellstand kann `-Pplaywright4jSource=<checkout>` verwendet werden.

```powershell
.\gradlew.bat test packageArgos argosZip
```

Erzeugt:

```text
build/package/
  argos.jar        ← einzige, vollständig ausführbare JAR
build/distributions/argos-package.zip  ← enthält nur argos.jar
```

**Wichtig:** `argos.jar` ist eine eigenständig startbare Spring-Boot-Executable-JAR (nur der Launcher; Argos bleibt eine gewöhnliche Java-Anwendung). Playwright4J, MCP SDK und GraalJS liegen als unveränderte verschachtelte Bibliotheken in `BOOT-INF/lib/`. Ein externes `lib/`-Verzeichnis ist **nicht** erforderlich. Gradles `bootJar` erhält Multi-Release- und Service-Loader-Metadaten. **Nur die JAR** muss im Webserver hinterlegt werden. Mit `java -jar argos.jar --verify-runtime` lässt sich der komplette Playwright4J-Treiber ohne Browser prüfen; bei Erfolg erscheint `ARGOS_RUNTIME_OK`. Dieser Selbsttest läuft zusätzlich **bei jedem `packageArgos`-Build automatisch** und lässt den Build scheitern, wenn das Paket zwar startet, aber der Treiber nicht geladen werden kann.

## Lokaler Start

Argos liest Browserpfade ausschließlich aus Umgebungsvariablen oder seiner eigenen Konfiguration `~/.argos/argos.properties` (alternativ über `-Dargos.config`). **Konfigurationsdateien anderer Anwendungen werden nicht gelesen.** Fehlen beide Browserpfade, meldet Argos beim ersten Browseraufruf eine verständliche Konfigurationsfehlermeldung. Argos verwendet ausschließlich eigene Browserprofile unter `~/.argos/profiles/default`.

Eigenständige Konfiguration über `~/.argos/argos.properties`:

```properties
server.host=127.0.0.1
server.port=8085
security.mode=central-oauth
public.origin=https://argos.aresstack.de
oauth.central-issuer=https://argos.aresstack.de
security.required-scope=argos
browser.profile=C:/Users/USERNAME/.argos/profiles/default
browser.headless=false
```

**Camoufox wird nicht von Hand installiert.** Beim ersten Browserzugriff lädt Argos seine eigene Camoufox-Distribution (Camoufox `156.0.1-beta.36` mit den WebDriver-BiDi-Fixes aus daijro/camoufox#952) automatisch herunter, prüft ihre Integrität (gepinnte SHA-256-Prüfsumme und vorhandene BiDi-Fixes), installiert sie atomar und startet sie mit dem eingebauten Fingerprint-Profil. Weder eine manuelle Camoufox-Installation noch Browserpfade noch eine externe `launch.json` sind erforderlich; Argos liest keine Konfigurationsdateien anderer Anwendungen.

Das Installationsverzeichnis ist optional über `BROWSER_DIR` (alternativ Property `browser.install-dir`; die Umgebungsvariable hat Vorrang) wählbar. Ohne Angabe verwendet Argos `~/.argos/browser`. Pfade mit Laufwerksbuchstaben und Leerzeichen sind erlaubt:

```powershell
# Windows
$env:BROWSER_DIR = "D:\Argos Browser"
java -jar argos.jar
```

```bash
# Linux
export BROWSER_DIR="/opt/argos browser"
java -jar argos.jar
```

Die Launch-Konfiguration (Fingerprint, Prefs, Startoptionen) ist immer vorhanden: Ohne `BROWSER_LAUNCH_CONFIG` (Property `browser.launch-config`) verwendet Argos sein eingebautes Standardprofil; nur wer eine eigene Datei will, setzt die Variable auf deren Pfad. Die ausführbare Datei ermittelt Argos selbst aus seiner verwalteten Installation.

Einzelheiten, Overrides und Fehlerverhalten: [docs/camoufox-distribution.md](docs/camoufox-distribution.md).

**Im AresStack Webserver haben die Umgebungsvariablen Vorrang vor den Properties.** Pflichtfeld ist `SERVER_PORT`; Argos besitzt **keinen fest einprogrammierten Standardport**. Wie bei Pyloros werden `SERVER_PORT`, `SERVER_HOST` (optional, nur Loopback), `SECURITY_MODE`, `REQUIRED_SCOPE`, `PUBLIC_ORIGIN` und `CENTRAL_OAUTH_ISSUER` ausgewertet. Für den Camoufox-Browser sind `BROWSER_PROFILE_DIR` und `BROWSER_HEADLESS` verfügbar; `BROWSER_LAUNCH_CONFIG`, `BROWSER_DIR` und `CAMOUFOX_DISTRIBUTION_URL` sind optionale Overrides (siehe [docs/camoufox-distribution.md](docs/camoufox-distribution.md)) und im Webserver nicht nötig.

**Umstellung von `ARGOS_*`:** Alle Konfigurationsvariablen tragen kein `ARGOS_`-Präfix mehr (z. B. `TOOL_PROFILE`, `PROFILE_ISOLATION`, `CHAT_PROFILES_DIR`, `MAX_BROWSERS`, `BROWSER_IDLE_MINUTES`, `SEARCH_ENGINES`). Sprechender benannt wurden `ARGOS_PROFILE_DIR` → `BROWSER_PROFILE_DIR`, `ARGOS_HEADLESS` → `BROWSER_HEADLESS`, `ARGOS_RESEARCH` → `RESEARCH_ENABLED` und `ARGOS_SEARCH_HISTORY` → `SEARCH_HISTORY_ENABLED`. Übergangsweise liest Argos die alten Namen weiter als veraltete Aliase: Der neue Name hat Vorrang, und jeder gefundene Alias (auch ein abweichender Doppelwert) wird beim Start nur mit seinem Namen, nie mit dem Wert, gemeldet. Die Liste steht in `EnvironmentVariables`; die Aliase entfallen erst in einem eigenen Breaking Change.

Die bereits angelegten Webserver-Variablen für Argos sind:

| Variable | Wert |
| --- | --- |
| `SERVER_PORT` | `8085` |
| `SECURITY_MODE` | `central-oauth` |
| `REQUIRED_SCOPE` | `argos` |
| `PUBLIC_ORIGIN` | `https://argos.aresstack.de` |
| `CENTRAL_OAUTH_ISSUER` | `https://argos.aresstack.de` |

`central-oauth` ist der sichere Standard und benötigt die beiden HTTPS-Origins. Argos validiert eingehende JWTs selbst mit der **AnAuthentia**-Bibliothek (Signatur aus JWKS, Issuer, Audience, Ablaufdatum und Scope). `/.well-known/oauth-protected-resource` ist ohne Token erreichbar und verweist auf den zentralen Issuer. `/health` bleibt lokal ohne Token erreichbar; `/` verlangt ein gültiges Bearer-Token mit Scope `argos`. Nur mit ausdrücklich gesetztem `SECURITY_MODE=none` sind lokale MCP-Tests ohne JWT möglich. Dieser Modus darf nicht im öffentlich angebundenen Webserver aktiviert werden.

Start:

```powershell
java -jar .\build\package\argos.jar
```

Prüfen bei der gezeigten Konfiguration: `http://127.0.0.1:8085/health`, MCP: `http://127.0.0.1:8085/`.

## AresStack Webserver – Managed JAR

- **JAR:** Pfad zur entpackten `argos.jar`.
- **Working directory:** Beliebiges Arbeitsverzeichnis für Argos; `argos.jar` benötigt kein externes `lib/`.
- **Java executable:** Java 21.
- **Start automatically / Restart automatically:** optional einschalten.
- **Health check:** `http://127.0.0.1:8085/health` (entspricht `SERVER_PORT`).
- **Forward to:** `127.0.0.1:8085`; **MCP-Endpunkt ist `/` (Root)** – genau wie bei Pyloros.

Die öffentliche Adresse und zentrale OAuth-JWT-Ausstellung erfolgen im AresStack Webserver. Argos verwendet dieselbe Signatur- und Scope-Prüfung wie Pyloros, führt jedoch **keine eigenen OAuth-Anmeldeseiten** aus. Ein nicht validiertes Bearer-Token wird mit HTTP 401 zurückgewiesen. **Niemals mit `SECURITY_MODE=none` öffentlich veröffentlichen.** Argos bindet sich nur an `127.0.0.1` oder `::1`.

## ChatGPT-Plugin – exakt dieselben OAuth-/Root-Endpunkte wie Pyloros

Die vorliegende Pyloros-Einrichtungsanleitung ist die Vorlage. Bei Argos werden lediglich der **Dienstname**, die **Subdomain** und der **dienstspezifische Berechtigungsumfang** ersetzt:

| Feld in ChatGPT | Argos |
| --- | --- |
| Verbindungsart | Server-URL |
| Verbindung / MCP-Server-URL | **`https://argos.aresstack.de/`** (Root inklusive abschließendem Slash) |
| Authentifizierung | OAuth |
| Registrierungsmethode | Benutzerdefinierter OAuth-Client |
| OAuth-Callback-URL | Von ChatGPT vorgegebenen Wert **unverändert** verwenden |
| Client-ID / Client-Secret | Vom zentralen AresStack-OAuth-Server eingerichtetes Wertepaar; keine festen Credentials im Repository |
| Token-Endpunkt-Authentifizierung | `client_secret_post` |
| Standard-Scopes | Leer |
| Basis-Scopes | Der mit dem AresStack OAuth-Client **tatsächlich vereinbarte Scope**; bei Argos muss das ausgestellte Token `argos` enthalten |
| Autorisierungs-URL | `https://argos.aresstack.de/oauth/authorize` |
| Token-URL | `https://argos.aresstack.de/oauth/token` |
| Registrierungs-URL | Leer (fest konfigurierte Clients) |
| Basis-URL des Autorisierungsservers | `https://argos.aresstack.de` |
| Resource | **`https://argos.aresstack.de/`** |
| PKCE (S256) | Aktiviert |
| OIDC | Deaktiviert; Zusatzfelder leer |

**Root-Verhalten wie Pyloros:**

- `POST /` verarbeitet MCP-JSON-RPC (`initialize`, `tools/list`, `tools/call`).
- `GET /` antwortet nach erfolgreicher Authentifizierung mit **405 Method Not Allowed** und `Allow: POST`. Kein leerer, sofort beendeter SSE-Stream.
- `/.well-known/oauth-protected-resource`, `/.well-known/oauth-authorization-server`, `/.well-known/jwks.json`, `/oauth/authorize` und `/oauth/token` werden wie bei Pyloros von der **zentralen OAuth-Komponente des AresStack Webservers** geliefert.
- `GET /health` ist der interne Prozess-Health-Check.
- **`/mcp` ist nicht die ChatGPT-Verbindungsadresse.** Der frühere abweichende Argos-Endpunkt wurde zugunsten des Pyloros-Root-Endpunkts ersetzt.

Der Login allein ist **keine** Discovery-Abnahme. Nach erfolgreicher OAuth-Verbindung muss ChatGPT `tools/list` an `POST /` ausführen und die Argos-Werkzeuge erkennen. Der lokale Test `scripts/smoke-standalone.ps1` prüft `GET /` → 405 und `POST /` → `initialize`, `tools/list`, Interaktionen.

## MCP-Werkzeuge

| Tool | Zweck |
| --- | --- |
| `browser_status` | Browserstatus und Profil |
| `browser_navigate` | HTTP(S)-Seiten aufrufen |
| `browser_snapshot` | Seitentext abhängig vom Dokumenttyp lesen (HTML, XML/RSS/Atom, JSON, Text; Binärdokumente nur erkennen) |
| `browser_wait` | (nur Profil `expert`) Auf Seitenbereitschaft warten, optional auf sichtbares Element (`selector`) oder sichtbaren Text (`text`) |
| `browser_inspect` | DOM-Elemente anhand CSS-Selektoren untersuchen |
| `browser_click` | Elemente anklicken |
| `browser_fill` | Eingabefelder ausfüllen |
| `browser_evaluate` | JavaScript in der Webseite ausführen |
| `browser_screenshot` | Screenshot als MCP-Image plus Textlegende: `mode` = `viewport` (Standard), `full` oder `element` (mit `target`), `mark=true` zeichnet die nummerierten Bedienelemente nur ins Bild (nicht ins DOM). Ein Bildpixel entspricht einem CSS-Pixel des Viewports (Device-Pixel-Ratio herausgerechnet, große Bilder auf max. 2000 px skaliert); die Legende nennt Umrechnung und Mittelpunkte in Viewport-Koordinaten für die Maus-Werkzeuge |
| `browser_network` | Letzte HTTP-Requests, Responses und Fehler untersuchen (eine Zeile je Request mit ID, optional `filter`) |

### Tabs, Historie, Bedienelemente und Extraktion

Diese Werkzeuge stehen in `ArgosBrowsingTools` als getrennte Listen `expert()` und `compact()` bereit und werden erst über die Profilauswahl veröffentlicht. Das bisherige Profil bleibt bei genau neun Werkzeugen. `web_controls`, `web_click` und `web_fill` sind für kleine Modelle vorgesehen, müssen aber vor der Aufnahme ins Kompaktprofil gegen kleine Modelle getestet werden.

| Tool | Profil | Zweck |
| --- | --- | --- |
| `browser_tabs`, `browser_tab_new`, `browser_tab_select`, `browser_tab_close` | expert | Tabs mit kurzen IDs (`t1`, `t2`); von Webseiten geöffnete Tabs (`target=_blank`) werden übernommen und nach einem Klick aktuell |
| `browser_navigate`, `browser_snapshot`, `browser_inspect`, `browser_click`, `browser_fill`, `browser_evaluate`, `browser_screenshot`, `browser_network` | legacy + expert | Optionales Feld `tab` adressiert einen bestimmten Tab; ohne Feld bleibt das aktuelle Verhalten erhalten |
| `browser_back`, `browser_forward`, `browser_reload` | expert | Historie des aktuellen oder explizit adressierten Tabs |
| `browser_pages_navigate`, `browser_pages_snapshot`, `browser_pages_inspect` | expert | Mehrere bestehende Tabs in einem MCP-Aufruf navigieren oder lesend inspizieren; Ergebnisse pro Tab und in Eingabereihenfolge |
| `browser_controls` | expert | Bedienelemente mit Nummer, Aria-Ref und semantischem Locator (`role=button name="Suchen"`) |
| `browser_click_target`, `browser_fill_target` | expert | Klick bzw. Eingabe auf Nummer, Ref, semantischen Locator (`role=`, `label=`, `placeholder=`, `text=`) oder sichtbaren Namen |
| `browser_mouse_move`, `browser_mouse_click` | expert | Zeiger bewegen bzw. klicken (`button`: `left`, `middle`, `right`) an `x`/`y` in CSS-Pixeln des Viewports: (0, 0) ist dessen linke obere Ecke, wie im Screenshot, unabhängig vom Scrollstand; Punkte außerhalb werden abgelehnt |
| `browser_hover` | expert | Zeiger auf ein Element (gleiche Ziele wie `browser_click_target`) legen, ohne zu klicken, z. B. für Hover-Menüs |
| `browser_scroll` | expert | Mausrad um `delta_x`/`delta_y` CSS-Pixel (positiv: rechts/unten) drehen; optional erst über `target` oder `x`/`y`, damit der Scrollbereich darunter scrollt |
| `browser_drag` | expert | Ziehen und Ablegen mit echter Maus (drücken, bewegen, loslassen über WebDriver BiDi): Quelle `source` oder `from_x`/`from_y`, Ziel `target` oder `to_x`/`to_y`; kein `locator.dragTo`, keine Skript-Events. Funktioniert für Seiten, die mit Pointer-/Maus-Events ziehen (Slider, Karten, Sortierlisten im Fallback-Modus). **Bekannte Grenze:** natives HTML5-Drag-and-Drop (`draggable`) schließt Firefox/Camoufox bei automatisierter Eingabe nicht ab ([Mozilla-Bug 1515879](https://bugzilla.mozilla.org/show_bug.cgi?id=1515879)): `dragstart`/`dragover` kommen, `drop`/`dragend` nicht; das Ergebnis enthält dann `warning`. Abnahme: `DragToolsBrowserTest.pointerDragsCompleteWithTrustedEvents` (muss grün sein); die Grenze prüft `html5DragReportsTheKnownFirefoxLimit` (Warnung, kein Drop vorausgesetzt) |
| `browser_extract` | expert | `overview`, `headings`, `links`, `tables`, `lists`, `metadata` (inkl. `citation_*`, Open Graph), `jsonld` |
| `web_controls`, `web_click`, `web_fill` | compact | Dieselbe Bedienung für kleine Modelle: nummerierte Textliste, ein bis zwei Parameter |

**Tarnung:** Gelesen wird nur über Aufrufe, die nachweislich keine Spuren in der Seite hinterlassen: den seitenweiten Aria-Snapshot, `aria-ref`-Locator mit `count`/`boundingBox` und `evaluateAll`. Locator-Aktionen (auch Trial-Klicks), `isEditable`, `getAttribute`, `innerHTML` und `locator.ariaSnapshot` lösen dagegen `__playwright_mark_target__`-Events aus, die eine Seite abhören kann ([playwright4j#38](https://github.com/aresstack/playwright4j/issues/38)), und werden deshalb nicht verwendet. Es gibt keine Preload- oder Init-Scripts und keine Marker-Attribute. Klicks, Scrollen und Tippen laufen als echte BiDi-Eingabe mit menschlichem Verhalten (siehe [Menschliche Eingabe](#menschliche-eingabe)). Die Webseite sieht `isTrusted=true`. Der echte Browsertest prüft, dass keine Marker-Events und keine neuen globalen Namen entstehen. Passwortwerte werden in Bedienelement-Listen nie ausgegeben.

**Playwright Core 1.62 (Playwright4J) und Unicode:** Mit dem gepinnten Playwright4J `0.3.0` (`2e889ce`, enthält [playwright4j#73](https://github.com/aresstack/playwright4j/pull/73)) nimmt `keyboard.down/up/press` über BiDi auch ein einzelnes Graphem außerhalb des US-Tastaturlayouts (z. B. `ä`, `ß`, `€`) an und sendet es als echtes, vertrauenswürdiges keyDown/keyUp über `input.performActions`. Argos tippt solche Zeichen Taste für Taste darüber. `type()`, `insertText()` und `fill()` bleiben unverändert; deren Seitenskript `window.__pw_bidiInsertText` ([playwright4j#36](https://github.com/aresstack/playwright4j/issues/36)) benutzt Argos nicht. Das zurückgenommene [playwright4j#59](https://github.com/aresstack/playwright4j/pull/59) wird nicht mehr gebraucht. Für zuverlässige Historie setzt Argos `browser.sessionhistory.max_total_viewers=0`, weil Playwright Rücksprünge aus dem Back-Forward-Cache sonst nicht bemerkt; die offizielle `launch.json` kann den Wert überschreiben.

### Menschliche Eingabe

Jede Maus- und Tastatureingabe geht als vertrauenswürdiges Ereignis über WebDriver BiDi `input.performActions`; die Einstellungen ändern nur Wege und Zeiten, nie den Kanal. Details und Quellen: [`docs/human-input.md`](docs/human-input.md).

- **Maus:** Der Zeiger betritt einen Tab über die obere Kante und springt danach nie. Jede Bewegung dauert so lange, wie Fitts' Gesetz für Entfernung und Zielgröße vorhersagt, folgt einer gekrümmten Bézier-Bahn mit Beschleunigen und Abbremsen, trägt ein leichtes Zittern (8–12 Hz), das zum Ziel hin abklingt, und schießt bei weiten Wegen manchmal kurz über das Ziel hinaus. Geklickt wird normalverteilt um die Mitte, nach einer kurzen Ruhepause auf dem Ziel und mit unterschiedlich langem Drücken.
- **Scrollen:** nur mit dem Mausrad, in Schüben von 1–5 Rasten mit Lesepausen; liegt das Element in einem eigenen Scrollbereich, fährt der Zeiger erst darüber.
- **Tastatur:** log-normal verteilte Tastenabstände, längere Pausen nach Leerzeichen und Satzzeichen, gelegentliches Zögern, überlappende Anschläge, Großbuchstaben und Shift-Symbole mit gedrückter Shift-Taste. Optional Tippfehler auf einer Nachbartaste, die sofort mit Backspace korrigiert werden. Lange Texte werden proportional gestrafft, damit ein Werkzeugaufruf unter 25 s bleibt.

| Variable | Property | Werte | Standard |
| --- | --- | --- | --- |
| `HUMAN_INPUT` | `input.human` | `off` (gerade Wege, feste kurze Pausen), `natural`, `careful` (langsamer) | `natural` |
| `HUMAN_SPEED` | `input.speed` | Tempo-Faktor 0.25–4 | `1` |
| `HUMAN_TYPO_RATE` | `input.typoRate` | Tippfehler je Buchstabe 0–0.2 | `0` |
| `HUMAN_SEED` | `input.seed` | feste Zufallszahl für reproduzierbare Eingaben | zufällig |
| `INPUT_UNICODE` | `input.unicode` | `strict`: Text mit Zeichen, die Playwright nicht als echte Taste sendet, wird vor dem Fokussieren abgelehnt (fail closed); `transliterate`: bewusste Ausnahme, ändert den Text (ä → ae, ß → ss, „“ → "); `insert`: nur Debug-/Kompatibilitätsmodus, Playwright-Fallback über ein Seitenskript (für die Seite sichtbar) | `strict` |

Ob Playwright Unicode-Tasten als echte Tasten sendet, prüft Argos einmal beim Browserstart auf einem leeren Tab. Sobald Playwright4J sie über `performActions` unterstützt ([playwright4j#36](https://github.com/aresstack/playwright4j/issues/36)), nutzt Argos sie automatisch.

**Stand:** Mit dem in der CI gepinnten Playwright4J `0.3.0` (`2e889ce`, enthält [playwright4j#73](https://github.com/aresstack/playwright4j/pull/73)) fällt diese Prüfung positiv aus (`KEY_NAMES`): Zeichen außerhalb des US-Layouts (Umlaute, `ß`, `€`, typografische Anführungszeichen) werden in der Voreinstellung `strict` als echte Tasten getippt. Nachgewiesen unter Linux mit Camoufox 156.0.1-beta.36 gegen `0.3.0` und `-PexpectUnicodeKeys=true`, bei dem eine Ablehnung als Fehler zählt: `UnicodeInputCamoufoxTest` (`web_fill` mit „Grüße“, vertrauenswürdige keydown/keyup/input für `ü` und `ß`) und `HumanInputBrowserTest` (`-PtestHumanBrowser`, keydown und input für `ü`, `ß`, `Ä`, `€`, keine nicht vertrauenswürdigen Ereignisse). Gegen `2aea2e4` (Revert von #59, ohne #73) schlagen beide mit dieser Einstellung fehl. Die vollständige Suite lief zusätzlich unter Windows gegen `fe12949` (in `0.3.0` enthalten) mit 586 von 586 Tests grün.

### Dokumenttypen und Seitenbereitschaft

`browser_snapshot` setzt kein HTML-`body` mehr voraus. Der Dokumenttyp wird aus dem `Content-Type` der Hauptdokument-Antwort bestimmt (Dateiendung nur als Rückfall) und als `documentType` zurückgegeben:

| `documentType` | Text |
| --- | --- |
| `html` | Sichtbarer Text des `body` |
| `feed` | RSS 2.0, RSS 1.0 (RDF) und Atom als nummerierte Einträge mit Titel, Link, Datum und Kurztext |
| `xml` | XML-Quelltext |
| `json` | Formatiertes JSON |
| `text` | Inhalt unverändert |
| `binary` | Kein Text, stattdessen `hint` mit Verweis auf die Dokumentverarbeitung (z. B. PDF) |

Die bisherigen Schlüssel `url`, `title` und `text` bleiben erhalten; neu sind `status`, `documentType`, `contentType`, `truncated`, `characters`, `source` (`dom` oder `response`) und `hint`. Die Ausgabegrenze bleibt 1 000–60 000 Zeichen (Standard 16 000). Externe XML-Entitäten und DTDs werden beim Feed-Parsen nie geladen. Firefox rendert `application/rss+xml` und manche anderen Quelldokumente nicht, sondern speichert sie als Download. Solche Feeds, XML-, JSON- und Textdokumente ohne `Content-Disposition: attachment` liest Argos aus der gespeicherten Datei, ohne die Seite zu berühren. Dateien mit `attachment` bleiben Downloads.

`browser_navigate` wartet nach dem Ladeereignis zusätzlich auf eine konfigurierbare **Seitenbereitschaft** und meldet sie als `readiness` (`state` = `ready`, `timeout`, `skipped` oder `static-document`, dazu `met` und `pending`). Ein Zeitlimit ist kein Fehler. Mögliche Bedingungen: Netzwerk ruhig, DOM stabil (Elementanzahl und Textlänge unverändert), kein sichtbarer Ladeindikator, sichtbarer Text vorhanden. Es gibt keine pauschalen Sleeps; abgetastet wird im Poll-Intervall und sofort zurückgekehrt, sobald alle Bedingungen gelten.

**Tarnung:** Für Bereitschaft und Textgewinnung wird kein Skript in die JavaScript-Welt der Seite injiziert (kein `addInitScript`, kein `page.evaluate`, kein MutationObserver). Netzwerkruhe stammt aus Protokollereignissen (`request`, `requestfinished`, `requestfailed`), DOM- und Sichtbarkeitsprüfungen aus Playwright-Locatoren in der isolierten Utility-Welt, XML/JSON/Text bevorzugt aus dem Antwort-Body über das Protokoll. `route()` wird nicht verwendet, die Viewport-Emulation bleibt abgeschaltet (`setViewportSize(null)`).

**Achtung, Nutzeraktivierung:** Unter Firefox BiDi gewährt derzeit jede Leseabfrage in der Utility-Welt (Locator, `page.content()`) der Seite eine kurzzeitige Nutzeraktivierung ([playwright4j#37](https://github.com/aresstack/playwright4j/issues/37)). Ständiges Abfragen würde die Seite dauerhaft aktiviert halten. Darum nutzt die Standardeinstellung `balanced` **nur Protokollereignisse** (Netzwerkruhe) und liest den DOM beim Warten gar nicht. Die DOM-Bedingungen (Stabilität, Text, Ladeindikatoren) sind nur in `thorough` aktiv oder wenn sie einzeln gesetzt werden. `browser_wait` mit `selector`/`text` fragt nur dieses Ziel ab. Nach dem Fix in Playwright4J kann `balanced` die DOM-Bedingungen wieder einschalten.

| Variable / Property | Bedeutung | Standard (`balanced`) |
| --- | --- | --- |
| `PAGE_READINESS` / `page.readiness` | Voreinstellung `fast` (bisheriges Verhalten: nur `DOMContentLoaded`), `balanced` (nur Netzwerkruhe), `thorough` (`load`, Netzwerkruhe 1 s, DOM stabil 1,5 s, Text, keine Ladeindikatoren, 15 s) | `balanced` |
| `READY_WAIT_UNTIL` / `ready.wait.until` | `commit`, `domcontentloaded`, `load` | `domcontentloaded` |
| `READY_TIMEOUT_MS` / `ready.timeout.ms` | Maximale Wartezeit nach dem Ladeereignis, `0` schaltet ab | `8000` |
| `READY_DOM_STABLE_MS` / `ready.dom.stable.ms` | Dauer ohne DOM-Änderung, `0` schaltet ab | `0` (aus) |
| `READY_NETWORK_QUIET_MS` / `ready.network.quiet.ms` | Dauer ohne Request-Start/-Ende, `0` schaltet ab | `750` |
| `READY_MAX_INFLIGHT` / `ready.max.inflight` | Offene Requests, die als ruhig gelten (Long-Polling, Beacons) | `2` |
| `READY_MIN_TEXT` / `ready.min.text` | Mindestlänge des sichtbaren Texts, `0` schaltet ab | `0` (aus) |
| `READY_LOADING_SELECTORS` / `ready.loading.selectors` | Ladeindikatoren, getrennt durch `\|`; `none` schaltet ab | aus; `thorough`: `[aria-busy='true']`, `[role='progressbar']`, `.spinner`, `.loader`, `.skeleton` u. a. |
| `READY_POLL_MS` / `ready.poll.ms` | Abtastintervall | `250` |

`browser_wait` verwendet dieselben Bedingungen (bei `fast` die von `balanced`) mit eigenem `timeoutMs` (500–60 000, Standard 10 000).

Mit `TOOL_PROFILE=expert` kommen `browser_download`, `browser_document`, `browser_open_many` und die `browser_pages_*`-Werkzeuge hinzu. `browser_open_many` nutzt Hintergrund-Tabs; `browser_pages_navigate` arbeitet auf vorhandenen, identifizierten Tabs und isoliert Ergebnisse pro Seite. Details und Parallelitätsgrenzen stehen in [`docs/batch-and-documents.md`](docs/batch-and-documents.md).

`browser_network` entfernt Query-Parameter und Benutzerinformationen aus den protokollierten URLs. Bis zu 200 Ereignisse bleiben im Speicher. `browser_evaluate` kann Webseiten verändern und ist deshalb nur für ausdrücklich autorisierte Nutzer über das gesicherte MCP-Plugin bestimmt.

`browser_network` entfernt Query-Parameter und Benutzerinformationen aus den protokollierten URLs. Bis zu 200 Requests bleiben im Speicher; jeder Request erscheint einmal mit `id` (z. B. `n12`), `kind` (`request`, `response`, `failed`), `status`, `type`, `mime` und Dauer `ms`. Der optionale Parameter `filter` sucht ohne Groß-/Kleinschreibung in URL, Typ, MIME-Typ, Status und Zustand, z. B. `xhr`, `json` oder `failed`.

### Netzwerkanalyse im Profil `expert`

Mit `TOOL_PROFILE=expert` (Property `tools.profile`) veröffentlicht Argos zusätzlich:

| Tool | Zweck |
| --- | --- |
| `browser_network_detail` | Einen Request per `id` vollständig anzeigen: URL mit maskierten Query-Werten, Status, MIME-Typ, Timing-Phasen (DNS, Verbindung, TLS, Warten, Empfang), Request-/Response-Header, POST-Daten, Weiterleitungskette; mit `body=true` zusätzlich den Text-Body (max. 32 000 Zeichen) |
| `browser_network_json` | JSON-Daten, die die Seite im Hintergrund per XHR/Fetch geladen hat, neueste zuerst, mit Struktur-Vorschau (z. B. `{results[20], total}`); vollständiger Inhalt über `browser_network_detail` mit `body=true` |
| `browser_network_har` | Die neuesten Requests (`limit`, Standard 50) als HAR 1.2 exportieren |
| `browser_network_clear` | Aufzeichnung leeren, um eine einzelne Aktion isoliert zu beobachten |

Ohne Angabe gilt das bisherige Profil (`legacy`) mit genau den neun Werkzeugen oben.

**Tarnung:** Die Aufzeichnung nutzt ausschließlich die Netzwerkereignisse von WebDriver BiDi über Playwright. Es wird kein Skript in die Seite injiziert und kein Request umgeleitet. Bodies und vollständige Header werden erst bei Bedarf und nie innerhalb eines Event-Handlers über das Protokoll abgefragt. Binäre Antworten (PDF, Bilder) werden nicht gelesen.

**Maskierung:** Alle Ausgaben laufen durch `SecretMasker`. `Authorization`, `Proxy-Authorization` und Header mit Namen wie `token`, `secret`, `api-key`, `session`, `csrf` werden vollständig ersetzt; bei `Cookie`/`Set-Cookie` bleiben nur Cookie-Namen und Attribute sichtbar. Query-, Formular- und JSON-Werte mit sensiblen Namen (`password`, `access_token`, `client_secret`, `code`, `key` …), versteckte Formularfelder sowie JWTs und `Bearer`-Werte im Text werden durch `[REDACTED]` ersetzt. Benutzerinformationen und Fragmente werden aus URLs entfernt.

**Schutz des Browser-Threads:** Bodies werden nur nach vollständig abgeschlossenem Download geholt, nie bei 204/304/Weiterleitungen, HEAD oder abgebrochenen Requests und nie, wenn `Content-Length` über 2 MB liegt. Automatisches Laden (`browser_network_json`, eager Capture) hat pro Browser-Aufruf ein Budget von höchstens 5 Bodies und 2 Sekunden; der Rest folgt beim nächsten Aufruf. Dauert ein einzelner Abruf länger als 3 Sekunden, wird das automatische Laden bis `browser_network_clear` ausgesetzt; `browser_network_detail` mit `body=true` bleibt als ausdrücklicher Einzelabruf möglich. Ein einzelner `Response.body()`-Aufruf lässt sich in Playwright nicht abbrechen; er betrifft aber nur bereits im Browser gesammelte Daten.

`NETWORK_BODIES=true` (Property `network.bodies`) speichert Text-Bodies von Dokument-, XHR- und Fetch-Antworten bereits beim Laden, damit sie auch nach einer weiteren Navigation noch abrufbar sind. Standard ist `false`; dann werden Bodies nur auf Anfrage geladen, solange der Browser sie noch vorhält. Diese Diagnoseeinstellung ist unabhängig von der dauerhaften Erfassung der Search History (`ARGOS_SEARCH_HISTORY_CAPTURE_*`, siehe [docs/search-history.md](docs/search-history.md)). `browser_evaluate` kann Webseiten verändern und ist deshalb nur für ausdrücklich autorisierte Nutzer über das gesicherte MCP-Plugin bestimmt.

### Werkzeugprofile

Welche Werkzeuge Argos veröffentlicht, bestimmt `TOOL_PROFILE` (Property `tools.profile`):

| Profil | Werkzeuge | Gedacht für |
| --- | --- | --- |
| `legacy` | die neun `browser_*`-Werkzeuge oben | bestehende Clients, unverändert |
| `compact` | nur `web_search`, `web_open`, `web_read`, `web_find`, `web_links`, `web_follow`, `web_back` | kleine lokale Modelle (wie in AskAI erprobt) |
| `expert` | `web_*`, `browser_*`, Tabs/Historie/Bedienelemente/Extraktion, Maus, Tastatur, Drag-and-drop, `browser_wait`, Dokumente und Batches, Netzwerkanalyse | leistungsfähige Modelle |
| `convenience` | `web_search`, `web_select_results`, `web_select_links`, `web_follow_link`, `web_read_tab`, `web_back`, `web_open_url`, `web_get_url`, `web_close_tabs`, `web_screenshot` (plus Search History) | Recherche mit ChatGPT: alle Suchmaschinen parallel, Seiten in eigenen Tabs, Navigation nur über IDs, JSON-Antworten |
| `developer` (Standard) | alle `convenience`-Werkzeuge plus `web_evaluate`, `web_inspect`, `web_extract`, `web_network`, `web_network_detail`, `web_network_json`, `web_network_har`, `web_network_clear` | technische Analyse von Webseiten auf denselben Tab-IDs |

Ohne `TOOL_PROFILE` (oder leer) gilt `developer`; `convenience` bleibt mit `TOOL_PROFILE=convenience` wählbar. `legacy`, `compact` und `expert` sind dadurch unverändert (Namen, Schemata, Verhalten; geprüft gegen Snapshots von develop in `ProfileSnapshotTest`). Details zum Convenience-Profil: [docs/convenience-profile.md](docs/convenience-profile.md).

Mit `RESEARCH_ENABLED=true` kommen in den Profilen `legacy`, `compact` und `expert` `web_research` und `web_research_stop` hinzu (nicht in `convenience`). `web_open`/`web_read` lesen Feeds, XML, JSON und Text wie `browser_snapshot` aus dem Antwort-Body und melden Binärdokumente mit Hinweis auf die Dokumentwerkzeuge. Antwortet der Server für das Hauptdokument mit Status 400 oder höher, steht in `web_open`, `web_follow`, `web_read` und `web_back` nach `TITLE` die Zeile `HTTP error: 500 Internal Server Error — the requested content may not be available on this page.` (Standard-Reason-Phrase nach RFC 9110, bei unbekanntem Code nur die Zahl): Kleine Modelle lasen „TITLE: 500 Internal Server Error" samt Servertext sonst als Inhalt und blieben an der Adresse hängen, statt den nächsten Treffer zu öffnen (Phase 7, `broken-link`). Mehr geschieht nicht: keine automatische Navigation, keine Bewertung des Inhalts. Der Status stammt aus der Navigationsantwort, die Argos ohnehin für Dokumenttyp und Bereitschaft aus den Protokollereignissen liest; ohne bekannte Antwort entfällt die Zeile (etwa wenn die Seite ihre Adresse nach dem Laden per Skript geändert hat; `web_back` lädt das Dokument neu, weil Argos den Back-Forward-Cache abschaltet, und kennt den Status). Es wird dafür kein Seitenskript ausgeführt.

Die `web_*`-Werkzeuge haben höchstens einen Parameter, Beispiele in der Beschreibung und antworten mit kurzem Klartext statt JSON:

```text
web_open url="https://example.org/"   → URL, TITLE, LINKS: 12, Seitentext (lange Seiten in Teilen: web_read part=2)
                                         Fehlerseite (HTTP 400+): zusätzlich HTTP error: 500 Internal Server Error —
                                         the requested content may not be available on this page.
web_find text="founding director"      → FOUND: 2 matches, je Treffer ein Ausschnitt (höchstens 200 Zeichen,
                                         höchstens 10 Treffer) mit dem web_read-Teil, in dem er steht; ist die
                                         gefundene Phrase selbst länger, zeigt der Ausschnitt ihre ersten 200 Zeichen
                                         (an einer Wortgrenze gekürzt), die Phrase wird höchstens 80 Zeichen lang zitiert
web_links                              → link-1: Text — https://…   (IDs bleiben auf derselben Seite stabil)
                                         Suchtreffer (result-N) und Seitenlinks (link-N) sind getrennt; eine
                                         bloße Zahl bezieht sich auf die zuletzt gezeigte Liste
web_follow link_id="link-3"            → öffnet den Link als echte Navigation mit Referer, ohne Seitenskript
web_back                               → vorherige Seite
web_search query="…"                   → result-1: Titel — URL (öffnen mit web_follow link_id="result-1")
```

Pro `web_open`/`web_read`/`web_find`/`web_back` liest Argos einmal `page.content()` in Playwrights isolierter Utility-Welt; Titel, Text und Links werden in Java (jsoup) daraus gewonnen. Nur eine Seite, die noch „Loading…"/„wird geladen" zeigt, während ihre eigenen Anfragen laufen (Single-Page-App), wird nach deren Ende ein zweites Mal gelesen (höchstens 8 s später); beobachtet werden dafür allein Protokollereignisse. `web_links` und `web_follow` lesen die Seite nicht erneut, solange der Browser dieselbe Adresse zeigt. So bleibt die Zahl der Protokoll-Evaluierungen minimal (jede trägt `userActivation`, siehe playwright4j #37); Locator-Aktionen, Screenshots und Viewport-Emulation werden nicht verwendet. Es wird kein für die Seite sichtbares Skript eingeschleust. `web_*` prüft jede Ziel-URL vor der Navigation und die finale URL danach mit derselben `NetworkPolicy`, die der Proxy durchsetzt (siehe [Netzwerkgrenze](#netzwerkgrenze-ssrf-schutz)); eine auf einem gesperrten Ziel gelandete Seite wird verworfen und der Tab auf `about:blank` gesetzt. Diese Prüfung liefert nur verständliche Fehlermeldungen; die Sperre selbst (Weiterleitungen, Unterressourcen, DNS-Rebinding) liegt im Proxy und gilt für alle Profile, auch für `legacy` und `browser_navigate`. Lokale Testziele werden mit `INTRANET_ALLOW` freigegeben.

Die `web_*`-Werkzeuge folgen dem Browser: `web_read` und `web_links` lesen auch eine Seite, die ein anderes Werkzeug geöffnet hat, im Profil `expert` `browser_navigate`, ein Klick oder ein Tab-Wechsel, in jedem Profil die zuletzt von `web_research` geladene Seite; die Link-IDs gehören dann zu dieser Seite, wofür `web_links` sie einmal neu liest. Alle Werkzeuge arbeiten auf demselben aktuellen Tab. Nur die Ergebnisseite der Suchmaschine nach `web_search` wird nicht übernommen (erkannt an Schema, Host und Pfad), auch nicht nach einer Suche ohne Treffer oder einer an einer Consent-/Challenge-Seite gescheiterten: Dort ist zuerst ein Treffer mit `web_follow` zu öffnen. Ein leerer Tab ist nichts zu lesen und meldet „No page is open yet" (nach einer `web_search` mit Treffern „No page is open since the last web_search"); ein Dokument ohne `http(s)`-Adresse (`blob:`, `srcdoc`) wird ebenso gemeldet, ohne den Tab zu verändern.

`web_search` liest Suchergebnisseiten ausschließlich über `page.content()` (isolierte Playwright-Welt) und analysiert sie in der JVM; Consent-Banner werden per echter Mausbewegung über WebDriver BiDi angeklickt. Konfiguration: `SEARCH_ENGINES` (Reihenfolge, Standard `duckduckgo,bing,google`; im Profil `convenience` die Menge der parallel befragten Suchmaschinen, keine Fallback-Reihenfolge) und `SEARCH_MAX_RESULTS` (Standard 10). Consent-Banner werden nur abgelehnt (oder auf „nur notwendige“ gesetzt); „Alle akzeptieren“ nur mit `SEARCH_CONSENT_ACCEPT=true`. Details: [docs/web-search.md](docs/web-search.md).

Optional (`RESEARCH_ENABLED=true`, Standard aus) kommen `web_research(question)` und `web_research_stop()` hinzu: eine begrenzte, deterministische Recherche nach dem Vorbild des AskAI-`ResearchLoop` (Suche → relevante Seiten lesen → relevanten Links folgen → nummerierte Quellen mit Zitat), ohne KI-Modell und ohne Seiten-Skripte. Details: [docs/research.md](docs/research.md).

Search History (Standard an, nur mit `PROFILE_ISOLATION=chat`): Argos speichert je Arbeitsbereich (ChatGPT-Unterhaltung, dauerhaft; bei Clients ohne Unterhaltungs-ID wie Claude nur für die Lebensdauer der MCP-Session und der Serverinstanz, siehe [docs/chat-profiles.md](docs/chat-profiles.md)) alle Suchen, Treffer und gelesenen Seiten dauerhaft unter `~/.argos/profiles/chats/<hash>/history/`, durchsuchbar per Lucene (`search_history`) und exportierbar als TXT, JSONL oder Single-File-HTML-ZIP (`search_history_export`). Details und Stealth-Hinweis: [docs/search-history.md](docs/search-history.md).

Modellvergleich (Phase 7): `gradlew evalModels` lässt lokale Ollama-Modelle (Standard: `gemma4:e2b` und `gpt-oss:20b`) Argos über MCP gegen eine lokale Fixture-Welt bedienen und schreibt einen JSON- und Markdown-Bericht. Details: [docs/model-evaluation.md](docs/model-evaluation.md).

## Netzwerkgrenze (SSRF-Schutz)

Camoufox erreicht das Netz **nur über einen in Argos eingebauten SOCKS5-Proxy** auf `127.0.0.1` (zufälliger Port). Der Browser löst DNS nicht selbst auf, sondern übergibt den Hostnamen an den Proxy. Der Proxy löst **einmal** auf, prüft **jede** Adresse und verbindet sich mit genau der geprüften Adresse. Das gilt für Navigation, Redirects, Bilder, Skripte, Formulare, WebSockets, Service-Worker und browserinterne Verbindungen. DNS-Rebinding hat dadurch keinen zweiten Auflösungszeitpunkt, den es ausnutzen könnte.

Ohne Regel blockiert sind die Adressen der eigenen Netzwerkschnittstellen (auch öffentliche IPv4- und globale IPv6-Adressen des Rechners), Loopback (`127.0.0.0/8`, `::1`), `0.0.0.0`, private Netze (RFC 1918), Link-Local (`169.254.0.0/16`, `fe80::/10`), Cloud-Metadaten (`169.254.169.254`, `fd00:ec2::254`, `100.100.100.200`), Unique-Local (`fc00::/7`), CGNAT (`100.64.0.0/10`), reservierte Bereiche (darunter der lokale NAT64-Präfix `64:ff9b:1::/48`: DNS64-Netze, die ihn statt `64:ff9b::/96` verwenden, brauchen eine Bereichsregel) und Multicast, auch in IPv6-Schreibweisen (IPv4-mapped, NAT64, 6to4). Abgewiesene Verbindungen erscheinen in `browser_network` mit `"kind":"blocked"` und Grund, `browser_status` zeigt unter `network` den Zustand des Proxys.

HTTPS bleibt ein unveränderter Tunnel: **kein MITM**, TLS, Header und Fingerprint sind die des Browsers, und für Webseiten ist der Proxy unsichtbar. Dadurch prüft die Richtlinie **Host und IP, keine Pfade**.

**Fail closed:** Der Proxy startet vor dem Browser; ohne Proxy startet kein Browser, und fällt er aus, erreicht der Browser nichts mehr. Nach jedem Browserstart prüft ein Selbsttest in einem kurzlebigen Tab, dass `127.0.0.1` über den Proxy läuft und abgewiesen wird, dass der Hostname beim Proxy ankommt (Remote-DNS) und dass Firefox nach Abweisungen nicht direkt verbindet. Schlägt er fehl, wird der Browser beendet.

| Variable | Property | Wirkung |
| --- | --- | --- |
| `NETWORK_POLICY` | `network.policy` | `strict` (Standard) oder `off` (kein Proxy, direkte Verbindungen; nur zur Fehlersuche) |
| `INTRANET_ALLOW` | `network.intranet-allow` | Kommagetrennte Ausnahmen, gleiche Syntax wie für die `web_*`-Werkzeuge: `wiki.firma.local`, `*.firma.local`, `10.20.0.0/16`, `192.168.1.5`, zusätzlich `wiki.firma.local@10.20.0.0/16` |
| `UPSTREAM_PROXY` | `network.upstream-proxy` | Optionaler vorgeschalteter Proxy, `socks5://[user:pass@]host:port` oder `http://[user:pass@]host:port` (HTTP CONNECT) |
| `UPSTREAM_DNS` | `network.upstream-dns` | `local` (Standard): Argos gibt dem Upstream die geprüfte IP; `remote`: der Upstream löst den Namen selbst auf (geo-konsistentes DNS, die Richtlinie sieht dann nur Argos' eigene Auflösung) |

`NetworkPolicy` ist die einzige Sicherheitslogik für alle Werkzeugprofile. Frühe Prüfungen vor einer Navigation (`checkUrl`/`checkHost`) verwenden dieselben Regeln, sind aber nur für verständliche Fehlermeldungen da: Sie lösen DNS unabhängig vom Browser auf. Durchgesetzt wird ausschließlich im Proxy (`resolve`, danach Verbindung nur zu den geprüften Adressen).

Eine **Host-Regel** lässt nur Intranet-Adressen zu (privat, Unique-Local, CGNAT). Loopback, Link-Local und Metadaten brauchen eine **Bereichsregel**, die sie ausdrücklich nennt. Das gilt auch für alle eigenen Interface-Adressen, öffentliche wie private: Eine Seite, die auf diesem Rechner selbst gehostet wird und auf eine seiner Adressen auflöst, wird abgewiesen, auch wenn eine Host-Regel ihren Namen nennt, denn die Verbindung wäre lokal und erreichte an der Host-Firewall vorbei alle Ports. Gezielt freigeben lässt sie sich mit Host und Bereich, z. B. `argos.aresstack.de@203.0.113.7/32` oder `wiki.local@10.0.0.5/32`; die Fehlermeldung nennt diese Regel. `0.0.0.0` und Multicast lassen sich nicht freigeben, Argos' eigener `SERVER_PORT` und der Proxy-Port bleiben auch bei freigegebenem Loopback gesperrt. Ein `proxy` in der Camoufox-`launch.json` wird automatisch als Upstream verkettet. `network.proxy.*`- oder `network.trr.*`-Prefs in der `launch.json` führen zu einem Startfehler, weil Argos diese Einstellungen selbst setzt.

Gesetzte Firefox-Prefs (gegen Firefox `StaticPrefList.yaml`/`all.js` und Camoufox 158 `camoufox.cfg` geprüft): `network.proxy.type=1`, `network.proxy.socks*` auf den Argos-Proxy mit Version 5, `network.proxy.socks5_remote_dns=true` (für SOCKS5 maßgeblich; `socks_remote_dns` gilt nur für SOCKS4 und wird zusätzlich gesetzt), `network.proxy.no_proxies_on=""`, `network.proxy.allow_hijacking_localhost=true`, `network.proxy.failover_direct=false`, `network.proxy.allow_bypass=false`, `network.proxy.retry_failed_proxies=true`, `network.trr.mode=5`, `network.dns.prefetch_via_proxy=false`, `network.http.altsvc.proxy_checks=true`, `network.dns.force_use_https_rr=false`, `media.peerconnection.ice.proxy_only=true` und `proxy_only_if_behind_proxy=true`. Kein WebRTC-UDP am Proxy vorbei: `proxy_only_if_behind_proxy` allein (Camoufox-Standard) greift nur für Dokumente mit HTTP-Kanal, nicht für `about:blank`-/`srcdoc`-Frames, `blob:` oder `data:`. `proxy_only` ist die eine bewusste Ausnahme von der Regel „nur Camoufox-Standard oder für Seiten unsichtbar": Allein in solchen Frames kann eine Seite sehen, dass kein Host-/srflx-Kandidat gesammelt wird, so wie ohnehin in jedem Dokument hinter dem Proxy; die Alternative wäre STUN-UDP mit der echten Absenderadresse an einen von der Seite gewählten Server. Normal aussehende WebRTC-Kandidaten in allen Dokumenten liefert Camoufox nur mit gesetzter Spoof-Adresse (`webrtc:ipv4`/`webrtc:ipv6` in `CAMOU_CONFIG`, etwa über `launch_options(proxy=…, geoip=True)`); ohne sie zeigt schon das Hauptdokument keine Kandidaten. Wer diesen Pref entfernt, öffnet den UDP-Weg am Proxy vorbei wieder. HTTP/3 und WebTransport brauchen keinen Pref: Mit SOCKS-Proxy ignoriert Firefox h3-Alt-Svc und baut auch für h3 eine TCP-Verbindung über den Proxy auf. **Nicht** verändert werden `network.dns.disablePrefetch`, `network.prefetch-next` und `network.http.speculative-parallel-limit`: Camoufox lässt sie bewusst auf Firefox-Standard, weil Seiten Abweichungen über Resource Timing erkennen können. Firefox löst hinter einem Proxy ohnehin kein DNS-Prefetch auf.

## GitHub Actions und Releases

Der Workflow [Build and Release Argos](.github/workflows/build-release.yml) läuft bei jedem Push auf `main`, bei Pull Requests und manuell über **Actions → Build and Release Argos → Run workflow**. Er baut Playwright4J aus einem **festgepinnten Commit** selbst in Maven Local, führt die Java-Tests aus und erstellt das plattformübergreifende `argos-package.zip`. Anschließend prüft ein separater Windows-Job sowohl den **echten Playwright4J-Treiberstart aus der einzelnen JAR** (`--verify-runtime`) als auch den MCP-Handshake und die Browserwerkzeugliste, ohne dass ein externes `lib/`-Verzeichnis existiert. Echte Camoufox-Tests laufen im Workflow [Camoufox distribution](.github/workflows/camoufox-distribution.yml): Er baut die Browserdistribution, veröffentlicht sie einmalig und lässt Argos sie unter Linux und Windows (je mit und ohne `playwright4j.bidi.nativeBehavior`) selbst herunterladen und starten.

**Ein GitHub Release wird ausschließlich beim Push eines Versionstags veröffentlicht**, und nur wenn Linux- und Windows-Jobs erfolgreich waren. Ein Push auf `main` oder ein manueller Workflow-Lauf veröffentlicht **kein** Release.

Beispiel für eine spätere explizite Release-Freigabe:

```bash
git tag v0.1.0
git push origin v0.1.0
```

Im GitHub Release liegen die **direkt ausführbare `argos.jar`** und optional `argos-package.zip` (enthält nur dieselbe JAR). Beide enthalten **keine Camoufox-Binärdatei und keine Benutzerprofile**; den Browser lädt Argos beim ersten Bedarf aus dem separaten Release `camoufox-<version>-argos.<n>`, das eingebaute Fingerprint-Profil steckt in der JAR. Der Webserver benötigt nur die einzelne JAR.

## Tests und Stand

```powershell
.\gradlew.bat test
.\gradlew.bat test --tests com.aresstack.argos.CamoufoxBrowserTest -PtestCamoufox=true
.\gradlew.bat test --tests com.aresstack.argos.CamoufoxNetworkBoundaryTest -PtestCamoufox=true
# Argos' eigene Distribution: Download, Prüfung und Start mit dem eingebauten Profil (lädt ~1,3 GB)
.\gradlew.bat test --tests "*CamoufoxProvisionedSmokeTest" -PtestCamoufox -PtestProvisionedCamoufox
```

Die Camoufox-Tests verwenden die von Argos unter `BROWSER_DIR` installierte Distribution (vorher einmal `CamoufoxProvisionedSmokeTest` laufen lassen, sonst meldet der erste Test nach 30 s „wird installiert“).

Alle Camoufox-Browsertests auf einmal (auch die Eingabetests `FieldSelectionBrowserTest`, `KeyboardShortcutBrowserTest`, `HumanInputBrowserTest`, die ohne `-PtestHumanBrowser` automatisch den von Argos installierten Camoufox verwenden). `-PtestCamoufox` ohne Wert gilt ebenfalls als eingeschaltet:

```powershell
$env:BROWSER_DIR = "D:\Argos Browser"
.\gradlew.bat test -PtestCamoufox=true `
  --tests "*BrowserTest*" --tests "*Camoufox*"
```

`CamoufoxNetworkBoundaryTest` prüft mit dem echten Camoufox, dass ein Redirect auf `127.0.0.2`, ein `<img>` ins LAN und WebRTC-UDP zu einem lokalen STUN-Port abgewiesen werden, während die freigegebene Testseite normal lädt. DNS-Rebinding prüft `Socks5ProxyTest` mit einem steuerbaren Resolver.

Der zweite Befehl verwendet eine **separate temporäre Headless-Camoufox-Instanz** und einen lokalen HTTP-Testserver. Er überprüft Navigation, Seitentext, DOM-Inspektion, Eingaben, Klicks, JavaScript, Screenshots und Netzwerkereignisse sowie mit den Fixtures aus `src/test/resources/fixtures` alle Dokumenttypen (RSS, XML, JSON, Text, PDF) und die Seitenbereitschaft einer verzögert rendernden Single-Page-Anwendung. Die Dokumenttyp- und Bereitschaftslogik ist zusätzlich ohne Browser gegen einen lokalen HTTP-Testserver getestet und läuft in der CI. Bereits laufende GPT-Desktop-Sitzungen werden nicht verwendet oder beendet.

Streamable-HTTP-MCP wurde zusätzlich mit `initialize`, `notifications/initialized`, `tools/list` und `tools/call(browser_status)` über HTTP geprüft. Das eigenständige Windows-Smoketestskript `scripts/smoke-standalone.ps1 -WithCamoufox` kopiert **nur `argos.jar`** in ein temporäres Verzeichnis und prüft zusätzlich einen echten Camoufox-Start, Navigation und Interaktion über MCP.

**Bekanntes externes Problem:** In Playwright4J/Core 1.62 kann ein persistenter Firefox-BiDi-Start unter Windows zwei sichtbare Fenster erzeugen, obwohl nur eines unter `context.pages()` auftaucht: [playwright4j#34](https://github.com/aresstack/playwright4j/issues/34). Argos implementiert keinen unsicheren Fensterschließ-Hack. Bei `BROWSER_HEADLESS=true` ist die Oberfläche nicht sichtbar. Die Korrektur soll aus Playwright4J übernommen werden.

Noch **nicht erledigt**: öffentliche HTTPS/OAuth-Veröffentlichung über den Webserver, Installation als ChatGPT-Plugin sowie Freigabe eines Releases.
