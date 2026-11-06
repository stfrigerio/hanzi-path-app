package dev.stefanofrigerio.hanzipath;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * The web app in a WebView, at https://appassets.androidplatform.net/ (the host Android keeps
 * for an app's own files: nothing goes to the network for it). Its files come from Web: the
 * newest downloaded copy, else the APK's assets. The page talks to Bridge as window.HanziApp.
 * The system bars are the page's paper; the page is kept clear of them by padding.
 */
public class Main extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final String HOME = "https://" + HOST + "/index.html";

    WebView web;
    Bridge bridge;
    FrameLayout root;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsets.CONSUMED;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        bridge = new Bridge(this);
        web.addJavascriptInterface(bridge, "HanziApp");
        final Web files = new Web(this);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                return HOST.equals(u.getHost()) ? files.open(u.getPath()) : null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { /* no browser */ }
                return true;
            }
        });
        if (saved != null) web.restoreState(saved);
        else web.loadUrl(HOME);
    }

    /** The page's paper behind the bars, and their icons dark on light paper. */
    void bars(String color, boolean dark) {
        runOnUiThread(() -> {
            int c;
            try { c = Color.parseColor(color); } catch (Exception e) { return; }
            root.setBackgroundColor(c);
            getWindow().getDecorView().setBackgroundColor(c);
            int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(dark ? 0 : light, light);
        });
    }

    void js(String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    void reload() {
        runOnUiThread(() -> web.reload());
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        Updater.maybe(this, false);
        js("window.hanziResume && hanziResume()");
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // the page goes back to the path first; from the path, Android's back
        web.evaluateJavascript("window.hanziBack ? hanziBack() : false", r -> {
            if (!"true".equals(r)) super.onBackPressed();
        });
    }

    @Override
    protected void onDestroy() {
        bridge.close();
        web.destroy();
        super.onDestroy();
    }
}
