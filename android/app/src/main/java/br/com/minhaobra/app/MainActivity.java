package br.com.minhaobra.app;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Bundled Angular UI with same-origin HTTPS API access and first-party sessions. */
public final class MainActivity extends Activity {
    private static final String APP_HOST = BuildConfig.APP_HOST;
    private static final String START_URL = "https://" + APP_HOST + "/assets/index.html";
    private WebView webView;
    private NotificationBridge notificationBridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int surface = Color.rgb(245, 246, 244);
        getWindow().setStatusBarColor(surface);
        getWindow().setNavigationBarColor(surface);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(surface);
        container.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
                return WindowInsets.CONSUMED;
            }
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });

        webView = new WebView(this);
        webView.setBackgroundColor(surface);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUserAgentString(settings.getUserAgentString() + " MinhaObraAndroid/" + BuildConfig.VERSION_NAME);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        WebView.setWebContentsDebuggingEnabled(
                (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        webView.setWebViewClient(new LocalContentClient());
        notificationBridge = NotificationBridge.attach(this, webView);
        container.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(container);
        container.requestApplyInsets();
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null
                || !isTrustedPage(webView.getUrl())) {
            webView.loadUrl(START_URL);
        }
    }

    static boolean hasAppOrigin(Uri uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme())
                && APP_HOST.equalsIgnoreCase(uri.getHost())
                && (APP_HOST.equalsIgnoreCase(uri.getEncodedAuthority())
                    || (APP_HOST + ":443").equalsIgnoreCase(uri.getEncodedAuthority()));
    }

    static boolean isTrustedPage(String url) {
        if (url == null) return false;
        Uri uri = Uri.parse(url);
        return hasAppOrigin(uri) && "/assets/index.html".equals(uri.getPath());
    }

    private final class LocalContentClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!hasAppOrigin(uri)) return errorResponse(403);
            String path = uri.getPath();
            // Only API requests reach the network. Keeping the bundle on the
            // server's HTTPS origin lets HttpOnly/Secure cookies and CSRF work
            // without CORS exceptions or exposing a native credential bridge.
            if (path != null && path.startsWith("/api/")) {
                String method = request.getMethod();
                if (method.equals("GET") || method.equals("HEAD") || method.equals("POST")
                        || method.equals("PUT") || method.equals("PATCH") || method.equals("DELETE")
                        || method.equals("OPTIONS")) return null;
                return errorResponse(403);
            }
            if (!"GET".equals(request.getMethod())) return errorResponse(403);
            if (path == null || !path.startsWith("/assets/")) return errorResponse(403);
            String assetPath = path.substring("/assets/".length());
            if (assetPath.isEmpty() || assetPath.indexOf('\\') >= 0 || assetPath.indexOf('\0') >= 0) {
                return errorResponse(403);
            }
            for (String part : assetPath.split("/", -1)) {
                if (part.isEmpty() || part.equals(".") || part.equals("..")) return errorResponse(403);
            }
            try {
                InputStream stream = getAssets().open(assetPath);
                String type = mimeType(assetPath);
                String encoding = type.startsWith("text/") || type.equals("application/javascript")
                        || type.equals("application/json") || type.equals("image/svg+xml") ? "UTF-8" : null;
                Map<String, String> headers = new HashMap<>();
                headers.put("X-Content-Type-Options", "nosniff");
                headers.put("Cache-Control", "no-store");
                headers.put("Content-Security-Policy", "default-src 'none'; script-src 'self'; "
                        + "style-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'self' data:; "
                        + "connect-src 'self'; object-src 'none'; base-uri 'self'; frame-src 'none'; form-action 'none'");
                return new WebResourceResponse(type, encoding, 200, "OK", headers, stream);
            } catch (IOException notFound) {
                return errorResponse(404);
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return !isTrustedPage(request.getUrl().toString());
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
        }
    }

    private static String mimeType(String path) {
        String name = path.toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".js") || name.endsWith(".mjs")) return "application/javascript";
        if (name.endsWith(".json") || name.endsWith(".webmanifest")) return "application/json";
        if (name.endsWith(".ttf")) return "font/ttf";
        if (name.endsWith(".otf")) return "font/otf";
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".woff")) return "font/woff";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static WebResourceResponse errorResponse(int status) {
        String reason = status == 404 ? "Not Found" : "Forbidden";
        return new WebResourceResponse("text/plain", "UTF-8", status, reason,
                new HashMap<>(), new ByteArrayInputStream(reason.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        webView.saveState(state);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        if (notificationBridge != null) notificationBridge.refresh();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (notificationBridge != null) notificationBridge.permissionsResult(requestCode);
    }

    @Override
    protected void onDestroy() {
        if (notificationBridge != null) notificationBridge.close();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
