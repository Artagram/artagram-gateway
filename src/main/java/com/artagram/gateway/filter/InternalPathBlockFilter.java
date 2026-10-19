package com.artagram.gateway.filter;

import com.artagram.gateway.error.ErrorResponseWriter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Bloquea cualquier intento externo de acceder a rutas /internal/**
 * (reservadas exclusivamente para llamadas servicio-a-servicio en la red Docker).
 */
@Component
public final class InternalPathBlockFilter implements GlobalFilter, Ordered {

    private final ErrorResponseWriter errorWriter;

    public InternalPathBlockFilter(ErrorResponseWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path != null && (path.equals("/internal") || path.startsWith("/internal/"))) {
            return errorWriter.escribir(
                    exchange,
                    HttpStatus.FORBIDDEN,
                    "SIN_PERMISO",
                    "No tienes permiso para acceder a rutas internas"
            );
        }
        return chain.filter(exchange);
    }
}
