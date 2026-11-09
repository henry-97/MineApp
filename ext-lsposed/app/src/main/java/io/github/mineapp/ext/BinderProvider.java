package io.github.mineapp.ext;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * 把 Shizuku 服务端的 binder 交给 Mine App —— 本模块 App 唯一的运行期职责。
 *
 * Shizuku 只把 binder 推给清单里声明了 API_V23 + ShizukuProvider 的应用（本 App 有，Mine App 没有）。
 * Mine App 调一次 call()，系统按需拉起本进程，Shizuku 随即把 binder 推过来，交出去后本进程即可被回收。
 * 交出去的只是「门票」：Shizuku 按调用方 uid 鉴权，Mine App 仍要以自己的身份申请授权。
 */
public class BinderProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!Const.METHOD_GET_BINDER.equals(method)) return null;
        enforceCaller();
        Bundle out = new Bundle();
        IBinder b = awaitShizuku(3000);
        if (b != null) out.putBinder(Const.KEY_BINDER, b);
        return out;
    }

    private void enforceCaller() {
        int uid = Binder.getCallingUid();
        if (uid == android.os.Process.myUid()) return;
        String[] pkgs = getContext().getPackageManager().getPackagesForUid(uid);
        if (pkgs != null) {
            for (String p : pkgs) {
                if (Const.TARGET_PKG.equals(p)) return;
            }
        }
        throw new SecurityException("caller uid " + uid + " is not " + Const.TARGET_PKG);
    }

    /** 本进程刚被拉起时 binder 还在路上，等一会儿。 */
    private static IBinder awaitShizuku(long waitMs) {
        if (Shizuku.pingBinder()) return Shizuku.getBinder();
        final CountDownLatch l = new CountDownLatch(1);
        Shizuku.OnBinderReceivedListener lis = l::countDown;
        Shizuku.addBinderReceivedListenerSticky(lis);
        try {
            l.await(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        } finally {
            Shizuku.removeBinderReceivedListener(lis);
        }
        return Shizuku.pingBinder() ? Shizuku.getBinder() : null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
