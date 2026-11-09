package io.github.mineapp.ext.hook;

/**
 * 注入 Mine App 设置页的「执行通道」一栏。
 *
 * 复用页面自己的 .setrow / .sortbar / .seg / .info-note / .edit / .switch 样式，
 * 深浅主题自动跟随；语言跟随页面的 lang()。脚本幂等，可重复执行。
 */
final class InjectJs {
    private InjectJs() { }

    static final String SCRIPT = """
(function(){
  if (window.__mineExt || typeof MineExt === 'undefined') return;
  var body = document.querySelector('#settings .sheet-body');
  if (!body) return;
  window.__mineExt = 1;

  var cbs = {}, seq = 0;
  window.__mineExtResult = function(id, s){
    var f = cbs[id]; delete cbs[id];
    if (!f) return;
    var d; try { d = JSON.parse(s); } catch(e){ d = {ok:false}; }
    f(d);
  };
  function ext(a, arg, f){
    var id = ++seq; cbs[id] = f || function(){};
    MineExt.call(a, arg == null ? '' : String(arg), id);
  }
  window.__mineExtCall = ext;
  function $(id){ return document.getElementById(id); }
  function isZh(){
    try { if (typeof lang === 'function') return lang() === 'zh'; } catch(e){}
    return (navigator.language || 'zh').toLowerCase().indexOf('zh') === 0;
  }
  var S = {
    zh: {
      title: '执行通道', fb: 'Root / Shizuku 不可用时回退 ADB', req: '申请授权', retry: '重新连接',
      adb: '使用内置 ADB 客户端，需要先执行一次 adb tcpip。',
      root: 'Root 已接管，不再需要 adb tcpip。',
      rootFail: 'Root 不可用', shz: 'Shizuku 已接管，不再需要 adb tcpip。',
      fbNow: '当前已回退到 ADB。', waiting: '正在连接…', asking: '请在弹出的 Shizuku 授权框中选择「始终允许」…',
      warn: '即将弹出 Shizuku 授权框。请选「始终允许」：选「拒绝」会被 Shizuku 永久记住。',
      bad: '当前 Mine App 版本与扩展模块不兼容。',
      sh: {
        '-1': '拿不到 Shizuku 连接：「Mine App 扩展」被禁用或未安装（装了 Sui 则不需要它）。',
        '0': 'Shizuku 未运行，请先在 Shizuku 应用中启动服务。',
        '1': 'Shizuku 尚未授权给 Mine App，点「申请授权」。',
        '2': 'Shizuku 授权已被拒绝并被永久记住。Mine App 不会出现在 Shizuku 的授权列表里；需卸载重装 Mine App，或删除 /data/user_de/0/com.android.shell/shizuku.json 并重启 Shizuku 后再申请。',
        '3': 'Shizuku 已授权。',
        '4': 'Shizuku 版本过低（需 v11 以上）。'
      }
    },
    en: {
      title: 'Shell backend', fb: 'Fall back to ADB if Root / Shizuku fails', req: 'Grant', retry: 'Reconnect',
      adb: 'Built-in ADB client; needs a one-time adb tcpip.',
      root: 'Running via root; adb tcpip not needed.',
      rootFail: 'Root unavailable', shz: 'Running via Shizuku; adb tcpip not needed.',
      fbNow: 'Fell back to ADB.', waiting: 'Connecting…', asking: 'Choose "Allow all the time" in the Shizuku dialog…',
      warn: 'A Shizuku dialog will appear. Choose "Allow all the time"; "Deny" is remembered permanently.',
      bad: 'This Mine App version is not supported by the extension.',
      sh: {
        '-1': 'Cannot reach Shizuku: "Mine App Extension" is disabled or missing (not needed with Sui).',
        '0': 'Shizuku is not running. Start it in the Shizuku app first.',
        '1': 'Shizuku is not granted to Mine App yet. Tap "Grant".',
        '2': 'Shizuku permission was denied and is remembered. Mine App is not listed in Shizuku; reinstall Mine App, or delete /data/user_de/0/com.android.shell/shizuku.json and restart Shizuku, then retry.',
        '3': 'Shizuku permission granted.',
        '4': 'Shizuku is too old (need v11+).'
      }
    }
  };
  function L(){ return isZh() ? S.zh : S.en; }

  var row = document.createElement('div');
  row.className = 'setrow';
  row.innerHTML = '<span class="setrow-lb" id="mxTitle"></span>'
    + '<div class="sortbar" id="mxBar">'
    + '<button class="seg" data-mx="adb">ADB</button>'
    + '<button class="seg" data-mx="root">Root</button>'
    + '<button class="seg" data-mx="shizuku">Shizuku</button></div>';
  var note = document.createElement('div');
  note.id = 'mxNote';
  note.className = 'info-note';
  note.style.cssText = 'border-top:0;padding-top:0;margin-top:2px;margin-bottom:12px';
  note.innerHTML = '<div style="display:flex;align-items:center;gap:8px">'
    + '<span id="mxText" style="flex:1;min-width:0"></span>'
    + '<button class="edit" id="mxReq" style="display:none"></button>'
    + '<button class="edit" id="mxRetry" style="display:none"></button></div>'
    + '<div style="display:flex;align-items:center;gap:8px;margin-top:8px">'
    + '<span id="mxFbLb" style="flex:1;min-width:0"></span>'
    + '<label class="switch"><input type="checkbox" id="mxFb"><span class="slider"></span></label></div>';
  var idx = $('idxBtn');
  var anchor = idx && idx.closest ? idx.closest('.setrow') : null;
  if (anchor && anchor.parentNode === body) {
    body.insertBefore(row, anchor);
    body.insertBefore(note, anchor);
  } else {
    body.appendChild(row);
    body.appendChild(note);
  }

  var st = null, asking = false;
  function render(){
    var l = L();
    $('mxTitle').textContent = l.title;
    $('mxFbLb').textContent = l.fb;
    $('mxReq').textContent = l.req;
    $('mxRetry').textContent = l.retry;
    var segs = document.querySelectorAll('#mxBar .seg');
    for (var i = 0; i < segs.length; i++)
      segs[i].classList.toggle('on', !!st && segs[i].getAttribute('data-mx') === st.mode);
    if (asking) {
      $('mxText').textContent = l.asking;
      $('mxReq').style.display = 'none';
      $('mxRetry').style.display = 'none';
      return;
    }
    if (!st || st.mode == null) { $('mxText').textContent = l.waiting; return; }
    $('mxFb').checked = !!st.fallback;
    var t, fb = st.active === 'adb-fallback' ? ' ' + l.fbNow : '';
    if (st.incompatible) t = l.bad;
    else if (st.mode === 'adb') t = l.adb;
    else if (st.active === st.mode) t = st.mode === 'root' ? l.root : l.shz;
    else if (st.mode === 'root') t = st.err ? l.rootFail + ': ' + st.err + fb : l.waiting;
    else if (st.shizuku != null && st.shizuku !== 3) t = (l.sh[String(st.shizuku)] || l.sh['0']) + fb;
    else t = st.err ? 'Shizuku: ' + st.err + fb : l.waiting;
    $('mxText').textContent = t;
    $('mxReq').style.display = (st.mode === 'shizuku' && st.shizuku === 1) ? '' : 'none';
    $('mxRetry').style.display = (st.mode !== 'adb' && st.active !== 'none'
                                  && st.active !== st.mode) ? '' : 'none';
  }
  function refresh(){ ext('status', '', function(d){ st = d; render(); }); }
  function reload(){
    try { if (typeof load === 'function') load(true); } catch(e){}
    setTimeout(refresh, 1500);
    setTimeout(refresh, 5000);
  }
  /* 授权框由 Shizuku 弹出，Java 侧一直等到用户选完才回调 */
  function requestShizuku(){
    if (asking) return;
    if (typeof toast === 'function') toast(L().warn, 4000);
    asking = true; render();
    setTimeout(function(){
      ext('requestShizuku', '', function(d){
        asking = false; st = d; render();
        if (d.shizuku === 3) reload();
      });
    }, 1500);
  }

  var segs = document.querySelectorAll('#mxBar .seg');
  for (var i = 0; i < segs.length; i++) segs[i].onclick = function(){
    var m = this.getAttribute('data-mx');
    st = st || {}; st.mode = m; st.active = 'none'; st.err = ''; st.shizuku = null;
    render();
    ext('setMode', m, function(d){
      st = d; render();
      if (m === 'shizuku' && d.shizuku === 1) requestShizuku();
      reload();
    });
  };
  $('mxReq').onclick = requestShizuku;
  $('mxRetry').onclick = function(){
    ext('reconnect', '', function(d){ st = d; render(); reload(); });
  };
  $('mxFb').onchange = function(){
    ext('fallback', this.checked ? '1' : '0', function(d){ st = d; render(); });
  };

  var sheet = $('settings');
  if (sheet && window.MutationObserver) {
    new MutationObserver(function(){
      if (sheet.classList.contains('show')) refresh();
    }).observe(sheet, {attributes: true, attributeFilter: ['class']});
  }
  render();
  refresh();
})();

/* ---------- 冻结应用快捷方式 ---------- */
(function(){
  if (window.__mineExtSc || !window.__mineExtCall) return;
  var note = document.getElementById('mxNote');
  if (!note) return;
  window.__mineExtSc = 1;
  var ext = window.__mineExtCall;
  function $(id){ return document.getElementById(id); }
  function isZh(){
    try { if (typeof lang === 'function') return lang() === 'zh'; } catch(e){}
    return (navigator.language || 'zh').toLowerCase().indexOf('zh') === 0;
  }
  function esc(s){
    return String(s).replace(/[&<>"']/g, function(c){
      return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
    });
  }
  var S = {
    zh: {
      entry: '冻结应用快捷方式', open: '打开',
      entryNote: '给已冻结的应用建桌面快捷方式：点一下就解冻并打开，锁屏后可自动重新冻结。',
      title: '冻结应用快捷方式', af: '锁屏后自动冻结',
      afOpts: [[-1,'不自动冻结'],[0,'锁屏立即'],[1,'1 分钟'],[5,'5 分钟'],[10,'10 分钟'],[30,'30 分钟'],[60,'1 小时']],
      afNote: '只对通过这里的快捷方式解冻的应用生效；在 Mine App 里手动解冻的不受影响。',
      temp: '当前临时解冻、锁屏后将重新冻结：',
      pinNote: '点「添加」后桌面没出现图标？MIUI / HyperOS 需要在应用信息 → 权限里允许 Mine App 的「桌面快捷方式」。',
      goSet: '去设置',
      pick: '添加弹窗入口到桌面', freezeAll: '全部冻结',
      pickNote: '弹窗入口：桌面上的一个图标，点开是已冻结应用的列表，可搜索，点一项就解冻并打开；「全部冻结」把解冻过的应用一次冻回去。',
      noTemp: '没有需要冻结的应用', fzDone: '已冻结 %d 个应用', fzLeft: '，%d 个失败', fzFail: '冻结失败',
      add: '添加', added: '已添加', loading: '加载中…', empty: '没有已冻结的应用',
      requested: '已请求添加到桌面', updated: '桌面快捷方式已更新',
      unsupported: '当前桌面不支持添加快捷方式', missing: '应用已不存在'
    },
    en: {
      entry: 'Frozen app shortcuts', open: 'Open',
      entryNote: 'Home-screen shortcuts for frozen apps: tap to unfreeze and open; optionally refreeze after screen off.',
      title: 'Frozen app shortcuts', af: 'Refreeze after screen off',
      afOpts: [[-1,'Never'],[0,'Immediately'],[1,'1 min'],[5,'5 min'],[10,'10 min'],[30,'30 min'],[60,'1 hour']],
      afNote: 'Only applies to apps unfrozen through these shortcuts; manual unfreezes in Mine App are left alone.',
      temp: 'Temporarily unfrozen, will refreeze after screen off: ',
      pinNote: 'No icon on the home screen? On MIUI / HyperOS allow Mine App the "Home screen shortcuts" permission in App info.',
      goSet: 'Settings',
      pick: 'Add picker to home screen', freezeAll: 'Freeze all',
      pickNote: 'Picker: one home-screen icon that opens a searchable list of frozen apps; tap one to unfreeze and open it. "Freeze all" refreezes everything you unfroze.',
      noTemp: 'Nothing to freeze', fzDone: 'Froze %d app(s)', fzLeft: ', %d failed', fzFail: 'Freeze failed',
      add: 'Add', added: 'Added', loading: 'Loading…', empty: 'No frozen apps',
      requested: 'Requested to add to home screen', updated: 'Shortcut updated',
      unsupported: 'This launcher does not support pinned shortcuts', missing: 'App no longer exists'
    }
  };
  function L(){ return isZh() ? S.zh : S.en; }

  /* 设置页入口 */
  var row = document.createElement('div');
  row.className = 'setrow';
  row.style.cssText = 'gap:12px;align-items:center';
  row.innerHTML = '<div class="setrow-lb" id="mxScEntry" style="width:auto;flex:1;min-width:0;white-space:normal;line-height:1.4"></div>'
    + '<button class="edit" id="mxScOpen" style="padding:0 14px;flex:none"></button>';
  var rowNote = document.createElement('div');
  rowNote.className = 'info-note';
  rowNote.id = 'mxScEntryNote';
  rowNote.style.cssText = 'border-top:0;padding-top:0;margin-top:2px;margin-bottom:12px';
  note.parentNode.insertBefore(rowNote, note.nextSibling);
  note.parentNode.insertBefore(row, rowNote);

  /* 独立页面，盖在设置页之上 */
  var sheet = document.createElement('div');
  sheet.id = 'mxSc';
  sheet.className = 'sheet';
  sheet.style.zIndex = '60';
  sheet.innerHTML = '<div class="sheet-hd"><h2 id="mxScTitle"></h2><button class="close" id="mxScClose">×</button></div>'
    + '<div class="sheet-body">'
    + '<div class="setrow"><span class="setrow-lb" id="mxAfLb"></span>'
    + '<select id="mxAf" style="flex:1;min-width:0;padding:8px 12px;font-size:14px;border:0.5px solid var(--line);'
    + 'border-radius:10px;background:var(--card);color:var(--ink);outline:none"></select></div>'
    + '<div class="info-note" id="mxAfNote" style="border-top:0;padding-top:0;margin-top:2px"></div>'
    + '<div class="info-note" id="mxTemp" style="border-top:0;padding-top:0;margin-top:4px;display:none"></div>'
    + '<div class="info-note" style="border-top:0;padding-top:0;margin-top:4px">'
    + '<span id="mxPinNote"></span>&nbsp;&nbsp;<a href="#" id="mxGoSet"></a></div>'
    + '<div class="setrow" style="gap:10px;margin-top:8px">'
    + '<button class="edit" id="mxPick" style="flex:1;padding:0 12px"></button>'
    + '<button class="edit" id="mxFreezeAll" style="flex:1;padding:0 12px"></button></div>'
    + '<div class="info-note" id="mxPickNote" style="border-top:0;padding-top:0;margin-top:2px"></div>'
    + '<ul id="mxScList" style="list-style:none;margin:6px 0 0;padding:0"></ul>'
    + '</div>';
  document.body.appendChild(sheet);

  var data = null;
  function renderStatic(){
    var l = L();
    $('mxScEntry').textContent = l.entry;
    $('mxScOpen').textContent = l.open;
    $('mxScEntryNote').textContent = l.entryNote;
    $('mxScTitle').textContent = l.title;
    $('mxAfLb').textContent = l.af;
    $('mxAfNote').textContent = l.afNote;
    $('mxPinNote').textContent = l.pinNote;
    $('mxGoSet').textContent = l.goSet;
    $('mxPick').textContent = l.pick;
    $('mxPickNote').textContent = l.pickNote;
    renderFreezeAll();
    var sel = $('mxAf'), cur = data ? data.autofreeze : 5, h = '';
    for (var i = 0; i < l.afOpts.length; i++) {
      var o = l.afOpts[i];
      h += '<option value="' + o[0] + '"' + (o[0] === cur ? ' selected' : '') + '>' + o[1] + '</option>';
    }
    sel.innerHTML = h;
  }
  function renderFreezeAll(){
    var n = data && data.temp ? data.temp.length : 0;
    $('mxFreezeAll').textContent = L().freezeAll + ' (' + n + ')';
    $('mxFreezeAll').style.opacity = n ? '' : '.5';
  }
  function renderList(){
    renderFreezeAll();
    var l = L(), ul = $('mxScList');
    if (!data) { ul.innerHTML = '<li class="panel-loading">' + l.loading + '</li>'; return; }
    var t = $('mxTemp');
    if (data.temp && data.temp.length) {
      t.style.display = '';
      t.textContent = l.temp + data.temp.join(isZh() ? '、' : ', ');
    } else t.style.display = 'none';
    if (!data.apps.length) { ul.innerHTML = '<li class="panel-loading">' + l.empty + '</li>'; return; }
    var h = '';
    for (var i = 0; i < data.apps.length; i++) {
      var a = data.apps[i];
      h += '<li class="trow">'
        + '<img src="' + a.icon + '" alt="" style="width:40px;height:40px;border-radius:9px;flex:none;filter:grayscale(.6)">'
        + '<div style="flex:1;min-width:0"><div class="tl" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'
        + esc(a.name) + '</div><div class="ts" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'
        + esc(a.pkg) + '</div></div>'
        + '<button class="edit" data-pin="' + esc(a.pkg) + '" style="flex:none;padding:0 12px">'
        + (a.pinned ? l.added : l.add) + '</button></li>';
    }
    ul.innerHTML = h;
  }
  function load(){
    ext('frozenList', '', function(d){
      if (!d || !d.apps) return;
      data = d; renderStatic(); renderList();
    });
  }

  function open(){
    data = null;
    renderStatic(); renderList();
    sheet.classList.add('show');
    /* 让系统返回键（MainActivity 里走 web.goBack）能关掉这一页 */
    try { history.pushState({mx: 'sc'}, ''); } catch(e){}
    load();
  }
  function hide(){ sheet.classList.remove('show'); }
  window.addEventListener('popstate', function(){
    if (sheet.classList.contains('show')) hide();
  });
  $('mxScOpen').onclick = open;
  $('mxScClose').onclick = function(){
    if (history.state && history.state.mx === 'sc') history.back(); else hide();
  };
  $('mxGoSet').onclick = function(e){ e.preventDefault(); ext('appInfo', ''); };
  $('mxAf').onchange = function(){
    ext('setAutoFreeze', this.value, function(d){ if (data && d && d.ok) data.autofreeze = d.autofreeze; });
  };
  $('mxPick').onclick = function(){
    ext('pinPicker', '', function(d){
      var l = L(), r = d && d.result;
      if (typeof toast === 'function') toast(l[r] || l.unsupported);
    });
  };
  var fzBusy = false;
  $('mxFreezeAll').onclick = function(){
    var l = L();
    if (fzBusy) return;
    if (!data || !data.temp || !data.temp.length) { if (typeof toast === 'function') toast(l.noTemp); return; }
    fzBusy = true;
    ext('freezeTemp', '', function(d){
      fzBusy = false;
      var m = d && d.ok ? l.fzDone.replace('%d', d.frozen) + (d.left ? l.fzLeft.replace('%d', d.left) : '')
                         : l.fzFail + (d && d.err ? ': ' + d.err : '');
      if (typeof toast === 'function') toast(m);
      load();
    });
  };
  $('mxScList').onclick = function(e){
    var b = e.target.closest ? e.target.closest('button[data-pin]') : null;
    if (!b) return;
    ext('pin', b.getAttribute('data-pin'), function(d){
      var l = L(), r = d && d.result;
      if (typeof toast === 'function') toast(l[r] || l.unsupported);
      if (r === 'requested' || r === 'updated') setTimeout(load, 2500);
    });
  };

  renderStatic();
  var settings = $('settings');
  if (settings && window.MutationObserver) {
    new MutationObserver(function(){
      if (settings.classList.contains('show')) renderStatic();
    }).observe(settings, {attributes: true, attributeFilter: ['class']});
  }
})();
""";
}
