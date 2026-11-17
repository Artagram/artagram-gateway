package com.artagram.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.artagram.gateway.config.GatewayCorsConfig;
import com.artagram.gateway.config.GatewayProperties;
import com.artagram.gateway.error.ErrorResponseWriter;
import com.artagram.gateway.filter.IdentityHeaderStripFilter;
import com.artagram.gateway.filter.InternalPathBlockFilter;
import com.artagram.gateway.filter.JwtGatewayGlobalFilter;
import com.artagram.gateway.filter.RateLimitGlobalFilter;
import com.artagram.gateway.filter.TraceIdGlobalFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.reactive.CorsWebFilter;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GatewayConfigYBordesTest {

    private ErrorResponseWriter errorWriter;
    private GatewayFilterChain chainOk;

    @BeforeEach
    void setUp() {
        errorWriter = new ErrorResponseWriter(new ObjectMapper());
        chainOk = exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        };
    }

    @Test
    void propiedadesTienenValoresPorDefectoYSeSobrescriben() {
        GatewayProperties props = new GatewayProperties();
        assertThat(props.isJwtEnabled()).isFalse();
        assertThat(props.getJwksUri()).contains("jwks.json");
        assertThat(props.getCorsAllowedOrigins()).containsExactly("http://localhost:5173");
        assertThat(props.getRateLimitPerSecond()).isEqualTo(120);

        props.setJwtEnabled(true);
        props.setJwksUri("http://auth/jwks");
        props.setCorsAllowedOrigins(List.of("https://artagram.app"));
        props.setRateLimitPerSecond(5);

        assertThat(props.isJwtEnabled()).isTrue();
        assertThat(props.getJwksUri()).isEqualTo("http://auth/jwks");
        assertThat(props.getCorsAllowedOrigins()).containsExactly("https://artagram.app");
        assertThat(props.getRateLimitPerSecond()).isEqualTo(5);
    }

    @Test
    void corsPermiteElOrigenConfiguradoYRespondeElPreflight() {
        GatewayProperties props = new GatewayProperties();
        props.setCorsAllowedOrigins(List.of("https://artagram.app"));
        CorsWebFilter filtro = new GatewayCorsConfig().corsWebFilter(props);

        MockServerWebExchange preflight = MockServerWebExchange.from(
                MockServerHttpRequest.options("http://gateway.local/api/feed")
                        .header("Origin", "https://artagram.app")
                        .header("Access-Control-Request-Method", "POST")
                        .build());

        StepVerifier.create(filtro.filter(preflight, e -> Mono.empty())).verifyComplete();
        assertThat(preflight.getResponse().getHeaders().getAccessControlAllowOrigin())
                .isEqualTo("https://artagram.app");
        assertThat(preflight.getResponse().getHeaders().getAccessControlAllowCredentials()).isTrue();
    }

    @Test
    void losFiltrosSeEjecutanEnElOrdenEsperado() {
        GatewayProperties props = new GatewayProperties();
        int trace = new TraceIdGlobalFilter().getOrder();
        int strip = new IdentityHeaderStripFilter().getOrder();
        int interno = new InternalPathBlockFilter(errorWriter).getOrder();
        int jwt = new JwtGatewayGlobalFilter(props, errorWriter).getOrder();
        int rate = new RateLimitGlobalFilter(props, errorWriter).getOrder();

        assertThat(trace).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(List.of(trace, strip, interno, jwt, rate)).isSorted();
    }

    @Test
    void jwtDeshabilitadoDejaPasarSinToken() {
        GatewayProperties props = new GatewayProperties();
        props.setJwtEnabled(false);
        JwtGatewayGlobalFilter filtro = new JwtGatewayGlobalFilter(props, errorWriter);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/feed").build());

        StepVerifier.create(filtro.filter(exchange, chainOk)).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void sinDecoderInyectadoCreaUnoConElJwksYRechazaElTokenQueNoPuedeValidar() {
        try (ServidorQueRechaza jwksCaido = new ServidorQueRechaza()) {
            GatewayProperties props = new GatewayProperties();
            props.setJwtEnabled(true);
            props.setJwksUri(jwksCaido.url() + "/.well-known/jwks.json");
            JwtGatewayGlobalFilter filtro = new JwtGatewayGlobalFilter(props, errorWriter);
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/feed").header("Authorization", "Bearer no-es-un-jwt").build());

            StepVerifier.create(filtro.filter(exchange, chainOk)).verifyComplete();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void rateLimitAgrupaPorIpCuandoNoHayUsuario() {
        GatewayProperties props = new GatewayProperties();
        props.setRateLimitPerSecond(1);
        RateLimitGlobalFilter filtro = new RateLimitGlobalFilter(props, errorWriter);

        MockServerWebExchange primera = MockServerWebExchange.from(MockServerHttpRequest.get("/api/feed")
                .remoteAddress(new InetSocketAddress("10.0.0.7", 4000)).build());
        MockServerWebExchange segunda = MockServerWebExchange.from(MockServerHttpRequest.get("/api/feed")
                .remoteAddress(new InetSocketAddress("10.0.0.7", 4001)).build());
        MockServerWebExchange otraIp = MockServerWebExchange.from(MockServerHttpRequest.get("/api/feed")
                .remoteAddress(new InetSocketAddress("10.0.0.8", 4000)).build());

        StepVerifier.create(filtro.filter(primera, chainOk)).verifyComplete();
        StepVerifier.create(filtro.filter(segunda, chainOk)).verifyComplete();
        StepVerifier.create(filtro.filter(otraIp, chainOk)).verifyComplete();

        assertThat(primera.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(segunda.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(otraIp.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rateLimitSinIpNiUsuarioUsaUnaClaveLocal() {
        GatewayProperties props = new GatewayProperties();
        props.setRateLimitPerSecond(1);
        RateLimitGlobalFilter filtro = new RateLimitGlobalFilter(props, errorWriter);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/feed").build());

        StepVerifier.create(filtro.filter(exchange, chainOk)).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void errorWriterUsaUnCuerpoMinimoSiJacksonFalla() throws Exception {
        ObjectMapper roto = mock(ObjectMapper.class);
        when(roto.writeValueAsBytes(any())).thenThrow(new JsonProcessingException("fallo") { });
        ErrorResponseWriter writer = new ErrorResponseWriter(roto);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x").build());

        StepVerifier.create(writer.escribir(exchange, HttpStatus.FORBIDDEN, "SIN_PERMISO", "No"))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String cuerpo = exchange.getResponse().getBodyAsString().block();
        assertThat(cuerpo).isEqualTo(new String("{\"code\":\"SIN_PERMISO\",\"message\":\"No\"}"
                .getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }

    @Test
    void rutaQueSoloEmpiezaPorInternalNoSeBloquea() {
        InternalPathBlockFilter filtro = new InternalPathBlockFilter(errorWriter);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/internalizar").build());

        StepVerifier.create(filtro.filter(exchange, chainOk)).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
