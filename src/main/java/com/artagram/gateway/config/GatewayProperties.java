package com.artagram.gateway.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.gateway")
public class GatewayProperties {

    private boolean jwtEnabled = false;
    private String jwksUri = "http://localhost:8081/.well-known/jwks.json";
    private List<String> corsAllowedOrigins = List.of("http://localhost:5173");
    private int rateLimitPerSecond = 120;

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
}
