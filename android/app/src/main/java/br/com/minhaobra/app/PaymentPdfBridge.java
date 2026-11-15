package br.com.minhaobra.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;

final class PaymentPdfBridge {
    private static final int CREATE_PDF = 22;
    private static final int MAX_ENCODED_LENGTH = 262144;
    private static final String PENDING_PDF = "br.com.minhaobra.pendingPaymentPdf";
    private final MainActivity activity;
    private byte[] pending;

    static PaymentPdfBridge attach(MainActivity activity, WebView webView, Bundle state) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return null;
        return new PaymentPdfBridge(activity, webView, state);
    }

    private PaymentPdfBridge(MainActivity activity, WebView webView, Bundle state) {
        this.activity = activity;
        if (state != null) pending = state.getByteArray(PENDING_PDF);
        WebViewCompat.addWebMessageListener(webView, "MinhaObraPaymentPdf",
                Collections.singleton("https://" + BuildConfig.APP_HOST),
                (view, message, sourceOrigin, isMainFrame, reply) -> {
                    if (isMainFrame && MainActivity.hasAppOrigin(sourceOrigin)
                            && MainActivity.isTrustedPage(view.getUrl())
                            && message.getType() == WebMessageCompat.TYPE_STRING) {
                        chooseDestination(message.getData());
                    }
                });
    }

    private void chooseDestination(String data) {
        if (pending != null || data == null) return;
        if (data.length() > MAX_ENCODED_LENGTH) {
            notifyUser("O PDF é grande demais para salvar no aplicativo.");
            return;
        }
        try {
            byte[] pdf = Base64.decode(data, Base64.DEFAULT);
            if (pdf.length < 5 || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D'
                    || pdf[3] != 'F' || pdf[4] != '-') {
                notifyUser("Não foi possível preparar o PDF.");
                return;
            }
            Intent document = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            document.addCategory(Intent.CATEGORY_OPENABLE);
            document.setType("application/pdf");
            document.putExtra(Intent.EXTRA_TITLE, "minha-obra-pagamentos.pdf");
            pending = pdf;
            activity.startActivityForResult(document, CREATE_PDF);
        } catch (ActivityNotFoundException | IllegalArgumentException exception) {
            pending = null;
            notifyUser("Não foi possível abrir a tela para salvar o PDF.");
        }
    }

    void saveState(Bundle state) {
        if (pending != null) state.putByteArray(PENDING_PDF, pending);
    }

    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != CREATE_PDF) return;
        byte[] pdf = pending;
        pending = null;
        if (resultCode != Activity.RESULT_OK || data == null) return;
        Uri destination = data.getData();
        if (pdf == null || destination == null || !"content".equals(destination.getScheme())) {
            notifyUser("Não foi possível salvar o PDF.");
            return;
        }
        try (OutputStream output = activity.getContentResolver().openOutputStream(destination, "wt")) {
            if (output == null) throw new IOException("Destination unavailable");
            output.write(pdf);
        } catch (IOException | SecurityException exception) {
            notifyUser("Não foi possível salvar o PDF.");
            return;
        }
        notifyUser("PDF salvo.");
    }

    private void notifyUser(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}
