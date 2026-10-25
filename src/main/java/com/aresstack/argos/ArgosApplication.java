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
import org.eclipse.jetty.server.Server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Standalone managed JAR: Streamable HTTP MCP is provided by the official Java SDK. */
public final class ArgosApplication {
    private ArgosApplication() { }

    public static void main(String[] args) throws Exception {
        ArgosConfig config = ArgosConfig.fromEnvironment();
        CamoufoxBrowser browser = new CamoufoxBrowser(config);
        HttpServletStreamableServerTransportProvider transport =
                HttpServletStreamableServerTransportProvider.builder()
                        .mcpEndpoint("/mcp").build();
        McpSyncServer mcp = McpServer.sync(transport)
                .serverInfo("argos", "0.1.0")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .tools(new ArgosTools(browser).all()).build();

        Server server = new Server(new InetSocketAddress(config.host(), config.port()));
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        ServletHolder mcpServlet = new ServletHolder(transport);
        mcpServlet.setAsyncSupported(true);
        context.addServlet(mcpServlet, "/mcp");
        context.addServlet(new ServletHolder(new HealthServlet()), "/health");
        server.setHandler(context);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("argos-shutdown").unstarted(() -> {
            try {
                server.stop();
            } catch (Exception error) {
                System.err.println("Failed to stop Argos HTTP server: " + error.getMessage());
            }
            mcp.close();
            browser.close();
        }));

        server.start();
        System.out.println("Argos ready: http://" + config.host() + ":"
                + ((org.eclipse.jetty.server.ServerConnector) server.getConnectors()[0]).getLocalPort()
                + "/mcp (health: /health)");
        server.join();
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
