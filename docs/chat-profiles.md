# Browserprofile je Unterhaltung oder MCP-Session

Argos ist ein clientneutraler MCP-Dienst. Jeder `tools/call` wird einem isolierten Arbeitsbereich zugeordnet
(eigenes Camoufox-Profil, eigene Kontexte, eigene Search History). Die Zuordnung übernimmt `McpCallerResolver`;
alle Werkzeuge einschließlich `browser_context_*` fragen denselben Resolver.

## Zuordnung

| Reihenfolge | Quelle | Arbeitsbereich | Lebensdauer |
|---|---|---|---|
| 1 | `_meta["openai/session"]` (ChatGPT, [OpenAI-Referenz](https://developers.openai.com/plugins/reference#_meta-fields-the-client-provides)) | je Unterhaltung, Verzeichnis `<hash>` | dauerhaft, auch nach Reload, Reconnect und Neustart |
| 2 | MCP-Transport-Session (`Mcp-Session-Id`, von Argos bei `initialize` vergeben) plus verifizierter Principal | je Verbindung, Verzeichnis `s-<hash>` | bis zum Ende der Session; nach einem Argos-Neustart gelöscht |
| 3 | keine von beiden | – | Aufruf wird abgelehnt |

- **ChatGPT:** Der Schlüssel ist unverändert derselbe Hash wie vor der Umstellung. Bestehende Profile werden weiter benutzt.
- **Claude und andere MCP-Clients:** Claude schickt keine Unterhaltungskennung mit. Belastbar vorhanden sind nur die
  Transport-Session, die das MCP Java SDK 0.18.4 als `exchange.sessionId()` liefert, und `clientInfo` aus `initialize`.
  Argos isoliert deshalb je Transport-Session. Das ist eine Verbindung, nicht zwingend eine Unterhaltung:
  Verbindet sich der Client neu, beginnt er in einem neuen, leeren Arbeitsbereich (keine Cookies, keine Tabs, eigene
  Search History). Eine Wiederverbindung wird nie als dieselbe Unterhaltung angesehen.
- **Principal:** Der Session-Schlüssel enthält den Principal, den `ArgosMcpAuthorizationFilter` aus dem geprüften Token
  gelesen hat (`iss` + `sub`, sonst `client_id`; mit `SECURITY_MODE=none` `anonymous`). Er gelangt über den
  Transport-Kontext des SDK (`contextExtractor` → `exchange.transportContext()`) zum Handler, nie über einen Header
  oder ein Argument. Wer eine fremde Session-ID kennt, landet mit einem anderen Principal in einem anderen Arbeitsbereich.
  Fehlt der Principal, wird der Aufruf abgelehnt (auch im ChatGPT-Pfad).
- **Eigentümer jedes Arbeitsbereichs:** `_meta["openai/session"]` ist Client-Input und kein Nachweis, dass ChatGPT
  aufruft. Deshalb gehört jeder Arbeitsbereich zusätzlich dem verifizierten Principal, der ihn zuerst benutzt hat.
  Argos legt dafür `<hash>/owner` mit einem Hash des Principals an (nie den Principal selbst). Ein anderer Principal
  mit derselben Unterhaltungs-ID wird abgelehnt („belongs to another signed-in account“) und sieht weder Cookies
  noch History noch Profil. Gilt ebenso für `s-`-Arbeitsbereiche.
- **Migration bestehender ChatGPT-Profile:** Verzeichnis und Hash bleiben unverändert. Profile ohne `owner`-Datei
  übernimmt beim ersten Aufruf nach dem Update der verifizierte Principal dieses Aufrufs; danach ist der Besitz fest.
  Ändert sich der Principal eines Kontos (z. B. anderer OAuth-Client oder anderer Issuer), sind seine alten
  Arbeitsbereiche gesperrt. Freigeben lässt sich ein Arbeitsbereich nur bewusst durch Löschen seiner `owner`-Datei.
  Hinweis: AnAuthentia setzt `sub` gleich der Client-ID. ChatGPT und Claude über denselben OAuth-Client `angel` sind
  also derselbe Principal. Getrennt sind sie trotzdem, weil ChatGPT-Arbeitsbereiche nur über die Unterhaltungs-ID und
  Claude-Arbeitsbereiche nur über die eigene MCP-Session erreichbar sind.
- **Keine Kollisionen:** ChatGPT-Schlüssel und Session-Schlüssel werden über verschiedene Domänen-Strings gehasht, und
  Session-Verzeichnisse beginnen mit `s-`. ChatGPT und Claude teilen nie ein Profil, auch nicht mit demselben OAuth-Client.
- Nicht verwendet werden `openai/subject` (das ist der Benutzer, keine Unterhaltung), `clientInfo.name` (vom Client frei
  gewählt; erscheint nur bereinigt im Log) und Tool-Argumente.
- **Aufräumen:** MCP-Sessions existieren nur im Speicher des laufenden Servers. Beim Start löscht Argos deshalb alle
  `s-`-Verzeichnisse, weil sie kein Client mehr erreichen kann. ChatGPT-Profile bleiben unberührt.

> **Einschränkung für Claude und andere Clients ohne Unterhaltungs-ID:** Ihr Arbeitsbereich ist ein temporärer,
> sicherer Fallback. Profil, Cookies, Kontexte **und Search History (einschließlich Exporte unter `history/exports`)**
> leben nur so lange wie die MCP-Session: Nach einem Reconnect beginnt ein neuer Arbeitsbereich, und beim Neustart von
> Argos werden alle `s-`-Verzeichnisse gelöscht. Eine dauerhafte Search History wie bei ChatGPT gibt es für diese
> Clients erst, wenn sie eine stabile, verifizierbare Unterhaltungskennung mitschicken.

> **Geltungsgrenze (Einzelbenutzer-Setup):** Mit der aktuellen AnAuthentia-Tokenausstellung ist `sub` die OAuth-`client_id`
> (z. B. `angel`), kein Endnutzerkonto. Die `owner`-Datei trennt deshalb verschiedene OAuth-Principals, aber **nicht**
> mehrere Menschen, die dieselbe Client-ID verwenden. Außerdem bleibt `_meta["openai/session"]` Client-Eingabe: Ein
> anderer Client mit derselben OAuth-Identität, der eine Conversation-ID kennt, erreicht denselben ChatGPT-Arbeitsbereich.
> Für ein selbst betriebenes Setup mit einem Benutzer ist das vertretbar. Vor einer Mehrbenutzer- oder Mandantenfreigabe
> muss eine echte serverseitige Benutzer- bzw. Clientbindung (eigene `sub` je Benutzer, getrennte Clients je Anwendung)
> diese Annahme ersetzen.

Ein Client mit nachweislich stabiler Unterhaltungskennung kann später als weitere Quelle vor Schritt 2 eingetragen werden.

## Profile und Kontexte

Wie im AskAI-Research-Plugin (`ResearchAgentSessionFactory`: `getWorkspaceDirectory(sessionId)` → `createSession(sessionId, dir)`) besitzt jeder Arbeitsbereich ein eigenes Verzeichnis, das dem Browser beim Start übergeben wird:

```
~/.argos/profiles/chats/<sha256-Präfix der Kennung>/   (bei MCP-Sessions: s-<sha256-Präfix>/)
    profile/                         Camoufox-Profil der Unterhaltung (launchPersistentContext), Kontext "start"
    contexts.properties              Kontexte + aktiver Kontext
    contexts/context-2.cookies.json  Cookies des User-Contexts (über das Protokoll gelesen/geschrieben)
```

- Je Unterhaltung läuft ein Camoufox. Höchstens `ARGOS_MAX_BROWSERS` (Standard 2) Browser laufen gleichzeitig: Bevor ein weiterer startet, wird der am längsten ungenutzte vollständig beendet. Nach `ARGOS_BROWSER_IDLE_MINUTES` ohne Aufruf wird ein Browser ebenfalls beendet. Das Profil bleibt erhalten.
- `browser_context_new` legt im Browser der Unterhaltung einen WebDriver-BiDi-User-Context an (Playwright `browser.newContext()`, in Firefox ein Container) mit eigenen Cookies, Storage und Tabs. `browser_context_select` wechselt, `browser_contexts` listet. Kontext-IDs vergibt der Server. Unbekannte IDs und IDs anderer Unterhaltungen werden abgelehnt.
- Persistenz: Firefox speichert Container im Profil (`containers.json`, Cookies mit `userContextId`). Playwright entfernt den Container aber beim Schließen des Kontexts (`browser.removeUserContext`) und kann nach einem Neustart keinen vorhandenen Container übernehmen. Deshalb sichert Argos die Cookies jedes zusätzlichen Kontexts nach jedem Aufruf über das Protokoll (`context.cookies()`) und lädt sie beim Neuanlegen wieder (`addCookies`). localStorage übersteht einen Neustart nicht.

## Konfiguration

| Variable | Property | Standard |
|---|---|---|
| `ARGOS_PROFILE_ISOLATION` | `browser.profile-isolation` | `chat` = Arbeitsbereich je ChatGPT-Unterhaltung oder MCP-Session (alle Clients); `single` = ausdrücklicher Legacy-Modus mit einem gemeinsamen Profil `ARGOS_PROFILE_DIR` für alle Aufrufer, ohne Search History |
| `ARGOS_CHAT_PROFILES_DIR` | `browser.chat-profiles` | `~/.argos/profiles/chats` |
| `ARGOS_MAX_BROWSERS` | `browser.max-browsers` | `2` (je Browser ein Camoufox-Prozess plus ein Playwright4J-GraalJS-Treiber in der Argos-JVM) |
| `ARGOS_BROWSER_IDLE_MINUTES` | `browser.idle-minutes` | `10`; 0 = nie wegen Leerlauf beenden |

## Diagnose

Auf stderr erscheint pro Aufruf eine Zeile
`[argos-profiles] <tool> source=<chatgpt|mcp-session> client=<clientInfo.name> chat=<10 Zeichen des Schlüssels> context=<id>`.
Fehlt jede Identität, lautet die Zeile `refused <tool>: no caller identity`. Vollständige Kennungen, Session-IDs,
Principals, Tokens, Pfade, Cookies und Seiteninhalte werden nicht geloggt.

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
8. Aus Claude (ohne `openai/session`) `web_search`, `web_read_tab`, `web_screenshot` und `search_history` aufrufen, ohne `single` zu setzen:
   Alles funktioniert, das Log zeigt `source=mcp-session client=…` und einen `chat=`-Wert, der mit `s-` beginnt und sich von allen ChatGPT-Werten unterscheidet.
   Ein zweiter Claude-Chat bzw. eine neue Verbindung zeigt einen anderen `chat=`-Wert.
   Ändert sich der Wert schon zwischen zwei Aufrufen derselben Unterhaltung, baut der Client je Aufruf eine neue Session auf; das ist dann im Log sichtbar.
9. Regressionen: die bisherigen Abnahmeskripte mit `ARGOS_PROFILE_ISOLATION=single` ausführen (Kriterium 7).
