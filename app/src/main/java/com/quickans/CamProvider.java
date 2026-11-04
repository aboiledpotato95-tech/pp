package com.quickans;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

// Gives the camera app a place to save the photo (no extra libraries needed).
public class CamProvider extends ContentProvider {
  @Override public boolean onCreate() { return true; }
  @Override public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
    File f = new File(getContext().getCacheDir(), u.getLastPathSegment());
    return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE);
  }
  @Override public String getType(Uri u) { return "image/jpeg"; }
  @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
  @Override public Uri insert(Uri u, ContentValues v) { return null; }
  @Override public int delete(Uri u, String s, String[] a) { return 0; }
  @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
