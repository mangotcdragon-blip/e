package com.mango.ficreader;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hosts the reader UI (assets/index.html) in a WebView and does the network work for it.
 *
 * AO3 pages are fetched here rather than from JavaScript because the page runs from
 * file:// and the browser would block cross-origin requests. Cookies are shared with
 * android.webkit.CookieManager, so logging in (or passing a Cloudflare check) inside
 * BrowserActivity carries over to these requests.
 */
public class MainActivity extends Activity {

    static final String HOME = "https://archiveofourown.org";
    private static final int REQ_BROWSER = 7;

    private WebView web;
    private WebFetcher fetcher;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private String userAgent;
    private String pendingLink;
    private boolean pageReady;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(Color.parseColor("#111216"));
        w.setNavigationBarColor(Color.parseColor("#111216"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#111216"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        // The page fetcher's WebView sits behind the UI, 1px and invisible: WebViews load
        // more reliably when attached to a window.
        fetcher = new WebFetcher(this);
        FrameLayout root = new FrameLayout(this);
        View fv = fetcher.view();
        fv.setAlpha(0f);
        root.addView(fv, new FrameLayout.LayoutParams(1, 1));
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(true);
        userAgent = WebSettings.getDefaultUserAgent(this);

        CookieManager.getInstance().setAcceptCookie(true);

        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("file".equals(u.getScheme())) return false;
                openExternal(u.toString());
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                flushPendingLink();
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        }
        if (web.getUrl() == null) {
            web.loadUrl("file:///android_asset/index.html");
        }
        handleIntent(getIntent());
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String link = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            link = intent.getData().toString();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            link = intent.getStringExtra(Intent.EXTRA_TEXT);
        }
        if (link != null) {
            pendingLink = link;
            flushPendingLink();
        }
    }

    private void flushPendingLink() {
        if (!pageReady || pendingLink == null) return;
        String js = "window.App && App.openLink(" + JSONObject.quote(pendingLink) + ")";
        pendingLink = null;
        web.evaluateJavascript(js, null);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.App ? App.back() : false", value -> {
            if (!"true".equals(value)) finishAfterBack();
        });
    }

    @SuppressWarnings("deprecation")
    private void finishAfterBack() {
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        web.evaluateJavascript("window.App && App.persist()", null);
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        fetcher.view().destroy();
        web.destroy();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BROWSER) {
            web.evaluateJavascript("window.App && App.browserClosed()", null);
        }
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ network

    private void deliver(String id, int status, String finalUrl, String body, String error) {
        try {
            JSONObject o = new JSONObject();
            o.put("status", status);
            o.put("url", finalUrl == null ? "" : finalUrl);
            o.put("body", body == null ? "" : body);
            if (error != null) o.put("error", error);
            final String js = "App.net.resolve(" + JSONObject.quote(id) + "," + o + ")";
            main.post(() -> {
                if (!isDestroyed()) web.evaluateJavascript(js, null);
            });
        } catch (Exception ignored) {
        }
    }

    private void doFetch(String id, String url) {
        CookieManager cookies = CookieManager.getInstance();
        String current = url;
        try {
            for (int hop = 0; hop < 8; hop++) {
                HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", userAgent);
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
                String ck = cookies.getCookie(current);
                if (ck != null && !ck.isEmpty()) c.setRequestProperty("Cookie", ck);

                int code = c.getResponseCode();
                Map<String, List<String>> headers = c.getHeaderFields();
                for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")) {
                        for (String v : e.getValue()) cookies.setCookie(current, v);
                    }
                }

                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    if (loc == null) break;
                    current = new URL(new URL(current), loc).toString();
                    continue;
                }

                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String body = in == null ? "" : readAll(in);
                c.disconnect();
                if (needsBrowser(code)) {
                    viaBrowser(id, url);
                } else {
                    deliver(id, code, current, body, null);
                }
                return;
            }
            deliver(id, 0, current, "", "Too many redirects");
        } catch (Exception e) {
            // Connection or TLS failure: a real browser often still gets through.
            viaBrowser(id, url);
        }
    }

    /** Statuses where AO3 / Cloudflare may serve a browser what it refused this request. */
    private static boolean needsBrowser(int code) {
        return code == 403 || code == 418 || code == 503 || (code >= 500 && code != 501);
    }

    private void viaBrowser(String id, String url) {
        main.post(() -> {
            if (isDestroyed()) return;
            fetcher.fetch(url, (status, finalUrl, html, error) ->
                    deliver(id, status, finalUrl == null ? url : finalUrl, html, error));
        });
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString(StandardCharsets.UTF_8.name());
    }

    // ------------------------------------------------------------------ bridge

    /** Methods callable from JavaScript as window.Native.*. They run on a binder thread. */
    class Bridge {
        @JavascriptInterface
        public void fetch(String id, String url) {
            if (url == null || !url.startsWith(HOME)) {
                deliver(id, 0, url, "", "Only archiveofourown.org links are fetched");
                return;
            }
            pool.execute(() -> doFetch(id, url));
        }

        /** Opens a full in-app browser (for logging in or passing a site check). */
        @JavascriptInterface
        public void openBrowser(String url) {
            main.post(() -> {
                Intent i = new Intent(MainActivity.this, BrowserActivity.class);
                i.putExtra("url", url);
                startActivityForResult(i, REQ_BROWSER);
            });
        }

        @JavascriptInterface
        public void openExternal(String url) {
            main.post(() -> MainActivity.this.openExternal(url));
        }

        @JavascriptInterface
        public void share(String title, String url) {
            main.post(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_SUBJECT, title);
                i.putExtra(Intent.EXTRA_TEXT, title + "\n" + url);
                try {
                    startActivity(Intent.createChooser(i, "Share"));
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void clearCookies() {
            main.post(() -> {
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();
            });
        }

        /** Hides the system bars while reading. */
        @JavascriptInterface
        public void setImmersive(boolean on) {
            main.post(() -> {
                int flags = on
                        ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        : 0;
                getWindow().getDecorView().setSystemUiVisibility(flags);
            });
        }

        @JavascriptInterface
        public void setBarColor(String hex) {
            main.post(() -> {
                try {
                    int c = Color.parseColor(hex);
                    getWindow().setStatusBarColor(c);
                    getWindow().setNavigationBarColor(c);
                    web.setBackgroundColor(c);
                    boolean light = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 > 150;
                    View d = getWindow().getDecorView();
                    int f = d.getSystemUiVisibility();
                    if (light) {
                        f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    } else {
                        f &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                    }
                    d.setSystemUiVisibility(f);
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void keepScreenOn(boolean on) {
            main.post(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }
    }
}
