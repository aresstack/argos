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

Voraussetzung: Java 21 sowie **Playwright4J-Commit `1c11fe9c2eb39eca1ca6abbc4ce0ffe3f183aa8c` oder neuer mit Fix #32**. Die alte, lokal veröffentlichte `0.2.2-SNAPSHOT` ohne Fix #32 startet den Treiber aus einer ausführbaren JAR nicht. Die CI baut die geprüfte Revision selbst; lokal kann sie in `.argos-build/maven/` veröffentlicht werden (das Verzeichnis ist Git-ignoriert).

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

In der vorhandenen Windows-Entwicklungsumgebung erkennt Argos standardmäßig die **Camoufox-Binärdatei und die offizielle Fingerprint-JSON** aus `~/.browser-event-hub/desktop/camoufox.properties`, falls keine Argos-Konfiguration vorliegt. **Browserprofile werden ausdrücklich nicht übernommen.** Argos verwendet ausschließlich `~/.argos/profiles/default`.

Eigenständige Konfiguration über `~/.argos/argos.properties`:

```properties
server.host=127.0.0.1
server.port=8085
security.mode=central-oauth
public.origin=https://argos.aresstack.de
oauth.central-issuer=https://argos.aresstack.de
security.required-scope=argos
camoufox.executable=G:/camoufox-bidi-smoke-20261025/browser/camoufox.exe
camoufox.launchOptions=G:/camoufox-bidi-smoke-20261025/cmp/launch.json
browser.profile=C:/Users/USERNAME/.argos/profiles/default
browser.headless=false
```

Die angegebenen Dateipfade sind Beispiele aus der isolierten Testinstallation und sind nicht Teil des Git-Repositories.

**Im AresStack Webserver haben die Umgebungsvariablen Vorrang vor den Properties.** Pflichtfeld ist `SERVER_PORT`; Argos besitzt **keinen fest einprogrammierten Standardport**. Wie bei Pyloros werden `SERVER_PORT`, `SERVER_HOST` (optional, nur Loopback), `SECURITY_MODE`, `REQUIRED_SCOPE`, `PUBLIC_ORIGIN` und `CENTRAL_OAUTH_ISSUER` ausgewertet. Für den Camoufox-Browser bleiben zusätzlich `ARGOS_CAMOUFOX_EXECUTABLE`, `ARGOS_CAMOUFOX_LAUNCH_OPTIONS`, `ARGOS_PROFILE_DIR` und `ARGOS_HEADLESS` verfügbar.

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
| `browser_snapshot` | Sichtbaren Text und Seitentitel lesen |
| `browser_inspect` | DOM-Elemente anhand CSS-Selektoren untersuchen |
| `browser_click` | Elemente anklicken |
| `browser_fill` | Eingabefelder ausfüllen |
| `browser_evaluate` | JavaScript in der Webseite ausführen |
| `browser_screenshot` | Screenshot als MCP-Image zurückgeben |
| `browser_network` | Letzte HTTP-Requests, Responses und Fehler untersuchen (eine Zeile je Request mit ID, optional `filter`) |

`browser_network` entfernt Query-Parameter und Benutzerinformationen aus den protokollierten URLs. Bis zu 200 Requests bleiben im Speicher; jeder Request erscheint einmal mit `id` (z. B. `n12`), `kind` (`request`, `response`, `failed`), `status`, `type`, `mime` und Dauer `ms`. Der optionale Parameter `filter` sucht ohne Groß-/Kleinschreibung in URL, Typ, MIME-Typ, Status und Zustand, z. B. `xhr`, `json` oder `failed`.

### Netzwerkanalyse im Profil `expert`

Mit `ARGOS_TOOL_PROFILE=expert` (Property `tools.profile`) veröffentlicht Argos zusätzlich:

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

`ARGOS_NETWORK_BODIES=true` (Property `network.bodies`) speichert Text-Bodies von Dokument-, XHR- und Fetch-Antworten bereits beim Laden, damit sie auch nach einer weiteren Navigation noch abrufbar sind. Standard ist `false`; dann werden Bodies nur auf Anfrage geladen, solange der Browser sie noch vorhält. `browser_evaluate` kann Webseiten verändern und ist deshalb nur für ausdrücklich autorisierte Nutzer über das gesicherte MCP-Plugin bestimmt.

## GitHub Actions und Releases

Der Workflow [Build and Release Argos](.github/workflows/build-release.yml) läuft bei jedem Push auf `main`, bei Pull Requests und manuell über **Actions → Build and Release Argos → Run workflow**. Er baut Playwright4J aus einem **festgepinnten Commit** selbst in Maven Local, führt die Java-Tests aus und erstellt das plattformübergreifende `argos-package.zip`. Anschließend prüft ein separater Windows-Job sowohl den **echten Playwright4J-Treiberstart aus der einzelnen JAR** (`--verify-runtime`) als auch den MCP-Handshake und die Browserwerkzeugliste, ohne dass ein externes `lib/`-Verzeichnis existiert. Die Browser-Funktionstests mit echter Camoufox-Binärdatei bleiben lokal, da der Runner diese nicht mitbringt.

**Ein GitHub Release wird ausschließlich beim Push eines Versionstags veröffentlicht**, und nur wenn Linux- und Windows-Jobs erfolgreich waren. Ein Push auf `main` oder ein manueller Workflow-Lauf veröffentlicht **kein** Release.

Beispiel für eine spätere explizite Release-Freigabe:

```bash
git tag v0.1.0
git push origin v0.1.0
```

Im GitHub Release liegen die **direkt ausführbare `argos.jar`** und optional `argos-package.zip` (enthält nur dieselbe JAR). Beide enthalten **keine Camoufox-Binärdatei, Fingerprint-Konfiguration oder Benutzerprofile**; diese werden auf dem Zielrechner gesondert eingerichtet. Der Webserver benötigt nur die einzelne JAR.

## Tests und Stand

```powershell
.\gradlew.bat test
.\gradlew.bat test --tests com.aresstack.argos.CamoufoxBrowserTest -PtestCamoufox=true
```

Der zweite Befehl verwendet eine **separate temporäre Headless-Camoufox-Instanz** und einen lokalen HTTP-Testserver. Er überprüft Navigation, Seitentext, DOM-Inspektion, Eingaben, Klicks, JavaScript, Screenshots und Netzwerkereignisse. Bereits laufende GPT-Desktop-Sitzungen werden nicht verwendet oder beendet.

Streamable-HTTP-MCP wurde zusätzlich mit `initialize`, `notifications/initialized`, `tools/list` und `tools/call(browser_status)` über HTTP geprüft. Das eigenständige Windows-Smoketestskript `scripts/smoke-standalone.ps1 -WithCamoufox` kopiert **nur `argos.jar`** in ein temporäres Verzeichnis und prüft zusätzlich einen echten Camoufox-Start, Navigation und Interaktion über MCP.

**Bekanntes externes Problem:** In Playwright4J/Core 1.62 kann ein persistenter Firefox-BiDi-Start unter Windows zwei sichtbare Fenster erzeugen, obwohl nur eines unter `context.pages()` auftaucht: [playwright4j#34](https://github.com/aresstack/playwright4j/issues/34). Argos implementiert keinen unsicheren Fensterschließ-Hack. Bei `ARGOS_HEADLESS=true` ist die Oberfläche nicht sichtbar. Die Korrektur soll aus Playwright4J übernommen werden.

Noch **nicht erledigt**: öffentliche HTTPS/OAuth-Veröffentlichung über den Webserver, Installation als ChatGPT-Plugin sowie Freigabe eines Releases.
