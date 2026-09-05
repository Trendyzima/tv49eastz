package com.fadcam.tv;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Native App Link entry point for the Testagram web identity.
 *
 * The website and Android app intentionally share the same product identity and
 * backend, but the Android experience is rendered by native Activities rather
 * than a WebView. Every verified Testagram URL is handed to the native social
 * shell so the installed app remains the canonical mobile experience.
 */
public final class TestagramAppLinkActivity extends Activity {
    public static final String SITE = "https://testagram.site";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent source = getIntent();
        Uri data = source == null ? null : source.getData();
        Intent nativeIntent = new Intent(this, XSocialActivity.class);
        if (data != null) nativeIntent.setData(data);
        if (source != null && source.getExtras() != null) nativeIntent.putExtras(source);
        startActivity(nativeIntent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }
}
