package com.modelstudio.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hosts the Model Studio web app (bundled in assets/www) in a full-screen WebView and
 * gives it the few native things it needs: picking files, saving to Downloads, sharing,
 * and receiving files from other apps.
 */
public class MainActivity extends ComponentActivity {
    private static final String TAG = "ModelStudio";
    private static final String HOST = "appassets.androidplatform.net";
    private static final String APP_URL = "https://" + HOST + "/assets/www/index.html";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private ActivityResultLauncher<Intent> picker;
    private ActivityResultLauncher<Intent> nativePicker;
    private String nativePickPurpose = "open";
    private final Map<String, Uri> incoming = new ConcurrentHashMap<>();
    private final Map<String, PendingSave> saves = new ConcurrentHashMap<>();
    private volatile boolean pageReady = false;
    private String pendingIncoming;
    private Uri lastSaved;
    private String lastSavedMime;

    private static class PendingSave {
        final String name;
        final String mime;
        final File temp;
        final OutputStream out;

        PendingSave(String name, String mime, File temp) throws IOException {
            this.name = name;
            this.mime = mime;
            this.temp = temp;
            this.out = new FileOutputStream(temp);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF16181D);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // Keep the page clear of the status bar, navigation bar and keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        picker = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (fileCallback == null) return;
            Uri[] uris = null;
            Intent data = result.getData();
            if (result.getResultCode() == Activity.RESULT_OK && data != null) {
                List<Uri> list = urisFrom(data);
                uris = list.toArray(new Uri[0]);
            }
            fileCallback.onReceiveValue(uris);
            fileCallback = null;
        });

        // Files picked through the bridge are served like shared files, under their real names.
        nativePicker = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            Intent data = result.getData();
            if (result.getResultCode() != Activity.RESULT_OK || data == null) return;
            String json = describeUris(urisFrom(data));
            if (json != null) web.evaluateJavascript("App.receivePicked(" + JSONObject.quote(nativePickPurpose) + ", " + json + ")", null);
        });

        WebViewAssetLoader assets = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/incoming/", this::serveIncoming)
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setTextZoom(100);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assets.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (HOST.equals(url.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    picker.launch(intent);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d(TAG, m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                web.evaluateJavascript("window.App ? App.onBack() : false", handled -> {
                    if (!"true".equals(handled)) {
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                        setEnabled(true);
                    }
                });
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(APP_URL);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- files from other apps

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_VIEW.equals(action) && !Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
        String json = describeUris(urisFrom(intent));
        if (json != null) deliverIncoming(json);
        intent.setAction(null);
    }

    /** Registers the files for /incoming/ and returns [{name, url}] as JSON, or null. */
    private String describeUris(List<Uri> uris) {
        if (uris.isEmpty()) return null;
        try {
            JSONArray list = new JSONArray();
            for (Uri uri : uris) {
                String id = UUID.randomUUID().toString();
                incoming.put(id, uri);
                String name = displayName(uri);
                JSONObject o = new JSONObject();
                o.put("name", name);
                o.put("url", "https://" + HOST + "/incoming/" + id + "/" + URLEncoder.encode(name, "UTF-8"));
                list.put(o);
            }
            return list.toString();
        } catch (Exception e) {
            Log.e(TAG, "Could not read picked files", e);
            return null;
        }
    }

    private void deliverIncoming(String json) {
        if (pageReady) web.evaluateJavascript("App.openIncoming(" + json + ")", null);
        else pendingIncoming = json;
    }

    @SuppressWarnings("deprecation")
    private List<Uri> urisFrom(Intent intent) {
        List<Uri> out = new ArrayList<>();
        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) out.add(u);
            }
        }
        if (out.isEmpty() && intent.getData() != null) out.add(intent.getData());
        if (out.isEmpty()) {
            Object extra = intent.getExtras() != null ? intent.getExtras().get(Intent.EXTRA_STREAM) : null;
            if (extra instanceof Uri) out.add((Uri) extra);
            else if (extra instanceof ArrayList) {
                for (Object o : (ArrayList<?>) extra) if (o instanceof Uri) out.add((Uri) o);
            }
        }
        return out;
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
        }
        if (name == null) name = uri.getLastPathSegment();
        return name == null ? "file" : name;
    }

    private WebResourceResponse serveIncoming(String path) {
        String id = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
        Uri uri = incoming.get(id);
        if (uri == null) return null;
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            return new WebResourceResponse("application/octet-stream", null, in);
        } catch (Exception e) {
            Log.e(TAG, "Could not read " + uri, e);
            return null;
        }
    }

    // ---------------------------------------------------------------- saving

    private String finishSave(PendingSave p) throws IOException {
        p.out.close();
        // MediaStore renames files whose extension doesn't match the MIME type, so only
        // pass through types whose extension it knows; everything else is a plain binary.
        String mime = p.mime;
        String lower = p.name.toLowerCase();
        if (!(lower.endsWith(".png") && mime.equals("image/png"))
                && !(lower.endsWith(".zip") && mime.equals("application/zip"))
                && !(lower.endsWith(".json") && mime.equals("application/json"))) {
            mime = "application/octet-stream";
        }
        lastSavedMime = mime;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, p.name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Model Studio");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("Android refused to create the file");
                try (OutputStream out = getContentResolver().openOutputStream(uri); InputStream in = new FileInputStream(p.temp)) {
                    copy(in, out);
                }
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);
                lastSaved = uri;
                return "Saved to Downloads/Model Studio/" + displayName(uri);
            }
            // Android 8/9: no permission-free way into the shared Downloads folder, so keep it
            // in the app's own folder and offer the share sheet to send it anywhere.
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create the save folder");
            File dest = uniqueFile(dir, p.name);
            try (InputStream in = new FileInputStream(p.temp); OutputStream out = new FileOutputStream(dest)) {
                copy(in, out);
            }
            lastSaved = FileProvider.getUriForFile(this, getPackageName() + ".files", dest);
            runOnUiThread(this::shareLastSaved);
            return "Saved " + dest.getName() + ". Use Share to send it to another app.";
        } finally {
            //noinspection ResultOfMethodCallIgnored
            p.temp.delete();
        }
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; f.exists(); i++) f = new File(dir, base + " (" + i + ")" + ext);
        return f;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private void shareLastSaved() {
        if (lastSaved == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(lastSavedMime != null ? lastSavedMime : "application/octet-stream");
        send.putExtra(Intent.EXTRA_STREAM, lastSaved);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share"));
    }

    // ---------------------------------------------------------------- JavaScript bridge

    private class Bridge {
        @JavascriptInterface
        public void ready() {
            runOnUiThread(() -> {
                pageReady = true;
                if (pendingIncoming != null) {
                    String json = pendingIncoming;
                    pendingIncoming = null;
                    deliverIncoming(json);
                }
            });
        }

        @JavascriptInterface
        public void pickFiles(String purpose) {
            runOnUiThread(() -> {
                nativePickPurpose = purpose == null ? "open" : purpose;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                nativePicker.launch(intent);
            });
        }

        @JavascriptInterface
        public String beginSave(String name, String mime) {
            try {
                String id = UUID.randomUUID().toString();
                File temp = File.createTempFile("save", ".tmp", getCacheDir());
                saves.put(id, new PendingSave(name.replaceAll("[\\\\/:*?\"<>|]", "_"), mime == null ? "" : mime, temp));
                return id;
            } catch (IOException e) {
                return "";
            }
        }

        @JavascriptInterface
        public void appendSave(String id, String base64) {
            PendingSave p = saves.get(id);
            if (p == null) return;
            try {
                p.out.write(Base64.decode(base64, Base64.DEFAULT));
            } catch (IOException e) {
                Log.e(TAG, "Write failed", e);
            }
        }

        @JavascriptInterface
        public String finishSave(String id) {
            PendingSave p = saves.remove(id);
            if (p == null) return "error:Saving failed to start";
            try {
                return MainActivity.this.finishSave(p);
            } catch (Exception e) {
                Log.e(TAG, "Save failed", e);
                return "error:" + e.getMessage();
            }
        }

        @JavascriptInterface
        public boolean shareLast() {
            if (lastSaved == null) return false;
            runOnUiThread(MainActivity.this::shareLastSaved);
            return true;
        }
    }
}
