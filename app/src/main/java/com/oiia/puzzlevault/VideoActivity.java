package com.oiia.puzzlevault;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.VideoView;

/** Plays the bundled video full screen once the vault is unlocked. */
public class VideoActivity extends Activity {
    private VideoView video;
    private LinearLayout endOverlay;
    private int resumePosition = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        endOverlay = Ui.column(this);
        endOverlay.setVisibility(View.GONE);
        Button again = Ui.button(this, "Play again", Ui.ACCENT);
        again.setOnClickListener(v -> {
            endOverlay.setVisibility(View.GONE);
            video.seekTo(0);
            video.start();
        });
        endOverlay.addView(again, Ui.wide(this, 0));
        Button done = Ui.button(this, "Done", Ui.CARD);
        done.setOnClickListener(v -> finish());
        endOverlay.addView(done, Ui.wide(this, 12));
        FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(
                Ui.dp(this, 220), FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        olp.bottomMargin = Ui.dp(this, 48);
        root.addView(endOverlay, olp);

        setContentView(root);
        hideSystemUi();

        video.setVideoURI(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.oiia_cat));
        video.setOnPreparedListener(mp -> {
            video.seekTo(resumePosition);
            video.start();
        });
        video.setOnCompletionListener(mp -> endOverlay.setVisibility(View.VISIBLE));
        video.setOnErrorListener((mp, what, extra) -> {
            endOverlay.setVisibility(View.VISIBLE);
            return true;
        });
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumePosition = video.getCurrentPosition();
        video.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (endOverlay.getVisibility() != View.VISIBLE && resumePosition > 0) {
            video.seekTo(resumePosition);
            video.start();
        }
    }
}
