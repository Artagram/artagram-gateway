package com.artagram.gateway.filter;

import com.artagram.gateway.config.GatewayProperties;
import com.artagram.gateway.error.ErrorResponseWriter;
import com.artagram.gateway.seguridad.BloqueoOrigenes;
import com.artagram.gateway.seguridad.OrigenCliente;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Valida tokens JWT RS256 contra el endpoint JWKS de artagram-auth cuando
 * {@code app.gateway.jwt-enabled=true}. Propaga cabeceras X-User-Id y X-User-Role
 * a los microservicios internos y devuelve 401 NO_AUTENTICADO o 403 SIN_PERMISO
 * con el mismo formato del monolito. Cada rechazo por autenticacion queda
 * registrado con su origen y motivo, y cuenta para el bloqueo de origenes.
 */
@Component
public final class JwtGatewayGlobalFilter implements GlobalFilter, Ordered {

    private final GatewayProperties properties;
    private final ErrorResponseWriter errorWriter;
    private final BloqueoOrigenes bloqueo;
    private final OrigenCliente origenCliente;
    private volatile ReactiveJwtDecoder jwtDecoder;

    @Autowired
    public JwtGatewayGlobalFilter(GatewayProperties properties, ErrorResponseWriter errorWriter,
                                  BloqueoOrigenes bloqueo, OrigenCliente origenCliente) {
        this(properties, errorWriter, null, bloqueo, origenCliente);
    }

    public JwtGatewayGlobalFilter(GatewayProperties properties, ErrorResponseWriter errorWriter) {
        this(properties, errorWriter, null, null, null);
    }

    public JwtGatewayGlobalFilter(GatewayProperties properties,
                                  ErrorResponseWriter errorWriter,
                                  ReactiveJwtDecoder jwtDecoder) {
        this(properties, errorWriter, jwtDecoder, null, null);
    }

    public JwtGatewayGlobalFilter(GatewayProperties properties,
                                  ErrorResponseWriter errorWriter,
                                  ReactiveJwtDecoder jwtDecoder,
                                  BloqueoOrigenes bloqueo,
                                  OrigenCliente origenCliente) {
        this.properties = properties;
        this.errorWriter = errorWriter;
        this.jwtDecoder = jwtDecoder;
        this.bloqueo = bloqueo;
        this.origenCliente = origenCliente;
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
            return rechazar(exchange, path, "SIN_TOKEN", "Debes iniciar sesión para realizar esta acción");
        }

        return obtenerDecoder().decode(token)
                .map(jwt -> new Validacion(jwt, null))
                // Solo los fallos al validar el token son 401. Si se atraparan
                // tambien los del servicio destino, quedarian ocultos como sesion expirada.
                .onErrorResume(ex -> Mono.just(new Validacion(null, motivo(ex))))
                .flatMap(validacion -> validacion.jwt() != null
                        ? continuar(exchange, chain, path, validacion.jwt())
                        : rechazar(exchange, path, validacion.motivo(), "Tu sesión expiró o el token no es válido"));
    }

    private record Validacion(Jwt jwt, String motivo) {
    }

    private Mono<Void> rechazar(ServerWebExchange exchange, String path, String motivo, String mensaje) {
        Mono<Void> respuesta = errorWriter.escribir(exchange, HttpStatus.UNAUTHORIZED, "NO_AUTENTICADO", mensaje);
        if (bloqueo == null || origenCliente == null) {
            return respuesta;
        }
        String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        return bloqueo.registrarFallo(origenCliente.resolver(exchange), path, motivo, traceId)
                .then(respuesta);
    }

    /** Distingue el token vencido del alterado o firmado con otra llave, para el registro. */
    static String motivo(Throwable ex) {
        String mensaje = ex.getMessage();
        if (mensaje != null && mensaje.toLowerCase(java.util.Locale.ROOT).contains("expired")) {
            return "TOKEN_VENCIDO";
        }
        return "TOKEN_INVALIDO";
    }

    private Mono<Void> continuar(ServerWebExchange exchange, GatewayFilterChain chain, String path, Jwt jwt) {
        String rol = jwt.getClaimAsString("rol");
        if (path != null && esRutaAdmin(path) && !"ADMIN".equalsIgnoreCase(rol)) {
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
    }

    private boolean esRutaPublica(HttpMethod method, String path) {
        if (path == null) {
            return false;
        }
        if (path.startsWith("/api/auth/")
                || path.equals("/.well-known/jwks.json")
                || path.startsWith("/media/")
                || path.startsWith("/actuator/health")
                || path.startsWith("/actuator/prometheus")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/api/pagos/webhook/")) {
            return true;
        }
        if (method != HttpMethod.GET) {
            return false;
        }
        return path.startsWith("/api/publicaciones/artista/")
                || (path.startsWith("/api/perfiles/") && !path.endsWith("/premium"));
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

    private boolean esRutaAdmin(String path) {
        return path.startsWith("/api/admin/") || path.startsWith("/api/moderacion/");
    }
}
