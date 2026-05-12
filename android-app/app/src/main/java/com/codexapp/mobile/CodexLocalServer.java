package com.codexapp.mobile;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CodexLocalServer {
    private static final int PORT = 37645;
    private static final String ASSET_ROOT = "codexui";
    private static final Object LOCK = new Object();

    private static LocalHttpServer server;

    private CodexLocalServer() {
    }

    public static String getBaseUrl() {
        return "http://127.0.0.1:" + PORT;
    }

    public static void ensureStarted(Context context) {
        synchronized (LOCK) {
            if (server != null && server.isRunning()) {
                return;
            }
            server = new LocalHttpServer(context.getApplicationContext(), PORT);
            server.start();
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            if (server == null) {
                return;
            }
            server.stop();
            server = null;
        }
    }

    private static final class LocalHttpServer implements Runnable {
        private final Context context;
        private final int port;
        private final ExecutorService clients = Executors.newCachedThreadPool();
        private volatile boolean running;
        private ServerSocket serverSocket;
        private Thread thread;

        LocalHttpServer(Context context, int port) {
            this.context = context;
            this.port = port;
        }

        boolean isRunning() {
            return running;
        }

        void start() {
            try {
                serverSocket = new ServerSocket(port, 16);
            } catch (IOException ignored) {
                running = false;
                return;
            }
            running = true;
            thread = new Thread(this, "codex-local-http");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running = false;
            clients.shutdownNow();
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }
            }
        }

        @Override
        public void run() {
            try {
                ServerSocket socket = serverSocket;
                while (running) {
                    Socket client = socket.accept();
                    clients.execute(() -> handleClient(client));
                }
            } catch (IOException ignored) {
                running = false;
            }
        }

        private void handleClient(Socket client) {
            try (Socket socket = client) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String requestLine = reader.readLine();
                if (requestLine == null || requestLine.isEmpty()) {
                    return;
                }
                while (true) {
                    String header = reader.readLine();
                    if (header == null || header.isEmpty()) {
                        break;
                    }
                }

                String[] parts = requestLine.split(" ");
                String method = parts.length > 0 ? parts[0] : "GET";
                String path = parts.length > 1 ? parts[1] : "/";
                if (!"GET".equals(method) && !"HEAD".equals(method)) {
                    writeText(socket.getOutputStream(), 405, "application/json", "{\"error\":\"method_not_allowed\"}", "HEAD".equals(method));
                    return;
                }

                if (path.startsWith("/android/health")) {
                    writeText(socket.getOutputStream(), 200, "application/json", "{\"ok\":true,\"platform\":\"android\"}", "HEAD".equals(method));
                    return;
                }
                if (path.startsWith("/android/runtime")) {
                    writeText(socket.getOutputStream(), 200, "application/json", "{\"platform\":\"android\",\"host\":\"local-service\",\"codexRuntime\":\"pending\"}", "HEAD".equals(method));
                    return;
                }

                serveAsset(socket.getOutputStream(), path, "HEAD".equals(method));
            } catch (IOException ignored) {
            }
        }

        private void serveAsset(OutputStream output, String rawPath, boolean headOnly) throws IOException {
            String path = normalizePath(rawPath);
            AssetManager assets = context.getAssets();
            String assetPath = ASSET_ROOT + path;
            try (InputStream input = assets.open(assetPath)) {
                byte[] body = readAll(input);
                writeBytes(output, 200, contentType(path), body, headOnly);
            } catch (IOException missing) {
                try (InputStream input = assets.open(ASSET_ROOT + "/index.html")) {
                    byte[] body = readAll(input);
                    writeBytes(output, 200, "text/html; charset=utf-8", body, headOnly);
                }
            }
        }

        private static String normalizePath(String rawPath) {
            String path = rawPath;
            int queryIndex = path.indexOf('?');
            if (queryIndex >= 0) {
                path = path.substring(0, queryIndex);
            }
            if (path.equals("/") || path.isEmpty()) {
                return "/index.html";
            }
            path = path.replace('\\', '/');
            while (path.contains("//")) {
                path = path.replace("//", "/");
            }
            if (path.contains("..")) {
                return "/index.html";
            }
            return path.startsWith("/") ? path : "/" + path;
        }

        private static String contentType(String path) {
            String lower = path.toLowerCase(Locale.US);
            if (lower.endsWith(".html")) return "text/html; charset=utf-8";
            if (lower.endsWith(".js")) return "text/javascript; charset=utf-8";
            if (lower.endsWith(".css")) return "text/css; charset=utf-8";
            if (lower.endsWith(".json") || lower.endsWith(".webmanifest")) return "application/json; charset=utf-8";
            if (lower.endsWith(".svg")) return "image/svg+xml";
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".webp")) return "image/webp";
            return "application/octet-stream";
        }

        private static byte[] readAll(InputStream input) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }

        private static void writeText(OutputStream output, int status, String type, String body, boolean headOnly) throws IOException {
            writeBytes(output, status, type, body.getBytes(StandardCharsets.UTF_8), headOnly);
        }

        private static void writeBytes(OutputStream output, int status, String type, byte[] body, boolean headOnly) throws IOException {
            String reason = status == 200 ? "OK" : status == 405 ? "Method Not Allowed" : "Error";
            String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n"
                + "\r\n";
            output.write(headers.getBytes(StandardCharsets.UTF_8));
            if (!headOnly) {
                output.write(body);
            }
            output.flush();
        }
    }
}
