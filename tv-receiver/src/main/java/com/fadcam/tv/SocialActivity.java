package com.fadcam.tv;

import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/** Compatibility entrypoint retained for older TV/FadCam links; routes into the native social shell. */
public final class SocialActivity extends AppCompatActivity {
    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        openSocialShell();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openSocialShell();
    }

    private void openSocialShell() {
        try {
            Intent intent = new Intent(this, SocialParityActivity.class);
            intent.setData(getIntent().getData());
            if (getIntent().getExtras() != null) intent.putExtras(getIntent());
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
            overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right);
        } catch (Throwable t) {
            setContentView(new android.widget.TextView(this) {{
                setText("TV 49 East Social could not open safely.\n\n" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
                setTextColor(android.graphics.Color.WHITE);
                setTextSize(15f);
                setGravity(android.view.Gravity.CENTER);
                setPadding(32,32,32,32);
                setBackgroundColor(android.graphics.Color.rgb(39,9,70));
            }});
        }
    }
}
