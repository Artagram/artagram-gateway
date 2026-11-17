package com.artagram.gateway.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.gateway")
public class GatewayProperties {

    private boolean jwtEnabled = false;
    private String jwksUri = "http://localhost:8081/.well-known/jwks.json";
    private List<String> corsAllowedOrigins = List.of("http://localhost:5173");
    private int rateLimitPerSecond = 120;

    /**
     * Proxies de confianza delante del gateway que agregan X-Forwarded-For
     * (CloudFront + ALB = 2). Con 0 se usa la direccion de la conexion TCP.
     */
    private int proxiesConfiables = 0;

    /** Intentos de autenticacion fallidos permitidos por origen dentro de la ventana. */
    private int maxFallosAutenticacion = 10;
    private Duration ventanaFallos = Duration.ofMinutes(1);
    private Duration duracionBloqueo = Duration.ofMinutes(15);

    public boolean isJwtEnabled() {
        return jwtEnabled;
    }

    public void setJwtEnabled(boolean jwtEnabled) {
        this.jwtEnabled = jwtEnabled;
    }

    public String getJwksUri() {
        return jwksUri;
    }

    public void setJwksUri(String jwksUri) {
        this.jwksUri = jwksUri;
    }

    public List<String> getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins;
    }

    public int getRateLimitPerSecond() {
        return rateLimitPerSecond;
    }

    public void setRateLimitPerSecond(int rateLimitPerSecond) {
        this.rateLimitPerSecond = rateLimitPerSecond;
    }

    public int getProxiesConfiables() {
        return proxiesConfiables;
    }

    public void setProxiesConfiables(int proxiesConfiables) {
        this.proxiesConfiables = proxiesConfiables;
    }

    public int getMaxFallosAutenticacion() {
        return maxFallosAutenticacion;
    }

    public void setMaxFallosAutenticacion(int maxFallosAutenticacion) {
        this.maxFallosAutenticacion = maxFallosAutenticacion;
    }

    public Duration getVentanaFallos() {
        return ventanaFallos;
    }

    public void setVentanaFallos(Duration ventanaFallos) {
        this.ventanaFallos = ventanaFallos;
    }

    public Duration getDuracionBloqueo() {
        return duracionBloqueo;
    }

    public void setDuracionBloqueo(Duration duracionBloqueo) {
        this.duracionBloqueo = duracionBloqueo;
    }
}
