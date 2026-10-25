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
Argos :8082 ─── GET /health
       └─────── /mcp (offizieller MCP Java SDK Streamable HTTP)
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

Voraussetzung: Java 21 und die Playwright4J-Version `0.2.2-SNAPSHOT` aus dem Projekt `aresstack/playwright4j` im lokalen Maven-Repository.

```powershell
.\gradlew.bat test packageArgos argosZip
```

Erzeugt:

```text
build/package/
  argos.jar        ← ausführbare Haupt-JAR
  lib/             ← alle Laufzeitbibliotheken (unverändert)
build/distributions/argos-package.zip
```

**Wichtig:** `argos.jar` benötigt das mitgelieferte Verzeichnis `lib/` neben der JAR. GraalJS/Truffle sind Multi-Release-Bibliotheken und dürfen nicht blind zu einer Fat-JAR zusammengeführt werden. Der Webserver startet dennoch ganz normal `java -jar argos.jar`.

## Lokaler Start

In der vorhandenen Windows-Entwicklungsumgebung erkennt Argos standardmäßig die **Camoufox-Binärdatei und die offizielle Fingerprint-JSON** aus `~/.browser-event-hub/desktop/camoufox.properties`, falls keine Argos-Konfiguration vorliegt. **Browserprofile werden ausdrücklich nicht übernommen.** Argos verwendet ausschließlich `~/.argos/profiles/default`.

Eigenständige Konfiguration über `~/.argos/argos.properties`:

```properties
server.host=127.0.0.1
server.port=8082
camoufox.executable=G:/camoufox-bidi-smoke-20261025/browser/camoufox.exe
camoufox.launchOptions=G:/camoufox-bidi-smoke-20261025/cmp/launch.json
browser.profile=C:/Users/USERNAME/.argos/profiles/default
browser.headless=false
```

Die angegebenen Dateipfade sind Beispiele aus der isolierten Testinstallation und sind nicht Teil des Git-Repositories.

Alternativ unterstützt Argos die Umgebungsvariablen `ARGOS_PORT`, `ARGOS_HOST` (nur Loopback), `ARGOS_CAMOUFOX_EXECUTABLE`, `ARGOS_CAMOUFOX_LAUNCH_OPTIONS`, `ARGOS_PROFILE_DIR` und `ARGOS_HEADLESS`.

Start:

```powershell
java -jar .\build\package\argos.jar
```

Prüfen: `http://127.0.0.1:8082/health`, MCP: `http://127.0.0.1:8082/mcp`.

## AresStack Webserver – Managed JAR

- **JAR:** Pfad zur entpackten `argos.jar`.
- **Working directory:** Verzeichnis mit `argos.jar` und `lib/`.
- **Java executable:** Java 21.
- **Start automatically / Restart automatically:** optional einschalten.
- **Health check:** `http://127.0.0.1:8082/health`.
- **Forward to:** `127.0.0.1:8082`, inkl. dem Pfad `/mcp`.

Die öffentliche Adresse wird ausschließlich im AresStack Webserver konfiguriert. Argos selbst bietet **keine eigene öffentliche Anmeldung**: Der AresStack-Webserver muss den externen MCP-Zugang mittels seiner zentralen OAuth/JWT-Prüfung schützen. **Niemals `/mcp` ohne Authentifizierung ins Internet veröffentlichen.** Argos bindet sich absichtlich nur an `127.0.0.1` oder `::1`.

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
| `browser_network` | Letzte HTTP-Requests, Responses und Fehler untersuchen |

`browser_network` entfernt Query-Parameter und Benutzerinformationen aus den protokollierten URLs. Bis zu 200 Ereignisse bleiben im Speicher. `browser_evaluate` kann Webseiten verändern und ist deshalb nur für ausdrücklich autorisierte Nutzer über das gesicherte MCP-Plugin bestimmt.

## Tests und Stand

```powershell
.\gradlew.bat test
.\gradlew.bat test --tests com.aresstack.argos.CamoufoxBrowserTest -PtestCamoufox=true
```

Der zweite Befehl verwendet eine **separate temporäre Headless-Camoufox-Instanz** und einen lokalen HTTP-Testserver. Er überprüft Navigation, Seitentext, DOM-Inspektion, Eingaben, Klicks, JavaScript, Screenshots und Netzwerkereignisse. Bereits laufende GPT-Desktop-Sitzungen werden nicht verwendet oder beendet.

Streamable-HTTP-MCP wurde zusätzlich mit `initialize`, `notifications/initialized`, `tools/list` und `tools/call(browser_status)` über HTTP geprüft.

**Bekanntes externes Problem:** In Playwright4J/Core 1.62 kann ein persistenter Firefox-BiDi-Start unter Windows zwei sichtbare Fenster erzeugen, obwohl nur eines unter `context.pages()` auftaucht: [playwright4j#34](https://github.com/aresstack/playwright4j/issues/34). Argos implementiert keinen unsicheren Fensterschließ-Hack. Bei `ARGOS_HEADLESS=true` ist die Oberfläche nicht sichtbar. Die Korrektur soll aus Playwright4J übernommen werden.

Noch **nicht erledigt**: öffentliche HTTPS/OAuth-Veröffentlichung über den Webserver, Installation als ChatGPT-Plugin sowie Freigabe eines Releases.
