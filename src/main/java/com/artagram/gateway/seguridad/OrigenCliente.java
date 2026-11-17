package com.artagram.gateway.seguridad;

import com.artagram.gateway.config.GatewayProperties;
import java.net.InetSocketAddress;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * Resuelve la IP real del cliente. Detras de CloudFront y el ALB la conexion
 * TCP llega desde el ALB, asi que se lee X-Forwarded-For contando solo las
 * entradas que agregaron los proxies de confianza (las de la izquierda las
 * puede inventar el cliente).
 */
@Component
public class OrigenCliente {

    static final String DESCONOCIDO = "desconocido";

    private final RemoteAddressResolver resolver;

    public OrigenCliente(GatewayProperties properties) {
        int proxies = properties.getProxiesConfiables();
        this.resolver = proxies > 0
                ? XForwardedRemoteAddressResolver.maxTrustedIndex(proxies)
                : new RemoteAddressResolver() { };
    }

    public String resolver(ServerWebExchange exchange) {
        InetSocketAddress direccion = resolver.resolve(exchange);
        if (direccion == null) {
            return DESCONOCIDO;
        }
        if (direccion.getAddress() != null) {
            return direccion.getAddress().getHostAddress();
        }
        return direccion.getHostString();
    }
}
