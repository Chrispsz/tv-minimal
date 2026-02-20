package com.fongmi.android.tv.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.Sniffer;

/**
 * Activity transparente para receber intents de URLs externas.
 * Redireciona para VideoActivity para reprodução.
 * 
 * Suporta:
 * - ACTION_VIEW com URLs .m3u8/.m3u
 * - ACTION_VIEW com video/*
 * - ACTION_SEND com text/plain (URLs compartilhadas)
 */
public class ExternalPlayActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            finish();
            return;
        }

        String action = intent.getAction();
        String url = null;

        // ACTION_VIEW - URL veio de clique em link
        if (Intent.ACTION_VIEW.equals(action)) {
            Uri data = intent.getData();
            if (data != null) {
                url = data.toString();
            }
        }
        // ACTION_SEND - URL veio de compartilhamento
        else if (Intent.ACTION_SEND.equals(action)) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (!TextUtils.isEmpty(text)) {
                // Extrair URL do texto (pode ter texto junto)
                url = Sniffer.getUrl(text);
            }
        }

        if (TextUtils.isEmpty(url)) {
            Toast.makeText(this, R.string.no_url_found, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Abrir VideoActivity com a URL
        playUrl(url);
    }

    private void playUrl(String url) {
        // Usar o método existente do VideoActivity
        VideoActivity.start(this, url);
        finish();
    }
}
