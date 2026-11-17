package com.artagram.gateway.seguridad;

import java.time.Duration;
import reactor.core.publisher.Mono;

/**
 * Contadores con vencimiento compartidos por todas las replicas del gateway
 * (en produccion viven en Redis).
 */
public interface AlmacenFallos {

    /** Suma uno al contador; si es el primero, le pone el vencimiento. Devuelve el valor nuevo. */
    Mono<Long> incrementar(String clave, Duration vencimiento);

    Mono<Boolean> existe(String clave);

    Mono<Boolean> marcar(String clave, Duration vencimiento);
}
