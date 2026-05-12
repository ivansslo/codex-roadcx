package com.codexapp.mobile;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

public final class CodexAndroidProjects {
    private static final String WORKSPACE_STATE_FILE = "workspace-roots-state.json";
    private static final String THREAD_QUEUE_STATE_FILE = "thread-queue-state.json";

    private CodexAndroidProjects() {
    }

    public static String homeDirectory(Context context) throws IOException {
        File home = CodexRuntimeProcess.get(context).getWorkspaceDirectory();
        ensureDirectory(home);
        return "{\"data\":{\"path\":\"" + escape(home.getAbsolutePath()) + "\"}}";
    }

    public static String listDirectories(Context context, String rawPath) throws Exception {
        String path = queryParam(rawPath, "path");
        boolean showHidden = "1".equals(queryParam(rawPath, "showHidden"));
        File directory = resolvePath(context, path);
        ensureDirectory(directory);

        JSONArray entries = new JSONArray();
        File[] children = directory.listFiles();
        if (children != null) {
            Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File child : children) {
                if (!child.isDirectory()) {
                    continue;
                }
                String name = child.getName();
                if (!showHidden && name.startsWith(".")) {
                    continue;
                }
                JSONObject row = new JSONObject();
                row.put("name", name);
                row.put("path", child.getCanonicalPath());
                entries.put(row);
            }
        }

        JSONObject data = new JSONObject();
        data.put("path", directory.getCanonicalPath());
        File parent = directory.getParentFile();
        data.put("parentPath", parent != null ? parent.getCanonicalPath() : directory.getCanonicalPath());
        data.put("entries", entries);
        JSONObject envelope = new JSONObject();
        envelope.put("data", data);
        return envelope.toString();
    }

    public static String workspaceRootsState(Context context) throws Exception {
        File stateFile = new File(context.getFilesDir(), WORKSPACE_STATE_FILE);
        if (stateFile.exists() && stateFile.length() > 0) {
            return "{\"data\":" + readFile(stateFile) + "}";
        }
        File home = CodexRuntimeProcess.get(context).getWorkspaceDirectory();
        ensureDirectory(home);
        JSONObject state = emptyWorkspaceState();
        String path = home.getCanonicalPath();
        state.getJSONArray("order").put(path);
        state.getJSONArray("active").put(path);
        writeFile(stateFile, state.toString());
        return "{\"data\":" + state.toString() + "}";
    }

    public static String saveWorkspaceRootsState(Context context, String body) throws Exception {
        JSONObject incoming = safeObject(body);
        JSONObject state = emptyWorkspaceState();
        copyArray(incoming, state, "order");
        copyObject(incoming, state, "labels");
        copyArray(incoming, state, "active");
        copyArray(incoming, state, "projectOrder");
        if (incoming.has("remoteProjects")) {
            state.put("remoteProjects", incoming.optJSONArray("remoteProjects") != null ? incoming.optJSONArray("remoteProjects") : new JSONArray());
        }
        writeFile(new File(context.getFilesDir(), WORKSPACE_STATE_FILE), state.toString());
        return "{\"ok\":true}";
    }

    public static String threadQueueState(Context context) throws IOException {
        File stateFile = new File(context.getFilesDir(), THREAD_QUEUE_STATE_FILE);
        if (!stateFile.exists() || stateFile.length() == 0) {
            return "{\"data\":{}}";
        }
        return "{\"data\":" + readFile(stateFile) + "}";
    }

    public static String saveThreadQueueState(Context context, String body) throws IOException {
        String raw = body == null || body.trim().isEmpty() ? "{}" : body;
        writeFile(new File(context.getFilesDir(), THREAD_QUEUE_STATE_FILE), raw);
        return "{\"ok\":true}";
    }

    public static String openProjectRoot(Context context, String body) throws Exception {
        JSONObject payload = safeObject(body);
        String rawPath = payload.optString("path", "").trim();
        boolean createIfMissing = payload.optBoolean("createIfMissing", false);
        if (rawPath.isEmpty()) {
            return "{\"error\":\"Missing path\"}";
        }
        File directory = resolvePath(context, rawPath);
        if (!directory.exists()) {
            if (!createIfMissing) {
                return "{\"error\":\"Directory does not exist\"}";
            }
            ensureDirectory(directory);
        }
        if (!directory.isDirectory()) {
            return "{\"error\":\"Path exists but is not a directory\"}";
        }
        persistWorkspaceRoot(context, directory.getCanonicalPath());
        return "{\"data\":{\"path\":\"" + escape(directory.getCanonicalPath()) + "\"}}";
    }

    public static String createLocalDirectory(Context context, String body) throws Exception {
        JSONObject payload = safeObject(body);
        String rawPath = payload.optString("path", "").trim();
        if (rawPath.isEmpty()) {
            return "{\"error\":\"Missing path\"}";
        }
        File directory = resolvePath(context, rawPath);
        ensureDirectory(directory);
        return "{\"data\":{\"path\":\"" + escape(directory.getCanonicalPath()) + "\"}}";
    }

    public static String createProjectlessThreadDirectory(Context context, String body) throws Exception {
        File root = new File(CodexRuntimeProcess.get(context).getWorkspaceDirectory(), "Chats");
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        File day = new File(root, date);
        ensureDirectory(day);
        File directory = uniqueChild(day, "chat");
        ensureDirectory(directory);
        String cwd = directory.getCanonicalPath();
        persistWorkspaceRoot(context, cwd);
        return "{\"data\":{\"cwd\":\"" + escape(cwd) + "\",\"outputDirectory\":\"" + escape(cwd) + "\",\"workspaceRoot\":\"" + escape(cwd) + "\"}}";
    }

    public static String projectRootSuggestion(Context context, String rawPath) throws Exception {
        String basePath = queryParam(rawPath, "basePath");
        File base = resolvePath(context, basePath);
        ensureDirectory(base);
        int index = 1;
        while (index < 100000) {
            String name = "New Project (" + index + ")";
            File candidate = new File(base, name);
            if (!candidate.exists()) {
                return "{\"data\":{\"name\":\"" + escape(name) + "\",\"path\":\"" + escape(candidate.getCanonicalPath()) + "\"}}";
            }
            index += 1;
        }
        return "{\"error\":\"Failed to compute project name suggestion\"}";
    }

    private static void persistWorkspaceRoot(Context context, String path) throws Exception {
        JSONObject state;
        File stateFile = new File(context.getFilesDir(), WORKSPACE_STATE_FILE);
        if (stateFile.exists() && stateFile.length() > 0) {
            state = safeObject(readFile(stateFile));
        } else {
            state = emptyWorkspaceState();
        }
        addUnique(state.getJSONArray("order"), path);
        addUnique(state.getJSONArray("active"), path);
        writeFile(stateFile, state.toString());
    }

    private static JSONObject emptyWorkspaceState() throws Exception {
        JSONObject state = new JSONObject();
        state.put("order", new JSONArray());
        state.put("labels", new JSONObject());
        state.put("active", new JSONArray());
        state.put("projectOrder", new JSONArray());
        state.put("remoteProjects", new JSONArray());
        return state;
    }

    private static void copyArray(JSONObject source, JSONObject target, String key) throws Exception {
        JSONArray value = source.optJSONArray(key);
        target.put(key, value != null ? value : new JSONArray());
    }

    private static void copyObject(JSONObject source, JSONObject target, String key) throws Exception {
        JSONObject value = source.optJSONObject(key);
        target.put(key, value != null ? value : new JSONObject());
    }

    private static void addUnique(JSONArray array, String value) throws Exception {
        for (int index = 0; index < array.length(); index += 1) {
            if (value.equals(array.optString(index))) {
                return;
            }
        }
        array.put(value);
    }

    private static File uniqueChild(File parent, String prefix) {
        int index = 1;
        while (true) {
            File candidate = new File(parent, index == 1 ? prefix : prefix + "-" + index);
            if (!candidate.exists()) {
                return candidate;
            }
            index += 1;
        }
    }

    private static File resolvePath(Context context, String rawPath) throws IOException {
        String path = rawPath == null ? "" : rawPath.trim();
        File directory = path.isEmpty()
            ? CodexRuntimeProcess.get(context).getWorkspaceDirectory()
            : new File(path);
        return directory.getCanonicalFile();
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory.exists()) {
            if (!directory.isDirectory()) {
                throw new IOException(directory.getAbsolutePath() + " is not a directory");
            }
            return;
        }
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Failed to create " + directory.getAbsolutePath());
        }
    }

    private static JSONObject safeObject(String body) throws Exception {
        if (body == null || body.trim().isEmpty()) {
            return new JSONObject();
        }
        return new JSONObject(body);
    }

    private static String queryParam(String rawPath, String name) throws IOException {
        int queryIndex = rawPath.indexOf('?');
        if (queryIndex < 0 || queryIndex == rawPath.length() - 1) {
            return "";
        }
        String[] pairs = rawPath.substring(queryIndex + 1).split("&");
        for (String pair : pairs) {
            int separator = pair.indexOf('=');
            String key = separator >= 0 ? pair.substring(0, separator) : pair;
            if (!name.equals(URLDecoder.decode(key, "UTF-8"))) {
                continue;
            }
            String value = separator >= 0 ? pair.substring(separator + 1) : "";
            return URLDecoder.decode(value, "UTF-8");
        }
        return "";
    }

    private static String readFile(File file) throws IOException {
        byte[] raw = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < raw.length) {
                int read = input.read(raw, offset, raw.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static void writeFile(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            ensureDirectory(parent);
        }
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
