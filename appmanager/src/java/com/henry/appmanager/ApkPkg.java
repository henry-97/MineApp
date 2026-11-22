package com.henry.appmanager;

/**
 * 从一个「还没装上」的 APK 文件里读出它的 applicationId。
 *
 * 为什么需要：5.3.1 桥接版要装成 com.henry.appmanager.next（新包名），
 * 而 pm install 到底是「覆盖旧包」还是「新装一个」完全取决于这两个包名是否相同 ——
 * 判断错了要么装不上（INSTALL_FAILED_ALREADY_EXISTS），要么把用户数据洗掉。
 * 所以下载完必须先读准目标包名。
 *
 * APK 里的 AndroidManifest.xml 是编译过的二进制 XML（AXML）。
 * 这里只解析到第一个 START_ELEMENT（也就是 manifest 节点）的 package 属性就停，
 * 不依赖第三方库、也不需要 aapt2 —— 手机上并没有 aapt2。
 *
 * 🔴 不要退化成「按文件名猜」：资产名是发布时定的，改一次名就全错。
 */
final class ApkPkg {

    /** 读不到就返回空串，调用方按「同包名覆盖安装」处理（最保守的那条路）。 */
    static String read(String path) {
        java.util.zip.ZipFile z = null;
        java.io.InputStream in = null;
        try {
            z = new java.util.zip.ZipFile(path);
            java.util.zip.ZipEntry e = z.getEntry("AndroidManifest.xml");
            if (e == null) return "";
            in = z.getInputStream(e);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            // manifest 条目正常只有几 KB；给 1MB 上限防止畸形包把内存吃光
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > (1 << 20)) break;
            }
            return parse(bos.toByteArray());
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
            if (z != null) try { z.close(); } catch (Throwable ignored) { }
        }
    }

    // ---------------- AXML ----------------

    private static final int RES_XML = 0x0003;
    private static final int STRING_POOL = 0x0001;
    private static final int START_ELEMENT = 0x0102;
    private static final int TYPE_STRING = 0x03;

    private static String parse(byte[] b) {
        if (b.length < 8 || u16(b, 0) != RES_XML) return "";
        // 文件头之后就是一串 chunk，字符串池一定在 START_ELEMENT 之前
        String[] pool = null;
        int p = u16(b, 2);
        while (p + 8 <= b.length) {
            int type = u16(b, p);
            int hsz = u16(b, p + 2);
            int size = i32(b, p + 4);
            if (size <= 0 || p + size > b.length || hsz < 8) break;

            if (type == STRING_POOL) {
                pool = strings(b, p, hsz, size);
            } else if (type == START_ELEMENT) {
                if (pool == null) return "";
                // ResXMLTree_node 的 headerSize 已经把 lineNumber/comment 算进去了
                // （= 16），所以 attrExt 紧接在 header 之后，不能再多跳 8 字节。
                int ext = p + hsz;
                int nameIdx = i32(b, ext + 4);
                if (!"manifest".equals(str(pool, nameIdx))) return "";
                int attrStart = u16(b, ext + 8);
                int attrSize = u16(b, ext + 10);
                int attrCount = u16(b, ext + 12);
                if (attrSize < 20) attrSize = 20;
                int a = ext + attrStart;
                for (int i = 0; i < attrCount; i++, a += attrSize) {
                    if (a + 20 > b.length) break;
                    if (!"package".equals(str(pool, i32(b, a + 4)))) continue;
                    int raw = i32(b, a + 8);
                    if (raw >= 0) {
                        String s = str(pool, raw);
                        if (s.length() > 0) return s;
                    }
                    // 没有 rawValue 就看 typed value
                    if ((b[a + 15] & 0xff) == TYPE_STRING) {
                        String s = str(pool, i32(b, a + 16));
                        if (s.length() > 0) return s;
                    }
                }
                // 第一个 START_ELEMENT 不是 manifest 就已经没戏了
                return "";
            }
            p += size;
        }
        return "";
    }

    private static String[] strings(byte[] b, int p, int hsz, int size) {
        int count = i32(b, p + 8);
        int flags = i32(b, p + 16);
        int start = i32(b, p + 20);
        if (count <= 0 || count > 100000) return null;
        boolean utf8 = (flags & 0x100) != 0;
        String[] out = new String[count];
        int base = p + start;
        for (int i = 0; i < count; i++) {
            int off = i32(b, p + hsz + i * 4);
            int q = base + off;
            if (q < 0 || q + 2 > b.length) { out[i] = ""; continue; }
            try {
                if (utf8) {
                    // UTF-8：先 UTF-16 长度（1-2 字节），再 UTF-8 长度（1-2 字节）
                    int v = b[q] & 0xff;
                    q += (v & 0x80) != 0 ? 2 : 1;
                    int w = b[q] & 0xff;
                    int len;
                    if ((w & 0x80) != 0) { len = ((w & 0x7f) << 8) | (b[q + 1] & 0xff); q += 2; }
                    else { len = w; q += 1; }
                    if (len < 0 || q + len > b.length) { out[i] = ""; continue; }
                    out[i] = new String(b, q, len, "UTF-8");
                } else {
                    // UTF-16：长度 2 或 4 字节，单位是字符
                    int len = u16(b, q);
                    q += 2;
                    if ((len & 0x8000) != 0) { len = ((len & 0x7fff) << 16) | u16(b, q); q += 2; }
                    len *= 2;
                    if (len < 0 || q + len > b.length) { out[i] = ""; continue; }
                    out[i] = new String(b, q, len, "UTF-16LE");
                }
            } catch (Throwable t) {
                out[i] = "";
            }
        }
        return out;
    }

    private static String str(String[] pool, int i) {
        if (pool == null || i < 0 || i >= pool.length) return "";
        String s = pool[i];
        return s == null ? "" : s;
    }

    private static int u16(byte[] b, int p) {
        if (p < 0 || p + 2 > b.length) return 0;
        return (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8);
    }

    private static int i32(byte[] b, int p) {
        if (p < 0 || p + 4 > b.length) return 0;
        return (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8)
                | ((b[p + 2] & 0xff) << 16) | ((b[p + 3] & 0xff) << 24);
    }
}
