package com.artagram.gateway.filter;

import com.artagram.gateway.config.GatewayProperties;
import com.artagram.gateway.error.ErrorResponseWriter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Valida tokens JWT RS256 contra el endpoint JWKS de artagram-auth cuando
 * {@code app.gateway.jwt-enabled=true}. Propaga cabeceras X-User-Id y X-User-Role
 * a los microservicios internos y devuelve 401 NO_AUTENTICADO o 403 SIN_PERMISO
 * con el mismo formato del monolito.
 */
@Component
public final class JwtGatewayGlobalFilter implements GlobalFilter, Ordered {

    private final GatewayProperties properties;
    private final ErrorResponseWriter errorWriter;
    private volatile ReactiveJwtDecoder jwtDecoder;

    public JwtGatewayGlobalFilter(GatewayProperties properties, ErrorResponseWriter errorWriter) {
        this.properties = properties;
        this.errorWriter = errorWriter;
    }

    public JwtGatewayGlobalFilter(GatewayProperties properties,
                                  ErrorResponseWriter errorWriter,
                                  ReactiveJwtDecoder jwtDecoder) {
        this.properties = properties;
        this.errorWriter = errorWriter;
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isJwtEnabled()) {
            return chain.filter(exchange);
        }

        HttpMethod method = exchange.getRequest().getMethod();
        String path = exchange.getRequest().getURI().getPath();
        if (method == HttpMethod.OPTIONS || esRutaPublica(method, path)) {
            return chain.filter(exchange);
        }

        String token = extraerToken(exchange.getRequest());
        if (token == null || token.isBlank()) {
            return errorWriter.escribir(
                    exchange,
                    HttpStatus.UNAUTHORIZED,
                    "NO_AUTENTICADO",
                    "Debes iniciar sesión para realizar esta acción"
            );
        }

        return obtenerDecoder().decode(token)
                .flatMap(jwt -> {
                    String rol = jwt.getClaimAsString("rol");
                    if (path != null && path.startsWith("/api/admin/") && !"ADMIN".equalsIgnoreCase(rol)) {
                        return errorWriter.escribir(
                                exchange,
                                HttpStatus.FORBIDDEN,
                                "SIN_PERMISO",
                                "No tienes permisos de administrador para acceder a este recurso"
                        );
                    }
                    ServerHttpRequest mutated = exchange.getRequest().mutate()
                            .header("X-User-Id", jwt.getSubject() != null ? jwt.getSubject() : "")
                            .header("X-User-Name", jwt.getClaimAsString("nombre") != null ? jwt.getClaimAsString("nombre") : "")
                            .header("X-User-Role", rol != null ? rol : "USUARIO")
                            .build();
                    return chain.filter(exchange.mutate().request(mutated).build());
                })
                .onErrorResume(ex -> errorWriter.escribir(
                        exchange,
                        HttpStatus.UNAUTHORIZED,
                        "NO_AUTENTICADO",
                        "Tu sesión expiró o el token no es válido"
                ));
    }

    private boolean esRutaPublica(HttpMethod method, String path) {
        if (path == null) {
            return false;
        }
        if (path.startsWith("/api/auth/")
                || path.equals("/.well-known/jwks.json")
                || path.startsWith("/media/")
                || path.startsWith("/ws")
                || path.startsWith("/actuator/health")
                || path.startsWith("/actuator/prometheus")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/api/pagos/webhook/")) {
            return true;
        }
        return method == HttpMethod.GET
                && path.startsWith("/api/perfiles/")
                && !path.endsWith("/premium");
    }

    private String extraerToken(ServerHttpRequest request) {
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }
        return request.getQueryParams().getFirst("token");
    }

    private ReactiveJwtDecoder obtenerDecoder() {
        ReactiveJwtDecoder actual = this.jwtDecoder;
        if (actual == null) {
            synchronized (this) {
                actual = this.jwtDecoder;
                if (actual == null) {
                    actual = NimbusReactiveJwtDecoder.withJwkSetUri(properties.getJwksUri()).build();
                    this.jwtDecoder = actual;
                }
            }
        }
        return actual;
    }
}
