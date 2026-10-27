# Menschliche Eingabe (Human Emulation)

Argos bedient Seiten nur mit echter Eingabe über WebDriver BiDi `input.performActions`. Diese Schicht sorgt dafür, dass die Eingabe auch im **Verlauf** wie die eines Menschen aussieht: keine geraden Mauswege, kein Springen des Zeigers, keine festen Tastenabstände.

## Aufbau

| Klasse | Aufgabe |
| --- | --- |
| `HumanMotion` | plant Mauswege, Klickpunkt, Ruhe- und Druckzeiten, Mausrad-Schübe. Reine Rechnung, kein Browserzugriff |
| `HumanTyping` | plant Tastenanschläge als Zeitachse von `keyDown`/`keyUp` inkl. Shift, Überlappung und Tippfehlern. Reine Rechnung |
| `InputDevice` | der rohe Kanal (`page.mouse()`/`page.keyboard()`): jeder Aufruf ist ein `input.performActions` |
| `RealInput` | misst das Element, scrollt per Mausrad, prüft Verdeckung, spielt die Pläne mit absoluten Zeitmarken ab |
| `HumanInputSettings` | Konfiguration (`ARGOS_HUMAN_*`, `ARGOS_INPUT_UNICODE`) mit festem Seed für Tests |

Die Planer sind getrennt von der Ausführung, damit sie mit festem Seed deterministisch getestet werden können. Beim Abspielen wartet `RealInput` bis zur geplanten Zeitmarke (`start + at - now`), so dass die Laufzeit der BiDi-Befehle die Zeiten nicht verschiebt.

## Maus

- **Eintritt:** In einem Tab ohne bisherige Bewegung betritt der Zeiger die Seite an der oberen Kante (wie eine Hand, die von Adressleiste oder Tableiste kommt), ungefähr über dem Ziel, und fährt von dort. Danach merkt sich Argos die Position je Tab; jede weitere Bewegung beginnt dort, wo die letzte endete.
- **Dauer:** Fitts' Gesetz, `90 ms + 120 ms · log2(D/W + 1)`, mit log-normaler Streuung (σ = 0,15). Weite Wege auf kleine Ziele dauern länger.
- **Bahn:** kubische Bézier-Kurve, beide Kontrollpunkte auf derselben Seite der Geraden (ein Bogen, kein S), Ausbiegung 4–22 % der Strecke, höchstens 180 px.
- **Geschwindigkeit:** Minimum-Jerk-Profil (`10u³ − 15u⁴ + 6u⁵`), leicht nach vorn verschoben, weil Menschen früh beschleunigen und lange abbremsen.
- **Zittern:** 8–12 Hz Sinus mit 0,4–1,1 px plus kleinem Rauschen, klingt zum Ziel hin ab. Abtastung alle 10–20 ms in ganzen Pixeln, doppelte Punkte entfallen (eine Maus meldet nur Änderungen).
- **Überschießen:** Bei Wegen über 250 px in 30 % der Fälle 2–6 % (≤ 28 px) über das Ziel hinaus, kurze Pause, dann eine kleine Korrekturbewegung.
- **Klick:** Punkt normalverteilt um die Mitte (σ = Breite/7), begrenzt auf die inneren 60 %. Vor dem Drücken 60–400 ms Ruhe (Median 120), gedrückt 45–180 ms (Median 85).
- **Scrollen:** nur per Mausrad, in Schüben von 1–5 Rasten à 100 px mit 25–75 ms Abstand und 180–500 ms Lesepause, bis der Klickpunkt im oberen Drittel bis zur Mitte des Fensters liegt. Bewegt sich die Seite nicht, liegt das Element in einem eigenen Scrollbereich: der Zeiger fährt erst über dessen Projektion. Danach prüft `elementFromPoint`, ob der Punkt verdeckt ist (z. B. Cookie-Banner); bis zu vier Punkte werden versucht.
- **Fenstergröße:** aus dem Camoufox-Fingerprint (`window.innerWidth/innerHeight` in `CAMOU_CONFIG_1..n`), sonst einmalig per kurzer, nur lesender Auswertung (Stufe 3). Bewegungen bleiben im Fenster; ein Zwischenpunkt außerhalb des echten Fensters wird übersprungen, nie das Ziel.

## Tastatur

- **Abstände** zwischen zwei Anschlägen log-normal, Median 150 ms (σ = 0,35, 50–650 ms); nach Leerzeichen und Satzzeichen × 1,25; gleiche Taste hintereinander × 0,85; vor einem Wort in 7 % der Fälle 250–1100 ms Nachdenken, sonst in 2 % ein kurzes Zögern.
- **Haltedauer** 35–180 ms (Median 85). Ist sie länger als der nächste Abstand, überlappen die Anschläge (nächste Taste unten, bevor die vorige oben ist), aber nie über einen Shift-Wechsel hinweg.
- **Shift:** Großbuchstaben und `~!@#$%^&*()_+{}|:"<>?` werden mit gedrückter Shift-Taste getippt, Shift geht 40–110 ms vorher runter und nach dem letzten Zeichen hoch. Playwrights eigenes `keyboard.type("A")` sendet nur `A` ohne Shift, sodass `shiftKey` falsch wäre.
- **Feld leeren:** Ctrl/Cmd+A und Backspace mit menschlichen Pausen. Der alte Wert wird nicht gelesen, weil `inputValue` Marker-Events auslöst.
- **Vor dem Absenden** (`\n` am Ende) 150–600 ms Pause, dann Enter.
- **Tippfehler** (`ARGOS_HUMAN_TYPO_RATE`, Standard 0): Nachbartaste der QWERTY-Reihe, 220–620 ms bis zum Bemerken, Backspace, richtiges Zeichen.
- **Lange Texte** werden proportional auf 25 s gestrafft; die Reihenfolge der Ereignisse bleibt gleich.

## Einzelne Tasten und Tastenkombinationen

`browser_press_key` (nur Profil `expert`) drückt eine Taste oder einen Shortcut als echte `keyDown`/`keyUp`-Folge über `input.performActions`, z. B. `Enter`, `Escape`, `Tab`, `ArrowDown`, `PageDown`, `F5`, `Control+a`, `Shift+Tab`, `Control+Shift+ArrowLeft`, wahlweise `times`-mal (1–20) und wahlweise nach einem echten Klick in ein Ziel (`target`, gleiche Auflösung wie bei `browser_fill_target`); ohne Ziel bekommt das fokussierte Element die Tasten. Text wird weiterhin nur mit `browser_fill_target` getippt, `fill` sendet nie ab: Enter gibt es nur als ausdrückliche Taste oder als abschließendes `\n`.

- **Ablauf:** Modifier gehen in der angegebenen Reihenfolge mit 40–120 ms Abstand runter, die Taste wird (bei `times` mehrfach, mit menschlichen Pausen) gedrückt, die Modifier kommen in umgekehrter Reihenfolge hoch. Scheitert eine Taste, werden alle gehaltenen Modifier losgelassen, damit späterer Text nicht zu Shortcuts wird.
- **Vorab-Prüfung:** Die ganze Kombination wird geprüft, bevor das Ziel aufgelöst, angeklickt oder eine Taste gedrückt wird: Modifier nur `Shift`, `Control`, `Alt`, `Meta`, `ControlOrMeta` (Aliasse `Ctrl`/`Strg`, `Cmd`, `Option`, `Esc`, `Return`, `Del`/`Entf`, `Up`/`Down`/`Left`/`Right`), danach genau eine benannte Taste oder ein Zeichen (`+` als Taste: `Control++`). Unbekannte Namen, ein Modifier allein, doppelte Modifier, Steuerzeichen und die WebDriver-Codepunkte U+E000–U+E05D werden abgelehnt.
- **Unicode streng:** Ein Zeichen außerhalb des US-Layouts (z. B. `ä`) wird nur gedrückt, wenn die Startprüfung `KEY_NAMES` ergab. `insertText` ist Text, keine Taste, und wird hier nie benutzt, auch nicht mit `ARGOS_INPUT_UNICODE=insert`.
- **Browser-Shortcuts gesperrt:** Kombinationen, die Fenster oder Tab schließen oder ersetzen würden (`Control+w/q/n/t`, `Meta+w/q/n/t`, `ControlOrMeta+…`, `Alt+F4`, `Control+F4`), werden abgelehnt; dafür gibt es die Tab-Werkzeuge.
- Nach dem Drücken wird die Seite wie nach einem Klick abgewartet; ein neu geöffneter Tab wird übernommen.

## Umlaute und andere Zeichen außerhalb des US-Layouts

Playwright Core 1.62 lehnt `keyboard.down("ä")` ab (`Unknown key`) und tippt solche Zeichen in `keyboard.type` über `window.__pw_bidiInsertText`, das ein **nicht vertrauenswürdiges** `InputEvent` auslöst ([playwright4j#36](https://github.com/aresstack/playwright4j/issues/36)). BiDi selbst würde jeden Unicode-Wert als echte Taste annehmen; die eigentliche Lösung ist der Fix in Playwright4J (`keyDown`/`keyUp` mit dem Unicode-Wert über `input.performActions`).

**Entscheidung (Angelo, 2026-10-25): fail closed.** Beim Browserstart prüft Argos auf einem leeren Tab, bevor eine Website geladen ist, einmal, ob Playwright `ä` als echte Taste sendet (Core 1.62 lehnt vor dem Senden ab; eine reparierte Version schickt ein harmloses `keyDown`/`keyUp` an `about:blank`). Jeder Text wird **vollständig vor dem Fokussieren, Leeren und Tippen** geprüft. Ist der echte Unicode-Pfad nicht nachgewiesen, gilt `ARGOS_INPUT_UNICODE`:

| Wert | Verhalten |
| --- | --- |
| `strict` (Standard) | Ablehnung mit Hinweis, bevor das Feld berührt wird; die Seite sieht nichts. Nur falls Playwright trotz positiver Prüfung mitten im Text ablehnt, wird das schon Getippte mit Backspace zurückgenommen |
| `transliterate` | **nur bewusst aktivierte Ausnahme**, weil sie den Text ändert (`Grüße` ≠ `Gruesse`, falsch bei Namen in Formularen): ä → ae, ö → oe, ü → ue, ß → ss, „“”»« → ", ‚‘’ → ', – — → -, … → ..., geschütztes Leerzeichen → Leerzeichen; übrige Zeichen wie `strict` |
| `insert` | **Debug-/Kompatibilitätsmodus, keine Stealth-Eingabe**: Playwrights Fallback über `__pw_bidiInsertText`; der Text kommt als skriptgemachtes Ereignis an. Argos warnt beim Start |

Die Startprüfung kennt zwei echte Wege: `KEY_NAMES` (`keyboard.down("ä")` wird angenommen, z. B. Playwright4J mit playwright4j#73) und `TEXT_AS_KEYS` (`insertText` kommt als echtes keyDown/keyUp je Zeichen an, wie im zurückgenommenen playwright4j#59; geprüft auf Argos' eigener Probeseite, nie auf einer Website). Nur bei einem der beiden wird `insertText` für solche Zeichen benutzt. Ein Zeilenumbruch mitten im Text eines einzeiligen Feldes wird vor dem Fokussieren abgelehnt (Enter würde das Formular vorzeitig absenden), ebenso die von WebDriver für benannte Tasten reservierten Codepunkte U+E000–U+E05D. `-Pplaywright4jSource=<checkout>` baut Playwright4J aus einem Quellstand; `UnicodeInputCamoufoxTest` verlangt dann echte Tasten.

Ein Tabulator wird nie als Tab-Taste gesendet (sie würde den Fokus verschieben); er wird wie ein nicht tippbares Zeichen behandelt.

**Stand:** Mit dem in der CI gepinnten Playwright4J `0.3.0` (`2e889ce`, enthält playwright4j#73: `keyboard.down/up/press` nimmt über BiDi ein einzelnes Graphem außerhalb des US-Layouts an; `type()`, `insertText()` und `fill()` bleiben unverändert) fällt die Startprüfung positiv aus (`KEY_NAMES`); Umlaute und andere Zeichen außerhalb des US-Layouts werden als echte Tasten getippt. `-PexpectUnicodeKeys=true` (Standard bei `-Pplaywright4jSource`) lässt `UnicodeInputCamoufoxTest` und `HumanInputBrowserTest` fehlschlagen, wenn Argos ablehnt, statt die Ablehnung als grün zu werten. So laufen beide unter Linux mit Camoufox 156.0.1-beta.36 gegen `0.3.0` grün und prüfen vertrauenswürdige keydown/keyup/input-Ereignisse und den Feldwert; gegen `2aea2e4` (Revert von playwright4j#59, ohne #73) schlagen sie fehl. Die vollständige Suite lief zusätzlich unter Windows gegen `fe12949` (in `0.3.0` enthalten) mit 586 von 586 Tests grün.

## Was die Seite sieht

Nur vertrauenswürdige `pointermove`/`mousemove`, `wheel`, `pointerdown`/`mousedown`, `pointerup`/`mouseup`, `click`, `keydown`/`keyup`/`input`. Keine Locator-Aktionen (auch keine Trial-Klicks), kein `isEditable`/`inputValue`/`scrollIntoView`, also keine `__playwright_mark_target__`-Events und kein Skript-Scrollen ([playwright4j#38](https://github.com/aresstack/playwright4j/issues/38), #42). Zwei kurze, nur lesende Auswertungen bleiben: `elementFromPoint` für die Verdeckung und die Editierbarkeit des Feldes (wie in PR #7), dazu höchstens einmal die Fenstergröße, wenn der Fingerprint sie nicht enthält.

## Herkunft

- **Enterprise AI Client** (`browser-firefox/HumanInput`, `InputActions`): quadratische Kurve mit seitlichem Kontrollpunkt, Gauß-Zittern, Tastenpausen 50–170 ms mit gelegentlichem Zögern, Startpunkt im oberen Seitenbereich, „Lesebewegungen“. Übernommen als Idee und verfeinert (Fitts-Dauer, Minimum-Jerk, abklingendes Zittern, Überschießen, Eintritt über die Kante statt Sprung). Der WD4J-Code selbst wird nicht übernommen.
- **AskAI** (`arch`) und **MainframeMate** spielen Aufzeichnungen mit festen Intervallen ab bzw. bewegen die Maus direkt (`mouse.move(x, y)`). Genau das ersetzt diese Schicht.

## Tests

- `HumanMotionTest`, `HumanTypingTest`, `HumanInputSettingsTest`, `RealInputTest`: Bahn endet exakt auf dem Ziel, ganze Pixel, keine Sprünge, im Fenster, gekrümmt, Fitts-Verhalten, Seed-Reproduzierbarkeit, Klickpunkt im inneren Bereich, Shift genau um Großbuchstaben, alle Tasten wieder losgelassen, Tippfehler korrigiert, Budget, Unicode-Strategien inkl. Löschen bei `strict`.
- `HumanInputBrowserTest` (opt-in, `-PtestHumanBrowser=<Pfad zu camoufox, firefox oder chromium>`): Die Seite zeichnet mit eigenen Listenern auf und prüft: nur `isTrusted`, Eintritt an der oberen Kante, kein Sprung über 150 px, Scrollen per `wheel`, `H` mit `shiftKey`, keine Playwright-Marker-Events. Danach `Grüße äöüÄÖÜß€`: ohne echten Unicode-Pfad muss die Eingabe abgelehnt werden, ohne dass eine einzige Taste gedrückt oder das Feld verändert wird; mit echtem Pfad müssen Feldwert, `event.key` und `isTrusted` stimmen.
