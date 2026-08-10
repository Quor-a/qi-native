/*
 * framework/character.js — 角色实体（栖）— 素材驱动·完整场景版
 * ----------------------------------------------------------------------------
 * 彻底放弃程序化面部绘制和 chroma-key 抠像。
 * 每张情绪立绘(emotions/emotion_{name}.png)自带完整房间场景
 *   （人物+背景是一体化的预渲染图），运行时直接 drawImage，
 *   物理上不可能有双层背景。
 *
 * LLM 通过 setEmotion('happy'|'sad'|...) 驱动切换，带淡入淡出过渡。
 */
(function () {
  'use strict';
  var QG = window.QG; if (!QG) return;

  var GEST = {
    wave:   { tx: 20, ty: -16, rot:  0.05 },
    nod:    { ty: -12 },
    shake:  { tx: 12, rot: -0.02 },
    bow:    { ty: 30, sc: 0.97 },
    heart:  { ty: -20 },
    cheer:  { ty: -24 },
    think:  { tx: -12, ty: -6 },
    shy:    { ty: 12 },
    point:  { tx: 16 },
    thumb:  { tx: 18 },
    shrug:  { ty: 8 },
    stretch:{ ty: -16, sc: 1.03 },
    tilt:   { rot: 0.07 },
    explain:{ tx: 10 },
    hair:   { ty: -10 }
  };
  QG.CHAR_GESTURES = GEST;

  var EMOTION_LIST = ['neutral', 'happy', 'sad', 'angry', 'surprised', 'calm'];

  QG.Character = function () {
    QG.Entity.call(this);
    this.style = 0;
    this.t = 0;
    this.speaking = false;
    this.mouth = 0;
    this.speakText = '';
    this.speakEnd = 0;
    this.face = 0;
    this.pendingGesture = null;
    this.fsm = new QG.StateMachine(this);
    this._setupStates();
    this.fsm.change('idle');
    this._shot = 0;

    // —— 素材驱动核心 ——
    this._targetEmotion = 'neutral';
    this._displayEmotion = 'neutral';
    this._emotionAlpha = 1;
    this._prevEmotionImg = null;
    this.emotionImages = {};
    this._preloadEmotions();
  };
  QG.Character.prototype = Object.create(QG.Entity.prototype);
  QG.Character.prototype.constructor = QG.Character;

  // 预加载 6 张情绪立绘（每张自带完整房间场景）
  QG.Character.prototype._preloadEmotions = function () {
    for (var i = 0; i < EMOTION_LIST.length; i++) {
      (function (em) {
        var img = new Image();
        img.src = 'emotions/emotion_' + em + '.png';
        this.emotionImages[em] = img;
      }).call(this, EMOTION_LIST[i]);
    }
  };

  QG.Character.prototype._setupStates = function () {
    this.fsm.add('idle', { enter: function () { this._g = null; } });
    this.fsm.add('gesture', {
      update: function (dt) {
        this._g.t += dt;
        if (this._g.t >= this._g.dur) this.fsm.change('idle');
      }
    });
  };

  // 固定步长更新
  QG.Character.prototype.update = function (dt) {
    this.t += dt;
    if (this.speaking && this.speakEnd && performance.now() > this.speakEnd) {
      this.speaking = false; this.speakText = '';
    }
    if (this.pendingGesture && GEST[this.pendingGesture]) {
      this._g = { name: this.pendingGesture, t: 0, dur: 1.2 };
      this.pendingGesture = null;
      this.fsm.change('gesture');
    }
    this.fsm.update(dt);

    // 情绪过渡动画（300ms 淡入淡出）
    if (this._targetEmotion !== this._displayEmotion) {
      this._emotionAlpha -= dt * 3.5;
      if (this._emotionAlpha <= 0) {
        this._displayEmotion = this._targetEmotion;
        this._emotionAlpha = 1;
        this._prevEmotionImg = null;
      }
    } else if (this._emotionAlpha < 1) {
      this._emotionAlpha = Math.min(1, this._emotionAlpha + dt * 5);
    }
  };

  QG.Character.prototype.setStyle = function (i) { this.style = i | 0; };
  QG.Character.prototype.setEmotion = function (k) {
    if (k && this.emotionImages[k]) this._targetEmotion = k;
  };
  QG.Character.prototype.playGesture = function (name) { this.pendingGesture = name; };
  QG.Character.prototype.setSpeaking = function (on, text, durMs) {
    this.speaking = !!on; this.speakText = on ? (text || '') : '';
    this.speakEnd = on && durMs ? performance.now() + durMs : 0;
  };

  // 裁剪区（镜头推近）
  QG.Character.prototype._crop = function (im) {
    if (!im || !im.complete || !im.naturalWidth) return null;
    var iw = im.naturalWidth, ih = im.naturalHeight;
    var sx = 0, sy = 0, sw = iw, sh = ih, shot = this._shot;
    if (shot === 1) { sy = ih * 0.10; sh = ih * 0.62; sx = iw * 0.12; sw = iw * 0.76; }
    else if (shot === 2) { sy = ih * 0.05; sh = ih * 0.34; sx = iw * 0.30; sw = iw * 0.40; }
    return { sx: sx, sy: sy, sw: sw, sh: sh, iw: iw, ih: ih };
  };

  // 渲染（由 Stage 调用）— 直接画不透明立绘（自带完整场景，无 chroma-key）
  QG.Character.prototype.render = function (ctx, env) {
    this._shot = env.shot;
    if (this.speaking && this.speakEnd && performance.now() > this.speakEnd) {
      this.speaking = false; this.speakText = '';
    }

    var emKey = this._displayEmotion || 'neutral';
    var im = this.emotionImages[emKey];
    if (!im || !im.complete) return;
    var c = this._crop(im);
    if (!c) return;

    // FIT 适配 + 底部锚定
    var margin = 0.02;          // 更小留边（图自带场景，尽量填满）
    var availW = env.W * (1 - margin * 2);
    var availH = env.H * (1 - margin * 2);
    var scale = Math.min(availW / c.sw, availH / c.sh);
    var dw = c.sw * scale, dh = c.sh * scale;
    var dx = (env.W - dw) / 2;
    var dy = env.H - dh - env.H * 0.005;

    // 律动（呼吸/待机/手势/说话）
    var breath = 1 + Math.sin(this.t * 1.6) * 0.004;  // 微呼吸
    var sway = Math.sin(this.t * 0.9) * 0.003;
    var tx = Math.sin(this.t * 0.7) * 2;
    var ty = Math.sin(this.t * 1.6) * 1.5;
    var sc = 1;
    if (this.speaking) ty += Math.sin(this.t * 9) * (1.2 + this.mouth * 1.2);
    if (this.face) tx += this.face * 8;
    if (this._g) {
      var e = Math.sin(Math.min(1, this._g.t / this._g.dur) * Math.PI);
      var G = GEST[this._g.name];
      if (G.tx) tx += G.tx * e;
      if (G.ty) ty += G.ty * e;
      if (G.rot) sway += G.rot * e;
      if (G.sc) sc *= 1 + (G.sc - 1) * e;
    }

    ctx.save();
    ctx.translate(env.W / 2, env.H * 0.92);
    ctx.rotate(sway);
    ctx.scale(breath * sc, breath * sc);
    ctx.translate(-env.W / 2 + tx, -env.H * 0.92 + ty);

    // 主情绪立绘（不透明，自带完整场景 → 单层，无双层可能）
    ctx.globalAlpha = this._emotionAlpha;
    ctx.drawImage(im, c.sx, c.sy, c.sw, c.sh, dx, dy, dw, dh);

    // 过渡中的旧图（淡出）
    if (this._prevEmotionImg && this._emotionAlpha < 1) {
      var pc = this._crop(this._prevEmotionImg);
      if (pc) {
        var pScale = Math.min(availW / pc.sw, availH / pc.sh);
        var pdw = pc.sw * pScale, pdh = pc.sh * pScale;
        var pdx = (env.W - pdw) / 2;
        var pdy = env.H - pdh - env.H * 0.005;
        ctx.globalAlpha = 1 - this._emotionAlpha;
        ctx.drawImage(this._prevEmotionImg, pc.sx, pc.sy, pc.sw, pc.sh, pdx, pdy, pdw, pdh);
      }
    }
    ctx.globalAlpha = 1;
    ctx.restore();
  };

  // 说话气泡（由 Stage 在角色之上绘制）
  QG.Character.prototype.drawCaption = function (ctx, env) {
    if (!this.speaking || !this.speakText) return;
    ctx.save();
    ctx.font = Math.max(14, Math.min(20, env.W * 0.045)) + 'px -apple-system,"PingFang SC","Noto Sans CJK SC",sans-serif';
    var padX = 16, padY = 10, maxW = env.W * 0.74, text = this.speakText;
    if (ctx.measureText(text).width > maxW) {
      while (text.length > 1 && ctx.measureText(text + '…').width > maxW) text = text.slice(0, -1);
      text += '…';
    }
    var tw = ctx.measureText(text).width, bw = tw + padX * 2, bh = 40;
    var bx = (env.W - bw) / 2, by = env.H * 0.80;
    ctx.fillStyle = 'rgba(20,18,26,0.78)';
    _round(ctx, bx, by, bw, bh, 12); ctx.fill();
    ctx.lineWidth = 1.5;
    var a = env.accentRgb || [255, 155, 179];
    ctx.strokeStyle = 'rgba(' + a[0] + ',' + a[1] + ',' + a[2] + ',0.9)';
    _round(ctx, bx, by, bw, bh, 12); ctx.stroke();
    ctx.fillStyle = '#fff'; ctx.textBaseline = 'middle';
    ctx.fillText(text, bx + padX, by + bh / 2 + 1);
    ctx.restore();
  };

  function _round(ctx, x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, y, x + w, y, r);
    ctx.closePath();
  }
})();
