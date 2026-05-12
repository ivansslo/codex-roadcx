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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CodexLocalServer {
    private static final int PORT = 37645;
    private static final String ASSET_ROOT = "codexui";
    private static final Object LOCK = new Object();
    private static final String METHOD_CATALOG = "{\"data\":["
        + "\"account/rateLimits/read\","
        + "\"app/list\","
        + "\"collaborationMode/list\","
        + "\"config/batchWrite\","
        + "\"config/mcpServer/reload\","
        + "\"config/read\","
        + "\"generate-thread-title\","
        + "\"mcpServer/oauth/login\","
        + "\"mcpServerStatus/list\","
        + "\"model/list\","
        + "\"plugin/install\","
        + "\"plugin/list\","
        + "\"plugin/read\","
        + "\"plugin/uninstall\","
        + "\"review/start\","
        + "\"setDefaultModel\","
        + "\"skills/list\","
        + "\"thread/archive\","
        + "\"thread/fork\","
        + "\"thread/list\","
        + "\"thread/name/set\","
        + "\"thread/read\","
        + "\"thread/resume\","
        + "\"thread/rollback\","
        + "\"thread/start\","
        + "\"turn/interrupt\","
        + "\"turn/start\""
        + "]}";
    private static final CopyOnWriteArrayList<NotificationClient> NOTIFICATION_CLIENTS = new CopyOnWriteArrayList<>();

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

    public static void broadcastNotification(String notificationJson) {
        byte[] body = ("data: " + notificationJson + "\n\n").getBytes(StandardCharsets.UTF_8);
        for (NotificationClient client : NOTIFICATION_CLIENTS) {
            if (!client.write(body)) {
                NOTIFICATION_CLIENTS.remove(client);
            }
        }
    }

    private static final class NotificationClient {
        private final OutputStream output;

        NotificationClient(OutputStream output) {
            this.output = output;
        }

        synchronized boolean write(byte[] body) {
            try {
                output.write(body);
                output.flush();
                return true;
            } catch (IOException ignored) {
                return false;
            }
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
                Map<String, String> headers = new HashMap<>();
                while (true) {
                    String header = reader.readLine();
                    if (header == null || header.isEmpty()) {
                        break;
                    }
                    int separator = header.indexOf(':');
                    if (separator > 0) {
                        headers.put(
                            header.substring(0, separator).trim().toLowerCase(Locale.US),
                            header.substring(separator + 1).trim()
                        );
                    }
                }

                String[] parts = requestLine.split(" ");
                String method = parts.length > 0 ? parts[0] : "GET";
                String path = parts.length > 1 ? parts[1] : "/";
                if (!"GET".equals(method) && !"HEAD".equals(method) && !"POST".equals(method) && !"PUT".equals(method)) {
                    writeText(socket.getOutputStream(), 405, "application/json", "{\"error\":\"method_not_allowed\"}", "HEAD".equals(method));
                    return;
                }

                if (path.startsWith("/android/health")) {
                    writeText(socket.getOutputStream(), 200, "application/json", "{\"ok\":true,\"platform\":\"android\"}", "HEAD".equals(method));
                    return;
                }
                if (path.startsWith("/android/runtime")) {
                    writeText(socket.getOutputStream(), 200, "application/json", CodexRuntimeProcess.get(context).getStatus().toJson(), "HEAD".equals(method));
                    return;
                }
                if (path.startsWith("/codex-api/")) {
                    if ("GET".equals(method) && path.startsWith("/codex-api/events")) {
                        handleEvents(socket, "HEAD".equals(method));
                        return;
                    }
                    handleCodexApi(socket.getOutputStream(), method, path, readBody(reader, headers), "HEAD".equals(method));
                    return;
                }
                if (path.startsWith("/codex-local-directories")) {
                    try {
                        writeText(socket.getOutputStream(), 200, "application/json", CodexAndroidProjects.listDirectories(context, path), "HEAD".equals(method));
                    } catch (Exception error) {
                        writeText(socket.getOutputStream(), 500, "application/json", "{\"error\":\"" + jsonEscape(error.getMessage()) + "\"}", "HEAD".equals(method));
                    }
                    return;
                }

                serveAsset(socket.getOutputStream(), path, "HEAD".equals(method));
            } catch (IOException ignored) {
            }
        }

        private void handleCodexApi(OutputStream output, String method, String path, String body, boolean headOnly) throws IOException {
            if ("GET".equals(method) && path.startsWith("/codex-api/meta/methods")) {
                writeText(output, 200, "application/json", METHOD_CATALOG, headOnly);
                return;
            }
            if ("GET".equals(method) && path.startsWith("/codex-api/meta/notifications")) {
                writeText(output, 200, "application/json", "{\"data\":[]}", headOnly);
                return;
            }
            if ("GET".equals(method) && path.startsWith("/codex-api/server-requests/pending")) {
                writeText(output, 200, "application/json", "{\"data\":[]}", headOnly);
                return;
            }
            if ("GET".equals(method) && path.startsWith("/codex-api/home-directory")) {
                writeText(output, 200, "application/json", CodexAndroidProjects.homeDirectory(context), headOnly);
                return;
            }
            if ("GET".equals(method) && path.startsWith("/codex-api/workspace-roots-state")) {
                try {
                    writeText(output, 200, "application/json", CodexAndroidProjects.workspaceRootsState(context), headOnly);
                } catch (Exception error) {
                    writeText(output, 500, "application/json", "{\"error\":\"" + jsonEscape(error.getMessage()) + "\"}", headOnly);
                }
                return;
            }
            if ("PUT".equals(method) && path.startsWith("/codex-api/workspace-roots-state")) {
                try {
                    writeText(output, 200, "application/json", CodexAndroidProjects.saveWorkspaceRootsState(context, body), headOnly);
                } catch (Exception error) {
                    writeText(output, 500, "application/json", "{\"error\":\"" + jsonEscape(error.getMessage()) + "\"}", headOnly);
                }
                return;
            }
            if ("GET".equals(method) && path.startsWith("/codex-api/thread-queue-state")) {
                writeText(output, 200, "application/json", CodexAndroidProjects.threadQueueState(context), headOnly);
                return;
            }
            if ("PUT".equals(method) && path.startsWith("/codex-api/thread-queue-state")) {
                writeText(output, 200, "application/json", CodexAndroidProjects.saveThreadQueueState(context, body), headOnly);
                return;
            }
            if ("POST".equals(method) && path.startsWith("/codex-api/project-root")) {
                try {
                    writeText(output, 200, "application/json", CodexAndroidProjects.openProjectRoot(context, body), headOnly);
                } catch (Exception error) {
                    writeText(output, 500, "application/json", "{\"error\":\"" + jsonEscape(error.getMessage()) + "\"}", headOnly);
                }
                return;
            }
            if ("POST".equals(method) && path.startsWith("/codex-api/local-directory")) {
                try {
                    writeText(output, 200, "application/json", CodexAndroidProjects.createLocalDirectory(context, body), headOnly);
                } catch (Exception error) {
                    writeText(output, 500, "application/json", "{\"error\":\"" + jsonEscape(error.getMessage()) + "\"}", headOnly);
                }
                return;
            }
            if ("POST".equals(method) && path.startsWith("/codex-api/projectless-thread-cwd")) {
                try {
                    writeText(output, 200, "application/json", CodexAndroidProjects.createProjectlessThreadDirectory(context, body), headOnly);
                } catch (Exception error) {
                    writeText(output, 500, "application/json", "{\"error\":\"" + jsonEscape(error.getMessage()) + "\"}", headOnly);
                }
                return;
            }
            if ("GET".equals(method) && path.startsWith("/codex-api/project-root-suggestion")) {
                try {
                    writeText(output, 200, "application/json", CodexAndroidProjects.projectRootSuggestion(context, path), headOnly);
                } catch (Exception error) {
                    writeText(output, 500, "application/json", "{\"error\":\"" + jsonEscape(error.getMessage()) + "\"}", headOnly);
                }
                return;
            }
            if ("GET".equals(method) && path.startsWith("/codex-api/accounts/active")) {
                writeText(output, 200, "application/json", CodexAndroidAccounts.listAccounts(context), headOnly);
                return;
            }
            if ("GET".equals(method) && path.startsWith("/codex-api/accounts")) {
                writeText(output, 200, "application/json", CodexAndroidAccounts.listAccounts(context), headOnly);
                return;
            }
            if ("POST".equals(method) && path.startsWith("/codex-api/accounts/refresh")) {
                writeText(output, 200, "application/json", CodexAndroidAccounts.refresh(context), headOnly);
                return;
            }
            if ("POST".equals(method) && path.startsWith("/codex-api/accounts/switch")) {
                writeText(output, 200, "application/json", CodexAndroidAccounts.switchActive(context, body), headOnly);
                return;
            }
            if ("POST".equals(method) && path.startsWith("/codex-api/accounts/remove")) {
                writeText(output, 200, "application/json", CodexAndroidAccounts.remove(context, body), headOnly);
                return;
            }
            if ("POST".equals(method) && path.startsWith("/codex-api/accounts/login/start")) {
                try {
                    writeText(output, 200, "application/json", CodexAndroidAccounts.startLogin(context), headOnly);
                } catch (Exception error) {
                    writeText(output, 500, "application/json", "{\"error\":\"account_login_start_failed\",\"message\":\"" + jsonEscape(error.getMessage()) + "\"}", headOnly);
                }
                return;
            }
            if ("POST".equals(method) && path.startsWith("/codex-api/accounts/login/complete")) {
                try {
                    writeText(output, 200, "application/json", CodexAndroidAccounts.completeLogin(context, body), headOnly);
                } catch (Exception error) {
                    writeText(output, 500, "application/json", "{\"error\":\"account_login_complete_failed\",\"message\":\"" + jsonEscape(error.getMessage()) + "\"}", headOnly);
                }
                return;
            }
            if ("POST".equals(method) && path.startsWith("/codex-api/server-requests/respond")) {
                writeText(output, 200, "application/json", "{\"ok\":true}", headOnly);
                return;
            }
            if ("POST".equals(method) && path.startsWith("/codex-api/rpc")) {
                try {
                    String response = CodexRuntimeProcess.get(context).callRpc(body);
                    writeText(output, 200, "application/json", response, headOnly);
                } catch (Exception error) {
                    writeText(output, 503, "application/json", "{\"error\":\"" + jsonEscape(error.getMessage()) + "\"}", headOnly);
                }
                return;
            }

            writeText(output, 404, "application/json", "{\"error\":\"android_endpoint_not_implemented\"}", headOnly);
        }

        private static String readBody(BufferedReader reader, Map<String, String> headers) throws IOException {
            int length = 0;
            try {
                String rawLength = headers.get("content-length");
                if (rawLength != null) {
                    length = Integer.parseInt(rawLength);
                }
            } catch (NumberFormatException ignored) {
                length = 0;
            }
            if (length <= 0) {
                return "";
            }
            char[] body = new char[length];
            int offset = 0;
            while (offset < length) {
                int read = reader.read(body, offset, length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            return new String(body, 0, offset);
        }

        private void handleEvents(Socket socket, boolean headOnly) throws IOException {
            OutputStream output = socket.getOutputStream();
            byte[] ready = "event: ready\ndata: {\"ok\":true,\"platform\":\"android\"}\n\n".getBytes(StandardCharsets.UTF_8);
            String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: keep-alive\r\n"
                + "X-Accel-Buffering: no\r\n"
                + "\r\n";
            output.write(headers.getBytes(StandardCharsets.UTF_8));
            if (headOnly) {
                output.flush();
                return;
            }

            NotificationClient client = new NotificationClient(output);
            NOTIFICATION_CLIENTS.add(client);
            try {
                if (!client.write(ready)) {
                    return;
                }
                byte[] ping = ": ping\n\n".getBytes(StandardCharsets.UTF_8);
                while (running && !socket.isClosed()) {
                    try {
                        Thread.sleep(15_000L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!client.write(ping)) {
                        return;
                    }
                }
            } finally {
                NOTIFICATION_CLIENTS.remove(client);
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

        private static String jsonEscape(String value) {
            if (value == null) {
                return "";
            }
            return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
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
            String reason = status == 200 ? "OK" : status == 404 ? "Not Found" : status == 405 ? "Method Not Allowed" : status == 503 ? "Service Unavailable" : "Error";
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
