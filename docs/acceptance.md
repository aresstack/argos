# Gesamtabnahme mit Playwright4J 0.3.0

Dieses Dokument bereitet die gemeinsame Abnahme der Arbeitspakete 1 bis 6 vor. Die Abnahme
beginnt erst, wenn alle sechs Pakete in einem Integrationsstand zusammengeführt sind.

## Playwright4J-Stand

- Argos `main` (878d8e5, "Update Argos to Playwright4J 0.3.0") bindet `com.aresstack:playwright4j:0.3.0`
  ein. CI baut sie aus `aresstack/playwright4j` Commit `2e889ce9` ("Merge develop into master for
  v0.3.0 local integration") mit `publishToMavenLocal -Pversion=0.3.0`.
- `2e889ce9` enthält `fe129492` (#73, `keyboard.down/up/press` für ein Graphem über BiDi), den Pin aus
  Argos PR #18. Außerdem #74 (BiDi-Ereignisreihenfolgen), #75 (späte iframe-Namen) und #72
  (Windows-Prozessbaum, Launch-Timeout 180 s). Der Revert von #59 (2aea2e4) ist enthalten.
- Damit ist keine Pin-Änderung nötig. PR #18 wird mit dem 0.3.0-Pin gemergt und bringt nur den verschärften `HumanInputBrowserTest` und Doku mit.
- Vorschlag (nicht umgesetzt): Wird auf Windows "0.3.0 aus Maven Local" benutzt, vorher prüfen, dass
  das lokale Artefakt aus `2e889ce9` gebaut wurde. Eine Gradle-Version `0.3.0` sagt nichts über den
  Commit. Im Zweifel `-Pplaywright4jSource=<checkout auf 2e889ce9>` benutzen; das setzt automatisch
  `expectUnicodeKeys=true`.

Bibliotheksseitige Referenztests (in Playwright4J, nicht in Argos): `FirefoxNaturalPointerTest`,
`FirefoxNativeInputTest`, `FirefoxKeyboardGraphemeTest`, `TestWheel`. Sie zeigen, dass die Bibliothek
die Eingaben kann, aber nicht, dass Argos sie korrekt als Werkzeuge anbietet.

## Testmatrix

| Umgebung | Browser | Wer | Pflicht |
|---|---|---|---|
| Linux-Container (Cloud-Session) | Camoufox 156.0.1-beta.36 Linux | Claude | ja |
| Angelos Windows-Rechner | gepatchtes Camoufox 156.0.1-beta.36 Windows | Angelo | ja |
| GitHub Actions `build-release.yml` | ohne Browser (Unit-Tests, Build) | CI | ja |
| Modellbewertung `evalModels` | Ollama gemma4:e2b, gpt-oss:20b | Angelo | nur bei Änderungen an Werkzeugtexten oder Profilen |

Pro Umgebung zwei Läufe mit identischen Optionen: Referenz auf `main` und Integrationsstand.

```bash
# Referenz (Stand vor den Paketen)
git checkout <main-sha> && scripts/acceptance/run-suite.sh baseline-main
# Integrationsstand
git checkout <integration-sha> && scripts/acceptance/run-suite.sh integration-<sha>
# Vergleich
python3 scripts/acceptance/summarize.py --compare \
  build/acceptance/baseline-main/summary.json build/acceptance/integration-<sha>/summary.json
```

`run-suite.sh` erwartet `ARGOS_CAMOUFOX_EXECUTABLE` (Datei heißt `camoufox`, nicht `camoufox-bin`) und
`ARGOS_CAMOUFOX_LAUNCH_OPTIONS` (launch.json, `env` nur `CAMOU_*`/`FONTCONFIG*`). Es setzt
`-PtestCamoufox=true -PexpectUnicodeKeys=true`, damit eine abgelehnte Unicode-Taste als Fehler zählt.
Unter Windows in Git Bash ausführen. Ergebnisse liegen unter `build/acceptance/<label>/` mit
`environment.txt` (Commit, Java, Browser), `gradle.log`, JUnit-XML und `summary.json`.

## Alte Fehler von neuen Regressionen unterscheiden

1. Die Referenz läuft auf derselben Maschine, mit demselben Browser und denselben Optionen wie der
   Integrationsstand. Nur so ist ein Unterschied dem Code zuzuschreiben.
2. `summarize.py --compare` ordnet jeden Test ein:
   - **Regression**: lief in der Referenz grün und schlägt jetzt fehl, oder ein neuer Test schlägt fehl.
   - **Fehlt**: die Referenz hat ihn ausgeführt, der Integrationsstand nicht (gelöscht oder nicht mehr gefunden).
   - **vorbestehend**: schlägt in beiden Läufen fehl.
   - **behoben**: schlug in der Referenz fehl und läuft jetzt grün.
   Exit-Code 1 bei Regression oder fehlendem Test.
3. Eine Regression, die beim zweiten Lauf nicht wiederkommt, ist instabil, nicht grün. Sie wird mit
   beiden Läufen in der Tabelle unten eingetragen. Tests werden nie deaktiviert, um grün zu werden.
4. Ein vorbestehender Fehler blockiert die Abnahme nicht, wenn er in der Liste unten steht. Ein
   Paket, das ihn beheben soll (z. B. Paket 5), muss ihn als "behoben" zeigen.

## Bekannte vorbestehende Fehler und Einschränkungen

| Test / Thema | Umgebung | Stand | Zuständig |
|---|---|---|---|
| `ParallelPageToolsBrowserTest` | wird bei Angelo gemeldet | in Bearbeitung, Paket 5 muss ihn als "behoben" zeigen | Thread "Parallele Seiten und Tabs absichern" |
| Referenzlauf `main` 878d8e5 ohne Browser (`gradlew test`) | Linux-Container | 563 grün, 0 Fehler, 23 übersprungen (Browsertests) | — |
| Screenshots und `page.content()` sind nicht spurenfrei (pw4j #37, #40) | alle | offen in Playwright4J | Playwright4J |
| `fill()`/`selectOption()` erzeugen untrusted Events (pw4j #41) | alle | offen, Argos meidet sie | Playwright4J |
| `newPage()` auf Windows zeitweise ~4 s pro Tab | Windows | Ursache ungeklärt, nur Laufzeit | — |

Vor der Abnahme ergänzt der Referenzlauf jeder Umgebung diese Tabelle.

## Abnahmekriterien pro Paket

Allgemein für jedes Paket: keine Regression laut Vergleich auf Linux und Windows; keine Preload-/Init-
Skripte, kein `exposeBinding`, keine DOM-Marker oder Seiten-Globals, keine Locator-Aktionen; Eingaben
nur über echte BiDi-Maus/Tastatur; Werkzeuge sind in den Profilen (legacy/compact/expert) so sichtbar
wie vorgesehen und `ToolProfileTest` bestätigt das; Doku in `docs/` beschreibt neue Werkzeuge.

| Paket | Inhalt | Zusätzliche Kriterien |
|---|---|---|
| 1 | Allgemeine Maussteuerung | Bewegen, Klicken (links/rechts/mittel, Doppelklick), Drücken/Loslassen, Mausrad gehen als echte BiDi-Ereignisse auf einer lokalen Testseite ein (`isTrusted`, Reihenfolge); Mausrad-Distanz exakt; Koordinaten außerhalb des Viewports werden abgelehnt |
| 2 | Tastatursteuerung | Einzeltasten, Kombinationen, Text mit Umlauten als echte Tasten; mit `expectUnicodeKeys=true` keine Ablehnung; `HumanInputBrowserTest`, `UnicodeInputCamoufoxTest` grün |
| 3 | Screenshots für den Agenten | Bild für Seite/Ausschnitt/Element; Größe begrenzt; dokumentiert, dass Screenshots das DOM berühren (pw4j #40), falls nicht vermieden |
| 4 | Human-Input-Strategie aus AskAI | wiederverwendbare Logik, deterministische Tests (fester Seed), keine unbeabsichtigte Änderung der elementbasierten Eingaben (`HumanMotionTest`, `RealInputTest`, `HumanInputBrowserTest` unverändert grün). Status: Empfehlung kein Übertrag (HumanMotion ist bereits weiter), höchstens kleine Pausen-Ergänzungen nach Paket 1; Entscheidung offen |
| 5 | Mehrere Seiten und Tabs | Parallelität nachgewiesen (Laufzeit gegen sequenziell gemessen); unabhängige Ergebnisse und Fehler je Seite; ursprünglicher Tab wiederhergestellt, temporäre Tabs geschlossen; `ParallelPageToolsBrowserTest` stabil grün auf Linux und Windows (zwei Läufe hintereinander) |
| 6 | Drag-and-Drop (`browser_drag`, Draft-PR #27) | echte `pointerdown`/`mousedown`, Bewegung und `pointerup`/`drop`-Ereignisse auf einer kontrollierten Testseite (`DragToolsBrowserTest`), alle `isTrusted`; Ziel erhält das Element; keine synthetischen DragEvents per Skript |
| 7 | Gesamtabnahme | alle Zeilen oben erfüllt; Vergleich Linux und Windows ohne Regression; Ergebnisse (Zähler, SHA, Browser) in der Integrations-PR |

## Ablauf der Gesamtabnahme

1. Pakete 1 bis 6 in einen Integrationsbranch von `main` zusammenführen, Konflikte dokumentieren.
2. CI grün.
3. Linux: Referenz `main` und Integrationsstand, Vergleich.
4. Windows (Angelo): dieselben zwei Läufe, `build/acceptance/` als Bericht.
5. Optional `evalModels`, wenn Werkzeugbeschreibungen geändert wurden.
6. Ergebnis in der Integrations-PR. Kein Merge ohne Angelos ausdrückliche Freigabe.
