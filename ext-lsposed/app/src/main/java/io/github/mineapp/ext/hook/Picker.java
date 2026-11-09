package io.github.mineapp.ext.hook;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.view.animation.OvershootInterpolator;
import android.view.animation.TranslateAnimation;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.mineapp.ext.Const;

/**
 * 桌面「冻结应用」入口点开的弹窗：已冻结的应用 + 本模块临时解冻过的应用，
 * 带搜索框；点一项就解冻并打开；底部「全部冻结」把临时解冻的应用一次冻回去。
 *
 * 全部用代码画原生控件：模块的资源在 Mine App 进程里取不到，也不想为此拉起 WebView。
 */
final class Picker {
    private Picker() { }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final class Item {
        String pkg, label, search;
        Bitmap icon;
        boolean frozen, system;
        char letter;
    }

    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ#";
    /** 各拼音首字母的第一个汉字（GB2312 惯用分界字），配合 zh 的 Collator 判断汉字的首字母 */
    private static final String BOUND = "啊芭擦搭蛾发噶哈击喀垃妈拿哦啪七然撒塌挖昔压匝";
    private static final String BOUND_L = "ABCDEFGHJKLMNOPQRSTWXYZ";

    private static boolean launching;
    /** 本次弹窗里刚移除 / 卸载的包。刚改完状态时本进程的 PackageManager 还可能读到旧值，不能只靠它重新判断。 */
    private static final java.util.Set<String> hidden = new java.util.HashSet<>();

    static void show(final Activity a) {
        launching = false;
        hidden.clear();
        final boolean zh = ShortcutLauncher.zh();
        final boolean night = (a.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        // 半透明面板（配合窗口背景模糊，Android 12+ 有毛玻璃效果）；强调色与桌面图标的蓝色渐变一致
        final int cPanel = night ? 0xD91B2030 : 0xE8FFFFFF;
        final int cStroke = night ? 0x33FFFFFF : 0x1F000000;
        final int cFg = night ? 0xFFF2F5FA : 0xFF1B2230;
        final int cSub = night ? 0xFF8E99AD : 0xFF6B7588;
        final int cField = night ? 0x26FFFFFF : 0x12000000;
        final int cPress = night ? 0x24FFFFFF : 0x14000000;
        final int cAccent = 0xFF3D7BFF;
        final int cAccent2 = 0xFF5CC8FF;
        final int cGreen = 0xFF34C759;

        final Dialog dlg = new Dialog(a, night ? android.R.style.Theme_DeviceDefault_Dialog
                : android.R.style.Theme_DeviceDefault_Light_Dialog);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        // 返回键和点空白处都自己处理，好播退出动画
        dlg.setCancelable(false);

        final FrameLayout scrim = new FrameLayout(a);
        final View dim = new View(a);
        dim.setBackgroundColor(night ? 0x73000000 : 0x40000000);
        scrim.addView(dim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panelBg = rounded(cPanel, dp(a, 26));
        panelBg.setStroke(dp(a, 1), cStroke);
        root.setBackground(panelBg);
        root.setClickable(true);
        root.setPadding(dp(a, 18), dp(a, 18), dp(a, 18), dp(a, 14));
        if (Build.VERSION.SDK_INT >= 21) root.setElevation(dp(a, 12));

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        final TextView title = text(a, zh ? "冻结的应用" : "Frozen apps", 20, cFg, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView count = text(a, "", 12, cAccent, true);
        count.setBackground(rounded((0x26 << 24) | (cAccent & 0xFFFFFF), dp(a, 10)));
        count.setPadding(dp(a, 10), dp(a, 3), dp(a, 10), dp(a, 3));
        count.setVisibility(View.GONE);
        head.addView(count);
        root.addView(head);

        final EditText search = new EditText(a);
        search.setHint(zh ? "搜索应用名或包名" : "Search by name or package");
        search.setHintTextColor(cSub);
        search.setHighlightColor((0x55 << 24) | (cAccent & 0xFFFFFF));
        search.setTextColor(cFg);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setBackground(rounded(cField, dp(a, 14)));
        search.setPadding(dp(a, 14), dp(a, 11), dp(a, 14), dp(a, 11));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(a, 12);
        root.addView(search, sp);

        final ListView list = new ListView(a);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        final TextView empty = text(a, zh ? "加载中…" : "Loading…", 14, cSub, false);
        empty.setGravity(Gravity.CENTER);
        final IndexBar bar0 = new IndexBar(a, cSub, cAccent);
        final TextView bubble = text(a, "", 32, 0xFFFFFFFF, true);
        bubble.setGravity(Gravity.CENTER);
        bubble.setBackground(gradient(cAccent2, cAccent, dp(a, 22)));
        bubble.setVisibility(View.GONE);
        FrameHolder body = new FrameHolder(a, list, bar0, empty, bubble, dp(a, 22), dp(a, 72));
        root.addView(body.view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        final TextView freezeAll = button(a, "", cAccent, 0xFFFFFFFF, true);
        freezeAll.setBackground(gradient(cAccent2, cAccent, dp(a, 14)));
        final TextView close = button(a, zh ? "关闭" : "Close", cField, cFg, false);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        fp.rightMargin = dp(a, 10);
        pressable(freezeAll);
        pressable(close);
        bar.addView(freezeAll, fp);
        bar.addView(close, new LinearLayout.LayoutParams(dp(a, 88), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(a, 12);
        root.addView(bar, bp);

        final List<Item> all = new ArrayList<>();
        final List<Item> shown = new ArrayList<>();
        final boolean[] busy = {false};
        final boolean[] rowsAnimated = {false};
        final boolean[] closing = {false};
        final int[] firstPos = new int[LETTERS.length()];

        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View v, ViewGroup parent) {
                Item it = shown.get(i);
                LinearLayout row;
                if (v instanceof LinearLayout) {
                    row = (LinearLayout) v;
                } else {
                    row = new LinearLayout(a);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(a, 8), dp(a, 8), dp(a, 8), dp(a, 8));
                    StateListDrawable sel = new StateListDrawable();
                    sel.addState(new int[]{android.R.attr.state_pressed}, rounded(cPress, dp(a, 16)));
                    sel.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
                    row.setBackground(sel);
                    ImageView iv = new ImageView(a);
                    row.addView(iv, new LinearLayout.LayoutParams(dp(a, 42), dp(a, 42)));
                    LinearLayout col = new LinearLayout(a);
                    col.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    cp.leftMargin = dp(a, 12);
                    cp.rightMargin = dp(a, 8);
                    row.addView(col, cp);
                    TextView t1 = text(a, "", 16, cFg, false);
                    t1.setSingleLine(true);
                    t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    TextView t2 = text(a, "", 12, cSub, false);
                    t2.setSingleLine(true);
                    t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    col.addView(t1);
                    col.addView(t2);
                    TextView tg = text(a, "", 11, cGreen, true);
                    tg.setBackground(rounded(0x2634C759, dp(a, 8)));
                    tg.setPadding(dp(a, 8), dp(a, 2), dp(a, 8), dp(a, 2));
                    row.addView(tg);
                }
                ImageView iv = (ImageView) row.getChildAt(0);
                LinearLayout col = (LinearLayout) row.getChildAt(1);
                TextView tag = (TextView) row.getChildAt(2);
                iv.setImageBitmap(it.icon);
                if (it.frozen) {
                    ColorMatrix cm = new ColorMatrix();
                    cm.setSaturation(0.15f);
                    iv.setColorFilter(new ColorMatrixColorFilter(cm));
                    iv.setAlpha(0.85f);
                } else {
                    iv.clearColorFilter();
                    iv.setAlpha(1f);
                }
                ((TextView) col.getChildAt(0)).setText(it.label);
                ((TextView) col.getChildAt(1)).setText(it.pkg);
                tag.setText(zh ? "已解冻" : "Unfrozen");
                tag.setVisibility(it.frozen ? View.GONE : View.VISIBLE);
                return row;
            }
        };
        list.setAdapter(adapter);

        final Runnable refreshButton = () -> {
            int n = AutoFreeze.tempUnfrozen().size();
            freezeAll.setText((zh ? "全部冻结" : "Freeze all") + " (" + n + ")");
            freezeAll.setAlpha(n > 0 && !busy[0] ? 1f : 0.45f);
        };
        final Runnable applyFilter = () -> {
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            shown.clear();
            for (Item it : all) if (q.isEmpty() || it.search.contains(q)) shown.add(it);
            adapter.notifyDataSetChanged();
            empty.setText(all.isEmpty() ? (zh ? "没有已冻结的应用" : "No frozen apps")
                    : (zh ? "没有匹配的应用" : "No matches"));
            empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
            java.util.Arrays.fill(firstPos, -1);
            for (int i = 0; i < shown.size(); i++) {
                int li = LETTERS.indexOf(shown.get(i).letter);
                if (li >= 0 && firstPos[li] < 0) firstPos[li] = i;
            }
            bar0.setPresent(firstPos);
            count.setText(String.valueOf(all.size()));
            count.setVisibility(all.isEmpty() ? View.GONE : View.VISIBLE);
            refreshButton.run();
        };
        // 重新读一遍（图标解码放后台线程）
        final Runnable reload = () -> new Thread(() -> {
            final List<Item> fresh = collect(a);
            MAIN.post(() -> {
                all.clear();
                all.addAll(fresh);
                applyFilter.run();
                if (!rowsAnimated[0] && !all.isEmpty()) {
                    rowsAnimated[0] = true;
                    list.setLayoutAnimation(rowsIn(a));
                    list.startLayoutAnimation();
                }
            });
        }, "mineext-picker").start();

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { applyFilter.run(); }
        });

        list.setOnItemClickListener((p, v, pos, id) -> {
            if (busy[0]) return;
            final Item it = shown.get(pos);
            launching = true;
            animateOut(dlg, root, dim, closing);
            ShortcutLauncher.open(a, it.pkg);
        });

        bar0.listener = (idx, touching) -> {
            if (!touching) {
                bubble.setVisibility(View.GONE);
                return;
            }
            int pos = -1;
            for (int i = idx; i < firstPos.length && pos < 0; i++) pos = firstPos[i];
            for (int i = idx - 1; i >= 0 && pos < 0; i--) pos = firstPos[i];
            bubble.setText(String.valueOf(LETTERS.charAt(idx)));
            bubble.setVisibility(View.VISIBLE);
            bubble.setScaleX(0.7f);
            bubble.setScaleY(0.7f);
            bubble.animate().scaleX(1f).scaleY(1f).setDuration(140)
                    .setInterpolator(new OvershootInterpolator(2.2f)).start();
            if (pos >= 0) list.setSelectionFromTop(pos, 0);
        };

        list.setOnItemLongClickListener((p, v, pos, id) -> {
            if (busy[0]) return true;
            itemMenu(a, night, shown.get(pos), dlg, reload, busy);
            return true;
        });

        close.setOnClickListener(v -> animateOut(dlg, root, dim, closing));
        scrim.setOnClickListener(v -> animateOut(dlg, root, dim, closing));
        dlg.setOnKeyListener((d, key, ev) -> {
            if (key == android.view.KeyEvent.KEYCODE_BACK) {
                if (ev.getAction() == android.view.KeyEvent.ACTION_UP) animateOut(dlg, root, dim, closing);
                return true;
            }
            return false;
        });

        freezeAll.setOnClickListener(v -> {
            if (busy[0]) return;
            if (AutoFreeze.tempUnfrozen().isEmpty()) {
                Toast.makeText(a, zh ? "没有需要冻结的应用" : "Nothing to freeze", Toast.LENGTH_SHORT).show();
                return;
            }
            busy[0] = true;
            refreshButton.run();
            Toast.makeText(a, zh ? "正在冻结…" : "Freezing…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String msg;
                try {
                    List<String> ok = AutoFreeze.freezeTemp(a);
                    msg = zh ? "已冻结 " + ok.size() + " 个应用" : "Froze " + ok.size() + " app(s)";
                    int left = AutoFreeze.tempUnfrozen().size();
                    if (left > 0) msg += zh ? "，" + left + " 个失败" : ", " + left + " failed";
                } catch (Throwable t) {
                    Env.log("freeze all failed", t);
                    msg = (zh ? "冻结失败：" : "Freeze failed: ") + t.getMessage();
                }
                final String m = msg;
                MAIN.post(() -> {
                    busy[0] = false;
                    Toast.makeText(a, m, Toast.LENGTH_LONG).show();
                    reload.run();
                });
            }, "mineext-freezeall").start();
        });

        dlg.setOnDismissListener(d -> {
            if (!launching) ShortcutLauncher.done(a, null);
        });

        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(
                Math.min(dm.widthPixels - dp(a, 32), dp(a, 440)), ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER_HORIZONTAL);
        rp.topMargin = (int) (dm.heightPixels * 0.09f);
        rp.bottomMargin = (int) (dm.heightPixels * 0.09f);
        scrim.addView(root, rp);
        dlg.setContentView(scrim);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (Build.VERSION.SDK_INT >= 31) {
                // 模糊面板后面的桌面；系统关掉跨窗口模糊时这两行不起作用
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.setBlurBehindRadius(36);
                w.setAttributes(lp);
            }
        }
        root.setAlpha(0f);
        root.setScaleX(0.92f);
        root.setScaleY(0.92f);
        root.setTranslationY(dp(a, 28));
        dim.setAlpha(0f);
        applyFilter.run();
        dlg.show();
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(300)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        dim.animate().alpha(1f).setDuration(260).start();
        reload.run();
    }

    /** 已冻结的应用，加上临时解冻名单里（还没冻回去）的应用，按名称排序。 */
    private static List<Item> collect(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Set<String> temp = AutoFreeze.tempUnfrozen();
        List<Item> out = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES
                | PackageManager.MATCH_DISABLED_COMPONENTS)) {
            if (Const.TARGET_PKG.equals(ai.packageName) || hidden.contains(ai.packageName)) continue;
            boolean frozen = FrozenApps.isFrozen(pm, ai);
            if (!frozen && !temp.contains(ai.packageName)) continue;
            if (!seen.add(ai.packageName)) continue;
            Item it = new Item();
            it.pkg = ai.packageName;
            it.label = FrozenApps.label(pm, ai);
            it.search = (it.label + "\n" + it.pkg).toLowerCase(Locale.ROOT);
            it.frozen = frozen;
            it.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            it.letter = initial(it.label);
            it.icon = FrozenApps.icon(pm, ai, 96);
            out.add(it);
        }
        final Collator col = Collator.getInstance(Locale.CHINA);
        // 先按首字母分组再按名称排，同一字母的应用才挨在一起，右侧字母条才能直接跳
        Collections.sort(out, (x, y) -> {
            int c = Integer.compare(LETTERS.indexOf(x.letter), LETTERS.indexOf(y.letter));
            return c != 0 ? c : col.compare(x.label, y.label);
        });
        return out;
    }

    private static char initial(String label) {
        String t = label == null ? "" : label.trim();
        if (t.isEmpty()) return '#';
        char c = t.charAt(0);
        if (c >= 'a' && c <= 'z') return Character.toUpperCase(c);
        if (c >= 'A' && c <= 'Z') return c;
        if (c >= 0x4E00 && c <= 0x9FFF) {
            Collator col = Collator.getInstance(Locale.CHINA);
            String cs = String.valueOf(c);
            for (int i = BOUND.length() - 1; i >= 0; i--) {
                if (col.compare(cs, BOUND.substring(i, i + 1)) >= 0) return BOUND_L.charAt(i);
            }
        }
        return '#';
    }

    /** 长按菜单：从列表移除（并解冻）/ 应用详情 / 卸载 */
    private static void itemMenu(final Activity a, boolean night, final Item it, final Dialog dlg,
                                 final Runnable reload, final boolean[] busy) {
        final boolean zh = ShortcutLauncher.zh();
        final int theme = night ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        String[] opts = {zh ? "从列表移除（并解冻）" : "Remove from list (and unfreeze)",
                zh ? "打开应用详情" : "App info",
                zh ? "卸载" : "Uninstall"};
        new android.app.AlertDialog.Builder(a, theme).setTitle(it.label).setItems(opts, (d, which) -> {
            if (which == 0) {
                runBusy(a, busy, reload, zh ? "正在处理…" : "Working…", () -> {
                    if (it.frozen) {
                        List<String> ok = AppOps.batch(a, "unfreeze", Collections.singletonList(it.pkg));
                        if (!ok.contains(it.pkg)) throw new IllegalStateException(zh ? "解冻失败" : "Unfreeze failed");
                    }
                    // 不再算「临时解冻」：以后再次被冻结时才会重新出现在列表里
                    AutoFreeze.unmark(Collections.singletonList(it.pkg));
                    hidden.add(it.pkg);
                    return zh ? "已从列表移除" : "Removed from list";
                });
            } else if (which == 1) {
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + it.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    launching = true;
                    dlg.dismiss();
                    a.startActivity(i);
                    ShortcutLauncher.doneLater(a);
                } catch (Throwable t) {
                    Env.log("open app info failed", t);
                    Toast.makeText(a, zh ? "无法打开应用详情" : "Cannot open app info", Toast.LENGTH_SHORT).show();
                    ShortcutLauncher.done(a, null);
                }
            } else {
                new android.app.AlertDialog.Builder(a, theme)
                        .setTitle((zh ? "卸载 " : "Uninstall ") + it.label + "?")
                        .setMessage(it.system
                                ? (zh ? "这是系统应用，只会对当前用户卸载（pm uninstall --user 0）。"
                                : "System app: uninstalled for the current user only.")
                                : (zh ? "将卸载这个应用及其数据。" : "This removes the app and its data."))
                        .setNegativeButton(zh ? "取消" : "Cancel", null)
                        .setPositiveButton(zh ? "卸载" : "Uninstall", (d2, w2) ->
                                runBusy(a, busy, reload, zh ? "正在卸载…" : "Uninstalling…", () -> {
                                    String cmd = "pm uninstall " + (it.system ? "--user 0 " : "") + "'" + it.pkg + "'";
                                    String out = AppOps.shell(a, cmd, 60000).trim();
                                    Env.log("uninstall: " + cmd + " -> " + out);
                                    if (!out.contains("Success")) {
                                        throw new IllegalStateException(out.isEmpty()
                                                ? (zh ? "卸载失败：执行通道不可用" : "Uninstall failed: shell unavailable") : out);
                                    }
                                    AutoFreeze.unmark(Collections.singletonList(it.pkg));
                                    hidden.add(it.pkg);
                                    return zh ? "已卸载" : "Uninstalled";
                                }))
                        .show();
            }
        }).show();
    }

    private interface Job {
        String run() throws Exception;
    }

    private static void runBusy(final Activity a, final boolean[] busy, final Runnable reload,
                                String hint, final Job job) {
        busy[0] = true;
        Toast.makeText(a, hint, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                msg = job.run();
            } catch (Throwable t) {
                Env.log("picker action failed", t);
                msg = String.valueOf(t.getMessage());
            }
            final String m = msg;
            MAIN.post(() -> {
                busy[0] = false;
                Toast.makeText(a, m, Toast.LENGTH_LONG).show();
                reload.run();
            });
        }, "mineext-picker-job").start();
    }

    /** 右侧字母条：点击或滑动，通过 listener 告诉外面当前选中的字母下标 */
    private static final class IndexBar extends View {
        interface Listener {
            void onLetter(int index, boolean touching);
        }

        Listener listener;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int dim, active;
        private final boolean[] has = new boolean[LETTERS.length()];
        private int current = -1;

        IndexBar(Context c, int dim, int active) {
            super(c);
            this.dim = dim;
            this.active = active;
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(11 * c.getResources().getDisplayMetrics().scaledDensity);
        }

        void setPresent(int[] firstPos) {
            for (int i = 0; i < has.length; i++) has[i] = firstPos[i] >= 0;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            int n = LETTERS.length();
            float cell = getHeight() / (float) n;
            for (int i = 0; i < n; i++) {
                paint.setColor(has[i] ? active : dim);
                paint.setAlpha(has[i] ? 255 : 110);
                float y = cell * i + cell / 2f - (paint.ascent() + paint.descent()) / 2f;
                cv.drawText(String.valueOf(LETTERS.charAt(i)), getWidth() / 2f, y, paint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                current = -1;
                if (listener != null) listener.onLetter(0, false);
                return true;
            }
            getParent().requestDisallowInterceptTouchEvent(true);
            int n = LETTERS.length();
            int idx = Math.max(0, Math.min(n - 1, (int) (e.getY() / getHeight() * n)));
            if (idx != current) {
                current = idx;
                if (listener != null) listener.onLetter(idx, true);
            }
            return true;
        }
    }

    /** 列表与「空 / 无匹配」提示叠在一起的容器 */
    private static final class FrameHolder {
        final android.widget.FrameLayout view;

        FrameHolder(Context c, View list, View bar, View empty, View bubble, int barW, int bubbleSize) {
            view = new android.widget.FrameLayout(c);
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(list, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            row.addView(bar, new LinearLayout.LayoutParams(barW, ViewGroup.LayoutParams.MATCH_PARENT));
            view.addView(row, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            view.addView(empty, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            view.addView(bubble, new android.widget.FrameLayout.LayoutParams(
                    bubbleSize, bubbleSize, Gravity.CENTER));
        }
    }

    private static void animateOut(final Dialog dlg, View panel, View dim, boolean[] closing) {
        if (closing[0]) return;
        closing[0] = true;
        panel.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).translationY(dp(panel.getContext(), 24))
                .setDuration(170).setInterpolator(new AccelerateInterpolator()).start();
        dim.animate().alpha(0f).setDuration(170).withEndAction(() -> {
            if (dlg.isShowing()) dlg.dismiss();
        }).start();
    }

    private static LayoutAnimationController rowsIn(Context c) {
        AnimationSet set = new AnimationSet(true);
        set.addAnimation(new AlphaAnimation(0f, 1f));
        set.addAnimation(new TranslateAnimation(0, 0, dp(c, 16), 0));
        set.setDuration(280);
        set.setInterpolator(new DecelerateInterpolator(1.6f));
        return new LayoutAnimationController(set, 0.06f);
    }

    /** 按下时轻微缩小，松手回弹；不消费事件，点击照常触发 */
    private static void pressable(View v) {
        v.setOnTouchListener((x, e) -> {
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                x.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start();
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                x.animate().scaleX(1f).scaleY(1f).setDuration(180)
                        .setInterpolator(new OvershootInterpolator(2.4f)).start();
            }
            return false;
        });
    }

    private static GradientDrawable gradient(int from, int to, int radiusPx) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{from, to});
        g.setCornerRadius(radiusPx);
        return g;
    }

    private static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private static TextView button(Context c, String s, int bg, int fg, boolean bold) {
        TextView t = text(c, s, 15, fg, bold);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(bg, dp(c, 12)));
        t.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        t.setClickable(true);
        return t;
    }

    private static GradientDrawable rounded(int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    private static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
