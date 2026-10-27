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

```
~/.argos/profiles/chats/<sha256-Präfix der Kennung>/
    contexts.properties      Liste der Kontexte + aktiver Kontext
    start/                   Startprofil der Unterhaltung (Camoufox-Profilverzeichnis)
    context-2/ ...           zusätzliche User-Kontexte
```

- Jeder Kontext hat ein eigenes persistentes Profilverzeichnis, das von einem eigenen Camoufox-Prozess genutzt wird
  (`launchPersistentContext`). Cookies und Local Storage werden deshalb nie geteilt.
- Jedes Profil bekommt den unveränderten Argos-Tool-Stapel: `CamoufoxBrowser`, `WebSession` und die Dokumente.
  `ChatRoutedTools` wählt lediglich aus, welcher Stapel antwortet.
- Der Fingerprint stammt für alle Profile aus derselben `launch.json`.
- Mehr als `ARGOS_MAX_BROWSERS` (Standard 4) gleichzeitig laufende Browser werden im Leerlauf nach LRU geschlossen.
  Das Profil bleibt erhalten, und der nächste Aufruf startet den Browser neu.
- Tools (nur im Profil `expert`): `browser_context_new`, `browser_contexts`, `browser_context_select {"context": "context-2"}`.
  Kontext-IDs vergibt der Server. Unbekannte IDs und IDs anderer Unterhaltungen werden abgelehnt.

## Konfiguration

| Variable | Property | Standard |
|---|---|---|
| `ARGOS_PROFILE_ISOLATION` | `browser.profile-isolation` | `chat`; `single` = altes Einzelprofil `ARGOS_PROFILE_DIR` für Clients ohne `openai/session` (z. B. Claude) |
| `ARGOS_CHAT_PROFILES_DIR` | `browser.chat-profiles` | `~/.argos/profiles/chats` |
| `ARGOS_MAX_BROWSERS` | `browser.max-browsers` | `4` |

## Diagnose

Auf stderr erscheint pro Aufruf eine Zeile `[argos-profiles] <tool> chat=<10 Hex-Zeichen des Hashes> context=<id>`.
Fehlt die Kennung, lautet die Zeile `refused <tool>: no openai/session`. Vollständige Kennungen, Pfade, Cookies
und Seiteninhalte werden nicht geloggt.

## Abnahme mit dem echten ChatGPT-Plugin

1. Argos von `develop` starten (Standard `ARGOS_PROFILE_ISOLATION=chat`) und in ChatGPT die Verbindung aktualisieren,
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
9. Regressionen: die bisherigen Abnahmeskripte mit `ARGOS_PROFILE_ISOLATION=single` ausführen (Kriterium 7).
