# Browserprofile je ChatGPT-Unterhaltung

## Zuordnung

ChatGPT schickt bei jedem `tools/call` die Unterhaltungskennung in `_meta["openai/session"]`
([OpenAI-Referenz](https://developers.openai.com/plugins/reference#_meta-fields-the-client-provides)).
Argos liest sie über `CallToolRequest.meta()` des MCP Java SDK 0.18.4 (`ChatRoutedTools.route`).
Andere Quellen werden nicht verwendet:

- `openai/subject` ist der Benutzer und kein Schlüssel für eine Unterhaltung.
- `Mcp-Session-Id` (`exchange.sessionId()`) ist die Transport-Session und kein Schlüssel.
- Tool-Argumente kommen nie als Schlüssel in Frage.

Fehlt die Kennung, liefert der Aufruf einen Fehler. Es wird kein Browser gestartet, und es gibt keinen Rückfall auf ein gemeinsames Profil.

## Profile und Kontexte

Wie im AskAI-Research-Plugin (`ResearchAgentSessionFactory`: `getWorkspaceDirectory(sessionId)` → `createSession(sessionId, dir)`) besitzt jede Unterhaltung ein eigenes Verzeichnis, das dem Browser beim Start übergeben wird:

```
~/.argos/profiles/chats/<sha256-Präfix der Kennung>/
    profile/                         Camoufox-Profil der Unterhaltung (launchPersistentContext), Kontext "start"
    contexts.properties              Kontexte + aktiver Kontext
    contexts/context-2.cookies.json  Cookies des User-Contexts (über das Protokoll gelesen/geschrieben)
```

- Je Unterhaltung läuft ein Camoufox. Höchstens `MAX_BROWSERS` (Standard 2) Browser laufen gleichzeitig: Bevor ein weiterer startet, wird der am längsten ungenutzte vollständig beendet. Nach `BROWSER_IDLE_MINUTES` ohne Aufruf wird ein Browser ebenfalls beendet. Das Profil bleibt erhalten.
- `browser_context_new` legt im Browser der Unterhaltung einen WebDriver-BiDi-User-Context an (Playwright `browser.newContext()`, in Firefox ein Container) mit eigenen Cookies, Storage und Tabs. `browser_context_select` wechselt, `browser_contexts` listet. Kontext-IDs vergibt der Server. Unbekannte IDs und IDs anderer Unterhaltungen werden abgelehnt.
- Persistenz: Firefox speichert Container im Profil (`containers.json`, Cookies mit `userContextId`). Playwright entfernt den Container aber beim Schließen des Kontexts (`browser.removeUserContext`) und kann nach einem Neustart keinen vorhandenen Container übernehmen. Deshalb sichert Argos die Cookies jedes zusätzlichen Kontexts nach jedem Aufruf über das Protokoll (`context.cookies()`) und lädt sie beim Neuanlegen wieder (`addCookies`). localStorage übersteht einen Neustart nicht.

## Konfiguration

| Variable | Property | Standard |
|---|---|---|
| `PROFILE_ISOLATION` | `browser.profile-isolation` | `chat`; `single` = altes Einzelprofil `BROWSER_PROFILE_DIR` für Clients ohne `openai/session` (z. B. Claude) |
| `CHAT_PROFILES_DIR` | `browser.chat-profiles` | `~/.argos/profiles/chats` |
| `MAX_BROWSERS` | `browser.max-browsers` | `2` (je Browser ein Camoufox-Prozess plus ein Playwright4J-GraalJS-Treiber in der Argos-JVM) |
| `BROWSER_IDLE_MINUTES` | `browser.idle-minutes` | `10`; 0 = nie wegen Leerlauf beenden |

## Diagnose

Auf stderr erscheint pro Aufruf eine Zeile `[argos-profiles] <tool> chat=<10 Hex-Zeichen des Hashes> context=<id>`.
Fehlt die Kennung, lautet die Zeile `refused <tool>: no openai/session`. Vollständige Kennungen, Pfade, Cookies
und Seiteninhalte werden nicht geloggt.

## Abnahme mit dem echten ChatGPT-Plugin

1. Argos von `develop` starten (Standard `PROFILE_ISOLATION=chat`) und in ChatGPT die Verbindung aktualisieren,
   damit die neuen Tools erscheinen.
2. Unterhaltung A: zwei beliebige Tools nacheinander aufrufen lassen, z. B. `web_open https://example.org` und `web_read`.
   Im Log müssen beide Zeilen denselben `chat=`-Wert und `context=start` zeigen (Kriterien 1 und 2).
3. In Unterhaltung A ChatGPT neu laden oder später fortsetzen, sodass eine neue Transport-Session entsteht, und erneut ein Tool aufrufen.
   Der `chat=`-Wert muss gleich bleiben (Kriterium 3).
4. Unterhaltung B: ein Tool aufrufen. Der `chat=`-Wert muss sich von A unterscheiden.
   Unter `~/.argos/profiles/chats/` gibt es dann zwei Verzeichnisse.
5. In A auf einer Seite mit Login anmelden. In B dieselbe Seite öffnen: Dort ist man nicht angemeldet.
6. In A `browser_context_new` aufrufen und dieselbe Seite öffnen: nicht angemeldet. Dort mit einem zweiten Konto anmelden.
   Danach Argos neu starten, `browser_contexts` aufrufen (context-2 ist aktiv) und die Seite öffnen: Das zweite Konto ist noch angemeldet.
   Mit `browser_context_select start` zurückwechseln: Dort ist das erste Konto angemeldet (Kriterium 4).
7. In B `browser_context_select context-2` aufrufen: Das muss mit einem Fehler „Unknown context“ enden (Kriterium 5).
8. Aus einem Client ohne `openai/session` (z. B. MCP Inspector oder Claude) ein Tool aufrufen: Fehler mit Hinweis auf `openai/session` (Kriterium 6).
9. Regressionen: die bisherigen Abnahmeskripte mit `PROFILE_ISOLATION=single` ausführen (Kriterium 7).
