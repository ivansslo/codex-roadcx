package com.codexapp.mobile;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
    private static final String RUNTIME_ASSET_ROOT = "codex-runtime";
    private static final String RUNTIME_VERSION_FILE = ".asset-version";
    private static final String RUNTIME_ASSET_VERSION = "1";
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
        try {
            installRuntimeAssetsIfAvailable();
        } catch (IOException ignored) {
            // Status should remain best-effort; callRpc reports install failures explicitly.
        }
        File executable = getExecutable();
        return new RuntimeStatus(executable.getAbsolutePath(), executable.canExecute(), process != null && process.isAlive());
    }

    public synchronized String callRpc(String httpBody) throws IOException, JSONException, TimeoutException {
        JSONObject incoming = new JSONObject(httpBody);
        String method = incoming.optString("method", "");
        if (method.isEmpty()) {
            return "{\"error\":\"Invalid body: expected { method, params? }\"}";
        }

        String androidFallback = getAndroidCatalogFallback(method);
        if (androidFallback != null) {
            return androidFallback;
        }

        ensureStarted();

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

    private static String getAndroidCatalogFallback(String method) {
        switch (method) {
            case "plugin/list":
                return "{\"result\":{\"marketplaces\":[]}}";
            case "plugin/read":
                return "{\"result\":{\"plugin\":null}}";
            case "plugin/install":
                return "{\"result\":{\"authPolicy\":\"NONE\",\"appsNeedingAuth\":[]}}";
            case "plugin/uninstall":
            case "config/mcpServer/reload":
            case "config/batchWrite":
                return "{\"result\":{}}";
            case "app/list":
            case "mcpServerStatus/list":
                return "{\"result\":{\"data\":[],\"nextCursor\":null}}";
            case "skills/list":
                return "{\"result\":{\"data\":[]}}";
            case "account/rateLimits/read":
                return "{\"result\":{\"primary\":null,\"secondary\":null}}";
            case "collaborationMode/list":
                return "{\"result\":{\"data\":[]}}";
            default:
                return null;
        }
    }

    private synchronized void ensureStarted() throws IOException {
        if (process != null && process.isAlive() && stdin != null) {
            return;
        }

        File executable = getExecutable();
        installRuntimeAssetsIfAvailable();
        if (!executable.exists()) {
            throw new IOException("Codex runtime is not installed at " + executable.getAbsolutePath());
        }
        if (!executable.canExecute() && !executable.setExecutable(true)) {
            throw new IOException("Codex runtime is not executable at " + executable.getAbsolutePath());
        }

        File codexHome = getCodexHomeDirectory();
        File workspaceRoot = getWorkspaceDirectory();
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
        populateRuntimeEnvironment(builder.environment());

        process = builder.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        startReaders(process);
    }

    private void installRuntimeAssetsIfAvailable() throws IOException {
        File runtimeDirectory = getRuntimeDirectory();
        if (!runtimeDirectory.exists() && !runtimeDirectory.mkdirs()) {
            throw new IOException("Failed to create " + runtimeDirectory.getAbsolutePath());
        }

        File versionFile = new File(runtimeDirectory, RUNTIME_VERSION_FILE);
        File executable = getExecutable();
        File sharedLibrary = new File(runtimeDirectory, "libc++_shared.so");
        if (executable.exists() && sharedLibrary.exists() && versionFile.exists()) {
            return;
        }

        if (!assetExists(RUNTIME_ASSET_ROOT + "/codex.bin")) {
            return;
        }
        copyAsset(RUNTIME_ASSET_ROOT + "/codex.bin", executable);
        copyAsset(RUNTIME_ASSET_ROOT + "/libc++_shared.so", sharedLibrary);
        if (!executable.setExecutable(true)) {
            throw new IOException("Failed to mark Codex runtime executable at " + executable.getAbsolutePath());
        }
        writeVersionFile(versionFile);
    }

    private boolean assetExists(String assetPath) {
        try (InputStream ignored = context.getAssets().open(assetPath)) {
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private void copyAsset(String assetPath, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create " + parent.getAbsolutePath());
        }
        try (InputStream input = context.getAssets().open(assetPath);
             FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private void writeVersionFile(File versionFile) throws IOException {
        try (FileOutputStream output = new FileOutputStream(versionFile, false)) {
            output.write(RUNTIME_ASSET_VERSION.getBytes(StandardCharsets.UTF_8));
        }
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
        File nativeExecutable = new File(getRuntimeLibraryDirectory(), "libcodex_bin.so");
        if (nativeExecutable.exists()) {
            return nativeExecutable;
        }
        return new File(getRuntimeDirectory(), "codex.bin");
    }

    public File getExecutableForLaunch() throws IOException {
        installRuntimeAssetsIfAvailable();
        File executable = getExecutable();
        if (!executable.exists()) {
            throw new IOException("Codex runtime is not installed at " + executable.getAbsolutePath());
        }
        if (!executable.canExecute() && !executable.setExecutable(true)) {
            throw new IOException("Codex runtime is not executable at " + executable.getAbsolutePath());
        }
        return executable;
    }

    public File getCodexHomeDirectory() {
        return new File(context.getFilesDir(), "codex-home");
    }

    public File getWorkspaceDirectory() {
        return new File(context.getFilesDir(), "workspaces");
    }

    public void populateRuntimeEnvironment(Map<String, String> env) throws IOException {
        File executable = getExecutableForLaunch();
        env.put("HOME", context.getFilesDir().getAbsolutePath());
        env.put("CODEX_HOME", getCodexHomeDirectory().getAbsolutePath());
        env.put("TMPDIR", context.getCacheDir().getAbsolutePath());
        env.put("CODEX_SELF_EXE", executable.getAbsolutePath());
        env.put("LD_LIBRARY_PATH", getRuntimeLibraryDirectory().getAbsolutePath());
    }

    private File getRuntimeDirectory() {
        return new File(context.getFilesDir(), "codex-runtime");
    }

    private File getRuntimeLibraryDirectory() {
        ApplicationInfo info = context.getApplicationInfo();
        if (info.nativeLibraryDir != null && !info.nativeLibraryDir.isEmpty()) {
            return new File(info.nativeLibraryDir);
        }
        return getRuntimeDirectory();
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
