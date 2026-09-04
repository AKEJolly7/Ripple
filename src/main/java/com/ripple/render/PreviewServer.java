package com.ripple.render;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 本地预览服务器（可选）：http://localhost:8080 预览生成的 HTML。
 * 只读单文件静态资源、正确 Content-Type、无 cookie、仅绑定 127.0.0.1。
 */
public final class PreviewServer {

    private PreviewServer() {
    }

    public static void start(Path file, int port) throws IOException {
        byte[] body = Files.readAllBytes(file);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> {
            String method = ex.getRequestMethod();
            if (!Objects.equals(method, "GET") && !Objects.equals(method, "HEAD")) {
                ex.getResponseHeaders().set("Allow", "GET, HEAD");
                ex.sendResponseHeaders(405, -1);
                ex.close();
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            if (Objects.equals(method, "HEAD")) {
                ex.sendResponseHeaders(200, -1);
                ex.close();
                return;
            }
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(null);
        server.start();
        System.out.printf("预览服务: http://localhost:%d/（Ctrl+C 退出）%n", port);
    }
}
