package com.henry.appmanager;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 跨包名升级的数据迁移（5.3.1 桥接版 → 6.0 NEXT）。
 *
 * 背景：6.0 起唯一包名是 com.henry.appmanager.next，和现有的
 * com.henry.appmanager / .multi 不是同一个 applicationId，所以
 * pm install -r 那套「数据原地保留」的路径不成立 —— 新包是一个全新的 App，
 * 数据目录是空的。用户不该因为换包名就把授权、图标、名称、布局全丢一遍。
 *
 * 做法（三段，全在 shell 通道上完成，不需要 root）：
 *   ① 旧 App 把偏好设置导出成 JSON，写进**自己的**外部私有目录
 *      （/sdcard/Android/data/<旧包>/files/migrate.json —— App 只能写自己的）
 *   ② shell 把它 cp 到**新包**的同名目录并放开权限
 *      （shell 有 ext_data_rw，可以跨包写；App 自己不行）
 *   ③ 新包首次启动时读该文件并写回 SharedPreferences，然后删除文件、
 *      留下 migrated 标记 —— 旧包那边的「守护脚本」看到标记才去卸载自己
 *
 * 🔴 标记文件是卸载旧包的唯一前置条件。导入没成功就绝不卸载，
 *    否则用户会同时失去新旧两个 App 的数据。
 */
final class Migrate {

    /** 迁移文件与完成标记，都放在新包自己的外部目录里。 */
    static final String FILE = "migrate.json";
    static final String MARK = "migrated";

    /** 参与迁移的 SharedPreferences 文件（其它都是运行期缓存，没必要搬）。 */
    private static final String[] PREFS = {"app", "relay"};

    /**
     * ADB 通道的密钥三件套（在 filesDir 里）。
     *
     * 🔴 这是「跨包名还能无缝用 ADB」的唯一办法：adbd 认的是公钥，
     *    密钥搬过去之后新包连 127.0.0.1:5555 不会再弹授权框。
     *    Shizuku / Root 的授权是按 uid 记的，搬不动，只能让用户重新点一次。
     */
    static final String[] KEY_FILES = {"adbkey.pk8", "adbkey.blob", "adbkey.pubstr"};

    private Migrate() { }

    // ---------------- 导出（旧包侧） ----------------

    static String exportJson(Context ctx) {
        try {
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("v", 1);
            root.put("pkg", ctx.getPackageName());
            root.put("ver", versionName(ctx));
            root.put("ts", System.currentTimeMillis());

            org.json.JSONObject all = new org.json.JSONObject();
            for (String name : PREFS) {
                org.json.JSONObject one = new org.json.JSONObject();
                for (java.util.Map.Entry<String, ?> e
                        : ctx.getSharedPreferences(name, 0).getAll().entrySet()) {
                    Object v = e.getValue();
                    org.json.JSONArray a = new org.json.JSONArray();
                    if (v instanceof String) {
                        a.put("s").put(v);
                    } else if (v instanceof Integer) {
                        a.put("i").put(((Integer) v).intValue());
                    } else if (v instanceof Long) {
                        a.put("l").put(((Long) v).longValue());
                    } else if (v instanceof Float) {
                        a.put("f").put(((Float) v).doubleValue());
                    } else if (v instanceof Boolean) {
                        a.put("b").put(((Boolean) v).booleanValue());
                    } else if (v instanceof java.util.Set) {
                        org.json.JSONArray arr = new org.json.JSONArray();
                        for (Object o : (java.util.Set<?>) v) arr.put(String.valueOf(o));
                        a.put("x").put(arr);
                    } else {
                        continue;
                    }
                    one.put(e.getKey(), a);
                }
                all.put(name, one);
            }
            root.put("prefs", all);

            // ADB 密钥单独一节：新包凭它可以沿用旧包在 adbd 里那条「已授权」记录
            org.json.JSONObject files = new org.json.JSONObject();
            for (String n : KEY_FILES) {
                java.io.File f = new java.io.File(ctx.getFilesDir(), n);
                if (!f.isFile()) continue;
                byte[] raw = readBytes(f);
                if (raw != null && raw.length > 0) {
                    files.put(n, android.util.Base64.encodeToString(
                            raw, android.util.Base64.NO_WRAP));
                }
            }
            if (files.length() > 0) root.put("files", files);
            return root.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------------- 导入（新包侧） ----------------

    /**
     * 首次启动时调用。返回「迁移来源包名」，没迁移就返回空串。
     *
     * @param extra 启动 Intent 里带的迁移数据（备用通道，文件拿不到时用）
     */
    static String runIfNeeded(Context ctx, String extra) {
        try {
            SharedPreferences app = ctx.getSharedPreferences("app", 0);
            // 🔴 不能只看 migrated_done 就跳过：之前可能已经导入过一份"空壳"
            //    （预览版迁移留下），这时必须允许**用更新的载荷覆盖重导**。
            //    判据：载荷里的 ts 比上次导入的更晚，就重来一次。
            long incomingTs = 0;

            // 外部存储没挂载时 getExternalFilesDir() 会返回 null，退回只用 Intent 通道
            java.io.File dir = ctx.getExternalFilesDir(null);
            java.io.File f = dir == null ? null : new java.io.File(dir, FILE);
            String json = null;
            if (f != null && f.isFile()) json = readAll(f);
            if (json == null || json.length() == 0) json = extra;
            if (json == null || json.length() == 0) return "";
            // 备用通道走 Intent extra，里面是 base64（避免命令行里出现空格/引号）
            if (json.charAt(0) != '{') {
                try {
                    json = new String(android.util.Base64.decode(
                            json, android.util.Base64.NO_WRAP), "UTF-8");
                } catch (Throwable t) {
                    return "";
                }
            }

            org.json.JSONObject root = new org.json.JSONObject(json);
            incomingTs = root.optLong("ts", 0);
            if (app.getBoolean("migrated_done", false)
                    && incomingTs <= app.getLong("migrated_ts", 0)) {
                return "";      // 同一份或更旧的载荷，已导入过
            }
            String from = root.optString("pkg", "");
            String ver = root.optString("ver", "");
            if (from.length() == 0 || from.equals(ctx.getPackageName())) {
                // 自己导自己的，忽略（正常情况下不会发生）
                app.edit().putBoolean("migrated_done", true).apply();
                return "";
            }

            org.json.JSONObject all = root.optJSONObject("prefs");
            int keys = 0;
            if (all != null) {
                for (String name : PREFS) {
                    org.json.JSONObject one = all.optJSONObject(name);
                    if (one == null) continue;
                    SharedPreferences.Editor ed = ctx.getSharedPreferences(name, 0).edit();
                    java.util.Iterator<String> it = one.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        if (apply(ed, k, one.optJSONArray(k))) keys++;
                    }
                    ed.apply();
                }
            }

            // ADB 密钥三件套：写回自己的 filesDir，新包连 adbd 时用的就是同一把公钥，
            // 「已授权」记录直接生效，不会再来一次授权框。
            int keyCount = 0;
            org.json.JSONObject files = root.optJSONObject("files");
            if (files != null) {
                java.util.Iterator<String> it = files.keys();
                while (it.hasNext()) {
                    String n = it.next();
                    if (!isKeyFile(n)) continue;   // 只认这三个名字，不让迁移文件往 filesDir 乱写
                    try {
                        byte[] raw = android.util.Base64.decode(
                                files.optString(n, ""), android.util.Base64.NO_WRAP);
                        if (raw == null || raw.length == 0) continue;
                        java.io.FileOutputStream fo =
                                new java.io.FileOutputStream(new java.io.File(ctx.getFilesDir(), n));
                        fo.write(raw);
                        fo.close();
                        keyCount++;
                    } catch (Throwable ignored) { }
                }
            }

            // 通道能不能复用：adb 能（密钥已搬），shizuku / root 的授权都按 uid 记，
            // 新包天然拿不到 —— 打个标记，让 Bridge 开局替用户发一次申请。
            String ch = app.getString("execChannel", "adb");
            if (!"adb".equals(ch)) app.edit().putBoolean("channel_need_grant", true).apply();

            // 🔴 兜底：迁移文件里有偏好、却一个都没应用上 —— 说明格式对不上（版本回滚、
            //    文件被截断、字段改名）。这时候绝不能留下「完成标记」：
            //    旧包的守护脚本只认这个标记，没有它就不会卸载旧包，
            //    用户最坏也就是手机上多一个旧版，数据一条都不会丢。
            int declared = (all == null) ? 0 : all.length();
            boolean sane = (keys > 0) || (declared == 0);

            // 迁移完成：删掉文件、留标记（旧包的守护脚本靠这个标记才敢卸载自己）
            app.edit()
                    .putBoolean("migrated_done", true)
                    .putLong("migrated_ts", incomingTs)
                    .putBoolean("migrated_ok", sane)
                    .putString("migrated_from", from)
                    .putString("migrated_ver", ver)
                    .putBoolean("open_settings_after_update", true)
                    .apply();
            try { if (f != null && sane && f.isFile()) f.delete(); } catch (Throwable ignored) { }
            if (sane && dir != null) {
                try {
                    java.io.File mk = new java.io.File(dir, MARK);
                    java.io.FileWriter w = new java.io.FileWriter(mk);
                    w.write(from);
                    w.close();
                } catch (Throwable ignored) { }
            } else if (!sane) {
                android.util.Log.w("Migrate", "迁移数据没解析出任何键，保留旧包（不写完成标记）");
            }
            android.util.Log.i("Migrate", "migrated " + keys + " keys + " + keyCount
                    + " keyfile(s) from " + from + " " + ver);
            return from;
        } catch (Throwable t) {
            android.util.Log.w("Migrate", "迁移失败: " + t);
            return "";
        }
    }

    private static boolean apply(SharedPreferences.Editor ed, String k, org.json.JSONArray a) {
        if (a == null || a.length() < 2) return false;
        String t = a.optString(0, "");
        try {
            if ("s".equals(t)) ed.putString(k, a.optString(1, ""));
            else if ("i".equals(t)) ed.putInt(k, a.optInt(1, 0));
            else if ("l".equals(t)) ed.putLong(k, a.optLong(1, 0));
            else if ("f".equals(t)) ed.putFloat(k, (float) a.optDouble(1, 0));
            else if ("b".equals(t)) ed.putBoolean(k, a.optBoolean(1, false));
            else if ("x".equals(t)) {
                org.json.JSONArray arr = a.optJSONArray(1);
                java.util.HashSet<String> set = new java.util.HashSet<String>();
                if (arr != null) for (int i = 0; i < arr.length(); i++) set.add(arr.optString(i, ""));
                ed.putStringSet(k, set);
            } else return false;
            return true;
        } catch (Throwable t2) {
            return false;
        }
    }

    private static boolean isKeyFile(String n) {
        for (String k : KEY_FILES) if (k.equals(n)) return true;
        return false;
    }

    /** 读整个文件（给密钥用，二进制）。 */
    private static byte[] readBytes(java.io.File f) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(2048);
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > (64 << 10)) break;
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static String readAll(java.io.File f) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(4096);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > (256 << 10)) break;
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static String versionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }
}
