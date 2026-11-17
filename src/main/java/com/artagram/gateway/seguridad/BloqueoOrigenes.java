package com.artagram.gateway.seguridad;

import com.artagram.gateway.config.GatewayProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Registra cada peticion rechazada por autenticacion y bloquea el origen que
 * acumula demasiados fallos en la ventana (escenario 6: 10 en un minuto
 * bloquean 15 minutos). Si Redis no responde se deja pasar: es preferible no
 * bloquear a nadie que bloquear a todos.
 */
@Component
public class BloqueoOrigenes {

    private static final Logger log = LoggerFactory.getLogger(BloqueoOrigenes.class);
    private static final String PREFIJO_FALLOS = "gateway:auth-fallos:";
    private static final String PREFIJO_BLOQUEO = "gateway:auth-bloqueo:";

    private final AlmacenFallos almacen;
    private final GatewayProperties properties;
    private final MeterRegistry registry;
    private final Counter bloqueos;

    public BloqueoOrigenes(AlmacenFallos almacen, GatewayProperties properties, MeterRegistry registry) {
        this.almacen = almacen;
        this.properties = properties;
        this.registry = registry;
        this.bloqueos = Counter.builder("gateway_origenes_bloqueados_total")
                .description("Origenes bloqueados por exceso de intentos de autenticacion fallidos")
                .register(registry);
    }

    public Mono<Boolean> estaBloqueado(String origen) {
        return almacen.existe(PREFIJO_BLOQUEO + origen)
                .onErrorResume(e -> {
                    log.warn("No se pudo consultar el bloqueo de origenes en Redis: {}", e.getMessage());
                    return Mono.just(false);
                })
                .defaultIfEmpty(false);
    }

    /** Registra el fallo y devuelve true si con este intento el origen quedo bloqueado. */
    public Mono<Boolean> registrarFallo(String origen, String ruta, String motivo, String traceId) {
        registry.counter("gateway_autenticacion_rechazada_total", "motivo", motivo).increment();
        log.warn("Peticion rechazada por autenticacion", kv("evento", "AUTENTICACION_RECHAZADA"),
                kv("origen", origen), kv("ruta", limpiar(ruta)), kv("motivo", motivo), kv("traceId", traceId));
        return almacen.incrementar(PREFIJO_FALLOS + origen, properties.getVentanaFallos())
                .flatMap(fallos -> fallos == properties.getMaxFallosAutenticacion()
                        ? bloquear(origen, fallos)
                        : Mono.just(false))
                .onErrorResume(e -> {
                    log.warn("No se pudo registrar el intento fallido en Redis: {}", e.getMessage());
                    return Mono.just(false);
                })
                .defaultIfEmpty(false);
    }

    private Mono<Boolean> bloquear(String origen, long fallos) {
        bloqueos.increment();
        log.warn("Origen bloqueado por intentos fallidos", kv("evento", "ORIGEN_BLOQUEADO"),
                kv("origen", origen), kv("fallos", fallos),
                kv("minutos", properties.getDuracionBloqueo().toMinutes()));
        return almacen.marcar(PREFIJO_BLOQUEO + origen, properties.getDuracionBloqueo()).thenReturn(true);
    }

    /** La ruta viene del cliente: se quitan los saltos de linea antes de llevarla al log. */
    static String limpiar(String valor) {
        return valor == null ? "" : valor.replaceAll("[\\r\\n\\t]", "_");
    }
}
