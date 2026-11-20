package com.artagram.gateway.filter;

import com.artagram.gateway.config.GatewayProperties;
import com.artagram.gateway.error.ErrorResponseWriter;
import com.artagram.gateway.seguridad.BloqueoOrigenes;
import com.artagram.gateway.seguridad.OrigenCliente;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Corta antes de cualquier otra validacion las peticiones de un origen
 * bloqueado por intentos de autenticacion fallidos. No llegan a ningun
 * microservicio.
 */
@Component
public final class OrigenBloqueadoGlobalFilter implements GlobalFilter, Ordered {

    private final BloqueoOrigenes bloqueo;
    private final OrigenCliente origenCliente;
    private final ErrorResponseWriter errorWriter;
    private final GatewayProperties properties;

    public OrigenBloqueadoGlobalFilter(BloqueoOrigenes bloqueo, OrigenCliente origenCliente,
                                       ErrorResponseWriter errorWriter, GatewayProperties properties) {
        this.bloqueo = bloqueo;
        this.origenCliente = origenCliente;
        this.errorWriter = errorWriter;
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!properties.isJwtEnabled() || path.startsWith("/actuator/")) {
            return chain.filter(exchange);
        }
        String origen = origenCliente.resolver(exchange);
        return bloqueo.estaBloqueado(origen)
                .flatMap(bloqueado -> {
                    if (!Boolean.TRUE.equals(bloqueado)) {
                        return chain.filter(exchange);
                    }
                    bloqueo.registrarPeticionBloqueada(origen, path,
                            exchange.getRequest().getHeaders().getFirst("X-Trace-Id"));
                    exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER,
                            String.valueOf(properties.getDuracionBloqueo().toSeconds()));
                    return errorWriter.escribir(exchange, HttpStatus.TOO_MANY_REQUESTS, "ORIGEN_BLOQUEADO",
                            "Demasiados intentos fallidos de autenticacion; intenta de nuevo mas tarde");
                });
    }
}
