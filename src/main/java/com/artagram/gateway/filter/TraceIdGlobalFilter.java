package com.artagram.gateway.filter;

import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Asegura que cada petición HTTP o WebSocket tenga una cabecera X-Trace-Id
 * y la devuelve también en la respuesta al cliente.
 */
@Component
public final class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(HEADER_TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        final String finalTraceId = traceId;
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(HEADER_TRACE_ID, finalTraceId)
                .build();
        exchange.getResponse().getHeaders().set(HEADER_TRACE_ID, finalTraceId);

        MDC.put("traceId", finalTraceId);
        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .doFinally(signal -> MDC.remove("traceId"));
    }
}
