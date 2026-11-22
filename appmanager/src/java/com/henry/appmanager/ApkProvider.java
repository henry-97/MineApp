package com.henry.appmanager;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 只对外提供一个文件：外部私有目录下的更新包，专门喂给系统安装器。
 *
 * 为什么必须要有它：
 *   MIUI / HyperOS 会拦下 shell 装新包（"shell 没有权限安装"），这时唯一的出路是
 *   让用户走**系统安装器**点一次「允许」。而拉起系统安装器需要一个它能读的 URI：
 *
 *   - `file:///sdcard/...`：Android 11+ 上安装器已经不接受 file 方案，
 *     `am start -d file://...` 直接 `unable to resolve Intent`（实测）；
 *   - `content://`：安装器只认这个，而且必须由**我们的进程**发起并授予临时读权限。
 *
 * 所以这里做一个最小的 ContentProvider，只服务一个固定文件名：
 *   ① App 把下载好的更新包放到 getExternalFilesDir()/mineapp-next.apk
 *   ② 用 content://<包名>.apk/mineapp-next.apk 拉起安装器，带 FLAG_GRANT_READ_URI_PERMISSION
 *   ③ 安装器读得到、用户点一次允许，安装完成
 *
 * 只读、不导出（exported=false），权限靠 URI 临时授予，别的应用拿不到。
 */
public class ApkProvider extends ContentProvider {

    /** 只认这一个文件名，避免变成通用文件服务器 */
    static final String FILE = "mineapp-next.apk";

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (getContext() == null) throw new FileNotFoundException("no context");
        File f = new File(getContext().getExternalFilesDir(null), FILE);
        if (!f.isFile()) throw new FileNotFoundException(f.getAbsolutePath());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
