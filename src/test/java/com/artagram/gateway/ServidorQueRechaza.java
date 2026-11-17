package com.artagram.gateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Servidor HTTP local que responde 403 a todo. Sirve para probar los caminos
 * de error contra S3 (o cualquier endpoint) sin depender de la red: la
 * respuesta llega al instante y un 4xx no dispara los reintentos del cliente.
 */
final class ServidorQueRechaza implements AutoCloseable {

    private final HttpServer servidor;

    ServidorQueRechaza() {
        try {
            servidor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        servidor.createContext("/", intercambio -> {
            intercambio.getRequestBody().readAllBytes();
            intercambio.sendResponseHeaders(403, -1);
            intercambio.close();
        });
        servidor.start();
    }

    String url() {
        return "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    @Override
    public void close() {
        servidor.stop(0);
    }
}
