# ID-Navigation und Recherche-Tabs

## ID-basierte Navigation (alle Profile mit `web_*`)

- `web_search` liefert `result-N` mit Titel und Snippet, **ohne Ziel-URL**.
- Jede Seitenantwort (`web_open`, `web_open_result`, `web_follow`, `web_back`, `web_read`) enthält Titel, Text
  und darunter `LINKS (open with web_follow link_id):` mit `link-N: Linktext`, **ohne URL**. Mehr als
  `DEFAULT_MAX_LINKS` (150) Links werden mit `(list truncated: more links not shown)` gekennzeichnet. Ein
  zusätzlicher `web_links`-Aufruf ist nicht nötig.
- `web_open_result` und `web_follow` navigieren mit der exakt gespeicherten Original-URL: keine Normalisierung,
  Query-Parameter wie ResearchGates `_sg`/`_tp` und Fragmente bleiben unverändert. Keine Seiten-Sonderbehandlung.
- `web_get_url` gibt die gespeicherte URL einer bekannten ID (im Profil `convenience` auch die erreichte URL
  eines Tabs, `t2`) zurück und navigiert nie.
- IDs werden innerhalb einer Sitzung nie wiederverwendet: neue Suche → `result-N` zählt weiter, neue Seite →
  `link-N` zählt weiter. IDs einer früheren Suche, einer früheren Seite oder eines geschlossenen Tabs werden als
  „Stale … id“ abgelehnt, unbekannte als „Unknown … id“. Eine bloße Zahl bezieht sich auf die zuletzt gezeigte Liste.
- `web_open(url)` bleibt für vom Nutzer vorgegebene URLs.

Registries: `LinkRegistry` (Links je Tab, gemeinsamer Zähler) und die Trefferliste in `WebSession`; es gibt
keine zweite Linkverwaltung.

## Recherche-Tabs (Profil `convenience`, Standard)

- `web_open_result result_id="result-1, result-3"` öffnet bis zu 5 Treffer, jeden in einem eigenen Tab. Die
  Antwort enthält je Tab `=== result-1 in tab t2 ===` mit `TAB:`, Titel, gekürztem Text (vollständig über
  `web_read tab="t2"`) und den Links. Ein Treffer, dessen exakte URL schon in einem Tab offen ist, wird nicht
  erneut geladen.
- Link-IDs sind tabübergreifend eindeutig: `web_follow link_id="link-12"` navigiert den Tab, zu dessen Seite der
  Link gehört; andere Tabs bleiben unverändert. `new_tab=true` öffnet den Link in einem neuen Tab und behält die
  Ausgangsseite.
- `web_read`, `web_find`, `web_links`, `web_back` nehmen optional `tab`.
- `web_tabs` zeigt je Tab Seite, Herkunft (`result-2`, `link-7 of t2`, `web_open`), Zahl gelesener Seiten, den
  Such-Tab und geschlossene Tabs. `web_close_tab` beendet einen Recherchepfad; dessen Link-IDs werden ungültig.
  Höchstens 8 Recherche-Tabs sind gleichzeitig offen.
- Eine neue `web_search` läuft im Such-Tab bzw. in einem neuen Tab, wenn der aktuelle Tab eine Recherche-Seite
  zeigt; offene Recherche-Tabs bleiben erhalten.

### Kontrollierte Parallelität

- Geladen wird über `CamoufoxBrowser.navigatePages` (vorhanden seit den `browser_pages_*`-Werkzeugen): bis zu 4
  Navigationen gleichzeitig bis zum Response-Commit, danach wartet jede Seite auf ihren Ladezustand. Das ist echte
  Überlappung des Ladens im Browser; Playwright-Aufrufe selbst laufen weiterhin auf dem einen Driver-Thread.
- Pro Host höchstens eine Navigation je Welle; zwischen zwei Navigationen zum selben Host liegen mindestens
  1,5 s (`HostThrottle`). Verschiedene Hosts laden parallel.
- Antwortet ein Host mit 429 oder 403, wird er 60 s nicht mehr angesteuert (verdoppelt sich je weiterer
  Ablehnung, höchstens 15 min; eine erfolgreiche Seite setzt zurück). Betroffene Treffer werden mit Wartezeit
  gemeldet statt geöffnet. Es werden keine zusätzlichen Requests zur Prüfung gesendet.
- Ein Browser ohne Tabs (Testdoubles, andere Ports) öffnet Treffer einzeln im aktuellen Tab; Parallelität wird
  dort nicht vorgetäuscht. In den Profilen `compact` und `expert` gilt dasselbe Ein-Tab-Verhalten.

## Grenzen

- Hosts werden exakt verglichen (`a.example.com` und `b.example.com` gelten als verschieden).
- Hintergrund-Tabs drosselt Firefox (Timer); Seiten, die erst per Skript nachladen, sind dort ggf. später fertig.
  Argos wechselt Tabs nur intern (kein `bringToFront`), damit keine Fokus-Ereignisse entstehen.
- `browser_*`-Werkzeuge im Profil `expert`/`convenience` geben weiterhin URLs aus (z. B. `browser_inspect`); die
  ID-Regel betrifft die `web_*`-Navigation.
- Die Search History zeichnet jede über IDs geöffnete Seite wie bisher als `web_open` auf.
