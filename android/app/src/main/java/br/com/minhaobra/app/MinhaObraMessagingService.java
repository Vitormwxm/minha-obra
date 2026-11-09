package br.com.minhaobra.app;

import android.content.Intent;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

public final class MinhaObraMessagingService extends FirebaseMessagingService {
    static final String TOKEN_CHANGED = "br.com.minhaobra.app.NOTIFICATION_TOKEN_CHANGED";

    @Override
    public void onNewToken(@NonNull String token) {
        sendBroadcast(new Intent(TOKEN_CHANGED).setPackage(getPackageName()));
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        Map<String, String> data = message.getData();
        String account = data.get("accountId");
        String deliveryId = data.get("deliveryId");
        String title = data.get("title");
        String body = data.get("body");
        if (account == null || deliveryId == null || !deliveryId.matches("[A-Za-z0-9:_-]{1,120}")
                || title == null || title.trim().isEmpty() || title.length() > 100
                || body == null || body.trim().isEmpty() || body.length() > 350) {
            return;
        }
        DeviceNotificationState.show(this, account, deliveryId, title, body);
    }
}
