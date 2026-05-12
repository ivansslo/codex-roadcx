package com.codexapp.mobile;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public final class CodexRuntimeProcess {
    private static final int RPC_TIMEOUT_SECONDS = 90;
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    private static final Object LOCK = new Object();

    private static CodexRuntimeProcess instance;

    private final Context context;
    private final Map<Integer, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private Process process;
    private BufferedWriter stdin;
    private Thread stdoutThread;
    private Thread stderrThread;

    private CodexRuntimeProcess(Context context) {
        this.context = context.getApplicationContext();
    }

    public static CodexRuntimeProcess get(Context context) {
        synchronized (LOCK) {
            if (instance == null) {
                instance = new CodexRuntimeProcess(context);
            }
            return instance;
        }
    }

    public synchronized RuntimeStatus getStatus() {
        File executable = getExecutable();
        return new RuntimeStatus(executable.getAbsolutePath(), executable.canExecute(), process != null && process.isAlive());
    }

    public synchronized String callRpc(String httpBody) throws IOException, JSONException, TimeoutException {
        ensureStarted();

        JSONObject incoming = new JSONObject(httpBody);
        String method = incoming.optString("method", "");
        if (method.isEmpty()) {
            return "{\"error\":\"Invalid body: expected { method, params? }\"}";
        }

        int id = NEXT_ID.getAndIncrement();
        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", incoming.has("params") ? incoming.get("params") : JSONObject.NULL);

        CompletableFuture<String> future = new CompletableFuture<>();
        pending.put(id, future);
        stdin.write(request.toString());
        stdin.write("\n");
        stdin.flush();

        try {
            String response = future.get(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            JSONObject parsed = new JSONObject(response);
            if (parsed.has("result")) {
                JSONObject envelope = new JSONObject();
                envelope.put("result", parsed.get("result"));
                return envelope.toString();
            }
            if (parsed.has("error")) {
                JSONObject envelope = new JSONObject();
                envelope.put("error", parsed.get("error"));
                return envelope.toString();
            }
            return "{\"error\":\"Malformed Codex app-server response\"}";
        } catch (TimeoutException error) {
            pending.remove(id);
            throw error;
        } catch (Exception error) {
            pending.remove(id);
            throw new IOException("Codex RPC failed", error);
        }
    }

    private synchronized void ensureStarted() throws IOException {
        if (process != null && process.isAlive() && stdin != null) {
            return;
        }

        File executable = getExecutable();
        if (!executable.exists()) {
            throw new IOException("Codex runtime is not installed at " + executable.getAbsolutePath());
        }
        if (!executable.canExecute() && !executable.setExecutable(true)) {
            throw new IOException("Codex runtime is not executable at " + executable.getAbsolutePath());
        }

        File codexHome = new File(context.getFilesDir(), "codex-home");
        File workspaceRoot = new File(context.getFilesDir(), "workspaces");
        if (!codexHome.exists() && !codexHome.mkdirs()) {
            throw new IOException("Failed to create " + codexHome.getAbsolutePath());
        }
        if (!workspaceRoot.exists() && !workspaceRoot.mkdirs()) {
            throw new IOException("Failed to create " + workspaceRoot.getAbsolutePath());
        }

        ProcessBuilder builder = new ProcessBuilder(
            executable.getAbsolutePath(),
            "app-server",
            "-c",
            "approval_policy=\"never\"",
            "-c",
            "sandbox_mode=\"danger-full-access\""
        );
        builder.directory(workspaceRoot);
        builder.environment().put("HOME", context.getFilesDir().getAbsolutePath());
        builder.environment().put("CODEX_HOME", codexHome.getAbsolutePath());
        builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());

        process = builder.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        startReaders(process);
    }

    private void startReaders(Process currentProcess) {
        stdoutThread = new Thread(() -> readStdout(currentProcess), "codex-runtime-stdout");
        stdoutThread.setDaemon(true);
        stdoutThread.start();

        stderrThread = new Thread(() -> drainStderr(currentProcess), "codex-runtime-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    private void readStdout(Process currentProcess) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(currentProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                dispatchRuntimeLine(line);
            }
        } catch (IOException ignored) {
        }
    }

    private void dispatchRuntimeLine(String line) {
        try {
            JSONObject parsed = new JSONObject(line);
            if (!parsed.has("id")) {
                return;
            }
            int id = parsed.getInt("id");
            CompletableFuture<String> future = pending.remove(id);
            if (future != null) {
                future.complete(line);
            }
        } catch (JSONException ignored) {
        }
    }

    private void drainStderr(Process currentProcess) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(currentProcess.getErrorStream(), StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) {
                // Keep stderr drained so the process cannot block on a full pipe.
            }
        } catch (IOException ignored) {
        }
    }

    private File getExecutable() {
        return new File(new File(context.getFilesDir(), "codex-runtime"), "codex");
    }

    public static final class RuntimeStatus {
        public final String executablePath;
        public final boolean executable;
        public final boolean running;

        RuntimeStatus(String executablePath, boolean executable, boolean running) {
            this.executablePath = executablePath;
            this.executable = executable;
            this.running = running;
        }

        public String toJson() {
            return "{\"platform\":\"android\",\"host\":\"local-service\",\"codexRuntime\":\""
                + (running ? "running" : executable ? "installed" : "missing")
                + "\",\"executablePath\":\"" + escape(executablePath) + "\"}";
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
