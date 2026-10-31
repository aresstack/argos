package com.aresstack.argos;

import com.aresstack.argos.history.SearchHistory;
import com.aresstack.argos.convenience.ConvenienceSession;
import com.aresstack.argos.convenience.DomainCoordinator;
import com.aresstack.argos.history.SearchHistoryCapture;
import com.aresstack.argos.search.ParallelWebSearch;
import com.aresstack.argos.history.SearchHistoryDownloadServlet;
import com.aresstack.argos.history.SearchHistoryDownloads;
import com.aresstack.argos.history.SearchHistorySettings;
import com.aresstack.argos.research.ResearchSettings;
import com.aresstack.argos.search.BrowserWebSearch;
import com.aresstack.argos.web.UrlPolicy;
import com.aresstack.argos.web.WebSession;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import jakarta.servlet.DispatcherType;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aresstack.argos.document.DocumentStore;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.eclipse.jetty.server.Server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Standalone managed JAR: Streamable HTTP MCP is provided by the official Java SDK. */
public final class ArgosApplication {
    private ArgosApplication() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--verify-runtime".equals(args[0])) {
            // Verify the real Playwright4J subprocess from the *packaged* JAR.
            // /health alone would miss missing driver classes and nested-classpath errors.
            try (com.microsoft.playwright.Playwright playwright = com.microsoft.playwright.Playwright.create()) {
                if (playwright.firefox() == null) {
                    throw new IllegalStateException("Playwright4J Firefox/BiDi browser type is unavailable");
                }
            }
            System.out.println("ARGOS_RUNTIME_OK");
            return;
        }
        ArgosConfig config = ArgosConfig.fromEnvironment();
        ArgosConfig.ProfileIsolation isolation = ArgosConfig.ProfileIsolation.fromEnvironment();
        SearchHistorySettings historySettings = SearchHistorySettings.fromEnvironment();
        List<SyncToolSpecification> tools;
        AutoCloseable browsers;
        if (isolation.perChat()) {
            // One isolated profile (and Camoufox process) per caller workspace: a ChatGPT conversation or an MCP
            // transport session (McpCallerResolver). The workspace's Search History is history/ next to profile/
            // and contexts/ (all user contexts share it).
            ChatProfiles profiles = new ChatProfiles(isolation.chatRoot());
            int stale = profiles.removeTransportWorkspaces();
            if (stale > 0) System.err.println("[argos-profiles] removed " + stale + " workspace(s) of ended MCP sessions");
            ChatRoutedTools routed = new ChatRoutedTools(profiles,
                    profile -> workspace(config.withProfile(profile), historySettings.enabled()
                            ? profile.getParent().resolve("history") : null, historySettings), isolation.maxBrowsers(),
                    java.time.Duration.ofMinutes(isolation.idleMinutes()));
            // The template only supplies the tool definitions; its browser is never started and its history
            // directory is never written (its handlers are never called).
            ChatRoutedTools.Workspace template = workspace(config, historySettings.enabled()
                    ? isolation.chatRoot().resolve("_template").resolve("history") : null, historySettings);
            tools = routed.specifications(template.tools(), config.toolProfile() == ToolProfile.EXPERT);
            template.resources().close();
            browsers = routed;
        } else {
            if (historySettings.enabled()) System.out.println(SINGLE_PROFILE_HISTORY);
            ChatRoutedTools.Workspace single = workspace(config, null, historySettings);
            tools = single.tools();
            browsers = single.resources();
        }
        RunningServer running = serve(config, tools);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("argos-shutdown").unstarted(() -> {
            running.close();
            try {
                browsers.close();
            } catch (Exception error) {
                System.err.println("Failed to stop Argos browsers: " + error.getMessage());
            }
        }));

        System.out.println("Argos ready: http://" + config.host() + ":" + running.port()
                + "/ (MCP root, health: /health, tool profile: "
                + config.toolProfile().name().toLowerCase(java.util.Locale.ROOT)
                + ", browser profiles: " + (isolation.perChat() ? "per ChatGPT conversation or MCP session" : "single")
                + ")");
        running.server().join();
    }

    /** The complete Argos tool stack on one browser profile: browser, web session, documents. */
    static ChatRoutedTools.Workspace workspace(ArgosConfig config) {
        return workspace(config, null, null);
    }

    static final String SINGLE_PROFILE_HISTORY = "Search History is disabled: PROFILE_ISOLATION=single has no "
            + "reliable chat key, and Argos keeps no global history. Start Argos with PROFILE_ISOLATION=chat "
            + "(the default) to use search_history, search_history_export, search_history_capture_settings and "
            + "browser_archive_html.";
    static final String HISTORY_SWITCHED_OFF = "Search History is switched off on this Argos server "
            + "(SEARCH_HISTORY_ENABLED=false).";

    /**
     * @param historyDirectory this chat's Search History, or null without one; then search_history and
     *                         search_history_export are still published (stable tool list) and explain why they are off
     * @param historySettings  null for the plain workspace without any Search History tools
     */
    static ChatRoutedTools.Workspace workspace(ArgosConfig config, java.nio.file.Path historyDirectory,
                                               SearchHistorySettings historySettings) {
        CamoufoxBrowser browser = new CamoufoxBrowser(config);
        // One security logic: every early URL check delegates to the proxy's NetworkPolicy.
        UrlPolicy urlPolicy = UrlPolicy.from(config.network());
        ResearchDocuments documents = new ResearchDocuments(browser, new DocumentStore(), urlPolicy);
        SearchHistoryCapture history = historyDirectory == null ? null : new SearchHistoryCapture(
                new SearchHistory(historyDirectory, historySettings.captureDefaults()),
                new SearchHistoryCapture.LivePages() {
                    @Override
                    public com.aresstack.argos.web.PageCapture capture(String tabId) {
                        return browser.capturePage(tabId);
                    }

                    @Override
                    public Object evaluate(String function, String tabId) {
                        return browser.evaluate(function, tabId);
                    }
                }, historySettings.collectorTimeoutMillis());
        // Received response bodies of this chat's browser go into this chat's history only.
        if (history != null) browser.contentSink(history.networkContent());
        com.aresstack.argos.web.WebSearchPort search = new BrowserWebSearch(browser);
        if (history != null) search = history.capturingSearch(search);
        ArgosTools argosTools = new ArgosTools(browser,
                new WebSession(browser, urlPolicy, search),
                ResearchSettings.fromEnvironment(), new DocumentAndBatchTools(documents), history);
        if (config.toolProfile().convenienceBased()) {
            argosTools.withConvenience(new ConvenienceTools(convenienceSession(browser, urlPolicy, history)));
        }
        List<SyncToolSpecification> tools = argosTools.forProfile(config.toolProfile());
        if (history == null && historySettings != null && config.toolProfile() != ToolProfile.LEGACY) {
            List<SyncToolSpecification> all = new java.util.ArrayList<>(tools);
            all.addAll(com.aresstack.argos.history.SearchHistoryTools.disabled(historySettings.enabled()
                    ? SINGLE_PROFILE_HISTORY : HISTORY_SWITCHED_OFF).specifications());
            tools = List.copyOf(all);
        }
        java.util.function.BiConsumer<String, java.nio.file.Path> contexts = history == null ? browser::useContext
                : (id, cookies) -> {
                    browser.useContext(id, cookies);
                    history.useContext(id);
                };
        return new ChatRoutedTools.Workspace(tools, contexts, () -> {
            browser.close();
            documents.close();
        });
    }

    /**
     * The convenience profile's research session of one workspace: parallel search over all engines in their own
     * tabs, every navigation under the process-wide domain coordinator (shared by all chats), pages recorded in the
     * chat's Search History (one event per web_search).
     */
    static ConvenienceSession convenienceSession(CamoufoxBrowser browser, UrlPolicy urlPolicy,
                                                 SearchHistoryCapture history) {
        DomainCoordinator coordinator = DomainCoordinator.shared();
        ParallelWebSearch search = ParallelWebSearch.fromEnvironment(browser, coordinator);
        ConvenienceSession.History recorder = history == null ? null : new ConvenienceSession.History() {
            @Override
            public void search(String query, String providers, List<com.aresstack.argos.web.SearchHit> hits) {
                history.search(query, providers, hits);
            }

            @Override
            public void page(com.aresstack.argos.web.PageCapture capture, String requestedUrl, boolean newVisit,
                             String tool, String tab) {
                history.tabPage(capture, requestedUrl, newVisit, tool, tab);
            }
        };
        return new ConvenienceSession(browser, coordinator, urlPolicy, search, recorder);
    }

    /** The started HTTP endpoint: Streamable HTTP MCP at the root, /health and protected-resource metadata. */
    public record RunningServer(Server server, McpSyncServer mcp, int port) implements AutoCloseable {
        @Override
        public void close() {
            try {
                server.stop();
            } catch (Exception error) {
                System.err.println("Failed to stop Argos HTTP server: " + error.getMessage());
            }
            mcp.close();
        }
    }

    /**
     * Publishes the given tools over Streamable HTTP on {@code config.host()}:{@code config.port()} (0 picks a
     * free port). Also used by the model evaluation, which drives the same endpoint as a real MCP client.
     */
    public static RunningServer serve(ArgosConfig config, List<SyncToolSpecification> tools) throws Exception {
        HttpServletStreamableServerTransportProvider transport =
                HttpServletStreamableServerTransportProvider.builder()
                        .mcpEndpoint("/").contextExtractor(ArgosApplication::transportContext).build();
        McpSyncServer mcp = McpServer.sync(transport)
                .serverInfo("argos", ArgosTools.VERSION)
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .instructions(config.toolProfile().convenienceBased() ? ConvenienceTools.INSTRUCTIONS : null)
                .tools(tools).build();

        Server server = new Server(new InetSocketAddress(config.host(), config.port()));
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        ServletHolder mcpServlet = new ServletHolder(new ArgosRootMcpServlet(transport));
        mcpServlet.setAsyncSupported(true);
        context.addServlet(mcpServlet, "/");
        FilterHolder authorization = new FilterHolder(new ArgosMcpAuthorizationFilter(config));
        authorization.setAsyncSupported(true);
        context.addFilter(authorization, "/", EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));
        context.addServlet(new ServletHolder(new HealthServlet()), "/health");
        // Search History exports: outside the bearer filter (a browser download carries no token); the random,
        // expiring export id is the link's credential, see SearchHistoryDownloads.
        SearchHistoryDownloads downloads = SearchHistoryDownloads.shared();
        context.addServlet(new ServletHolder(new SearchHistoryDownloadServlet(downloads)),
                SearchHistoryDownloads.PATH + "*");
        context.addServlet(new ServletHolder(new ProtectedResourceServlet(config)),
                "/.well-known/oauth-protected-resource");
        server.setHandler(context);
        server.start();
        int port = ((org.eclipse.jetty.server.ServerConnector) server.getConnectors()[0]).getLocalPort();
        downloads.origin(config.publicOrigin() != null ? config.publicOrigin()
                : "http://" + (config.host().contains(":") ? "[" + config.host() + "]" : config.host()) + ":" + port);
        return new RunningServer(server, mcp,
                ((org.eclipse.jetty.server.ServerConnector) server.getConnectors()[0]).getLocalPort());
    }

    /** Hands the principal verified by {@link ArgosMcpAuthorizationFilter} to the tool handlers, nothing else. */
    static io.modelcontextprotocol.common.McpTransportContext transportContext(HttpServletRequest request) {
        Object principal = request.getAttribute(McpCallerResolver.PRINCIPAL);
        return principal instanceof String value
                ? io.modelcontextprotocol.common.McpTransportContext.create(Map.of(McpCallerResolver.PRINCIPAL, value))
                : io.modelcontextprotocol.common.McpTransportContext.EMPTY;
    }

    static final class ProtectedResourceServlet extends HttpServlet {
        private final byte[] metadata;

        ProtectedResourceServlet(ArgosConfig config) throws IOException {
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            if (config.publicOrigin() != null) {
                data.put("resource", config.publicOrigin() + "/");
                data.put("authorization_servers", java.util.List.of(config.centralOauthIssuer() == null
                        ? config.publicOrigin() : config.centralOauthIssuer()));
                data.put("scopes_supported", java.util.List.of(config.requiredScope()));
                data.put("bearer_methods_supported", java.util.List.of("header"));
            }
            metadata = new ObjectMapper().writeValueAsBytes(data);
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json; charset=utf-8");
            response.getOutputStream().write(metadata);
        }
    }

    static final class HealthServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json; charset=utf-8");
            response.getOutputStream().write(
                    "{\"status\":\"UP\",\"service\":\"argos\"}".getBytes(StandardCharsets.UTF_8));
        }
    }
}
