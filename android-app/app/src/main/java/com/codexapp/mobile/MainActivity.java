package com.codexapp.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebChromeClient;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;


import java.io.File;

public final class MainActivity extends Activity {
    private static final int AUDIO_PERMISSION_REQUEST = 1001;
    private WebView webView;
    private TextView statusBadge;
    private String localIp;
    private int serverPort = 37645;
    private String password;
    private boolean serverReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestAudioPermission();

        // Start server
        CodexLocalServer.ensureStarted(getApplicationContext());
        startService(new Intent(this, CodexRuntimeService.class));

        localIp = CodexLocalServer.getLocalIpAddress();
        serverPort = 37645;

        // Create main layout
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Color.parseColor("#020617"));

        // Top toolbar
        LinearLayout toolbar = createToolbar();
        rootLayout.addView(toolbar);

        // WebView
        webView = createWebView();
        rootLayout.addView(webView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
            1.0f
        ));

        setContentView(rootLayout);
        webView.loadUrl(CodexLocalServer.getBaseUrl() + "/index.html");

        // Generate local password
        password = generateLocalPassword();
        File passFile = new File(getFilesDir(), "codexui-password");
        passFile.getParentFile().mkdirs();
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(passFile);
            fos.write(password.getBytes());
            fos.close();
        } catch (Exception e) {
            // Ignore
        }
    }

    private LinearLayout createToolbar() {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(16, 0, 16, 0);
        toolbar.setBackgroundColor(Color.parseColor("#0f172a"));
        toolbar.setElevation(4);

        LinearLayout.LayoutParams toolbarParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (int) (56 * getResources().getDisplayMetrics().density)
        );
        toolbar.setLayoutParams(toolbarParams);

        // Title
        TextView title = new TextView(this);
        title.setText("Codex Web Local");
        title.setTextColor(Color.parseColor("#f1f5f9"));
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f
        );
        title.setLayoutParams(titleParams);
        toolbar.addView(title);

        // Status badge
        statusBadge = new TextView(this);
        statusBadge.setText("Starting");
        statusBadge.setTextColor(Color.parseColor("#facc15"));
        statusBadge.setTextSize(11);
        statusBadge.setTypeface(null, Typeface.BOLD);
        statusBadge.setPadding(10, 4, 10, 4);
        statusBadge.setBackgroundResource(android.R.drawable.editbox_background);
        statusBadge.setVisibility(View.GONE);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            (int) (24 * getResources().getDisplayMetrics().density)
        );
        badgeParams.setMargins(0, 0, 8, 0);
        statusBadge.setLayoutParams(badgeParams);
        toolbar.addView(statusBadge);

        // Settings gear button
        ImageView settingsBtn = new ImageView(this);
        settingsBtn.setImageResource(android.R.drawable.ic_menu_manage);
        settingsBtn.setColorFilter(Color.parseColor("#94a3b8"));
        settingsBtn.setPadding(8, 8, 8, 8);
        settingsBtn.setContentDescription("Settings");
        settingsBtn.setClickable(true);
        settingsBtn.setFocusable(true);
        settingsBtn.setOnClickListener(v -> showSettingsDialog());
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            (int) (40 * getResources().getDisplayMetrics().density),
            (int) (40 * getResources().getDisplayMetrics().density)
        );
        settingsBtn.setLayoutParams(btnParams);
        toolbar.addView(settingsBtn);

        return toolbar;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private WebView createWebView() {
        WebView view = new WebView(this);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                if (android.os.Build.VERSION.SDK_INT < 21) {
                    return;
                }
                request.grant(request.getResources());
            }
        });
        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (android.os.Build.VERSION.SDK_INT < 21) {
                    return false;
                }
                return openExternalIfNeeded(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return openExternalIfNeeded(Uri.parse(url));
            }
        });
        view.addJavascriptInterface(new NativeBridge(), "CodexAndroid");
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        return view;
    }

    private boolean openExternalIfNeeded(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return false;
        }
        String host = uri.getHost();
        if ("127.0.0.1".equals(host) || "localhost".equals(host)) {
            return false;
        }
        startActivity(new Intent(Intent.ACTION_VIEW, uri));
        return true;
    }

    private void requestAudioPermission() {
        if (android.os.Build.VERSION.SDK_INT < 23) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, AUDIO_PERMISSION_REQUEST);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    private String generateLocalPassword() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            sb.append(chars.charAt((int)(Math.random() * chars.length())));
        }
        return sb.toString();
    }

    private void showSettingsDialog() {
        String localIpStr = localIp != null ? localIp : "127.0.0.1";
        String localUrl = "http://127.0.0.1:" + serverPort;
        String networkUrl = "http://" + localIpStr + ":" + serverPort + "/";

        StringBuilder info = new StringBuilder();
        info.append("⚙️ Codex Web Local\n\n");
        info.append("Version:  1.0.90\n");
        info.append("Bind:     http://0.0.0.0:").append(serverPort).append("\n");
        info.append("Status:   ").append(serverReady ? "Running" : "Starting...").append("\n\n");
        info.append("Local:    ").append(localUrl).append("\n");
        info.append("Network:  ").append(networkUrl).append("\n");
        if (password != null) {
            info.append("\nPassword: ").append(password).append("\n");
        }
        info.append("\nSandbox:  danger-full-access\n");
        info.append("Approval: never\n");

        AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Server Settings");
        builder.setMessage(info.toString());
        builder.setPositiveButton("Open Browser", (dialog, which) -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(localUrl));
            startActivity(intent);
        });
        builder.setNeutralButton("Copy Password", (dialog, which) -> {
            if (password != null) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Codex Password", password);
                clipboard.setPrimaryClip(clip);
            }
        });
        builder.setNegativeButton("Close", null);
        builder.show();
    }

    public void setServerReady(boolean ready) {
        this.serverReady = ready;
        runOnUiThread(() -> {
            statusBadge.setVisibility(View.VISIBLE);
            statusBadge.setText(ready ? "Running" : "Starting");
            statusBadge.setTextColor(Color.parseColor(ready ? "#22c55e" : "#facc15"));
        });
    }

    public static final class NativeBridge {
        @JavascriptInterface
        public String getRuntimeInfo() {
            return "{\"platform\":\"android\",\"host\":\"webview\",\"server\":\"" + CodexLocalServer.getBaseUrl() + "\",\"codexRuntime\":\"pending\"}";
        }
    }
}
