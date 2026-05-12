package com.codexapp.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebChromeClient;
import android.webkit.WebViewClient;

public final class MainActivity extends Activity {
    private static final int AUDIO_PERMISSION_REQUEST = 1001;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestAudioPermission();
        CodexLocalServer.ensureStarted(getApplicationContext());
        startService(new Intent(this, CodexRuntimeService.class));
        webView = createWebView();
        setContentView(webView);
        webView.loadUrl(CodexLocalServer.getBaseUrl() + "/index.html");
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
        view.setWebViewClient(new WebViewClient());
        view.addJavascriptInterface(new NativeBridge(), "CodexAndroid");
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        return view;
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

    public static final class NativeBridge {
        @JavascriptInterface
        public String getRuntimeInfo() {
            return "{\"platform\":\"android\",\"host\":\"webview\",\"server\":\"" + CodexLocalServer.getBaseUrl() + "\",\"codexRuntime\":\"pending\"}";
        }
    }
}
