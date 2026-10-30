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
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Central JWT authorization, equivalent to the Pyloros central-oauth mode.
 * Only the MCP endpoint is protected; health and RFC 9728 metadata are public.
 * <p>
 * A verified request carries its principal in the request attribute {@link McpCallerResolver#PRINCIPAL}; the MCP
 * transport hands it to the tool handlers, which bind transport-session workspaces to it. The principal is
 * {@code iss} plus {@code sub} of the validated token, or its {@code client_id} when it has no subject, and
 * {@code anonymous} with {@code SECURITY_MODE=none}.
 */
final class ArgosMcpAuthorizationFilter implements Filter {
    private static final String BEARER_PREFIX = "Bearer ";
    static final String ANONYMOUS = "anonymous";
    private final Function<String, Optional<String>> principalOfToken;
    private final String resourceMetadata;
    private final boolean authenticationDisabled;

    ArgosMcpAuthorizationFilter(ArgosConfig config) {
        this(config, createTokenVerifier(config));
    }

    // Test seam: verification failures, missing tokens and successful tokens (all with one principal).
    ArgosMcpAuthorizationFilter(ArgosConfig config, Predicate<String> authorizedToken) {
        this(config, (Function<String, Optional<String>>) token -> authorizedToken.test(token)
                ? Optional.of("authenticated") : Optional.empty());
    }

    // Test seam: the verified principal of each token, empty when the token is rejected.
    static ArgosMcpAuthorizationFilter withPrincipals(ArgosConfig config,
                                                      Function<String, Optional<String>> principalOfToken) {
        return new ArgosMcpAuthorizationFilter(config, principalOfToken);
    }

    private ArgosMcpAuthorizationFilter(ArgosConfig config, Function<String, Optional<String>> principalOfToken) {
        this.principalOfToken = principalOfToken;
        this.authenticationDisabled = "none".equals(config.securityMode());
        this.resourceMetadata = config.publicOrigin() == null
                ? null : config.publicOrigin() + "/.well-known/oauth-protected-resource";
    }

    private static Function<String, Optional<String>> createTokenVerifier(ArgosConfig config) {
        if ("none".equals(config.securityMode())) return token -> Optional.of(ANONYMOUS);
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
            return token -> validator.validate(token).filter(access -> access.hasScope(scope))
                    .map(access -> principal(token, access.getClientId()));
        } catch (IOException invalidJwksUrl) {
            throw new IllegalArgumentException("Invalid CENTRAL_OAUTH_ISSUER: " + issuer, invalidJwksUrl);
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (authenticationDisabled) {
            request.setAttribute(McpCallerResolver.PRINCIPAL, ANONYMOUS);
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest http = (HttpServletRequest) request;
        HttpServletResponse result = (HttpServletResponse) response;
        String authorization = http.getHeader("Authorization");
        Optional<String> principal = Optional.empty();
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)
                && !authorization.substring(BEARER_PREFIX.length()).isBlank()) {
            try {
                principal = principalOfToken.apply(authorization.substring(BEARER_PREFIX.length()).trim())
                        .filter(value -> !value.isBlank());
            } catch (Exception validationFailure) {
                principal = Optional.empty(); // No fallback to unauthenticated access.
            }
        }
        if (principal.isEmpty()) {
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
        request.setAttribute(McpCallerResolver.PRINCIPAL, principal.get());
        chain.doFilter(request, response);
    }

    /**
     * The principal of a token whose signature, issuer, expiry and scope were already validated: {@code iss} and
     * {@code sub} from its payload, otherwise its client id.
     */
    static String principal(String validatedToken, String clientId) {
        try {
            String[] parts = validatedToken.split("\\.");
            Map<?, ?> claims = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
            if (claims.get("sub") instanceof String subject && !subject.isBlank()) {
                return "sub:" + claims.get("iss") + "\u0000" + subject;
            }
        } catch (Exception unreadable) {
            // The validator accepted the token; fall back to its client id.
        }
        return clientId == null || clientId.isBlank() ? "authenticated" : "client:" + clientId;
    }
}
