package com.artagram.gateway;

import com.artagram.gateway.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        // Cloud Map publica las replicas con TTL de 10 s: la JVM no debe
        // recordar la IP de una replica caida mas que eso.
        java.security.Security.setProperty("networkaddress.cache.ttl", "5");
        java.security.Security.setProperty("networkaddress.cache.negative.ttl", "1");
        SpringApplication.run(GatewayApplication.class, args);
    }
}
