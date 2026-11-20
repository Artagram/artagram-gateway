package com.artagram.gateway.config;

import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultNameResolver;
import io.netty.resolver.RoundRobinInetAddressResolver;
import io.netty.util.concurrent.EventExecutor;
import java.net.InetSocketAddress;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Reparte las conexiones hacia los microservicios entre todas sus réplicas.
 * <p>
 * Cloud Map responde {@code <servicio>.artagram.local} con la IP de cada
 * réplica, pero el resolver de la JVM se queda con la primera durante los 5 s
 * que la guarda en caché ({@code networkaddress.cache.ttl}): todo lo que se
 * conectaba en esa ventana caía en la misma tarea. Con muchos WebSocket
 * abriéndose juntos (escenario 1) una réplica de canvas quedaba al 100 % y las
 * otras casi sin uso. Con esto cada conexión nueva elige una IP al azar entre
 * las que devolvió el DNS. Aplica también al proxy de WebSocket, que usa el
 * mismo {@code HttpClient} del gateway.
 */
@Configuration
public class ReparticionReplicasConfig {

    @Bean
    HttpClientCustomizer repartirEntreReplicas() {
        return cliente -> cliente.resolver(new ResolverAleatorio());
    }

    static class ResolverAleatorio extends AddressResolverGroup<InetSocketAddress> {
        @Override
        protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
            return new RoundRobinInetAddressResolver(executor, new DefaultNameResolver(executor)).asAddressResolver();
        }
    }
}
