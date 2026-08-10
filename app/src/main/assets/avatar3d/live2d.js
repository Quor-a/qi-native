/*
 * live2d.js — Live2D 分层形象引擎（离线 WebView）
 * ──────────────────────────────────────────────
 * 架构：AI 立绘(透明PNG) + 代码矢量五官 + 背景图
 *
 * 驱动源（原生 Kotlin 经 evaluateJavascript 注入）：
 *   setMouth(v)   — 口型幅度 0..1（逐音节 sin² 包络，~40fps）
 *   setEmotion(k) — 情绪 key（neutral/calm/happy/sad/angry/surprised）
 *   say(text,dur) — 开始说话
 *   endSpeech()   — 结束说话
 *   gesture(name) — 手势名
 *
 * 五官层叠顺序（从底到顶）：
 *   背景 → 身体立绘 → [肤色补丁] → 眉毛 → 眼睛/眼睑 → 嘴巴
 *
 * 坐标来源：calib.json（Python 离线像素分析，非猜测比例）
 */
(function () {
  'use strict';

  // ════════════════════════════════════════════
  // ① 标定数据（由 calib.py 从 base_cutout.png 计算）
  //     所有值为归一化坐标（相对原始立绘 832×1216）
  // ════════════════════════════════════════════
  var CAL = {
    imgW: 832, imgH: 1216,
    body: { x0: 0.27885, y0: 0.07895, x1: 0.70192, y1: 0.95066 },
    face: { cx: 0.48257, y0: 0.11266, y1: 0.36842, w: 0.21995, h: 0.25576 },
    eye:  { y: 0.23397, lx: 0.41902, rx: 0.54249, half: 0.06174, h: 0.11842 },
    brow: { y: 0.12147 },
    mouth:{ x: 0.48784, y: 0.31257, w: 0.1274 },
    skin: { r: 247, g: 219, b: 197 },
    lip:  { r: 241, g: 181, b: 186 }
  };

  // ════════════════════════════════════════════
  // ② Canvas & 缩放体系
  // ════════════════════════════════════════════
  var canvas = document.getElementById('stage');
  if (!canvas) return;
  var ctx = canvas.getContext('2d');
  var W = 0, H = 0;                    // canvas 物理尺寸
  var scale = 1;                        // 归一化→物理的缩放比

  function resize() {
    W = canvas.clientWidth;
    H = canvas.clientHeight;
    var dpr = window.devicePixelRatio || 1;
    canvas.width = W * dpr;
    canvas.height = H * dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    // FIT 模式：保持立绘宽高比，居中裁切
    scale = Math.max(W / CAL.imgW, H / CAL.imgH);
  }

  // n(x,y) 把归一化坐标转成 canvas 物理坐标（含缩放+居中偏移）
  function nx(v) { return (v * CAL.imgW - (CAL.imgW * scale - W) / 2); }
  function ny(v) { return (v * CAL.imgH - (CAL.imgH * scale - H) / 2); }
  function ns(v) { return v * scale; }  // 归一化长度→物理长度

  // ════════════════════════════════════════════
  // ③ 图片资源
  // ════════════════════════════════════════════
  var IMG = { bg: null, body: null };
  var loaded = 0, TOTAL = 2;

  function onLoad() {
    if (++loaded >= TOTAL && !STATE.ready) {
      STATE.ready = true;
      if (window.__avatar) window.__avatar.ready = true;
      var ld = document.getElementById('loading');
      if (ld) ld.classList.add('gone');
    }
  }

  function loadImg(key, src) {
    var im = new Image();
    im.onload = function () { IMG[key] = im; onLoad(); };
    im.onerror = function () { console.warn('[live2d] load fail:', src); onLoad(); };
    im.src = src;
  }

  loadImg('bg',   'live2d_assets/bg.png');
  loadImg('body', 'live2d_assets/base.png');

  // ════════════════════════════════════════════
  // ④ 运行时状态
  // ════════════════════════════════════════════
  var STATE = {
    ready: false,
    emotion: 'neutral',
    speaking: false,
    mouthAmp: 0,          // 0=闭嘴 .. 1=全开
    targetMouth: 0,
    blinkT: 0,            // 眨眼相位 0..1
    breathPhase: 0,       // 呼吸相位
    lastFrame: 0
  };

  // 情绪→眉毛形态参数
  var EMOPARAMS = {
    neutral: { browDy: 0, browCurve: 0.18, browAngle: 0, cheek: null },
    calm:    { browDy: -1, browCurve: 0.14, browAngle: 0, cheek: null },
    happy:   { browDy: -3, browCurve: 0.22, browAngle: 0.04, cheek: '#ffb6c1' },
    sad:     { browDy: 3,  browCurve: 0.16, browAngle: -0.06, cheek: null },
    angry:   { browDy: 4,  browCurve: 0.10, browAngle: -0.08, cheek: null },
    surprised:{ browDy:-5, browCurve: 0.26, browAngle: 0, cheek: null }
  };

  // ════════════════════════════════════════════
  // ⑤ 绘制函数
  // ════════════════════════════════════════════

  /** 绘制一层图片（FIT 居中） */
  function drawImageFit(im) {
    if (!im || !im.complete) return;
    var dw = im.width * scale, dh = im.height * scale;
    var dx = (W - dw) / 2, dy = (H - dh) / 2;
    ctx.drawImage(im, dx, dy, dw, dh);
  }

  /** 肤色补丁：覆盖原图嘴/眉区域（用径向渐变柔边） */
  function drawSkinPatch(x, y, pw, ph) {
    var sx = nx(x), sy = ny(y);
    var sw = ns(pw), sh = ns(ph);
    if (sw < 2 || sh < 2) return;
    var g = ctx.createRadialGradient(sx + sw/2, sy + sh/2, 0, sx + sw/2, sy + sh/2, Math.max(sw,sh)*0.65);
    g.addColorStop(0, 'rgba('+CAL.skin.r+','+CAL.skin.g+','+CAL.skin.b+',1)');
    g.addColorStop(1, 'rgba('+CAL.skin.r+','+CAL.skin.g+','+CAL.skin.b+',0)');
    ctx.fillStyle = g;
    ctx.fillRect(sx, sy, sw, sh);
  }

  /** 眉毛（弧线） */
  function drawBrow(cx, isLeft) {
    var ep = EMOPARAMS[STATE.emotion] || EMOPARAMS.neutral;
    var bx = nx(cx);
    var by = ny(CAL.brow.y) + ns(ep.browDy * 0.003);
    var bw = ns(CAL.eye.half * 0.75);
    var dir = isLeft ? 1 : -1;
    var tilt = ep.browAngle * bw;

    ctx.save();
    ctx.translate(bx, by);
    ctx.rotate(tilt * dir);
    ctx.beginPath();
    ctx.moveTo(-bw * 0.85, bw * 0.15);
    ctx.quadraticCurveTo(0, -bw * ep.browCurve, bw * 0.85, bw * 0.15);
    ctx.strokeStyle = '#6b4423';
    ctx.lineWidth = Math.max(1.2, ns(0.0045));
    ctx.lineCap = 'round';
    ctx.stroke();
    ctx.restore();
  }

  /** 眼睛高光 + 眨眼眼睑 */
  function drawEye(cx) {
    var ex = nx(cx);
    var ey = ny(CAL.eye.y);
    var ew = ns(CAL.eye.half * 0.82);
    var eh = ns(CAL.eye.h * 0.45);

    // 眨眼：眼睑从上往下盖
    var blink = STATE.blinkT;
    if (blink > 0.85) {
      // 完全闭合：画一条缝
      ctx.strokeStyle = '#3a2010';
      ctx.lineWidth = Math.max(1, eh * 0.25);
      ctx.beginPath();
      ctx.moveTo(ex - ew * 0.7, ey);
      ctx.lineTo(ex + ew * 0.7, ey);
      ctx.stroke();
      return;
    }
    if (blink > 0.3) {
      // 半闭：画眼睑
      var lidH = eh * ((blink - 0.3) / 0.55);
      var g = ctx.createLinearGradient(ex, ey - eh, ex, ey + eh * 0.3);
      g.addColorStop(0, 'rgba('+CAL.skin.r+','+CAL.skin.g+','+CAL.skin.b+',0.95)');
      g.addColorStop(1, 'rgba('+CAL.skin.r+','+CAL.skin.g+','+CAL.skin.b+',0)');
      ctx.fillStyle = g;
      ctx.fillRect(ex - ew, ey - eh, ew * 2, lidH);
    }

    // 高光点（让眼睛"有神"）
    ctx.fillStyle = 'rgba(255,255,255,0.72)';
    ctx.beginPath();
    ctx.arc(ex - ew * 0.22, ey - eh * 0.18, Math.max(1.5, ew * 0.18), 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = 'rgba(255,255,255,0.38)';
    ctx.beginPath();
    ctx.arc(ex + ew * 0.28, ey + eh * 0.12, Math.max(1, ew * 0.10), 0, Math.PI * 2);
    ctx.fill();
  }

  /** 嘴巴（口型驱动） */
  function drawMouth() {
    var mx = nx(CAL.mouth.x);
    var my = ny(CAL.mouth.y);
    var mw = ns(CAL.mouth.w);
    var mh_base = mw * 0.12;       // 闭嘴高度
    var amp = STATE.mouthAmp;       // 0..1 来自原生包络
    var open_h = amp * mw * 0.32;   // 最大张开高度

    // 先用肤色补丁盖住原嘴
    drawSkinPatch(CAL.mouth.x - CAL.mouth.w*0.55, CAL.mouth.y - 0.02,
                  CAL.mouth.w * 1.10, 0.06);

    if (open_h < 1.5) {
      // 闭嘴：细线
      ctx.strokeStyle = 'rgb('+CAL.lip.r+','+CAL.lip.g+','+CAL.lip.b+')';
      ctx.lineWidth = Math.max(1.2, mh_base * 0.6);
      ctx.beginPath();
      ctx.moveTo(mx - mw * 0.35, my);
      ctx.quadraticCurveTo(mx, my + mh_base * 0.15, mx + mw * 0.35, my);
      ctx.stroke();
      return;
    }

    // 张嘴：上唇弧 + 下唇弧 + 内腔深色
    var oh = open_h;
    // 上唇
    ctx.beginPath();
    ctx.moveTo(mx - mw * 0.38, my);
    ctx.quadraticCurveTo(mx - mw * 0.15, my - oh * 0.35, mx, my - oh * 0.45);
    ctx.quadraticCurveTo(mx + mw * 0.15, my - oh * 0.35, mx + mw * 0.38, my);
    ctx.quadraticCurveTo(mx, my + oh * 0.05, mx - mw * 0.38, my);
    ctx.closePath();
    ctx.fillStyle = 'rgb('+CAL.lip.r+','+CAL.lip.g+','+CAL.lip.b+')';
    ctx.fill();

    // 下唇
    ctx.beginPath();
    ctx.moveTo(mx - mw * 0.34, my + oh * 0.03);
    ctx.quadraticCurveTo(mx, my + oh * 0.78, mx + mw * 0.34, my + oh * 0.03);
    ctx.closePath();
    var lg = ctx.createLinearGradient(mx, my, mx, my + oh);
    lg.addColorStop(0, 'rgba('+CAL.lip.r+','+CAL.lip.g+','+CAL.lip.b+',0.9)');
    lg.addColorStop(1, 'rgba(200,120,130,0.7)');
    ctx.fillStyle = lg;
    ctx.fill();

    // 内腔（深色）
    ctx.beginPath();
    ctx.ellipse(mx, my + oh * 0.12, mw * 0.22, oh * 0.30, 0, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(60,20,25,0.62)';
    ctx.fill();

    // 舌头（微露）
    if (amp > 0.45) {
      ctx.beginPath();
      ctx.ellipse(mx, my + oh * 0.30, mw * 0.14, oh * 0.14, 0, 0, Math.PI * 2);
      ctx.fillStyle = 'rgba(220,130,140,' + ((amp-0.45)/0.55 * 0.7) + ')';
      ctx.fill();
    }

    // 牙齿（上排微露）
    if (amp > 0.55) {
      ctx.beginPath();
      ctx.rect(mx - mw * 0.16, my - oh * 0.38, mw * 0.32, oh * 0.12);
      ctx.fillStyle = 'rgba(250,245,240,' + ((amp-0.55)/0.45 * 0.85) + ')';
      ctx.fill();
    }
  }

  /** 腮红（happy 时） */
  function drawCheek(isLeft) {
    var ep = EMOPARAMS[STATE.emotion];
    if (!ep.cheek) return;
    var cx = nx(isLeft ? CAL.face.cx - CAL.face.w * 0.32 : CAL.face.cx + CAL.face.w * 0.32);
    var cy = ny(CAL.eye.y + CAL.eye.h * 0.25);
    var cr = ns(CAL.face.w * 0.12);
    var g = ctx.createRadialGradient(cx, cy, 0, cx, cy, cr);
    g.addColorStop(0, ep.cheek.replace(')', ',0.35)').replace('rgb', 'rgba'));
    g.addColorStop(1, 'rgba(255,182,193,0)');
    ctx.fillStyle = g;
    ctx.beginPath();
    ctx.ellipse(cx, cy, cr, cr * 0.65, 0, 0, Math.PI * 2);
    ctx.fill();
  }

  // ════════════════════════════════════════════
  // ⑥ 主渲染循环
  // ════════════════════════════════════════════
  function render(now) {
    requestAnimationFrame(render);

    if (!IMG.bg || !IMG.body) return;
    resize();

    var dt = STATE.lastFrame ? (now - STATE.lastFrame) / 1000 : 0.016;
    STATE.lastFrame = now;

    // ── 清屏 ──
    ctx.clearRect(0, 0, W, H);

    // ── Layer 0: 背景 ──
    drawImageFit(IMG.bg);

    // ── Layer 1: 身体立绘 ──
    drawImageFit(IMG.body);

    // ── Layer 2: 眉毛补丁 + 眉毛 ──
    drawSkinPatch(CAL.face.cx - CAL.face.w * 0.30, CAL.brow.y - 0.015,
                  CAL.face.w * 0.60, 0.035);
    drawBrow(CAL.eye.lx, true);
    drawBrow(CAL.eye.rx, false);

    // ── Layer 3: 眼睛高光 + 眨眼 ──
    drawEye(CAL.eye.lx);
    drawEye(CAL.eye.rx);

    // ── Layer 4: 嘴巴（口型驱动）──
    drawMouth();

    // ── Layer 5: 腮红 ──
    drawCheek(true);
    drawCheek(false);

    // ── 动画更新 ──
    // 口型平滑插值
    STATE.mouthAmp += (STATE.targetMouth - STATE.mouthAmp) * Math.min(1, dt * 18);

    // 眨眼：随机间隔 2-5 秒，闭 120ms
    STATE.blinkT -= dt * (STATE.blinkT > 0.5 ? 5 : (STATE.blinkT > 0 ? 8 : 0));
    if (STATE.blinkT <= 0 && !STATE._blinkCd) {
      STATE._blinkCd = 2 + Math.random() * 3;
      STATE.blinkT = 1;
    }
    if (STATE._blinkCd !== undefined) { STATE._blinkCd -= dt; if (STATE._blinkCd < 0) STATE._blinkCd = undefined; }

    // 呼吸浮动（身体微微上下）
    STATE.breathPhase += dt * 0.8;
  }

  requestAnimationFrame(render);

  // ════════════════════════════════════════════
  // ⑦ LLM 驱动契约（Kotlin evaluateJavascript）
  // ════════════════════════════════════════════
  var AvatarAPI = {
    /** 口型幅度 0..1（原生每 ~25ms 推送一次 sin² 音节包络） */
    setMouth: function (v) {
      STATE.targetMouth = Math.max(0, Math.min(1, parseFloat(v) || 0));
    },

    /** 情绪切换 */
    setEmotion: function (k) {
      if (EMOPARAMS[k]) STATE.emotion = k;
    },

    /** 开始说话 */
    say: function (text, dur) {
      STATE.speaking = true;
    },

    /** 结束说话：嘴闭合 */
    endSpeech: function () {
      STATE.speaking = false;
      STATE.targetMouth = 0;
    },

    /** 手势（预留） */
    gesture: function () {},
    setStyle: function () {},
    setAccent: function () {},
    setScene: function () {},
    setShot: function () {},
    face: function () {},
    getState: function () { return JSON.parse(JSON.stringify(STATE)); },
    ready: false
  };

  window.__avatar = AvatarAPI;

  // 点击交互
  canvas.addEventListener('pointerdown', function () {
    if (window.QiBridge && QiBridge.onTap) QiBridge.onTap();
  });

  // 安全超时：3s 后强制标记 ready（即使图没加载完也不卡 UI）
  setTimeout(function () {
    if (!STATE.ready) { STATE.ready = true; if (window.__avatar) window.__avatar.ready = true; }
  }, 3000);

})();
