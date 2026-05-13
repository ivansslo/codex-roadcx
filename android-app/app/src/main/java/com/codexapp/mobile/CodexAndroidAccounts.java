package com.codexapp.mobile;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
            JSONObject profile = readProfile(auth);
            String email = profile.optString("email", "");
            if (email.isEmpty()) {
                email = profile.optString("preferred_username", "");
            }
            if (email.isEmpty()) {
                email = profile.optString("name", "");
            }
            String now = java.time.Instant.now().toString();
            return "{\"data\":{\"activeAccountId\":\"" + escape(accountId) + "\",\"accounts\":[{"
                + "\"accountId\":\"" + escape(accountId) + "\","
                + "\"authMode\":\"" + escape(authMode) + "\","
                + "\"email\":" + nullableString(email) + ","
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
            String url = sanitizeLoginUrl(loginUrl.get(20, TimeUnit.SECONDS));
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

    public static String importAuth(Context context, String body) throws Exception {
        JSONObject auth = new JSONObject(body == null ? "{}" : body);
        JSONObject tokens = auth.optJSONObject("tokens");
        String apiKey = auth.optString("OPENAI_API_KEY", "");
        if ((tokens == null || tokens.optString("refresh_token", "").isEmpty()) && apiKey.isEmpty()) {
            throw new IOException("Invalid Codex auth.json: missing refresh token or API key.");
        }
        File codexHome = CodexRuntimeProcess.get(context).getCodexHomeDirectory();
        if (!codexHome.exists() && !codexHome.mkdirs()) {
            throw new IOException("Failed to create " + codexHome.getAbsolutePath());
        }
        try (FileOutputStream output = new FileOutputStream(authFile(context), false)) {
            output.write(auth.toString().getBytes(StandardCharsets.UTF_8));
        }
        return listAccounts(context);
    }

    public static String switchActive(Context context, String body) {
        return listAccounts(context);
    }

    public static String remove(Context context, String body) throws IOException {
        File auth = authFile(context);
        if (auth.exists() && !auth.delete()) {
            try (FileOutputStream output = new FileOutputStream(auth, false)) {
                output.write(new byte[0]);
            }
        }
        return listAccounts(context);
    }

    private static void startLoginReader(InputStream stream, CompletableFuture<String> loginUrl) {
        Thread thread = new Thread(() -> {
            try (BufferedReader buffered = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = buffered.readLine()) != null) {
                    Matcher matcher = URL_PATTERN.matcher(line);
                    if (matcher.find()) {
                        String url = sanitizeLoginUrl(matcher.group());
                        if (!isLoopbackUrl(url)) {
                            loginUrl.complete(url);
                        }
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

    private static JSONObject readProfile(JSONObject auth) {
        try {
            JSONObject tokens = auth.optJSONObject("tokens");
            if (tokens == null) {
                return new JSONObject();
            }
            String idToken = tokens.optString("id_token", "");
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) {
                return new JSONObject();
            }
            byte[] decoded = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            return new JSONObject(new String(decoded, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static String sanitizeLoginUrl(String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        while (url.endsWith(".") || url.endsWith(",") || url.endsWith(";") || url.endsWith(")") || url.endsWith("]")) {
            url = url.substring(0, url.length() - 1);
        }
        url = url.replaceAll(":([0-9]+)\\.(?=/|$)", ":$1");
        return url;
    }

    private static boolean isLoopbackUrl(String url) {
        return url.startsWith("http://localhost")
            || url.startsWith("https://localhost")
            || url.startsWith("http://127.0.0.1")
            || url.startsWith("https://127.0.0.1");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String nullableString(String value) {
        if (value == null || value.isEmpty()) {
            return "null";
        }
        return "\"" + escape(value) + "\"";
    }
}
