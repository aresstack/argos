package com.aresstack.argos;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

import java.util.Map;

/**
 * Which isolated browser workspace a {@code tools/call} belongs to, independent of the MCP client. The identity
 * comes only from what the server itself verified or what the protocol carries, never from a tool argument or an
 * unchecked HTTP header:
 * <ol>
 *     <li>{@code _meta["openai/session"]} (ChatGPT): one persistent workspace per conversation. The key is the same
 *     hash as before, so existing ChatGPT profiles keep working. {@code _meta} is client input and proves nothing:
 *     the workspace is additionally owned by the verified principal that first used it (see
 *     {@link ChatProfiles}), and any other principal is refused.</li>
 *     <li>Otherwise the MCP transport session ({@code Mcp-Session-Id}, issued by this server at {@code initialize})
 *     bound to the authenticated principal: one workspace per transport session. It isolates the connection, not
 *     necessarily the conversation: a client that reconnects gets a new session and therefore a new, empty
 *     workspace, and after an Argos restart no old session can be resumed (see
 *     {@link ChatProfiles#removeTransportWorkspaces()}).</li>
 *     <li>Neither: the call is refused. There is no shared default workspace.</li>
 * </ol>
 * The namespaces never collide: ChatGPT keys are plain hashes, transport keys start with
 * {@link ChatProfiles#TRANSPORT_PREFIX} and hash a different domain string.
 */
public final class McpCallerResolver {
    /** Transport context key under which {@link ArgosMcpAuthorizationFilter} hands over the verified principal. */
    public static final String PRINCIPAL = "argos.principal";

    private McpCallerResolver() { }

    /** The workspace of a call as the MCP Java SDK delivers it to the tool handler. */
    public static ChatProfiles.Chat resolve(McpSyncServerExchange exchange, CallToolRequest request) {
        String sessionId = null;
        Object principal = null;
        String client = null;
        if (exchange != null) {
            sessionId = exchange.sessionId();
            McpTransportContext context = exchange.transportContext();
            principal = context == null ? null : context.get(PRINCIPAL);
            client = exchange.getClientInfo() == null ? null : exchange.getClientInfo().name();
        }
        return resolve(request.meta(), sessionId, principal instanceof String text ? text : null, client);
    }

    /**
     * @param meta      the request's {@code _meta}
     * @param sessionId the transport session the SDK assigned, or null
     * @param principal the principal the authorization filter verified, or null
     * @param client    {@code clientInfo.name} from {@code initialize}; only shown in diagnostics, never a key
     */
    static ChatProfiles.Chat resolve(Map<String, Object> meta, String sessionId, String principal, String client) {
        boolean verified = principal != null && !principal.isBlank();
        Object conversation = meta == null ? null : meta.get(ChatProfiles.SESSION_META_KEY);
        if (conversation instanceof String session && !session.isBlank()) {
            if (!verified) throw unverified();
            return new ChatProfiles.Chat(ChatProfiles.hash(session.strip()), "chatgpt", label(client),
                    ChatProfiles.ownerOf(principal));
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new ChatProfiles.MissingChatIdentity("Argos could not identify this caller: the call carries no "
                    + "conversation id and no MCP session (Mcp-Session-Id). Argos keeps an isolated browser "
                    + "profile per conversation or MCP session and has no shared default profile, so the call "
                    + "was refused. Reconnect the client so that it sends initialize again.");
        }
        if (!verified) throw unverified();
        return new ChatProfiles.Chat(ChatProfiles.transportKey(principal, sessionId.strip()), "mcp-session",
                label(client), ChatProfiles.ownerOf(principal));
    }

    private static ChatProfiles.MissingChatIdentity unverified() {
        return new ChatProfiles.MissingChatIdentity("Argos could not identify this caller: the call has no "
                + "verified principal, so it was refused.");
    }

    /** {@code clientInfo.name} is client supplied: only a short, harmless form ever reaches a log line. */
    static String label(String client) {
        if (client == null || client.isBlank()) return "unknown";
        String cleaned = client.strip().replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() <= 32 ? cleaned : cleaned.substring(0, 32);
    }
}
