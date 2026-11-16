package com.mango.ficreader;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Loads AO3 pages in a hidden WebView, i.e. with the same engine, TLS handshake and cookies
 * as a real browser. Used when AO3 (or Cloudflare in front of it) refuses the plain HTTP
 * request but would serve a browser, and it gets through "checking your browser" pages on
 * its own because they run here like in any browser.
 *
 * One page at a time, on the main thread.
 */
class WebFetcher {

    interface Callback {
        void done(int status, String url, String html, String error);
    }

    private static final long TIMEOUT_MS = 30000;

    private static class Job {
        final String url;
        final Callback cb;
        Job(String url, Callback cb) { this.url = url; this.cb = cb; }
    }

    private final WebView view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private Job current;
    private int status;
    private String error;
    private int seq;
    private final Runnable timeout = this::onTimeout;

    @SuppressLint("SetJavaScriptEnabled")
    WebFetcher(Context ctx) {
        view = new WebView(ctx);
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadsImagesAutomatically(false);
        s.setBlockNetworkImage(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
        view.setWebViewClient(new Client());
    }

    WebView view() { return view; }

    void fetch(String url, Callback cb) {
        queue.add(new Job(url, cb));
        if (current == null) next();
    }

    private void next() {
        current = queue.poll();
        if (current == null) {
            view.loadUrl("about:blank");
            return;
        }
        status = 200;
        error = null;
        seq++;
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, TIMEOUT_MS);
        view.stopLoading();
        view.loadUrl(current.url);
    }

    private void finish(int code, String url, String html, String err) {
        main.removeCallbacks(timeout);
        Job j = current;
        current = null;
        if (j != null) j.cb.done(code, url, html, err);
        next();
    }

    private void onTimeout() {
        if (current == null) return;
        // Hand back whatever is on screen; if it is a challenge page the app says so.
        grab(seq, status == 200 ? 403 : status);
    }

    private void grab(int forSeq, int code) {
        final String url = view.getUrl();
        view.evaluateJavascript("document.documentElement ? document.documentElement.outerHTML : ''", value -> {
            if (forSeq != seq || current == null) return;
            String html = "";
            try {
                html = new JSONArray("[" + value + "]").getString(0);
            } catch (Exception ignored) {
            }
            finish(code, url, html, null);
        });
    }

    private static boolean isChallenge(String title) {
        if (title == null) return false;
        String t = title.toLowerCase(Locale.ROOT);
        return t.contains("just a moment") || t.contains("checking your browser") || t.contains("attention required");
    }

    private class Client extends WebViewClient {
        @Override
        public void onPageStarted(WebView v, String url, Bitmap favicon) {
            // A challenge page that passes reloads the real page, which starts clean.
            if (current != null && !"about:blank".equals(url)) {
                status = 200;
                error = null;
            }
        }

        @Override
        public void onReceivedHttpError(WebView v, WebResourceRequest req, WebResourceResponse res) {
            if (req.isForMainFrame()) status = res.getStatusCode();
        }

        @Override
        public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
            if (req.isForMainFrame()) {
                status = 0;
                error = String.valueOf(err.getDescription());
            }
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
            if (req.isForMainFrame()) return null;
            // Skip things the reader never shows, to keep it quick and light on data.
            String p = req.getUrl().getPath();
            if (p != null && p.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|gif|webp|svg|ico|woff2?|ttf|otf|eot|css|mp4|webm|mp3)$")) {
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }
            return null;
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            if (current == null || "about:blank".equals(url)) return;
            if (status == 0) {
                finish(0, url, "", error);
                return;
            }
            // Wait for Cloudflare's check to finish and load the real page (or time out).
            if (isChallenge(v.getTitle())) return;
            final int forSeq = seq;
            final int code = status;
            CookieManager.getInstance().flush();
            grab(forSeq, code);
        }
    }
}
