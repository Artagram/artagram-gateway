package com.artagram.gateway.filter;

import java.util.Locale;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Los servicios internos confian en las cabeceras X-User-* porque se supone
 * que solo las escribe el gateway tras validar el JWT. Por eso se eliminan las
 * que manda el cliente antes de cualquier otro filtro: en las rutas publicas
 * nadie las sobrescribe, y un cliente podria hacerse pasar por otro usuario.
 */
@Component
public final class IdentityHeaderStripFilter implements GlobalFilter, Ordered {

    private static final String PREFIJO = "x-user-";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest limpia = exchange.getRequest().mutate()
                .headers(h -> h.keySet().removeIf(n -> n.toLowerCase(Locale.ROOT).startsWith(PREFIJO)))
                .build();
        return chain.filter(exchange.mutate().request(limpia).build());
    }
}
