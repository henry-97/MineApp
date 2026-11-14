package com.henry.appmanager;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import org.json.JSONObject;

import java.security.MessageDigest;

/**
 * 「授权应用」名单：哪些第三方应用可以借用本应用的 shell 通道。
 *
 * 与 Shizuku 的做法一致 —— 由用户在设置页显式勾选，而不是靠某个全局开关。
 *
 * 🔴 记的不是「包名」，是「包名 + 签名指纹」：
 *    只认包名的话，别人卸掉这个应用、装一个同包名的假货，就白拿授权了。
 *    授权时把该应用当时的签名证书 SHA-256 存下来，每次调用再比对一次；
 *    对方换签名（重打包）后指纹对不上，授权自动失效，不用用户手动撤销。
 */
final class RelayGrant {

    private static final String SP = "relay";
    private static final String KEY = "granted";
    /** 主动来申请过的应用：{pkg: 签名指纹}。只出现在面板里，不等于已授权。 */
    private static final String KEY_REQ = "requested";

    private RelayGrant() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /** 全部授权记录：{pkg: 签名指纹} */
    static JSONObject all(Context c) {
        if (c == null) return new JSONObject();
        try {
            return new JSONObject(sp(c).getString(KEY, "{}"));
        } catch (Throwable e) {
            return new JSONObject();
        }
    }

    static boolean isGranted(Context c, String pkg) {
        return pkg != null && all(c).optString(pkg, "").length() > 0;
    }

    /** 授权：记下该应用**当前**的签名指纹 */
    static boolean grant(Context c, String pkg) {
        String d = digest(c, pkg);
        if (d == null) return false;
        try {
            JSONObject o = all(c);
            o.put(pkg, d);
            sp(c).edit().putString(KEY, o.toString()).apply();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    static void revoke(Context c, String pkg) {
        try {
            JSONObject o = all(c);
            o.remove(pkg);
            sp(c).edit().putString(KEY, o.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** 调用方校验：在名单里，且**当前**签名与授权时记录的一致 */
    static boolean allowed(Context c, String pkg) {
        if (c == null || pkg == null) return false;
        String want = all(c).optString(pkg, "");
        if (want.length() == 0) return false;
        String now = digest(c, pkg);
        return now != null && now.equals(want);
    }

    // ---- 申请制：只有主动来找过本应用的应用，才会出现在授权面板里 ----

    /** 记录一次申请（幂等）。返回是否记成功（读不到签名就算失败）。 */
    static boolean request(Context c, String pkg) {
        String d = digest(c, pkg);
        if (d == null) return false;
        try {
            JSONObject o = reqAll(c);
            // 已经授权过、或申请过且签名没变，都不用重写
            if (d.equals(o.optString(pkg, ""))) return true;
            o.put(pkg, d);
            sp(c).edit().putString(KEY_REQ, o.toString()).apply();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    static JSONObject reqAll(Context c) {
        if (c == null) return new JSONObject();
        try {
            return new JSONObject(sp(c).getString(KEY_REQ, "{}"));
        } catch (Throwable e) {
            return new JSONObject();
        }
    }

    /** 把某个应用从「申请列表」里彻底移除（取消授权时用，避免它又赖着不消失） */
    static void forgetRequest(Context c, String pkg) {
        try {
            JSONObject o = reqAll(c);
            o.remove(pkg);
            sp(c).edit().putString(KEY_REQ, o.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** 取签名证书的 SHA-256（十六进制），多签名时取第一个 */    static String digest(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            Signature[] ss;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                if (pi.signingInfo == null) return null;
                ss = pi.signingInfo.hasMultipleSigners()
                        ? pi.signingInfo.getApkContentsSigners()
                        : pi.signingInfo.getSigningCertificateHistory();
            } else {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
                ss = pi.signatures;
            }
            if (ss == null || ss.length == 0) return null;
            return sha256(ss[0].toByteArray());
        } catch (Throwable e) {
            return null;
        }
    }

    private static String sha256(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte x : d) {
                int v = x & 0xff;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable e) {
            return null;
        }
    }
}
