package com.henry.appmanager;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * 只读 SQLite 解析器：全表扫描一张表就够，其余一概不管。
 *
 * 为什么不用 sqlite3：那个二进制只在 eng/userdebug 构建里编译，零售机（哪怕装了 Magisk）
 * 通常没有。root 能给我们的只有「把 launcher.db 拷出来」，解析必须自己做。
 *
 * 覆盖：文件头、表 b-tree（叶子 0x0D / 内部 0x05）、变长整数、record 编码、溢出页。
 * 不覆盖：索引 b-tree、WAL 未合并内容、加密库 —— 桌面库都用不到。
 */
final class MiniSqlite {

    private final byte[] d;
    private final int ps;      // 页大小
    private final int usable;  // 页内可用字节
    private final int enc;     // 1=UTF-8, 2=UTF-16LE, 3=UTF-16BE
    private List<String[]> master;

    MiniSqlite(File f) throws Exception {
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            long n = r.length();
            if (n < 512 || n > 32L * 1024 * 1024) throw new java.io.IOException("bad size " + n);
            d = new byte[(int) n];
            r.readFully(d);
        } finally {
            r.close();
        }
        int p = u16(16);
        ps = p == 1 ? 65536 : p;
        usable = ps;
        int e = u32(56);
        enc = e >= 1 && e <= 3 ? e : 1;
    }

    /** 列名；表不存在返回 null */
    String[] columns(String table) {
        for (String[] r : master())
            if ("table".equals(r[0]) && table.equals(r[1]) && r[4] != null) return splitCols(r[4]);
        return null;
    }

    /** 全表扫描；表不存在返回 null */
    List<String[]> rows(String table) {
        String[] cols = columns(table);
        if (cols == null) return null;
        int root = 0;
        for (String[] r : master())
            if ("table".equals(r[0]) && table.equals(r[1])) root = Integer.parseInt(r[3]);
        if (root <= 0) return null;
        List<Integer> leaf = new ArrayList<Integer>();
        collect(root, leaf);
        List<String[]> out = new ArrayList<String[]>(leaf.size());
        int[] rid = new int[1];
        for (int i = 0; i < leaf.size(); i++) {
            String[] r = record(payload(leaf.get(i), rid), cols.length);
            // _id 是 INTEGER PRIMARY KEY 时记录里存的是 NULL（它只是 rowid 的别名）
            for (int k = 0; k < r.length; k++)
                if (r[k] == null && "_id".equalsIgnoreCase(cols[k])) r[k] = Long.toString(rid[0]);
            out.add(r);
        }
        return out;
    }

    /** sqlite_master 是第 1 页的那棵表 b-tree，只扫一次 */
    private List<String[]> master() {
        if (master == null) {
            List<Integer> leaf = new ArrayList<Integer>();
            collect(1, leaf);
            master = new ArrayList<String[]>(leaf.size());
            for (int i = 0; i < leaf.size(); i++)
                master.add(record(payload(leaf.get(i), new int[1]), 5));
        }
        return master;
    }

    /** 深度优先收集表 b-tree 的叶子 cell 偏移 */
    private void collect(int page, List<Integer> leaf) {
        int base = (page - 1) * ps, h = page == 1 ? 100 : base;
        int type = d[h] & 255, n = u16(h + 3);
        if (type == 13) {
            for (int i = 0; i < n; i++) leaf.add(base + u16(h + 8 + i * 2));
        } else if (type == 5) {
            for (int i = 0; i < n; i++) collect(u32(base + u16(h + 12 + i * 2)), leaf);
            collect(u32(h + 8), leaf);
        }
    }

    /** 一个 cell 的完整 payload（含溢出页拼接），顺带把 rowid 带回去 */
    private byte[] payload(int c, int[] rowid) {
        int[] u = new int[1];
        int len = (int) varint(d, c, u);
        int p = c + u[0];
        rowid[0] = (int) varint(d, p, u);
        p += u[0];
        byte[] out = new byte[len];
        // 表叶子页：X = U-35；M = ((U-12)*32/255)-23。超长 payload 先放 M 段再挂溢出页
        int maxLocal = usable - 35;
        if (len <= maxLocal) {
            System.arraycopy(d, p, out, 0, len);
            return out;
        }
        int minLocal = ((usable - 12) * 32 / 255) - 23;
        int local = minLocal + (len - minLocal) % (usable - 4);
        if (local > maxLocal) local = minLocal;
        System.arraycopy(d, p, out, 0, local);
        int pos = local, next = u32(p + local);
        while (next > 0 && pos < len) {
            int b = (next - 1) * ps, take = Math.min(usable - 4, len - pos);
            next = u32(b);
            System.arraycopy(d, b + 4, out, pos, take);
            pos += take;
        }
        return out;
    }

    /** record：变长头 + 一串 serial type，再按类型取值 */
    private String[] record(byte[] b, int ncol) {
        int[] u = new int[1];
        int hl = (int) varint(b, 0, u);
        int hp = u[0], end = hl, bp = hl;   // hl 已包含自身那几个字节
        String[] r = new String[ncol];
        for (int i = 0; i < ncol && hp < end; i++) {
            int st = (int) varint(b, hp, u);
            hp += u[0];
            if (st == 0) continue;                       // NULL
            if (st <= 6) {
                int n = st == 5 ? 6 : st == 6 ? 8 : st;
                r[i] = Long.toString(intN(b, bp, n));
                bp += n;
            } else if (st == 7) {
                r[i] = Double.toString(Double.longBitsToDouble(intN(b, bp, 8)));
                bp += 8;
            } else if (st == 8 || st == 9) {
                r[i] = st == 8 ? "0" : "1";              // 常量 0 / 1
            } else {
                int n = (st - 12) / 2;
                r[i] = text(b, bp, n);
                bp += n;
            }
        }
        return r;
    }

    private String text(byte[] b, int p, int n) {
        if (n <= 0) return "";
        try {
            if (enc == 1) return new String(b, p, n, "UTF-8");
            return new String(b, p, n, enc == 2 ? "UTF-16LE" : "UTF-16BE");
        } catch (Exception e) {
            return "";
        }
    }

    /** CREATE TABLE sql → 按顺序的列名 */
    private static String[] splitCols(String sql) {
        int a = sql.indexOf('('), b = sql.lastIndexOf(')');
        if (a < 0 || b <= a) return new String[0];
        List<String> out = new ArrayList<String>();
        int depth = 0, s = a + 1;
        for (int i = a + 1; i <= b; i++) {
            char ch = i == b ? ',' : sql.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                String part = sql.substring(s, i).trim();
                s = i + 1;
                if (part.isEmpty()) continue;
                int sp = 0;
                while (sp < part.length() && part.charAt(sp) != ' ' && part.charAt(sp) != '\t') sp++;
                String n = part.substring(0, sp).replace("`", "").replace("\"", "")
                        .replace("[", "").replace("]", "");
                if (n.length() > 0 && !n.equalsIgnoreCase("PRIMARY") && !n.equalsIgnoreCase("UNIQUE")
                        && !n.equalsIgnoreCase("CONSTRAINT") && !n.equalsIgnoreCase("FOREIGN"))
                    out.add(n);
            }
        }
        return out.toArray(new String[0]);
    }

    private int u16(int o) {
        return ((d[o] & 255) << 8) | (d[o + 1] & 255);
    }

    private int u32(int o) {
        return ((d[o] & 255) << 24) | ((d[o + 1] & 255) << 16)
                | ((d[o + 2] & 255) << 8) | (d[o + 3] & 255);
    }

    /** n 字节大端有符号整数（n = 1/2/3/4/6/8） */
    private static long intN(byte[] b, int p, int n) {
        long v = (b[p] & 128) != 0 ? -1L : 0L;
        for (int i = 0; i < n; i++) v = (v << 8) | (b[p + i] & 255);
        return v;
    }

    private static long varint(byte[] b, int p, int[] used) {
        long v = 0;
        for (int i = 0; i < 9; i++) {
            int c = b[p + i] & 255;
            v = (v << 7) | (c & 127);
            if ((c & 128) == 0) {
                used[0] = i + 1;
                return v;
            }
        }
        used[0] = 9;
        return v;
    }
}
