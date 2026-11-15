package com.mango.ficreader;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A plain in-app browser on archiveofourown.org. Used to log in or to get past a
 * "checking your browser" page. Its cookies are shared with the reader's own requests,
 * so once you are through here the reader works too. Tap Done to go back.
 */
public class BrowserActivity extends Activity {

    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int bg = Color.parseColor("#16171c");
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(10);
        bar.setPadding(dp(16), pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("AO3 — log in or verify, then tap Done");
        title.setTextColor(Color.parseColor("#c9cad1"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button done = new Button(this);
        done.setText("Done");
        done.setAllCaps(false);
        done.setTextColor(Color.parseColor("#16171c"));
        done.setBackgroundColor(Color.parseColor("#e0868a"));
        done.setOnClickListener(v -> finishWithCookies());
        bar.addView(done, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        root.addView(bar);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient());
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        String url = getIntent().getStringExtra("url");
        web.loadUrl(url != null ? url : MainActivity.HOME);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void finishWithCookies() {
        CookieManager.getInstance().flush();
        setResult(RESULT_OK);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else finishWithCookies();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
