package br.com.minhaobra.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.WebView;

import androidx.core.content.ContextCompat;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.ZoneId;
import java.util.Collections;

final class NotificationBridge {
    private static final String BRIDGE_NAME = "MinhaObraNotifications";
    private static final int NOTIFICATION_PERMISSION = 21;
    private final MainActivity activity;
    private final WebView webView;
    private final String installationId;
    private JavaScriptReplyProxy page;
    private Runnable afterPermission;
    private String preparedAccount;
    private boolean closed;
    private final BroadcastReceiver tokenChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };

    static NotificationBridge attach(MainActivity activity, WebView webView) {
        if (FirebaseApp.getApps(activity).isEmpty()
                || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            return null;
        }
        return new NotificationBridge(activity, webView);
    }

    private NotificationBridge(MainActivity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.installationId = DeviceNotificationState.installation(activity);
        ContextCompat.registerReceiver(activity, tokenChanged, new IntentFilter(MinhaObraMessagingService.TOKEN_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, Collections.singleton("https://" + BuildConfig.APP_HOST),
                (view, message, sourceOrigin, isMainFrame, reply) -> {
                    if (closed || !isMainFrame || !MainActivity.hasAppOrigin(sourceOrigin)
                            || !MainActivity.isTrustedPage(view.getUrl())
                            || message.getType() != androidx.webkit.WebMessageCompat.TYPE_STRING) return;
                    String content = message.getData();
                    if (content != null && content.length() <= 512) handle(content, reply);
                });
    }

    private void handle(String content, JavaScriptReplyProxy reply) {
        try {
            JSONObject message = new JSONObject(content);
            int id = message.getInt("id");
            String action = message.getString("action");
            String account = message.getString("accountId");
            if (id <= 0 || !account.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) return;
            page = reply;
            switch (action) {
                case "installation":
                    clearOtherAccount(account);
                    reply(reply, id, "inactive", null);
                    break;
                case "status":
                    clearOtherAccount(account);
                    if (DeviceNotificationState.enabledFor(activity, account)) token(account, reply, id, false);
                    else reply(reply, id, "inactive", null);
                    break;
                case "enable":
                    clearOtherAccount(account);
                    requestPermission(() -> token(account, reply, id, true), reply, id);
                    break;
                case "confirm":
                    if (account.equals(preparedAccount) && DeviceNotificationState.permitted(activity)) {
                        preparedAccount = null;
                        DeviceNotificationState.enable(activity, account);
                        FirebaseMessaging.getInstance().setAutoInitEnabled(true);
                        reply(reply, id, "active", null);
                    } else {
                        reply(reply, id, "inactive", null);
                    }
                    break;
                case "disable":
                    preparedAccount = null;
                    if (DeviceNotificationState.enabledFor(activity, account)) clear();
                    reply(reply, id, "inactive", null);
                    break;
                default:
                    reply(reply, id, "unavailable", null);
            }
        } catch (JSONException ignored) {
            // Invalid messages cannot access the notification bridge.
        }
    }

    private void clearOtherAccount(String account) {
        if (DeviceNotificationState.hasDifferentAccount(activity, account)) clear();
    }

    private void clear() {
        preparedAccount = null;
        DeviceNotificationState.clear(activity);
        FirebaseMessaging.getInstance().setAutoInitEnabled(false);
    }

    private void requestPermission(Runnable onResult, JavaScriptReplyProxy reply, int id) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (afterPermission != null) {
                reply(reply, id, "permission", null);
                return;
            }
            afterPermission = onResult;
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
        } else {
            onResult.run();
        }
    }

    void permissionsResult(int requestCode) {
        if (requestCode == NOTIFICATION_PERMISSION && afterPermission != null) {
            Runnable action = afterPermission;
            afterPermission = null;
            action.run();
        }
    }

    private void token(String account, JavaScriptReplyProxy reply, int id, boolean preparing) {
        if (!DeviceNotificationState.permitted(activity)) {
            reply(reply, id, "permission", null);
            return;
        }
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (closed) return;
            String token = task.isSuccessful() ? task.getResult() : null;
            if (token == null || !token.matches("[A-Za-z0-9:_-]{16,4096}")) {
                reply(reply, id, "unavailable", null);
                return;
            }
            if (preparing) preparedAccount = account;
            reply(reply, id, preparing ? "ready" : "active", token);
        });
    }

    private void reply(JavaScriptReplyProxy reply, int id, String status, String token) {
        if (closed) return;
        try {
            JSONObject result = new JSONObject();
            result.put("id", id);
            result.put("status", status);
            result.put("installationId", installationId);
            if (token != null) {
                result.put("token", token);
                result.put("timeZone", ZoneId.systemDefault().getId());
            }
            reply.postMessage(result.toString());
        } catch (JSONException ignored) {
            // Values have already been restricted to JSON primitives.
        }
    }

    void refresh() {
        if (!closed && page != null && MainActivity.isTrustedPage(webView.getUrl())) {
            page.postMessage("{\"refresh\":true}");
        }
    }

    void close() {
        closed = true;
        activity.unregisterReceiver(tokenChanged);
        WebViewCompat.removeWebMessageListener(webView, BRIDGE_NAME);
        page = null;
        afterPermission = null;
    }
}
