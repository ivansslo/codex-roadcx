package com.codexapp.mobile;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CodexAndroidAccounts {
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
    private static final Object LOCK = new Object();
    private static Process activeLogin;

    private CodexAndroidAccounts() {
    }

    public static String listAccounts(Context context) {
        try {
            JSONObject auth = readAuth(context);
            if (auth == null) {
                return "{\"data\":{\"activeAccountId\":null,\"accounts\":[]}}";
            }
            String accountId = auth.optJSONObject("tokens") != null
                ? auth.optJSONObject("tokens").optString("account_id", "")
                : "";
            if (accountId.isEmpty()) {
                return "{\"data\":{\"activeAccountId\":null,\"accounts\":[]}}";
            }
            String authMode = auth.optString("auth_mode", "chatgpt");
            String now = java.time.Instant.now().toString();
            return "{\"data\":{\"activeAccountId\":\"" + escape(accountId) + "\",\"accounts\":[{"
                + "\"accountId\":\"" + escape(accountId) + "\","
                + "\"authMode\":\"" + escape(authMode) + "\","
                + "\"email\":null,"
                + "\"planType\":null,"
                + "\"lastRefreshedAtIso\":\"" + now + "\","
                + "\"lastActivatedAtIso\":\"" + now + "\","
                + "\"quotaStatus\":\"idle\","
                + "\"quotaError\":null,"
                + "\"unavailableReason\":null,"
                + "\"isActive\":true"
                + "}]}}";
        } catch (Exception error) {
            return "{\"data\":{\"activeAccountId\":null,\"accounts\":[]}}";
        }
    }

    public static String startLogin(Context context) throws Exception {
        synchronized (LOCK) {
            if (activeLogin != null && activeLogin.isAlive()) {
                activeLogin.destroy();
            }

            File executable = CodexRuntimeProcess.get(context).getExecutableForLaunch();
            File codexHome = CodexRuntimeProcess.get(context).getCodexHomeDirectory();
            File workspaceRoot = CodexRuntimeProcess.get(context).getWorkspaceDirectory();
            if (!codexHome.exists() && !codexHome.mkdirs()) {
                throw new IOException("Failed to create " + codexHome.getAbsolutePath());
            }
            if (!workspaceRoot.exists() && !workspaceRoot.mkdirs()) {
                throw new IOException("Failed to create " + workspaceRoot.getAbsolutePath());
            }

            ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath(), "login");
            builder.directory(workspaceRoot);
            Map<String, String> env = builder.environment();
            CodexRuntimeProcess.get(context).populateRuntimeEnvironment(env);

            CompletableFuture<String> loginUrl = new CompletableFuture<>();
            activeLogin = builder.start();
            startLoginReader(activeLogin.getInputStream(), loginUrl);
            startLoginReader(activeLogin.getErrorStream(), loginUrl);
            String url = loginUrl.get(20, TimeUnit.SECONDS);
            return "{\"ok\":true,\"data\":{\"loginUrl\":\"" + escape(url) + "\"}}";
        }
    }

    public static String completeLogin(Context context, String body) throws Exception {
        String callbackUrl = "";
        try {
            JSONObject payload = new JSONObject(body == null ? "{}" : body);
            callbackUrl = payload.optString("callbackUrl", "").trim();
        } catch (Exception ignored) {
            callbackUrl = "";
        }
        if (!callbackUrl.isEmpty()) {
            HttpURLConnection connection = (HttpURLConnection) new URL(callbackUrl).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.getResponseCode();
            connection.disconnect();
        }
        waitForAuth(context);
        return listAccounts(context);
    }

    public static String refresh(Context context) {
        return listAccounts(context);
    }

    private static void startLoginReader(InputStream stream, CompletableFuture<String> loginUrl) {
        Thread thread = new Thread(() -> {
            try (BufferedReader buffered = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = buffered.readLine()) != null) {
                    Matcher matcher = URL_PATTERN.matcher(line);
                    if (matcher.find()) {
                        loginUrl.complete(matcher.group());
                    }
                }
            } catch (IOException ignored) {
            }
        }, "codex-login-reader");
        thread.setDaemon(true);
        thread.start();
    }

    private static void waitForAuth(Context context) throws Exception {
        File auth = authFile(context);
        long deadline = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < deadline) {
            if (auth.exists() && auth.length() > 0) {
                return;
            }
            Thread.sleep(250L);
        }
        throw new IOException("Codex login did not write auth.json yet.");
    }

    private static JSONObject readAuth(Context context) throws Exception {
        File auth = authFile(context);
        if (!auth.exists()) {
            return null;
        }
        byte[] raw = new byte[(int) auth.length()];
        try (FileInputStream input = new FileInputStream(auth)) {
            int offset = 0;
            while (offset < raw.length) {
                int read = input.read(raw, offset, raw.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }
        return new JSONObject(new String(raw, StandardCharsets.UTF_8));
    }

    private static File authFile(Context context) {
        return new File(CodexRuntimeProcess.get(context).getCodexHomeDirectory(), "auth.json");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
