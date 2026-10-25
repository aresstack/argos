package com.aresstack.argos;

import com.aresstack.anauthentia.jwt.JwtAccessTokenValidator;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

/**
 * Central JWT authorization, equivalent to the Pyloros central-oauth mode.
 * Only the MCP endpoint is protected; health and RFC 9728 metadata are public.
 */
final class ArgosMcpAuthorizationFilter implements Filter {
    private static final String BEARER_PREFIX = "Bearer ";
    private final Predicate<String> authorizedToken;
    private final String resourceMetadata;
    private final boolean authenticationDisabled;

    ArgosMcpAuthorizationFilter(ArgosConfig config) {
        this(config, createTokenVerifier(config));
    }

    // Test seam: verification failures, missing tokens and successful tokens.
    ArgosMcpAuthorizationFilter(ArgosConfig config, Predicate<String> authorizedToken) {
        this.authorizedToken = authorizedToken;
        this.authenticationDisabled = "none".equals(config.securityMode());
        this.resourceMetadata = config.publicOrigin() == null
                ? null : config.publicOrigin() + "/.well-known/oauth-protected-resource";
    }

    private static Predicate<String> createTokenVerifier(ArgosConfig config) {
        if ("none".equals(config.securityMode())) return token -> true;
        if (!"central-oauth".equals(config.securityMode())) {
            throw new IllegalArgumentException("Unknown SECURITY_MODE: " + config.securityMode());
        }
        String issuer = config.centralOauthIssuer();
        String scope = config.requiredScope();
        if (issuer == null || issuer.isBlank() || scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("Central OAuth requires CENTRAL_OAUTH_ISSUER and REQUIRED_SCOPE");
        }
        try {
            final JwtAccessTokenValidator validator = JwtAccessTokenValidator.forRemoteJwks(
                    issuer, issuer + "/", URI.create(issuer + "/.well-known/jwks.json").toURL());
            return token -> validator.validate(token).map(access -> access.hasScope(scope)).orElse(false);
        } catch (IOException invalidJwksUrl) {
            throw new IllegalArgumentException("Invalid CENTRAL_OAUTH_ISSUER: " + issuer, invalidJwksUrl);
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (authenticationDisabled) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest http = (HttpServletRequest) request;
        HttpServletResponse result = (HttpServletResponse) response;
        String authorization = http.getHeader("Authorization");
        boolean authorized = authorization != null && authorization.startsWith(BEARER_PREFIX)
                && !authorization.substring(BEARER_PREFIX.length()).isBlank();
        if (authorized) {
            try {
                authorized = authorizedToken.test(authorization.substring(BEARER_PREFIX.length()).trim());
            } catch (Exception validationFailure) {
                authorized = false; // No fallback to unauthenticated access.
            }
        }
        if (!authorized) {
            result.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            result.setContentType("application/json; charset=utf-8");
            String challenge = "Bearer error=\"invalid_token\"";
            if (resourceMetadata != null) {
                challenge += ", resource_metadata=\"" + resourceMetadata + "\"";
            }
            result.setHeader("WWW-Authenticate", challenge);
            result.getOutputStream().write(
                    "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }
}
