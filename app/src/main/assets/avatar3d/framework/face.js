/*
 * framework/face.js — 程序化面部（栖）
 * ----------------------------------------------------------------------------
 * 直接用代码绘制表情，叠加在 chroma-key 抠像后的立绘上：
 *   - 眉：细弧线（非粗直线），随情绪微挑/垂/拧
 *   - 嘴：闭嘴=细腻弧线；张嘴=小椭圆口腔+唇线
 *   - 腮红：柔和径向渐变
 *   - 眼神高光：呼吸闪烁
 *
 * 锚点 ANCHOR 是占"整张立绘图像"的比例。五官偏了只调这几个数。
 */
(function () {
  'use strict';
  var QG = window.QG; if (!QG) return;

  // 色彩（暖调二次元肤色系）
  var BROW = '#8b6352';       // 眉毛：柔棕（非深褐）
  var MOUTH_LINE = '#c4787e'; // 唇线：柔玫红
  var MOUTH_IN = '#a84858';   // 口腔内：暗玫红
  var BLUSH = '#ffb0be';      // 腮红：淡粉

  // 五官在整图中的位置（比例）。立绘顶部有深蓝留白，五官偏下。
  var ANCHOR = {
    cx: 0.500,        // 脸中心 X
    eyeY: 0.370,      // 眼中心 Y
    browY: 0.338,     // 眉中心 Y（眼上方 ~3%）
    mouthY: 0.462,    // 嘴中心 Y
    faceW: 0.148,     // 脸宽基准
    eyeDX: 0.052      // 半眼距
  };

  // 情绪 → 目标参数
  var EMO = {
    neutral:   { brow:  0.00, mouth: 0.05, blush: 0.00, eye: 1.00, mOpen: 0.04 },
    calm:      { brow: -0.10, mouth: 0.16, blush: 0.08, eye: 1.00, mOpen: 0.03 },
    happy:     { brow: -0.28, mouth: 0.58, blush: 0.35, eye: 0.94, mOpen: 0.14 },
    sad:       { brow: -0.50, mouth: -0.50, blush: 0.08, eye: 0.82, mOpen: 0.04 },
    angry:     { brow:  0.58, mouth: -0.32, blush: 0.14, eye: 0.86, mOpen: 0.08 },
    surprised: { brow: -0.42, mouth: 0.28, blush: 0.20, eye: 1.16, mOpen: 0.40 }
  };

  function draw(ctx, geo, p, t) {
    var c = geo.crop;
    var S = geo.dw * (c.iw / c.sw);
    function PX(fx) { return geo.dx + (fx * c.iw - c.sx) / c.sw * geo.dw; }
    function PY(fy) { return geo.dy + (fy * c.ih - c.sy) / c.sh * geo.dh; }

    var cx = PX(ANCHOR.cx);
    var eyeY = PY(ANCHOR.eyeY), browY = PY(ANCHOR.browY), mouthY = PY(ANCHOR.mouthY);
    var gap = ANCHOR.eyeDX * S;
    var fW = ANCHOR.faceW * S;
    var browLen = fW * 0.38;           // 眉长
    var browTh = Math.max(1.0, fW * 0.045); // 眉粗（细！之前 0.075 太粗）
    var mouthW = fW * 0.44, mouthH = fW * 0.26;

    // —— 腮红 ——
    if (p.blush > 0.02) {
      ctx.save();
      var ba = Math.min(0.45, p.blush * 0.5);
      var bg = ctx.createRadialGradient(0, 0, 0, 0, 0, fW * 0.28);
      bg.addColorStop(0, 'rgba(255,176,190,' + ba.toFixed(3) + ')');
      bg.addColorStop(1, 'rgba(255,176,190,0)');
      ctx.fillStyle = bg;
      _ell(ctx, cx - fW * 0.40, mouthY - fW * 0.02, fW * 0.28, fW * 0.15); ctx.fill();
      _ell(ctx, cx + fW * 0.40, mouthY - fW * 0.02, fW * 0.28, fW * 0.15); ctx.fill();
      ctx.restore();
    }

    // —— 眉（细弧线，非粗直线）——
    ctx.save();
    ctx.strokeStyle = BROW;
    ctx.lineWidth = browTh;
    ctx.lineCap = 'round';
    _browArc(ctx, cx - gap, browY, browLen, p.brow * 0.40);
    _browArc(ctx, cx + gap, browY, browLen, -p.brow * 0.40);
    ctx.restore();

    // —— 嘴 ——
    var o = QG.clamp(p.mouthOpen, 0, 1);
    ctx.save();
    if (o < 0.10) {
      // 闭嘴：细腻弧线（笑上扬 / 平 / 皱下垂）
      ctx.strokeStyle = MOUTH_LINE;
      ctx.lineWidth = Math.max(1.2, fW * 0.038);
      ctx.lineCap = 'round';
      var lift = p.mouth * mouthH * 0.50;
      ctx.beginPath();
      ctx.moveTo(cx - mouthW / 2, mouthY + lift * 0.35);
      ctx.quadraticCurveTo(cx, mouthY + lift * 0.80, cx + mouthW / 2, mouthY + lift * 0.35);
      ctx.stroke();
    } else {
      // 张嘴：小椭圆口腔 + 唇线
      var mh = mouthH * (0.24 + o * 0.90);
      var mw = mouthW * (1 - o * 0.16);
      var g = ctx.createLinearGradient(0, mouthY - mh, 0, mouthY + mh);
      g.addColorStop(0, '#c46870'); g.addColorStop(1, MOUTH_IN);
      ctx.fillStyle = g;
      _ell(ctx, cx, mouthY, mw / 2, mh); ctx.fill();
      // 上唇线
      ctx.strokeStyle = MOUTH_LINE; ctx.lineWidth = Math.max(1.0, fW * 0.032);
      ctx.lineCap = 'round';
      ctx.beginPath();
      var ly = mouthY - mh + (p.mouth * mh * 0.45);
      ctx.moveTo(cx - mw / 2, ly);
      ctx.quadraticCurveTo(cx, ly - p.mouth * mh * 0.35, cx + mw / 2, ly);
      ctx.stroke();
    }
    ctx.restore();

    // —— 眼神高光 ——
    ctx.save();
    ctx.fillStyle = 'rgba(255,255,255,' + (0.75 + 0.25 * Math.sin(t * 2.0)).toFixed(2) + ')';
    var sp = 0.55 + 0.45 * Math.sin(t * 1.8);
    _ell(ctx, cx - gap + fW * 0.025, eyeY - fW * 0.025, fW * 0.045 * sp, fW * 0.065 * sp); ctx.fill();
    _ell(ctx, cx + gap + fW * 0.025, eyeY - fW * 0.025, fW * 0.045 * sp, fW * 0.065 * sp); ctx.fill();
    ctx.restore();
  }

  function _ell(ctx, x, y, rx, ry) { ctx.beginPath(); ctx.ellipse(x, y, Math.abs(rx), Math.abs(ry), 0, 0, 6.283); }

  // 细弧眉（二次贝塞尔，非直线）：眉峰在 45% 处微微上挑
  function _browArc(ctx, x, y, len, ang) {
    ctx.save();
    ctx.translate(x, y);
    ctx.rotate(ang);
    var h = len * 0.08; // 弧高（很浅的弧度）
    ctx.beginPath();
    ctx.moveTo(-len / 2, h * 0.3);
    ctx.quadraticCurveTo(0, -h, len / 2, h * 0.3);
    ctx.stroke();
    ctx.restore();
  }

  QG.Face = { ANCHOR: ANCHOR, EMO: EMO, draw: draw };
})();
