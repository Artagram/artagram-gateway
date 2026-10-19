package com.artagram.gateway.filter;

import com.artagram.gateway.config.GatewayProperties;
import com.artagram.gateway.error.ErrorResponseWriter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Rate limiter en el Gateway basado en Bucket4j por usuario autenticado (X-User-Id)
 * o dirección IP del cliente.
 */
@Component
public final class RateLimitGlobalFilter implements GlobalFilter, Ordered {

    private final GatewayProperties properties;
    private final ErrorResponseWriter errorWriter;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitGlobalFilter(GatewayProperties properties, ErrorResponseWriter errorWriter) {
        this.properties = properties;
        this.errorWriter = errorWriter;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 30;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String clave = resolverClave(exchange);
        Bucket bucket = buckets.computeIfAbsent(clave, this::nuevoBucket);

        if (bucket.tryConsume(1)) {
            return chain.filter(exchange);
        }

        return errorWriter.escribir(
                exchange,
                HttpStatus.TOO_MANY_REQUESTS,
                "LIMITE_PETICIONES",
                "Has realizado demasiadas peticiones; intenta de nuevo en unos segundos"
        );
    }

    private Bucket nuevoBucket(String clave) {
        int limite = Math.max(1, properties.getRateLimitPerSecond());
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(limite)
                .refillGreedy(limite, Duration.ofSeconds(1))
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }

    private String resolverClave(ServerWebExchange exchange) {
        String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        if (userId != null && !userId.isBlank()) {
            return "user:" + userId;
        }
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            return "ip:" + remoteAddress.getAddress().getHostAddress();
        }
        return "ip:local";
    }
}
