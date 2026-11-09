package br.com.minhaobra.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.UUID;

final class DeviceNotificationState {
    private static final String CHANNEL_ID = "minha-obra-avisos";
    private static final String INSTALLATION = "installation";
    private static final String ACCOUNT = "account";

    private DeviceNotificationState() {}

    static synchronized String installation(Context context) {
        SharedPreferences preferences = preferences(context);
        String installation = preferences.getString(INSTALLATION, null);
        if (installation == null) {
            installation = UUID.randomUUID().toString();
            preferences.edit().putString(INSTALLATION, installation).apply();
        }
        return installation;
    }

    static synchronized boolean enabledFor(Context context, String account) {
        return account.equals(preferences(context).getString(ACCOUNT, null));
    }

    static synchronized boolean hasDifferentAccount(Context context, String account) {
        String current = preferences(context).getString(ACCOUNT, null);
        return current != null && !current.equals(account);
    }

    static synchronized void enable(Context context, String account) {
        preferences(context).edit().putString(ACCOUNT, account).apply();
    }

    static synchronized void clear(Context context) {
        preferences(context).edit().remove(ACCOUNT).apply();
        manager(context).cancelAll();
    }

    static boolean permitted(Context context) {
        NotificationManager manager = manager(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        return manager.areNotificationsEnabled()
                && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    static synchronized void show(Context context, String account, String deliveryId, String title, String body) {
        if (!enabledFor(context, account) || !permitted(context)) return;
        NotificationManager manager = manager(context);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            manager.notify(deliveryId, 0, notification);
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("notifications", Context.MODE_PRIVATE);
    }

    private static NotificationManager manager(Context context) {
        return context.getSystemService(NotificationManager.class);
    }
}
