package com.codexapp.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public final class MainActivity extends Activity {
    private static final int AUDIO_PERMISSION_REQUEST = 1001;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestAudioPermission();
        webView = createWebView();
        setContentView(webView);
        webView.loadUrl("file:///android_asset/codexui/index.html");
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
        view.setWebViewClient(new WebViewClient());
        view.addJavascriptInterface(new NativeBridge(), "CodexAndroid");
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
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
            return "{\"platform\":\"android\",\"host\":\"webview\",\"codexRuntime\":\"pending\"}";
        }
    }
}
