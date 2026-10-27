package com.aresstack.argos;

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
        List<SyncToolSpecification> tools;
        AutoCloseable browsers;
        if (isolation.perChat()) {
            // One isolated, persistent profile (and Camoufox process) per ChatGPT conversation context.
            ChatRoutedTools routed = new ChatRoutedTools(new ChatProfiles(isolation.chatRoot()),
                    profile -> workspace(config.withProfile(profile)), isolation.maxBrowsers());
            // The template only supplies the tool definitions; its browser is never started.
            ChatRoutedTools.Workspace template = workspace(config);
            tools = routed.specifications(template.tools(), config.toolProfile() == ToolProfile.EXPERT);
            template.resources().close();
            browsers = routed;
        } else {
            ChatRoutedTools.Workspace single = workspace(config);
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
                + ", browser profiles: " + (isolation.perChat() ? "per ChatGPT conversation" : "single") + ")");
        running.server().join();
    }

    /** The complete Argos tool stack on one browser profile: browser, web session, documents. */
    static ChatRoutedTools.Workspace workspace(ArgosConfig config) {
        CamoufoxBrowser browser = new CamoufoxBrowser(config);
        // One security logic: every early URL check delegates to the proxy's NetworkPolicy.
        UrlPolicy urlPolicy = UrlPolicy.from(config.network());
        ResearchDocuments documents = new ResearchDocuments(browser, new DocumentStore(), urlPolicy);
        List<SyncToolSpecification> tools = new ArgosTools(browser,
                new WebSession(browser, urlPolicy, new BrowserWebSearch(browser)),
                ResearchSettings.fromEnvironment(), new DocumentAndBatchTools(documents))
                .forProfile(config.toolProfile());
        return new ChatRoutedTools.Workspace(tools, () -> {
            browser.close();
            documents.close();
        });
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
                        .mcpEndpoint("/").build();
        McpSyncServer mcp = McpServer.sync(transport)
                .serverInfo("argos", ArgosTools.VERSION)
                .capabilities(ServerCapabilities.builder().tools(true).build())
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
        context.addServlet(new ServletHolder(new ProtectedResourceServlet(config)),
                "/.well-known/oauth-protected-resource");
        server.setHandler(context);
        server.start();
        return new RunningServer(server, mcp,
                ((org.eclipse.jetty.server.ServerConnector) server.getConnectors()[0]).getLocalPort());
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
