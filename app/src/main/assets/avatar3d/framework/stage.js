/*
 * framework/stage.js — 舞台场景（栖）
 * ----------------------------------------------------------------------------
 * QG.Entity 子类，作为游戏的主场景：
 *   - 背景渐变（随主题色 accent 变化）+ 顶部窗光
 *   - 尘埃粒子系统（内部小实体数组，update 上浮）
 *   - 持有并编排 Character 的渲染（镜头 shot / 主题色）
 *   - 角色之上的说话气泡、情绪辉光
 * 通过 setAccent / setShot / setScene 响应外部指令。
 */
(function () {
  'use strict';
  var QG = window.QG; if (!QG) return;

  function hexToRgb(hex) {
    var m = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex || '');
    if (!m) return [255, 155, 179];
    return [parseInt(m[1], 16), parseInt(m[2], 16), parseInt(m[3], 16)];
  }

  QG.Stage = function (opts) {
    QG.Entity.call(this);
    this.character = opts.character;
    this.accent = opts.accent || '#ff9bb3';
    this.accentRgb = hexToRgb(this.accent);
    this.shot = 0;
    this.scene = 0;
    this.W = 0; this.H = 0;
    this.dust = [];
    this._initDust();
  };
  QG.Stage.prototype = Object.create(QG.Entity.prototype);
  QG.Stage.prototype.constructor = QG.Stage;

  QG.Stage.prototype._initDust = function () {
    this.dust = [];
    for (var i = 0; i < 64; i++) {
      this.dust.push({
        x: Math.random(), y: Math.random(),
        r: Math.random() * 1.8 + 0.4,
        sp: Math.random() * 0.02 + 0.004,
        ph: Math.random() * 6.283
      });
    }
  };

  QG.Stage.prototype.setAccent = function (hex) {
    if (!hex) return;
    this.accent = hex; this.accentRgb = hexToRgb(hex);
  };
  QG.Stage.prototype.setShot = function (i) { this.shot = i || 0; };
  QG.Stage.prototype.setScene = function (m) { this.scene = m || 0; };

  QG.Stage.prototype.update = function (dt) {
    QG.Entity.prototype.update.call(this, dt); // 更新子实体（角色 FSM 在 render 内推进）
    for (var i = 0; i < this.dust.length; i++) {
      var d = this.dust[i];
      d.y -= d.sp * dt;
      if (d.y < -0.02) { d.y = 1.02; d.x = Math.random(); }
    }
  };

  QG.Stage.prototype.draw = function (ctx, W, H, dt) {
    this.W = W; this.H = H;
    var env = { W: W, H: H, shot: this.shot, accent: this.accent, accentRgb: this.accentRgb };

    // 纯色兜底（立绘自带完整房间场景，此处仅防 canvas 留边透明）
    // 色值取自立绘房间主色调（暖米色），与立绘边缘无缝融合
    ctx.fillStyle = '#f5e6d3';
    ctx.fillRect(0, 0, W, H);

    // 角色（含脚底柔影/情绪染色/辉光）
    if (this.character) {
      this.character.render(ctx, env);
      this.character.drawCaption(ctx, env);
    }

    // 尘埃粒子
    ctx.save();
    for (var i = 0; i < this.dust.length; i++) {
      var d = this.dust[i];
      var x = (d.x + Math.sin((this.character ? this.character.t : 0) * 0.3 + d.ph) * 0.01) * W;
      var y = d.y * H;
      var a = 0.10 + 0.08 * Math.sin((this.character ? this.character.t : 0) * 0.8 + d.ph);
      ctx.fillStyle = 'rgba(255,240,220,' + a.toFixed(3) + ')';
      ctx.beginPath(); ctx.arc(x, y, d.r, 0, 6.283); ctx.fill();
    }
    ctx.restore();
  };
})();
