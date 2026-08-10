/*
 * framework/core.js — 栖 游戏框架内核
 * ----------------------------------------------------------------------------
 * 提供与渲染无关的基础架构：
 *   - QG.EventBus      事件总线（解耦 Kotlin<->JS 通信与内部模块）
 *   - QG.Entity        实体/场景图节点（transform + 子树 update/draw）
 *   - QG.StateMachine 通用有限状态机（enter/update/exit）
 *   - QG.Game          固定步长游戏主循环（update 与 render 解耦）
 *
 * 全部挂在全局 window.QG 下，以经典 <script> 顺序加载（无 ES module，兼容 file://）。
 */
(function () {
  'use strict';
  var QG = (window.QG = window.QG || {});

  // ---- 数学工具 ----
  QG.clamp = function (v, a, b) { return v < a ? a : (v > b ? b : v); };
  QG.lerp = function (a, b, t) { return a + (b - a) * t; };
  QG.easeInOut = function (t) { return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2; };

  // ---- 事件总线 ----
  QG.EventBus = function () { this._h = {}; };
  QG.EventBus.prototype.on = function (ev, fn) {
    (this._h[ev] = this._h[ev] || []).push(fn);
  };
  QG.EventBus.prototype.off = function (ev, fn) {
    var a = this._h[ev]; if (!a) return;
    var i = a.indexOf(fn); if (i >= 0) a.splice(i, 1);
  };
  QG.EventBus.prototype.emit = function (ev, data) {
    var a = this._h[ev]; if (!a) return;
    for (var i = 0; i < a.length; i++) a[i](data);
  };

  // ---- 实体 / 场景图节点 ----
  QG.Entity = function () {
    this.children = [];
    this.x = 0; this.y = 0;
    this.scaleX = 1; this.scaleY = 1;
    this.rotation = 0;
    this.visible = true;
    this.parent = null;
  };
  QG.Entity.prototype.add = function (c) {
    c.parent = this; this.children.push(c); return c;
  };
  QG.Entity.prototype.update = function (dt) {
    for (var i = 0; i < this.children.length; i++) this.children[i].update(dt);
  };
  QG.Entity.prototype.draw = function (ctx) {
    if (!this.visible) return;
    for (var i = 0; i < this.children.length; i++) this.children[i].draw(ctx);
  };

  // ---- 有限状态机 ----
  QG.StateMachine = function (owner) {
    this.owner = owner; this.states = {}; this.current = null; this.currentName = null;
  };
  QG.StateMachine.prototype.add = function (name, def) { this.states[name] = def; };
  QG.StateMachine.prototype.change = function (name, data) {
    if (!this.states[name]) return;
    if (this.current && this.current.exit) this.current.exit.call(this.owner);
    this.currentName = name;
    this.current = this.states[name];
    if (this.current.enter) this.current.enter.call(this.owner, data);
  };
  QG.StateMachine.prototype.update = function (dt) {
    if (this.current && this.current.update) this.current.update.call(this.owner, dt);
  };

  // ---- 游戏主循环（固定步长 update + 渲染解耦）----
  QG.Game = function (opts) {
    this.canvas = opts.canvas;
    this.ctx = this.canvas.getContext('2d');
    this.scene = null;
    this.bus = new QG.EventBus();
    this.fixed = 1 / 60;
    this._acc = 0; this._last = 0; this.running = false; this._raf = null;
    this.W = 0; this.H = 0;
    this.dpr = Math.min(window.devicePixelRatio || 1, 2.5);
  };
  QG.Game.prototype.setScene = function (s) { this.scene = s; };
  QG.Game.prototype.resize = function () {
    var r = this.canvas.getBoundingClientRect();
    this.W = Math.max(1, Math.floor(r.width));
    this.H = Math.max(1, Math.floor(r.height));
    this.canvas.width = Math.floor(this.W * this.dpr);
    this.canvas.height = Math.floor(this.H * this.dpr);
    this.ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
  };
  QG.Game.prototype.start = function () {
    if (this.running) return;
    this.running = true; this.resize();
    var self = this;
    this._raf = requestAnimationFrame(function tick(ts) { self._frame(ts); });
  };
  QG.Game.prototype._frame = function (ts) {
    if (!this.running) return;
    if (!this._last) this._last = ts;
    var dt = (ts - this._last) / 1000; this._last = ts;
    if (dt > 0.1) dt = 0.1;
    this._acc += dt;
    while (this._acc >= this.fixed) {
      if (this.scene) this.scene.update(this.fixed);
      this._acc -= this.fixed;
    }
    if (this.scene) this.scene.draw(this.ctx, this.W, this.H, dt);
    var self = this;
    this._raf = requestAnimationFrame(function (ts) { self._frame(ts); });
  };
  QG.Game.prototype.stop = function () {
    this.running = false;
    if (this._raf) cancelAnimationFrame(this._raf);
  };
})();
