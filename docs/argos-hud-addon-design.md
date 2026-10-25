# Argos-HUD als Camoufox-Add-on statt In-Page-Overlay – Entwurf

Stand: 2026-10-25 · Status: Entwurf, nicht implementiert, **nicht getestet** · Bezug: `docs/argos-optimization-analysis.md`
(Stealth-Stufe 2, Risiko R5, Paket 7)

> **Machbarkeits-Gate.** Dieses Dokument beschreibt kein getestetes Add-on. Alle Aussagen zur
> Unsichtbarkeit sind **Ziele**, keine Befunde. Sie gelten erst, wenn der Vergleich mit und ohne Add-on
> (Abschnitt 6) keine Unterschiede zeigt. Vor dem Abschluss von H1 (Abschnitt 7) wird nichts
> implementiert und nichts gemergt, was über diesen Entwurf hinausgeht.

## 1. Ziel und Kurzfassung

AskAI zeigt während einer Recherche ein HUD **in der besuchten Seite** an (`ResearchHudOverlay`):
Status-Badge, Statuszeile, Pause/Resume, Skip, „Weiter“ während der Wartezeit, Verzögerungs-Slider,
Stern „relevant“ und bei CAPTCHAs einen Countdown mit „✓ Gelöst“. Dafür werden per `page.evaluate`
ein Shadow-DOM-Host an `document.documentElement` gehängt, `window.__askaiResearchHudRoot`,
`window.__askaiHudPaused` und `window.__askaiHudRelevant` gesetzt und die Knöpfe über
`context.exposeBinding("__askaiHudCommand")` an Java angebunden. Das ist nach Abschnitt 3 der Analyse
**verboten** (DOM-Overlay, Globals, Binding mit `__playwright__binding__`-Spuren in jeder Seite).

Vorschlag: Argos bekommt ein eigenes WebExtension-Add-on **„Argos HUD“**, das

- **keine Content-Scripts, keine `web_accessible_resources`, keine Netzwerk-Eingriffe** hat und damit
  Webseiten keine bekannte Angriffsfläche bietet (Ziel; Nachweis erst durch Abschnitt 6),
- die Bedienelemente in einem **eigenen kleinen Browserfenster** (Add-on-Seite) sowie im Browser-Chrome
  (Toolbar-Badge, Fenstertitel-Präfix) zeigt, **nicht in einer Sidebar** (siehe 4.4),
- über seinen Hintergrund-Script per **Loopback-WebSocket mit Token** mit Argos spricht,
- **opt-in** ist (`ARGOS_HUD=addon`, Standard `off`) und im Headless-Betrieb gar nicht geladen wird.

Das AskAI-Werkzeugpaar `web_hud_render` / `web_hud_poll` bleibt als Schnittstelle für das Modell bzw. den
Recherche-Agenten erhalten; nur der Transport darunter wird ausgetauscht. Das Modell merkt keinen
Unterschied.

## 2. Was das HUD leisten muss (aus AskAI `arch`)

| Element | AskAI heute | Im Add-on |
|---|---|---|
| Phase-Badge, Statuszeile | Overlay oben | HUD-Fenster; Kurzform zusätzlich als Toolbar-Badge (`action.setBadgeText`) und Fenstertitel-Präfix |
| Pause/Resume, Skip, Weiter (nur in Phase `DELAY`) | Overlay unten | Knöpfe im HUD-Fenster; Pause/Skip zusätzlich im Toolbar-Popup |
| Verzögerungs-Slider 0–30 s | Overlay unten | HUD-Fenster |
| ⭐ relevant für die aktuelle Seite | Overlay oben | HUD-Fenster + Kontextmenü-Eintrag (`menus`) „Für Argos als relevant markieren“ |
| Warten auf Benutzer + Countdown, „✓ Gelöst“ | Overlay Mitte | HUD-Fenster hebt sich hervor; optional Systembenachrichtigung (`notifications`) |
| Ende des Laufs (`terminal`) | Steuerelemente ausblenden | identisch |

Zustand und Befehle bleiben exakt die von `ResearchHudState` (`phase`, `status`, `waiting`, `countdown`,
`paused`, `delay`, `relevant`, `terminal`) und die Befehle `PAUSE`, `RESUME`, `SKIP`, `NEXT`,
`SET_DELAY:<n>`, `SET_RELEVANCE:on|off`. `HudCommandInbox` (Steuerkanal außerhalb der Aktor-Queue) kann
unverändert übernommen werden; nur der Schreiber ist künftig der WebSocket statt des Bindings.

## 3. Architektur

```
 Modell / Recherche-Agent
        │ MCP: web_hud_render(state) / web_hud_poll()
        ▼
 Argos (Java 21)
  ├─ CamoufoxBrowser ── Playwright4J / WebDriver BiDi ──► Camoufox (Seiten bleiben unberührt)
  └─ HudBridge ── ws://127.0.0.1:<Argos-Port>/hud  (Token, Origin-Prüfung)
                          ▲
                          │ WebSocket aus dem Add-on-Hintergrund
 Camoufox
  └─ Add-on „Argos HUD“ (temporär geladen, keine Content-Scripts)
       ├─ background.js   – Verbindung, Zustand, Badge, Titelpräfix, Kontextmenü, Tab-Beobachtung
       ├─ hud.html/js     – eigenes kleines Fenster (windows.create type "popup")
       └─ popup.html/js   – Toolbar-Popup mit Pause/Skip
```

### 3.1 Manifest (Skizze)

```json
{
  "manifest_version": 2,
  "name": "Argos HUD",
  "version": "0.1.0",
  "browser_specific_settings": { "gecko": { "id": "argos-hud@aresstack.com" } },
  "background": { "scripts": ["background.js"], "persistent": true },
  "browser_action": { "default_title": "Argos", "default_popup": "popup.html" },
  "permissions": ["tabs", "menus", "notifications"]
}
```

Bewusst **nicht** enthalten: `content_scripts`, `web_accessible_resources`, `webRequest`/
`webRequestBlocking`, `declarativeNetRequest`, `proxy`, `scripting`/`tabs.executeScript`,
`<all_urls>`-Host-Rechte, `devtools_page`, `sidebar_action`. MV2 statt MV3, weil Firefox bei MV2 eine
dauerhafte Hintergrundseite erlaubt und die WebSocket-Verbindung so nicht vom Event-Page-Lebenszyklus
abhängt; MV3 mit Event-Page ist möglich, verlangt aber Wiederverbindungslogik bei jedem Aufwachen.

`tabs` liefert URL und Titel der Tabs ohne Seitenkontakt (nur Browser-Chrome-Daten). Damit kann das HUD
anzeigen, auf welchem Tab Argos arbeitet, und „⭐ relevant“ an die Tab-URL binden. Ein Unit-Test in Argos
prüft das Manifest auf die verbotenen Schlüssel (analog zum Musterverbot für Stufe-3-Skripte).

### 3.2 Laden des Add-ons in Camoufox

Argos startet Camoufox über `launchPersistentContext` mit Executable und `launch.json` (Fingerprint in
`CAMOU_CONFIG_*`). Playwright selbst hat für Firefox keine Option zum Laden von Erweiterungen. Es gibt zwei
Wege; beide installieren das Add-on **temporär** (pro Browserstart, unsigniert zulässig, kein Eintrag im
Profil):

1. **Camoufox-eigener Mechanismus (bevorzugt).** Camoufox lädt Add-ons aus entpackten Ordnern
   (`addons=[...]` im Python-Launcher) und lädt standardmäßig uBlock Origin. Ein weiteres Add-on ist für
   Camoufox also der Normalfall. Argos erzeugt `launch.json` heute nicht selbst, sondern übernimmt die vom
   Camoufox-Launcher erzeugte Datei. Umsetzung: `launch.json` einmalig mit
   `launch_options(addons=[<argos-hud-Ordner>], ...)` neu erzeugen bzw. **messen, wie die Add-on-Pfade dort
   landen** (vermutlich als Schlüssel `addons` in der `CAMOU_CONFIG`-JSON) und Argos den Pfad zur Laufzeit
   ergänzen lassen. Vorteil: identischer Pfad wie uBlock Origin, also keine neue Art von Spur.
2. **WebDriver BiDi `webExtension.install`** (Firefox ≥ 137, Camoufox basiert inzwischen auf Firefox 152)
   mit `extensionData: {type: "path", path: <Ordner>}` nach dem Start. Standard ist temporäre Installation.
   Das braucht in Playwright4J einen Durchgriff auf rohe BiDi-Befehle, den die Java-Playwright-API nicht
   bietet. Nur als Rückfallebene, falls Weg 1 sich nicht sauber konfigurieren lässt.

Nicht verwenden: dauerhafte Installation ins Profil (`<profil>/extensions/*.xpi`) mit
`xpinstall.signatures.required=false`. Das verändert das persistente Profil dauerhaft und hängt davon ab,
ob der Build die Pref respektiert.

Der Add-on-Ordner liegt in Argos als Ressource (`src/main/resources/argos-hud/`) und wird beim Start nach
`~/.argos/addon/argos-hud/` entpackt. In diesen Ordner schreibt Argos vor dem Browserstart eine
`connection.json` mit Port und Token. Das Add-on liest sie per `fetch(browser.runtime.getURL(...))`
(Add-on-interne URL; ohne `web_accessible_resources` für Seiten nicht erreichbar).

**Bedingungen für das Laden (Teil des H1-Gates):**

- Die geprüfte Camoufox-`launch.json` wird **nicht verändert**. Argos ergänzt den Add-on-Pfad nur im
  Speicher beim Start; die Fingerprint-Werte in `CAMOU_CONFIG_*` bleiben bytegleich. Nachweis: Hash der
  dekodierten Konfiguration ohne den Add-on-Eintrag vor und nach dem Start identisch, Fingerprint-Probe
  (User-Agent, Bildschirm, Fenster, WebGL, Fonts) mit und ohne Add-on identisch.
- Das Profil `~/.argos/profiles/default` erhält keine dauerhafte Installation. Nachweis: Nach einem Lauf mit
  Add-on und einem Neustart ohne Add-on taucht es in `extensions.json` nicht als installiert auf, und
  Prefs außer den von Firefox selbst gepflegten (z. B. `extensions.webextensions.uuids`) sind unverändert.
  Der Test läuft zuerst auf einer Kopie des Profils.
- Lässt sich eines davon nicht einhalten, wird H2 nicht begonnen.

#### `connection.json` und Token

- **Rechte:** Datei nur für den Argos-Benutzer lesbar (POSIX `600`, Ordner `700`; unter Windows ACL nur
  für den aktuellen Benutzer, Vererbung aus). Argos verweigert den HUD-Start, wenn es die Rechte nicht
  setzen kann.
- **Inhalt:** Port und ein Token mit 256 Bit aus `SecureRandom`, Base64url. Kein Token in URLs (Logs,
  Proxys): Es wird als erste WebSocket-Nachricht gesendet und in Argos zeitkonstant verglichen.
- **Lebensdauer:** ein Token pro Browserstart. Argos erzeugt es direkt vor dem Start und löscht
  `connection.json`, sobald die erste gültige Verbindung steht. Das Token bleibt nur im Speicher von Argos
  und im Hintergrund-Script. Beim Beenden von Argos oder des Browsers verfällt es.
- **Rotation:** Ein neuer Browserstart erzeugt immer ein neues Token. Nach drei fehlgeschlagenen
  Anmeldungen innerhalb einer Minute sperrt Argos `/hud` bis zum nächsten Browserstart.
- **Nur eine Sitzung:** Höchstens eine angemeldete HUD-Verbindung. Eine zweite wird abgewiesen, solange
  die erste lebt.

### 3.3 Kommunikation Add-on ↔ Argos

Empfehlung: **WebSocket vom Add-on-Hintergrund zu Argos über Loopback**, auf dem ohnehin vorhandenen
Argos-Port unter einem eigenen Pfad (`/hud`).

- Argos lauscht bereits auf Loopback (`SERVER_PORT`, `SERVER_HOST` nur Loopback). Ein zusätzlicher Pfad
  öffnet **keinen neuen Port**. Seiten können lokale Ports scannen (bekannte Fingerprinting-Technik);
  ein eigener HUD-Port wäre eine neue, scanbare Auffälligkeit, ein Pfad auf dem bestehenden nicht.
- **Authentifizierung ist allein das Token.** `Origin` ist keine Authentifizierung, sondern nur ein Filter
  gegen Anfragen aus Webseiten (die einen `http(s)`-Origin senden). Die Add-on-UUID und damit der
  `moz-extension://<uuid>`-Origin ist pro Profil zufällig. Argos lernt sie nach der ersten erfolgreichen
  Token-Anmeldung und akzeptiert danach nur noch diesen Origin. Vorher wird jeder Origin abgewiesen, der
  nicht mit `moz-extension://` beginnt.
- Alles, was nicht angemeldet ist, wird genauso beantwortet wie jeder unbekannte Pfad (gleicher
  Statuscode, gleiche Länge), damit `/hud` von außen nicht unterscheidbar ist. Die Anmeldung muss
  innerhalb von 2 s nach dem Upgrade kommen, sonst wird die Verbindung geschlossen.
- **Wiederanlauf:** Bricht die Verbindung ab (Argos-Neustart, Ruhezustand), versucht das Add-on es erneut
  mit wachsendem Abstand (1, 2, 4 … höchstens 30 s) und dem Token aus seinem Speicher. Startet Argos neu,
  ist das alte Token ungültig; das HUD zeigt „getrennt“, bis der Browser mit neuem Token neu startet.
  Argos sendet nach jeder Anmeldung den letzten Zustand erneut. Befehle, die während einer Trennung
  geklickt werden, verwirft das Add-on (kein verspätetes `SKIP`).
- Die Verbindung geht vom Add-on-Hintergrund aus, nicht von einer Seite, und wird von Firefox keinem Tab
  zugeordnet. Dass Seiten sie nicht sehen, ist zu messen (Abschnitt 6, Punkt 3).
- Nachrichten (JSON, eine Zeile):
  - Argos → Add-on: `{"type":"state","state":{…ResearchHudState…}}`, `{"type":"focusTab","url":…}` (nur
    Hervorhebung im HUD, keine Tab-Umschaltung)
  - Add-on → Argos: `{"type":"command","command":"PAUSE"}` usw., `{"type":"hello","version":"0.1.0"}`
- `web_hud_render` schreibt den Zustand in die `HudBridge` (letzter Zustand wird bei Neuverbindung erneut
  gesendet), `web_hud_poll` leert wie bisher die `HudCommandInbox`.
- Ohne verbundenes Add-on liefert `web_hud_render` `"no-hud"` (wie AskAI heute bei fehlendem Overlay). Der
  Recherchelauf läuft weiter.

Verworfene Alternativen:

| Variante | Warum nicht |
|---|---|
| Native Messaging | kein Port, aber Host-Manifest muss im System registriert werden (Windows-Registry bzw. `~/.mozilla/native-messaging-hosts`), Firefox startet pro Verbindung einen Prozess, der wiederum mit Argos sprechen muss. Mehr Installationsaufwand bei gleichem Nutzen. Bleibt Plan B, falls Loopback-Zugriff aus dem Add-on in Camoufox gesperrt ist. |
| HUD-Seite nur per BiDi steuern (Argos ruft `page.evaluate` auf der `moz-extension://…/hud.html`-Seite auf, kein Netzwerk) | elegant und ohne Port, ohne Token-Verwaltung, aber unklar, ob Firefox-BiDi Add-on-Seiten als Browsing-Kontext an Playwright meldet und ob Playwright sie bedienen darf. Pflichtfrage in H1; wenn es geht, wird diese Variante bevorzugt und der WebSocket entfällt. |
| Sidebar (`sidebar_action`) | verkleinert den Inhaltsbereich (siehe 4.4) |
| DevTools-Panel | geöffnete DevTools sind für Seiten über Fenstergröße und Debugger-Timing erkennbar |
| Content-Script mit Shadow-DOM-Overlay | DOM-Änderung in der Seite; genau das, was ersetzt werden soll |

## 4. Erkennungsflächen und wie der Entwurf sie meidet

### 4.1 `web_accessible_resources`
Über freigegebene Ressourcen können Seiten `moz-extension://<UUID>/…` laden. In Firefox ist die UUID pro
Profil zufällig, aber **stabil**; sie wäre damit sogar ein Tracking-Merkmal. Das Add-on gibt **nichts**
frei. Interne Seiten (`hud.html`, `popup.html`, `connection.json`) sind dann für Webseiten nicht ladbar.

### 4.2 Content-Scripts
Auch in der isolierten Welt (Xray) hinterlassen Content-Scripts Spuren, sobald sie etwas tun: DOM- oder
Style-Änderungen, Event-Listener (Timing), injizierte `<script>`-Elemente, Fehlermeldungen. Der Entwurf
hat **keine** Content-Scripts und nutzt kein `tabs.executeScript`.

Die Analyse sah Stufe 2 auch für „geometrische Seitenerfassung“ per Content-Script vor. Empfehlung:
**nicht** über das Add-on. Playwrights Utility-Welt (`locator.boundingBox()`, `page.content()`) ist bereits
eine BiDi-Sandbox mit derselben Isolation; ein Content-Script brächte keinen Tarnungsgewinn, aber einen
zweiten Code-Pfad in jeder Seite. Geometrie bleibt Stufe 1 bzw. Stufe 3.

### 4.3 Netzwerk
Keine Rechte für `webRequest`, `declarativeNetRequest` oder `proxy`; das Add-on ändert weder Header noch
Antworten. Die einzige Verbindung ist der WebSocket zu Argos (3.3). Er läuft nicht über einen Tab und
sollte daher in keiner `performance`-Liste einer Seite auftauchen (Messpunkt 3).

### 4.4 Fenster- und Viewport-Geometrie
Eine geöffnete Sidebar verkleinert `innerWidth` bei gleichem `outerWidth`. Camoufox fälscht
Fenster- und Bildschirmmaße per Fingerprint; eine Sidebar erzeugt dann entweder einen ungewöhnlichen
Abstand `outerWidth − innerWidth` oder einen Widerspruch zwischen gefälschtem und tatsächlichem Layout
(Media Queries). Deshalb ein **separates Fenster**. Toolbar-Knopf und Badge ändern die Höhe der
vorhandenen Toolbar nicht. Messung in H1: `innerWidth/innerHeight/outerWidth/outerHeight` mit und ohne
Add-on vergleichen.

### 4.5 Fokus
Ein Klick ins HUD-Fenster nimmt der Zielseite den Fokus (`blur`, `document.hasFocus() === false`). Das ist
normales menschliches Verhalten, solange **nur der Mensch** es auslöst. Regeln: HUD-Fenster mit
`focused: false` erzeugen, Zustandsupdates fokussieren nie, kein `windows.update({focused:true})` aus dem
Hintergrund. Vor echten Eingaben über BiDi holt Argos die Zielseite mit `bringToFront()` zurück
(Stufe 0), falls nötig.

Ein separates Fenster kann aber auch **ohne Klick** Fokus und Timing der Zielseite verändern. Das ist
ungeprüft und wird in H1 gemessen (Abschnitt 6, Punkte 8–10):

- Öffnen des HUD-Fensters könnte trotz `focused: false` je nach Fenstermanager den Fokus wegnehmen
  (`blur`, `visibilitychange`).
- Verdeckt das HUD-Fenster das Browserfenster teilweise oder ganz, kann Firefox Timer und
  `requestAnimationFrame` drosseln (Occlusion). Das HUD-Fenster wird deshalb neben, nicht über dem
  Zielfenster platziert.
- Wiederholtes Rendern im HUD-Fenster läuft im selben Browserprozess wie die Zielseite und könnte deren
  Event-Loop- bzw. Frame-Timing messbar beeinflussen. Updates werden deshalb gedrosselt (höchstens 2 pro
  Sekunde, Countdown nur sekundenweise).

Zeigt die Messung Effekte, gibt es einen **fensterlosen Modus**: nur Toolbar-Badge, Fenstertitel-Präfix
und Toolbar-Popup (öffnet sich nur auf Klick des Menschen). Er ist der Rückfall und kann auch Standard
werden.

### 4.6 Sichtbarkeit in Playwright
Add-on-Seiten in eigenen Fenstern können in `context.pages()` erscheinen. `CamoufoxBrowser.activePage()`
nimmt heute die erste offene Seite. Änderung: Seiten mit `moz-extension:`-URL aus allen Tab-Listen,
`browser_tabs`-Ausgaben und `open_many`-Zählungen herausfiltern; Netzwerk-Events dieser Seiten nicht
protokollieren. Das HUD-Fenster ist dadurch für das Modell unsichtbar und kann nicht versehentlich
navigiert werden.

### 4.7 Fenstertitel
`windows.update(id, {titlePreface: "[Argos: READING] "})` ändert nur den Titel des Browserfensters im
Betriebssystem. `document.title` der Seite bleibt unverändert. Optional, standardmäßig an.

### 4.8 Add-on an sich
Webseiten haben in Firefox keine API, installierte Add-ons aufzulisten. Camoufox bringt mit uBlock Origin
ohnehin ein Add-on mit. Ein zusätzliches, passives Add-on ändert den für Seiten messbaren Zustand nicht,
solange 4.1 bis 4.5 eingehalten werden. Das wird gemessen, nicht angenommen (Abschnitt 6).

## 5. Änderungen in Argos

- `src/main/resources/argos-hud/` mit `manifest.json`, `background.js`, `hud.html/js/css`,
  `popup.html/js`, Icons. Reines JavaScript ohne Build-Schritt.
- `HudAddon` (Java): entpackt den Ordner, schreibt `connection.json` (Port, Token aus `SecureRandom`),
  liefert den Pfad an `CamoufoxBrowser` zum Laden (3.2).
- `HudBridge` (Java): WebSocket-Endpunkt `/hud` im vorhandenen Jetty (Token- und Origin-Prüfung), hält den
  letzten `ResearchHudState`, schreibt Befehle in `HudCommandInbox`.
- Werkzeuge `web_hud_render(state)` und `web_hud_poll()` im `compact`- und `expert`-Profil; Zustand und
  Format aus AskAI `ResearchHudState` übernehmen (Java 8, MIT, AresStack, also direkt kopierbar).
  `ResearchHudOverlay` und das `exposeBinding` werden **nicht** übernommen.
- `CamoufoxBrowser`: `moz-extension:`-Seiten filtern (4.6).
- Konfiguration: `ARGOS_HUD=off|addon` (Standard `off`), bei `ARGOS_HEADLESS=true` immer aus.
- Tests: Manifest-Verbotsliste, `HudBridge` (Token, Origin, Antwort auf Fremdzugriffe gleich wie 404),
  Zustand-Roundtrip, Filterung der Add-on-Seiten.

## 6. Messung vor dem Einsatz (R1/R5)

Ablauf wie im Signal-Regressionstest der Analyse (opt-in, lokal mit echter Camoufox-Binärdatei), jeweils
**ohne** und **mit** Add-on, HUD-Fenster offen, mehrere Zustandswechsel:

1. Globals und `Object.getOwnPropertyNames(window)` vor und nach einem Lauf identisch.
2. `document.documentElement.outerHTML`-Hash ohne Unterschied durch Argos (Seite selbst statisch).
3. `performance.getEntries()` ohne `moz-extension:`- oder Loopback-Einträge.
4. Probe auf `moz-extension://<uuid>/manifest.json` aus der Seite schlägt fehl (UUID aus `about:debugging`).
5. Fenstergeometrie wie in 4.4 unverändert.
6. Portscan aus einer Testseite auf den Argos-Port: `/hud` ist nicht von einem unbekannten Pfad zu
   unterscheiden.
7. Praxisprobe auf Seiten, die bisher funktionieren (u. a. ResearchGate, Suchmaschinen aus `web_search`):
   kein neuer Block.
8. Fokus: `document.hasFocus()`, `document.visibilityState` und die Zahl der `blur`/`focus`/
   `visibilitychange`-Ereignisse auf der Zielseite beim Öffnen des HUD-Fensters und bei
   Zustandsupdates, ohne Klick ins HUD. Erwartung: keine Ereignisse.
9. Timing: Intervalle von `requestAnimationFrame` und `setTimeout(0)` sowie `performance.now()`-Jitter auf
   einer Messseite mit und ohne HUD-Fenster (offen, neben dem Zielfenster, laufender Countdown). Erwartung:
   keine Abweichung über das Rauschen hinaus.
10. Fingerprint-Konfiguration und Profil wie in 3.2 (bytegleich, keine dauerhafte Installation).

Die Messseiten setzen ihre Listener selbst, als wäre es eine fremde Seite; Argos injiziert dafür nichts.
Erst wenn alle Punkte gleich ausfallen, darf das Add-on als „für Seiten unsichtbar“ bezeichnet und
`ARGOS_HUD=addon` für den Recherche-Agenten empfohlen werden.

## 7. Arbeitspakete

| Paket | Inhalt | Ergebnis |
|---|---|---|
| H1 – Machbarkeits-Gate (lokal, mit echter Camoufox-Installation) | Add-on-Gerüst (Manifest, Hintergrund, HUD-Fenster mit festem Text); Laden über die vorhandene `launch.json` ohne Änderung der Fingerprint-Daten und ohne dauerhafte Profiländerung (3.2), sonst BiDi `webExtension.install`; prüfen, ob `moz-extension`-Seiten in `context.pages()` auftauchen und per Playwright/BiDi steuerbar sind; Messpunkte 1–5 und 8–10 | Bericht mit Messwerten. **Go/No-Go durch Angelo.** Bei No-Go bleibt es bei „kein HUD“ bzw. HUD außerhalb des Browsers. |
| H2 – Brücke | `HudBridge`, `HudAddon`, `connection.json`, `web_hud_render/poll`, Filter in `CamoufoxBrowser`, Unit-Tests | Pause/Skip/Weiter/Delay/⭐ funktionieren Ende-zu-Ende |
| H3 – Komfort | Badge, Titelpräfix, Kontextmenü „relevant“, Systembenachrichtigung bei „Warten auf Benutzer“ | |
| H4 – Freigabe | Messpunkte 1–7, Ergebnis in die Analyse eintragen, Stufe 2 von „geplant“ auf „gemessen“ setzen | |

H2 bis H4 beginnen erst nach einem Go für H1. H2 hängt außerdem vom Recherche-Agenten (Paket H der
Gesamtplanung) ab, der die HUD-Werkzeuge nutzt.

## 8. Offene Fragen

1. In welcher Form landen die `addons` des Camoufox-Launchers in `launch.json`, und darf Argos dort zur
   Laufzeit einen Pfad ergänzen, ohne die geprüfte Fingerprint-Datei zu verändern? (H1)
2. Erlaubt Camoufox Loopback-WebSockets aus einem Add-on-Hintergrund ohne zusätzliche Pref? (H1)
3. Soll das HUD auch ohne Recherche-Agent nützlich sein, etwa als Anzeige „Argos steuert gerade diesen
   Tab“ für jedes MCP-Werkzeug? Kosten: gering, da der Zustand ohnehin über die Brücke läuft.
