package com.aresstack.argos;

import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Pyloros-compatible root endpoint: POST serves Streamable HTTP JSON-RPC;
 * GET explicitly returns 405 rather than pretending to expose a SSE stream.
 */
final class ArgosRootMcpServlet extends HttpServlet {
    private final HttpServletStreamableServerTransportProvider transport;

    ArgosRootMcpServlet(HttpServletStreamableServerTransportProvider transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        response.setHeader("Allow", "POST");
        response.setContentType("application/json; charset=utf-8");
        response.getOutputStream().write(
                "{\"error\":\"GET is not supported on the MCP endpoint\"}"
                        .getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        transport.service(request, response);
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        transport.service(request, response);
    }
}
