package com.artagram.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.artagram.gateway.config.GatewayProperties;
import com.artagram.gateway.error.ErrorResponseWriter;
import com.artagram.gateway.filter.InternalPathBlockFilter;
import com.artagram.gateway.filter.JwtGatewayGlobalFilter;
import com.artagram.gateway.filter.RateLimitGlobalFilter;
import com.artagram.gateway.filter.TraceIdGlobalFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GatewayFiltersTest {

    private ErrorResponseWriter errorWriter;
    private GatewayFilterChain passThroughChain;

    @BeforeEach
    void setUp() {
        errorWriter = new ErrorResponseWriter(new ObjectMapper());
        passThroughChain = exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        };
    }

    @Test
    void traceIdFilterGeneraCabeceraSiFaltaYLaConservaSiViene() {
        TraceIdGlobalFilter filter = new TraceIdGlobalFilter();
        MockServerWebExchange sinTrace = MockServerWebExchange.from(MockServerHttpRequest.get("/api/feed").build());

        StepVerifier.create(filter.filter(sinTrace, passThroughChain)).verifyComplete();
        assertThat(sinTrace.getResponse().getHeaders().getFirst("X-Trace-Id")).hasSize(16);

        MockServerWebExchange conTrace = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/feed").header("X-Trace-Id", "trace-fijo-12345").build());
        StepVerifier.create(filter.filter(conTrace, passThroughChain)).verifyComplete();
        assertThat(conTrace.getResponse().getHeaders().getFirst("X-Trace-Id")).isEqualTo("trace-fijo-12345");
    }

    @Test
    void internalPathBlockFilterRechazaRutasInternasCon403() {
        InternalPathBlockFilter filter = new InternalPathBlockFilter(errorWriter);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/internal/communities/123/members/456").build());

        StepVerifier.create(filter.filter(exchange, passThroughChain)).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void jwtFilterConBanderaActivaExigeTokenEnRutasProtegidasYPermiteRutasPublicas() {
        GatewayProperties props = new GatewayProperties();
        props.setJwtEnabled(true);
        ReactiveJwtDecoder decoder = mock(ReactiveJwtDecoder.class);
        JwtGatewayGlobalFilter filter = new JwtGatewayGlobalFilter(props, errorWriter, decoder);

        // Ruta pública (/api/auth/login) pasa sin token
        MockServerWebExchange loginExchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login").build());
        StepVerifier.create(filter.filter(loginExchange, passThroughChain)).verifyComplete();
        assertThat(loginExchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);

        // Ruta protegida (/api/feed) sin token -> 401 NO_AUTENTICADO
        MockServerWebExchange feedSinToken = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/feed").build());
        StepVerifier.create(filter.filter(feedSinToken, passThroughChain)).verifyComplete();
        assertThat(feedSinToken.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Ruta protegida con token válido propaga X-User-Id
        Jwt jwt = new Jwt(
                "token-valido",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("sub", "a1b2c3d4-0000-4000-8000-000000000002", "nombre", "sam_pixel", "rol", "USUARIO")
        );
        when(decoder.decode(any())).thenReturn(Mono.just(jwt));

        MockServerWebExchange feedConToken = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token-valido")
                        .build());
        StepVerifier.create(filter.filter(feedConToken, passThroughChain)).verifyComplete();
        assertThat(feedConToken.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);

        // Ruta /api/admin/kpis con rol USUARIO -> 403 SIN_PERMISO
        MockServerWebExchange adminSinRol = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/admin/kpis")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token-valido")
                        .build());
        StepVerifier.create(filter.filter(adminSinRol, passThroughChain)).verifyComplete();
        assertThat(adminSinRol.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rateLimitFilterDevuelve429AlSuperarTopePorSegundo() {
        GatewayProperties props = new GatewayProperties();
        props.setRateLimitPerSecond(2);
        RateLimitGlobalFilter filter = new RateLimitGlobalFilter(props, errorWriter);

        MockServerWebExchange req1 = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/feed").header("X-User-Id", "user-1").build());
        MockServerWebExchange req2 = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/feed").header("X-User-Id", "user-1").build());
        MockServerWebExchange req3 = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/feed").header("X-User-Id", "user-1").build());

        StepVerifier.create(filter.filter(req1, passThroughChain)).verifyComplete();
        StepVerifier.create(filter.filter(req2, passThroughChain)).verifyComplete();
        StepVerifier.create(filter.filter(req3, passThroughChain)).verifyComplete();

        assertThat(req1.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(req2.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(req3.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
