package com.aresstack.argos;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.List;
import java.util.Map;

import static com.aresstack.argos.ArgosTools.field;
import static com.aresstack.argos.ArgosTools.flag;
import static com.aresstack.argos.ArgosTools.number;
import static com.aresstack.argos.ArgosTools.optionalString;
import static com.aresstack.argos.ArgosTools.string;
import static com.aresstack.argos.ArgosTools.tool;

/**
 * Expert-profile network analysis on top of browser_network. Everything returned
 * is masked by SecretMasker; observation happens only through protocol events.
 */
final class NetworkTools {
    private final CamoufoxBrowser browser;

    NetworkTools(CamoufoxBrowser browser) {
        this.browser = browser;
    }

    List<SyncToolSpecification> expertTools() {
        return List.of(
                tool("browser_network_json", "List the JSON data the page loaded in the background (XHR/fetch APIs), newest first, "
                                + "with id, URL and top-level keys, e.g. {results[20], total}. Read one fully with "
                                + "browser_network_detail and body=true. Captured invisibly by the browser. Example: {\"filter\":\"search\"}",
                        Map.of("filter", field("string", "Optional text the URL must contain"),
                                "limit", field("integer", "Maximum responses (1–20, default 10)"),
                                "tab", field("string", "Optional tab id from browser_tabs, e.g. t2")),
                        List.of(), args -> browser.networkJson(optionalString(args, "filter"),
                                number(args, "limit", 10), optionalString(args, "tab"))),
                tool("browser_network_detail", "Show one request from browser_network by id: full URL, status, MIME type, "
                                + "timing, request/response headers and POST data. Secrets (cookies, tokens, passwords) are "
                                + "masked. Set body=true to include the text response body. Example: {\"id\":\"n12\",\"body\":true}",
                        Map.of("id", field("string", "Request id from browser_network, e.g. n12"),
                                "body", field("boolean", "Include the response body for text/JSON/XML responses (default false)"),
                                "tab", field("string", "Optional tab id from browser_tabs; reject ids from other tabs")),
                        List.of("id"), args -> browser.networkDetail(string(args, "id"), flag(args, "body"),
                                optionalString(args, "tab"))),
                tool("browser_network_har", "Export the newest recorded requests as HAR 1.2 JSON with masked secrets. "
                                + "Bodies appear only if already captured. Example: {\"limit\":30}",
                        Map.of("limit", field("integer", "Newest entries to export (1–200, default 50)"),
                                "tab", field("string", "Optional tab id from browser_tabs, e.g. t2")),
                        List.of(), args -> browser.networkHar(number(args, "limit", 50), optionalString(args, "tab"))),
                tool("browser_network_clear", "Forget all recorded requests, e.g. before an action you want to observe in isolation.",
                        Map.of(), List.of(), args -> browser.clearNetwork()));
    }
}
