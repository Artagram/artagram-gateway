package com.artagram.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.artagram.gateway.config.GatewayProperties;
import com.artagram.gateway.error.ErrorResponseWriter;
import com.artagram.gateway.filter.JwtGatewayGlobalFilter;
import com.artagram.gateway.filter.OrigenBloqueadoGlobalFilter;
import com.artagram.gateway.seguridad.AlmacenFallos;
import com.artagram.gateway.seguridad.AlmacenFallosRedis;
import com.artagram.gateway.seguridad.BloqueoOrigenes;
import com.artagram.gateway.seguridad.OrigenCliente;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.core.OAuth2Error;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class BloqueoOrigenesTest {

    /** Redis en memoria: suficiente para probar la logica de ventana y bloqueo. */
    static final class AlmacenEnMemoria implements AlmacenFallos {
        final Map<String, Long> contadores = new HashMap<>();
        final Set<String> marcas = new HashSet<>();

        @Override
        public Mono<Long> incrementar(String clave, Duration vencimiento) {
            return Mono.just(contadores.merge(clave, 1L, Long::sum));
        }

        @Override
        public Mono<Boolean> existe(String clave) {
            return Mono.just(marcas.contains(clave));
        }

        @Override
        public Mono<Boolean> marcar(String clave, Duration vencimiento) {
            return Mono.just(marcas.add(clave));
        }
    }

    private GatewayProperties props;
    private AlmacenEnMemoria almacen;
    private SimpleMeterRegistry registry;
    private BloqueoOrigenes bloqueo;
    private ErrorResponseWriter errorWriter;
    private final GatewayFilterChain chainOk = exchange -> {
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        return Mono.empty();
    };

    @BeforeEach
    void setUp() {
        props = new GatewayProperties();
        props.setJwtEnabled(true);
        almacen = new AlmacenEnMemoria();
        registry = new SimpleMeterRegistry();
        bloqueo = new BloqueoOrigenes(almacen, props, registry);
        errorWriter = new ErrorResponseWriter(new ObjectMapper());
    }

    @Test
    void elDecimoFalloEnLaVentanaBloqueaElOrigen() {
        for (int i = 1; i < 10; i++) {
            StepVerifier.create(bloqueo.registrarFallo("1.2.3.4", "/api/feed", "SIN_TOKEN", "t"))
                    .expectNext(false).verifyComplete();
        }
        StepVerifier.create(bloqueo.estaBloqueado("1.2.3.4")).expectNext(false).verifyComplete();

        StepVerifier.create(bloqueo.registrarFallo("1.2.3.4", "/api/feed", "TOKEN_VENCIDO", "t"))
                .expectNext(true).verifyComplete();

        StepVerifier.create(bloqueo.estaBloqueado("1.2.3.4")).expectNext(true).verifyComplete();
        StepVerifier.create(bloqueo.estaBloqueado("5.6.7.8")).expectNext(false).verifyComplete();
        assertThat(registry.get("gateway_origenes_bloqueados_total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("gateway_autenticacion_rechazada_total").tag("motivo", "SIN_TOKEN")
                .counter().count()).isEqualTo(9.0);
    }

    @Test
    void siRedisFallaNoSeBloqueaANadie() {
        AlmacenFallos roto = mock(AlmacenFallos.class);
        when(roto.existe(anyString())).thenReturn(Mono.error(new IllegalStateException("redis caido")));
        when(roto.incrementar(anyString(), any())).thenReturn(Mono.error(new IllegalStateException("redis caido")));
        BloqueoOrigenes conRedisCaido = new BloqueoOrigenes(roto, props, registry);

        StepVerifier.create(conRedisCaido.estaBloqueado("1.2.3.4")).expectNext(false).verifyComplete();
        StepVerifier.create(conRedisCaido.registrarFallo("1.2.3.4", "/x\n", "SIN_TOKEN", null))
                .expectNext(false).verifyComplete();
    }

    @Test
    void elFiltroCortaAlOrigenBloqueadoSinLlegarAlServicio() {
        almacen.marcas.add("gateway:auth-bloqueo:10.0.0.9");
        OrigenBloqueadoGlobalFilter filtro = new OrigenBloqueadoGlobalFilter(
                bloqueo, new OrigenCliente(props), errorWriter, props);
        MockServerWebExchange bloqueado = MockServerWebExchange.from(MockServerHttpRequest.get("/api/feed")
                .remoteAddress(new InetSocketAddress("10.0.0.9", 1234)).build());

        StepVerifier.create(filtro.filter(bloqueado, e -> Mono.error(new AssertionError("no debe pasar"))))
                .verifyComplete();
        assertThat(bloqueado.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(bloqueado.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("900");

        MockServerWebExchange otro = MockServerWebExchange.from(MockServerHttpRequest.get("/api/feed")
                .remoteAddress(new InetSocketAddress("10.0.0.10", 1234)).build());
        StepVerifier.create(filtro.filter(otro, chainOk)).verifyComplete();
        assertThat(otro.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);

        MockServerWebExchange salud = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health")
                .remoteAddress(new InetSocketAddress("10.0.0.9", 1234)).build());
        StepVerifier.create(filtro.filter(salud, chainOk)).verifyComplete();
        assertThat(salud.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);

        props.setJwtEnabled(false);
        MockServerWebExchange sinJwt = MockServerWebExchange.from(MockServerHttpRequest.get("/api/feed")
                .remoteAddress(new InetSocketAddress("10.0.0.9", 1234)).build());
        StepVerifier.create(filtro.filter(sinJwt, chainOk)).verifyComplete();
        assertThat(sinJwt.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void cadaRechazoDelFiltroJwtCuentaParaElBloqueoConSuMotivo() {
        ReactiveJwtDecoder decoder = mock(ReactiveJwtDecoder.class);
        when(decoder.decode("vencido")).thenReturn(Mono.error(new JwtValidationException(
                "Jwt expired at 2026-01-01", java.util.List.of(new OAuth2Error("invalid_token")))));
        when(decoder.decode("alterado")).thenReturn(Mono.error(new BadJwtException("Signed JWT rejected")));
        JwtGatewayGlobalFilter filtro = new JwtGatewayGlobalFilter(props, errorWriter, decoder,
                bloqueo, new OrigenCliente(props));

        for (String token : new String[] {null, "vencido", "alterado"}) {
            MockServerHttpRequest.BaseBuilder<?> req = MockServerHttpRequest.get("/api/feed")
                    .remoteAddress(new InetSocketAddress("7.7.7.7", 1));
            if (token != null) {
                req.header("Authorization", "Bearer " + token);
            }
            MockServerWebExchange exchange = MockServerWebExchange.from(req.build());
            StepVerifier.create(filtro.filter(exchange, e -> Mono.error(new AssertionError("no debe pasar"))))
                    .verifyComplete();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        assertThat(almacen.contadores.get("gateway:auth-fallos:7.7.7.7")).isEqualTo(3L);
        for (String motivo : new String[] {"SIN_TOKEN", "TOKEN_VENCIDO", "TOKEN_INVALIDO"}) {
            assertThat(registry.get("gateway_autenticacion_rechazada_total").tag("motivo", motivo)
                    .counter().count()).isEqualTo(1.0);
        }
    }

    @Test
    void elWebSocketTambienExigeToken() {
        JwtGatewayGlobalFilter filtro = new JwtGatewayGlobalFilter(props, errorWriter, mock(ReactiveJwtDecoder.class),
                bloqueo, new OrigenCliente(props));
        MockServerWebExchange ws = MockServerWebExchange.from(MockServerHttpRequest.get("/ws")
                .remoteAddress(new InetSocketAddress("8.8.8.8", 1)).build());

        StepVerifier.create(filtro.filter(ws, chainOk)).verifyComplete();
        assertThat(ws.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void detrasDeCloudFrontYElAlbSeUsaLaIpQueAgregoCloudFront() {
        props.setProxiesConfiables(2);
        OrigenCliente origen = new OrigenCliente(props);
        // El cliente inventa la primera entrada; CloudFront agrega la real y el ALB la de CloudFront.
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/feed")
                .header("X-Forwarded-For", "6.6.6.6, 203.0.113.5, 130.176.0.1")
                .remoteAddress(new InetSocketAddress("10.0.1.20", 443)).build());

        assertThat(origen.resolver(exchange)).isEqualTo("203.0.113.5");

        OrigenCliente directo = new OrigenCliente(new GatewayProperties());
        assertThat(directo.resolver(exchange)).isEqualTo("10.0.1.20");
        MockServerWebExchange sinDireccion = MockServerWebExchange.from(MockServerHttpRequest.get("/x").build());
        assertThat(directo.resolver(sinDireccion)).isNotBlank();
    }

    @Test
    @SuppressWarnings("unchecked")
    void elAlmacenRedisPoneVencimientoSoloAlPrimerFallo() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> ops = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment("k1")).thenReturn(Mono.just(1L));
        when(ops.increment("k2")).thenReturn(Mono.just(4L));
        when(redis.expire(any(), any())).thenReturn(Mono.just(true));
        when(redis.hasKey("b")).thenReturn(Mono.just(true));
        when(ops.set("b", "1", Duration.ofMinutes(15))).thenReturn(Mono.just(true));
        AlmacenFallosRedis almacenRedis = new AlmacenFallosRedis(redis);

        StepVerifier.create(almacenRedis.incrementar("k1", Duration.ofMinutes(1))).expectNext(1L).verifyComplete();
        verify(redis).expire("k1", Duration.ofMinutes(1));
        StepVerifier.create(almacenRedis.incrementar("k2", Duration.ofMinutes(1))).expectNext(4L).verifyComplete();
        verify(redis, never()).expire("k2", Duration.ofMinutes(1));
        StepVerifier.create(almacenRedis.existe("b")).expectNext(true).verifyComplete();
        StepVerifier.create(almacenRedis.marcar("b", Duration.ofMinutes(15))).expectNext(true).verifyComplete();
    }
}
