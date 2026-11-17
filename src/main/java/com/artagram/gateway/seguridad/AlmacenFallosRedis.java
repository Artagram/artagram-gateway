package com.artagram.gateway.seguridad;

import java.time.Duration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class AlmacenFallosRedis implements AlmacenFallos {

    private final ReactiveStringRedisTemplate redis;

    public AlmacenFallosRedis(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Long> incrementar(String clave, Duration vencimiento) {
        return redis.opsForValue().increment(clave)
                .flatMap(valor -> valor == 1L
                        ? redis.expire(clave, vencimiento).thenReturn(valor)
                        : Mono.just(valor));
    }

    @Override
    public Mono<Boolean> existe(String clave) {
        return redis.hasKey(clave);
    }

    @Override
    public Mono<Boolean> marcar(String clave, Duration vencimiento) {
        return redis.opsForValue().set(clave, "1", vencimiento);
    }
}
