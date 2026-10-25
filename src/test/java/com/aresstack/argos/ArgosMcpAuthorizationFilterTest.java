package com.aresstack.argos;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

final class ArgosMcpAuthorizationFilterTest {
    @TempDir Path temporary;

    private ArgosConfig config(String mode) {
        return new ArgosConfig("127.0.0.1", 8085, null, null, temporary, false,
                "https://argos.aresstack.de", mode, "https://argos.aresstack.de", "argos");
    }

    private HttpServletResponse response(ByteArrayOutputStream body) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener listener) { }
            @Override public void write(int value) { body.write(value); }
        });
        return response;
    }

    @Test
    void centralOauthRejectsUnauthenticatedMcpRequest() throws Exception {
        ArgosMcpAuthorizationFilter filter = new ArgosMcpAuthorizationFilter(config("central-oauth"),
                token -> true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        FilterChain chain = mock(FilterChain.class);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpServletResponse response = response(body);

        filter.doFilter(request, response, chain);

        verify(response).setStatus(401);
        verify(response).setHeader(eq("WWW-Authenticate"),
                contains("https://argos.aresstack.de/.well-known/oauth-protected-resource"));
        verifyNoInteractions(chain);
        assertTrue(body.toString().contains("unauthorized"));
    }

    @Test
    void centralOauthRejectsInvalidBearerToken() throws Exception {
        ArgosMcpAuthorizationFilter filter = new ArgosMcpAuthorizationFilter(config("central-oauth"),
                token -> false);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer rejected");
        FilterChain chain = mock(FilterChain.class);
        HttpServletResponse response = response(new ByteArrayOutputStream());

        filter.doFilter(request, response, chain);

        verify(response).setStatus(401);
        verifyNoInteractions(chain);
    }

    @Test
    void centralOauthAcceptsOnlyValidatedBearerTokens() throws Exception {
        ArgosMcpAuthorizationFilter filter = new ArgosMcpAuthorizationFilter(config("central-oauth"),
                token -> "granted".equals(token));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer granted");
        FilterChain chain = mock(FilterChain.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    @Test
    void noSecurityModeIsExplicitlyUnprotected() throws Exception {
        ArgosMcpAuthorizationFilter filter = new ArgosMcpAuthorizationFilter(config("none"),
                token -> false);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void validatorExceptionFailsClosed() throws Exception {
        ArgosMcpAuthorizationFilter filter = new ArgosMcpAuthorizationFilter(config("central-oauth"),
                token -> { throw new IllegalStateException("JWKS unreachable"); });
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer compromised");
        HttpServletResponse response = response(new ByteArrayOutputStream());
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(response).setStatus(401);
        verifyNoInteractions(chain);
    }
}
