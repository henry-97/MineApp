package com.henry.appmanager;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 操作历史：记录每次归档 / 还原 / 限制 / 解限。
 *
 * 只存「时间 + 包名 + 动作」，应用名由前端用它自己已经拿到的列表补 ——
 * 后端每记一条都去查一次 label 要跑 shell，而且批量操作时会被放大成几百次。
 *
 * 条数上限可配（默认 200，最大 10000）。**设为 0 即彻底关闭**：
 * 不写新记录，已有记录也不再增长（但不清空，万一误设还能看到旧的）。
 */
final class OpHistory {

    private static final String SP = "ophistory";
    private static final String KEY_LIST = "items";
    private static final String KEY_LIMIT = "limit";

    static final int DEFAULT_LIMIT = 200;
    static final int MAX_LIMIT = 10000;

    private OpHistory() { }

    static int limit(Context c) {
        if (c == null) return DEFAULT_LIMIT;
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE).getInt(KEY_LIMIT, DEFAULT_LIMIT);
    }

    static int setLimit(Context c, int n) {
        if (n < 0) n = 0;
        if (n > MAX_LIMIT) n = MAX_LIMIT;
        if (c != null) {
            SharedPreferences sp = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
            sp.edit().putInt(KEY_LIMIT, n).apply();
            // 新上限比现有条数少 → 立刻把较远的记录裁掉，而不是等到下次写入才裁。
            // n == 0 表示「关闭记录」，只停止新增，**不清空已有记录**。
            if (n > 0) {
                try {
                    JSONArray old = new JSONArray(sp.getString(KEY_LIST, "[]"));
                    if (old.length() > n) {
                        JSONArray keep = new JSONArray();
                        for (int i = 0; i < n; i++) keep.put(old.opt(i));
                        sp.edit().putString(KEY_LIST, keep.toString()).apply();
                    }
                } catch (Throwable ignored) { }
            }
        }
        return n;
    }

    /** 记一条；limit 为 0 时直接返回（关闭状态） */
    static void record(Context c, String pkg, String action) {
        if (c == null || pkg == null || pkg.length() == 0) return;
        int lim = limit(c);
        if (lim <= 0) return;

        SharedPreferences sp = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
        JSONArray old;
        try {
            old = new JSONArray(sp.getString(KEY_LIST, "[]"));
        } catch (Throwable e) {
            old = new JSONArray();
        }

        JSONArray out = new JSONArray();
        try {
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("p", pkg);
            o.put("a", action);
            out.put(o);                       // 最新的排最前
        } catch (Throwable ignored) { }
        for (int i = 0; i < old.length() && out.length() < lim; i++) {
            out.put(old.opt(i));
        }
        sp.edit().putString(KEY_LIST, out.toString()).apply();
    }

    static String list(Context c) {
        if (c == null) return "[]";
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(KEY_LIST, "[]");
    }

    static void clear(Context c) {
        if (c == null) return;
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().remove(KEY_LIST).apply();
    }
}
