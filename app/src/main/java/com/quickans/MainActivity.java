package com.quickans;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

public class MainActivity extends Activity {
  WebView w; ValueCallback<Uri[]> cb; Uri cam;
  PdfRenderer pr; ParcelFileDescriptor pfd;
  TextRecognizer rec;
  final ExecutorService qEx = Executors.newSingleThreadExecutor();   // reads text in the question photos
  final ExecutorService bEx = Executors.newSingleThreadExecutor();   // reads books (many pages) in the background
  final Set<String> cancelled = Collections.synchronizedSet(new HashSet<String>());
  int bookSeq = 0;

  void js(final String s) { runOnUiThread(new Runnable() { @Override public void run() { w.evaluateJavascript(s, null); } }); }
  static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

  // Reads the text in a picture on the phone itself (Google ML Kit text recognition: works without internet).
  synchronized String ocrBitmap(Bitmap b) {
    try {
      if (rec == null) rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
      Text t = Tasks.await(rec.process(InputImage.fromBitmap(b, 0)));
      return t.getText();
    } catch (Throwable e) { return ""; }
  }
  static Bitmap decodeScaled(byte[] d, int max) {
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(d, 0, d.length, o);
    int s = 1;
    while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2;
    BitmapFactory.Options o2 = new BitmapFactory.Options();
    o2.inSampleSize = s;
    return BitmapFactory.decodeByteArray(d, 0, d.length, o2);
  }
  // A book PDF: every page is drawn, its text is read on the phone and sent to the page one page at a time.
  void readPdfBook(String id, String name, Uri u) throws Exception {
    File f = new File(getCacheDir(), "book_" + id + ".pdf");
    InputStream in = getContentResolver().openInputStream(u);
    FileOutputStream fo = new FileOutputStream(f);
    byte[] buf = new byte[65536]; int n;
    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
    fo.close(); in.close();
    PdfRenderer r = null; ParcelFileDescriptor fd = null;
    try {
      fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
      r = new PdfRenderer(fd);
      int total = r.getPageCount();
      js("window.bookInfo(" + q(id) + "," + q(name) + "," + total + ")");
      for (int i = 0; i < total; i++) {
        if (cancelled.contains(id)) break;
        PdfRenderer.Page p = r.openPage(i);
        float s = 1300f / p.getWidth();
        if (p.getHeight() * s > 2200f) s = 2200f / p.getHeight();
        Bitmap b = Bitmap.createBitmap(Math.max(1, Math.round(p.getWidth() * s)), Math.max(1, Math.round(p.getHeight() * s)), Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        Matrix m = new Matrix();
        m.postScale(s, s);
        p.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        p.close();
        String t = ocrBitmap(b);
        b.recycle();
        js("window.bookPage(" + q(id) + "," + i + "," + q(t) + ")");
      }
      js("window.bookDone(" + q(id) + ")");
    } finally {
      try { if (r != null) r.close(); if (fd != null) fd.close(); } catch (Throwable e) { }
      f.delete();
      cancelled.remove(id);
    }
  }
  // A Word / text book: the file goes to the page, which splits it into pages.
  void sendBookFile(String id, String name, Uri u) throws Exception {
    InputStream in = getContentResolver().openInputStream(u);
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    byte[] buf = new byte[65536]; int n; long tot = 0;
    while ((n = in.read(buf)) > 0) {
      tot += n;
      if (tot > 12L * 1024 * 1024) { in.close(); js("window.bookFail(" + q(id) + "," + q(name + " is over 12 MB.") + ")"); return; }
      o.write(buf, 0, n);
    }
    in.close();
    js("window.bookFile(" + q(id) + "," + q(name) + "," + q(Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)) + ")");
  }
  void addBook(final Uri u) {
    final String id = "b" + System.currentTimeMillis() + "_" + (bookSeq++);
    bEx.execute(new Runnable() { @Override public void run() {
      String name = "book";
      try {
        Cursor cu = getContentResolver().query(u, null, null, null, null);
        if (cu != null) {
          try {
            if (cu.moveToFirst()) { int k = cu.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (k >= 0) name = cu.getString(k); }
          } finally { cu.close(); }
        }
      } catch (Throwable e) { }
      boolean pdf = name.toLowerCase().endsWith(".pdf") || "application/pdf".equals(getContentResolver().getType(u));
      try {
        if (pdf) readPdfBook(id, name, u); else sendBookFile(id, name, u);
      } catch (Throwable e) { js("window.bookFail(" + q(id) + "," + q("Could not read " + name) + ")"); }
    }});
  }

  // Lets the page turn a PDF into page pictures using Android's own PDF renderer.
  public class Pdf {
    @JavascriptInterface public synchronized int open(String b64) {
      try {
        close();
        File f = new File(getCacheDir(), "in.pdf");
        FileOutputStream o = new FileOutputStream(f);
        o.write(Base64.decode(b64, Base64.DEFAULT));
        o.close();
        pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        pr = new PdfRenderer(pfd);
        return pr.getPageCount();
      } catch (Exception e) { return -1; }
    }
    @JavascriptInterface public synchronized String page(int i) {
      try {
        PdfRenderer.Page p = pr.openPage(i);
        float s = 1400f / p.getWidth();
        if (p.getHeight() * s > 3000f) s = 3000f / p.getHeight();   // keep very tall pages from using too much memory
        int W = Math.max(1, Math.round(p.getWidth() * s));
        int H = Math.max(1, Math.round(p.getHeight() * s));
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        Matrix m = new Matrix();
        m.postScale(s, s);
        p.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        p.close();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 80, o);
        b.recycle();
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
      } catch (Throwable e) { return ""; }
    }
    @JavascriptInterface public String paste() {
      final String[] r = { "" };
      final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
      runOnUiThread(new Runnable() {
        @Override public void run() {
          try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData d = cm.getPrimaryClip();
            if (d != null && d.getItemCount() > 0) {
              CharSequence t = d.getItemAt(0).coerceToText(MainActivity.this);
              if (t != null) r[0] = t.toString();
            }
          } catch (Throwable e) { }
          l.countDown();
        }
      });
      try { l.await(2, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { }
      return r[0];
    }
    @JavascriptInterface public void copy(final String s) {
      runOnUiThread(new Runnable() {
        @Override public void run() {
          try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("answers", s));
          } catch (Throwable e) { }
        }
      });
    }
    // Offline mode: read the text in a photo on the phone, answer comes back through window.ocrDone
    @JavascriptInterface public void ocr(final String id, final String b64) {
      qEx.execute(new Runnable() { @Override public void run() {
        String t = "";
        try {
          Bitmap b = decodeScaled(Base64.decode(b64, Base64.DEFAULT), 2000);
          if (b != null) { t = ocrBitmap(b); b.recycle(); }
        } catch (Throwable e) { }
        js("window.ocrDone(" + q(id) + "," + q(t) + ")");
      }});
    }
    // Offline mode: pick a book (PDF, Word, text) with the phone's own file picker
    @JavascriptInterface public void pickBook() {
      runOnUiThread(new Runnable() { @Override public void run() {
        try {
          Intent i = new Intent(Intent.ACTION_GET_CONTENT);
          i.addCategory(Intent.CATEGORY_OPENABLE);
          i.setType("*/*");
          i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{ "application/pdf", "text/plain",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.oasis.opendocument.text" });
          i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
          startActivityForResult(Intent.createChooser(i, "Add a book"), 3);
        } catch (Exception e) { }
      }});
    }
    @JavascriptInterface public void cancelBook(String id) { cancelled.add(id); }
    @JavascriptInterface public synchronized void close() {
      try { if (pr != null) pr.close(); if (pfd != null) pfd.close(); } catch (Exception e) { }
      pr = null; pfd = null;
    }
  }

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    w = new WebView(this); setContentView(w);
    WebSettings s = w.getSettings();
    s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
    s.setAllowUniversalAccessFromFileURLs(true); // lets the local page call the AI APIs
    w.addJavascriptInterface(new Pdf(), "Android");
    w.setWebViewClient(new WebViewClient());
    w.setWebChromeClient(new WebChromeClient() {
      @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> c, FileChooserParams p) {
        if (cb != null) cb.onReceiveValue(null);
        cb = c;
        try {
          if (p.isCaptureEnabled()) { // "Camera" button
            cam = Uri.parse("content://com.quickans.cam/p" + System.currentTimeMillis() + ".jpg");
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, cam);
            i.setClipData(ClipData.newRawUri("", cam));
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, 2);
          } else {                    // "Photos / files" button: photos, PDF, Word, text; many at once
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
              "image/*", "application/pdf", "text/plain", "application/msword",
              "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
              "application/vnd.oasis.opendocument.text"});
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(Intent.createChooser(i, "Add photos or files"), 1);
          }
        } catch (Exception e) { cb = null; return false; }
        return true;
      }
    });
    w.loadUrl("file:///android_asset/index.html");
  }
  @Override protected void onActivityResult(int r, int c, Intent d) {
    if (r == 3) { // a book was picked
      if (c == RESULT_OK && d != null) {
        ArrayList<Uri> us = new ArrayList<Uri>();
        if (d.getClipData() != null) {
          for (int k = 0; k < d.getClipData().getItemCount(); k++) us.add(d.getClipData().getItemAt(k).getUri());
        } else if (d.getData() != null) us.add(d.getData());
        for (Uri u : us) addBook(u);
      }
      return;
    }
    if (cb == null) return;
    if (r == 2) cb.onReceiveValue(c == RESULT_OK ? new Uri[]{cam} : null);
    else cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(c, d));
    cb = null;
  }
  @Override protected void onDestroy() {
    try { if (pr != null) pr.close(); if (pfd != null) pfd.close(); } catch (Exception e) { }
    super.onDestroy();
  }
}
