# ArtaGram — API Gateway (`artagram-gateway`)

Punto de entrada único HTTP y WebSocket (`/ws`) en el puerto `:8080`, construido sobre **Spring Cloud Gateway (WebFlux)**.

## Responsabilidades
- **Enrutamiento Strangler Fig:** Redirige cada prefijo de API (`/api/auth/**`, `/api/perfiles/**`, `/api/feed/**`, `/api/mensajes/**`, `/api/comunidades/**`, `/ws/**`) al microservicio correspondiente (configurable por variables de entorno).
- **Seguridad perimetral:**
  - Bloquea todas las rutas `/internal/**` desde el exterior (`403 SIN_PERMISO`).
  - Valida tokens JWT RS256 contra el endpoint `/.well-known/jwks.json` de `artagram-auth` (`GATEWAY_JWT_ENABLED=true`) y propaga `X-User-Id`, `X-User-Name` y `X-User-Role`.
- **Rate Limiting:** Bucket4j por usuario autenticado (`X-User-Id`) o dirección IP (`429 LIMITE_PETICIONES`).
- **Trazabilidad y CORS:** Genera y propaga `X-Trace-Id` y centraliza CORS (`CORS_ORIGINS`).
