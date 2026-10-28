# Argos' eigene Camoufox-Distribution

Argos beschafft, prüft und startet seinen Browser selbst. Für den normalen Betrieb, auch im AresStack Webserver, sind
**keine Browserpfade, keine `launch.json` und keine Fingerprint-Umgebungsvariablen** nötig.

## Was installiert wird

| | |
|---|---|
| Browser | Camoufox `156.0.1-beta.36` aus [daijro/camoufox](https://github.com/daijro/camoufox/releases/tag/v156.0.1-beta.36) (Windows x86_64, Linux x86_64) |
| WebDriver-BiDi-Fixes | die beiden Fixes aus [daijro/camoufox#952](https://github.com/daijro/camoufox/pull/952) (Fenster öffnen, Tab-Wechsel), angewendet mit `docs/camoufox/hotpatch_omni.py` aus [aresstack/playwright4j](https://github.com/aresstack/playwright4j) Commit `6b52161` (PR #78). Das veröffentlichte Camoufox-Archiv enthält sie noch nicht. |
| Add-on | uBlock Origin `1.75.0` (addons.mozilla.org, SHA-256 `5b744158…a5287`) unter `addons/UBO/`, wie es das bisher verwendete Profil geladen hat |
| Quelle | öffentliches GitHub-Release `camoufox-156.0.1-beta.36-argos.<n>` in diesem Repository, ohne Anmeldung abrufbar |
| Pin | `src/main/resources/camoufox/distribution.properties`: Release, Argos-Revision, Download-URL, SHA-256 je Archiv, SHA-256 der beiden gepatchten `omni.ja`-Einträge |

Browser-Version und Archiv-Revision sind unabhängig von der Argos-Version. Ein Argos-Release veröffentlicht den Browser
nicht erneut; die JAR enthält nur die Pins und das Standardprofil. Ein Wechsel der Camoufox-Version oder der Revision
ist eine eigene Änderung an `distribution.properties` und muss den Workflow unten bestehen. Argos wählt nie
selbstständig eine neuere Version.

## Herstellung (GitHub Action)

[`.github/workflows/camoufox-distribution.yml`](../.github/workflows/camoufox-distribution.yml) läuft bei Änderungen an
der Distribution (Workflow, `tools/camoufox/`, `src/main/resources/camoufox/`, `com.aresstack.argos.camoufox`) und
manuell:

1. **package** (je Plattform): lädt das Camoufox-Archiv, `hotpatch_omni.py --install <dir> --release <version>
   --archive <zip>` prüft dessen gepinnte SHA-256 und wendet beide Fixes an, `hotpatch_omni.py --check <dir>` bestätigt
   sie. uBlock Origin wird gegen den von addons.mozilla.org veröffentlichten und den im Workflow gepinnten Hash geprüft.
   `tools/camoufox/package_distribution.py` packt alles deterministisch (sortiert, feste Zeitstempel, ohne
   `omni.ja.orig`) und legt `argos-distribution.json` bei (Version, Ziel, Fix-Herkunft, Add-on-Hash, ausführbare
   Dateien). Nicht-ASCII-Dateinamen unter `fonts/` (japanische macOS-Schriften) werden ASCII-maskiert, weil eine JVM mit
   C/POSIX-Locale sie nicht anlegen kann; fontconfig liest Schriften nach Inhalt.
2. **publish**: legt das Release mit beiden Archiven und `SHA256SUMS` **einmalig** an. Ein bestehendes Release wird nie
   überschrieben; weicht ein Neubau ab, bleibt das veröffentlichte Archiv maßgeblich (Hinweis im Log).
3. **smoke** (Linux und Windows, jeweils `playwright4j.bidi.nativeBehavior=false` und `true`): prüft, dass die in Argos
   gepinnten Prüfsummen zum Release passen (anonymer Download), lässt Argos dann **ohne** `ARGOS_CAMOUFOX_*` den Browser
   herunterladen, prüfen und starten (`CamoufoxProvisionedSmokeTest`) und führt `CamoufoxBrowserTest`,
   `CamoufoxNetworkBoundaryTest` und die Konfigurations-/Installationstests gegen die Installation aus. Der Job schlägt
   fehl, wenn ein Browsertest übersprungen wurde.

Nicht mehr verwendete Revisionen bleiben veröffentlicht und werden vom Workflow als `[superseded]` markiert
(`SUPERSEDED_TAGS`, derzeit `argos.1`). Argos lädt ausschließlich die in `distribution.properties` gepinnte Revision
mit deren Prüfsummen.

Python wird nur hier gebraucht, nicht auf dem Produktionsrechner.

## Installation zur Laufzeit

Beim ersten Browserzugriff (nicht beim Start des HTTP-/MCP-Dienstes) prüft Argos, ob
`~/.argos/browser/camoufox-<release>-<revision>-<ziel>/` eine verifizierte Installation enthält. Sonst:

1. Download nach `~/.argos/browser/.partial-<zufall>/` (Java-HTTP-Client, Redirects, Systemproxy-Einstellungen der JVM),
2. SHA-256 gegen den Pin, bei Abweichung Abbruch ohne Installation,
3. Entpacken in denselben temporären Ordner (Schutz gegen Pfade außerhalb),
4. Prüfung von `argos-distribution.json`, Ausführungsrechten (POSIX), Executable und beiden BiDi-Fixes in `omni.ja`,
5. Markierung `.argos-verified` und **atomares Umbenennen** in den endgültigen Ordner,
6. Start mit dem eingebauten Profil.

Eine vorhandene verifizierte Installation wird wiederverwendet (bei jedem Start werden nur Marker, Executable und die
beiden `omni.ja`-Einträge geprüft). Ältere Versionen in Nachbarordnern bleiben unberührt. Reste abgebrochener Versuche
(`.partial-*`) und ein unvollständiger Zielordner werden beim nächsten Versuch entfernt. Gleichzeitige Anfragen mehrerer
Chats teilen sich eine Installation; mehrere Prozesse serialisieren sich über `~/.argos/browser/.install.lock`.

Während der Installation wartet ein Browserwerkzeug höchstens 30 s und meldet dann z. B. „Argos is downloading its
Camoufox browser (412 of 1295 MiB); retry the tool in a minute.“ Die Installation läuft im Hintergrund weiter; der
Health-Check bleibt unbeeinflusst. Ein Fehler (Prüfsumme, fehlende Quelle, HTTP-Status, fehlender Fix) wird mit Ursache
gemeldet, beim nächsten Werkzeugaufruf wird neu versucht. Protokolliert werden nur Version, Ziel, Download-Host/-Pfad
und Installationsordner, keine Fingerprint-Werte, Umgebungsvariablen oder Zugangsdaten.

**Nicht unterstützte Plattformen** (macOS, ARM): Argos meldet, dass es nur Windows x86_64 und Linux x86_64 selbst
installiert, und verweist auf `ARGOS_CAMOUFOX_EXECUTABLE`.

## Eingebautes Profil

`src/main/resources/camoufox/default-launch.json` übernimmt die bisher verwendete, funktionierende `launch.json`
(camoufox 0.5.8 `launch_options`, Windows-Fingerprint) ohne Änderung der Werte:

- **Fingerprint (`CAMOU_CONFIG`)**: User-Agent Firefox 156 / Windows, `navigator.*`, Bildschirm 1366×800, Fenster
  1293×752, 24 Kerne, Windows-Schriftliste, `audio:seed`, Windows-SAPI-Stimmen, WebGL/WebGL2 (ANGLE, GTX 980) samt
  Erweiterungen, Parametern und Präzisionen.
- **Media-Devices**: ein Lautsprecher `Speakers (Realtek(R) Audio)` (Gruppe `hw-1`), kein Mikrofon, keine Kamera.
  Die Vorlage hatte zusätzlich `DELL S2721DGF (NVIDIA High Definition Audio)`. Beide Bezeichnungen stammen nicht vom
  Rechner, sondern aus dem Katalog des camoufox-Launchers (`media-devices.json`, Windows): Er zieht pro Identität eine
  Soundkarte und optional (28 %) ein Monitor-Audiogerät. Argos lässt diesen optionalen Schritt weg, damit kein
  bestimmtes Monitormodell festgeschrieben ist; das Ergebnis ist eine Kombination, die der Launcher selbst erzeugt.
  Firefox zeigt Audioausgaben ohnehin erst nach einer Gerätefreigabe; vor der Freigabe sieht eine Seite keinen
  Unterschied (vom Smoke-Test geprüft). Eigene Hardwarewerte gehen weiterhin über eine eigene `launch.json`.
- **Firefox-Prefs**: `intl.locale.requested=en-US`, Overlay-Scrollbars, Quota-Limit, DNT/GPC aus, WebGL2, WebRTC an,
  COOP; zusätzlich wie bisher als `CAMOU_PREFS_1..n`, damit Gecko sie schon beim Start liest.
- **Startargumente**: keine (die Vorlage hatte keine).
- **Rechnerspezifisch** war nur der absolute Pfad des uBlock-Origin-Add-ons. Er wird zur Laufzeit durch
  `<Installationsordner>/addons/UBO` ersetzt.

Argos baut daraus `CAMOU_CONFIG_1..n` (unter Windows je höchstens 2047 Zeichen, sonst 32767, nie mitten in einem
Surrogatpaar) und ergänzt sie zur geerbten Prozessumgebung. Unter Linux erzeugt Argos wie der camoufox-Launcher eine
`fonts.conf` aus `fontconfig/windows/` der Distribution mit den Schriftgruppen, die Windows liest
(`~/.argos/browser/fontconfig/fonts-<hash>.conf`, `FONTCONFIG_FILE`). Argos' eigene Netzwerk-Prefs (SOCKS5,
`network.trr.mode=5`, `ice.proxy_only` …) werden danach gesetzt und vom Profil nicht berührt; Profil-Prefs mit
`network.proxy.*`/`network.trr.*` führen wie bisher zu einem Startfehler.

## Optionale Overrides (Entwicklung)

| Variable / Property | Wirkung |
|---|---|
| `ARGOS_CAMOUFOX_EXECUTABLE` / `camoufox.executable` | eigener Camoufox statt der Distribution (muss existieren und `camoufox`/`camoufox.exe` heißen) |
| `ARGOS_CAMOUFOX_LAUNCH_OPTIONS` / `camoufox.launchOptions` | eigene `launch.json` statt des eingebauten Profils; fehlt oder ist sie ungültig, startet Argos nicht und nennt die Datei |
| `ARGOS_BROWSER_DIR` / `browser.install-dir` | anderer Installationsordner als `~/.argos/browser` |
| `ARGOS_CAMOUFOX_DISTRIBUTION_URL` / `camoufox.distributionUrl` | Spiegel der Archive (`https://…/` oder `file:///…/`); die gepinnten Prüfsummen gelten weiter |

Umgebungsvariablen haben Vorrang vor `~/.argos/argos.properties` (oder `-Dargos.config`). Ein eigener Camoufox ohne
eigene `launch.json` verlangt `addons/UBO` neben dem Executable, sonst nennt Argos beide Auswege.

Nicht zur Distribution gehören Browserprofile: sie bleiben unter `~/.argos/profiles/` (Chat-Isolation unverändert).
