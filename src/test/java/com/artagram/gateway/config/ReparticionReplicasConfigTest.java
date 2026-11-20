package com.artagram.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.resolver.AddressResolver;
import io.netty.util.concurrent.ImmediateEventExecutor;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import reactor.netty.http.client.HttpClient;

class ReparticionReplicasConfigTest {

    @Test
    void elClienteDelGatewayUsaElResolverQueReparte() {
        HttpClient cliente = new ReparticionReplicasConfig().repartirEntreReplicas().customize(HttpClient.create());
        assertThat(cliente.configuration().resolver()).isInstanceOf(ReparticionReplicasConfig.ResolverAleatorio.class);
    }

    @Test
    void resuelveNombresANUnaDeSusDirecciones() throws Exception {
        AddressResolver<InetSocketAddress> resolver =
                new ReparticionReplicasConfig.ResolverAleatorio().getResolver(ImmediateEventExecutor.INSTANCE);
        InetSocketAddress direccion = resolver.resolve(InetSocketAddress.createUnresolved("localhost", 8084)).get();
        assertThat(direccion.isUnresolved()).isFalse();
        assertThat(direccion.getPort()).isEqualTo(8084);
        assertThat(direccion.getAddress().isLoopbackAddress()).isTrue();
    }
}
