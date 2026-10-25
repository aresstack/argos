package com.aresstack.argos;

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
import com.aresstack.argos.web.UrlPolicy;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.ArrayList;
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
        CamoufoxBrowser browser = new CamoufoxBrowser(config);
        ResearchDocuments documents = new ResearchDocuments(browser, new DocumentStore(), UrlPolicy.fromEnvironment());
        List<SyncToolSpecification> tools = new ArrayList<>(new ArgosTools(browser).all());
        // Documents and batches belong to the expert profile only; the legacy tool list stays unchanged.
        if ("expert".equalsIgnoreCase(System.getenv("ARGOS_TOOL_PROFILE"))) {
            tools.addAll(new DocumentAndBatchTools(documents).all());
        }
        HttpServletStreamableServerTransportProvider transport =
                HttpServletStreamableServerTransportProvider.builder()
                        .mcpEndpoint("/").build();
        McpSyncServer mcp = McpServer.sync(transport)
                .serverInfo("argos", "0.1.0")
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
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("argos-shutdown").unstarted(() -> {
            try {
                server.stop();
            } catch (Exception error) {
                System.err.println("Failed to stop Argos HTTP server: " + error.getMessage());
            }
            mcp.close();
            browser.close();
            documents.close();
        }));

        server.start();
        System.out.println("Argos ready: http://" + config.host() + ":"
                + ((org.eclipse.jetty.server.ServerConnector) server.getConnectors()[0]).getLocalPort()
                + "/ (MCP root, health: /health)");
        server.join();
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
