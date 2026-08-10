/**
 * avatar3d 绘制调用分布分析。
 * 复用冒烟测试的桩环境，统计每帧 drawRoom / drawCharacter / drawVolumetrics 各占多少 draw call。
 * 移动端 WebGL 的瓶颈几乎总是 draw call 数而非三角形数，所以这个数字比顶点数更有参考价值。
 *
 * 用法：node tools/avatar3d_profile.js
 */
'use strict';
process.env.AVATAR3D_PROFILE = '1';

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

// 直接复用 smoke 的环境搭建：把 smoke.js 读进来，截取到「执行」之前的部分太脆弱，
// 这里改为独立最小实现（只关心计数）。
const DIR = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'avatar3d');
const FILES = ['engine.js', 'room.js', 'character.js', 'anim.js', 'main.js'];

let draws = 0;
const GLbase = {
  createShader: () => ({}), shaderSource: (s, src) => { srcMap.set(s, src); }, compileShader: () => {},
  getShaderParameter: () => true, getShaderInfoLog: () => '', deleteShader: () => {},
  createProgram: () => { const p = {}; pu.set(p, []); ps.set(p, []); return p; },
  attachShader: (p, s) => ps.get(p).push(srcMap.get(s) || ''),
  bindAttribLocation: () => {},
  linkProgram: (p) => {
    const n = []; for (const src of ps.get(p)) { const re = /uniform\s+\w+\s+(\w+)\s*(\[\s*\d+\s*\])?\s*;/g; let m; while ((m = re.exec(src))) n.push(m[1]); }
    pu.set(p, [...new Set(n)]);
  },
  getProgramParameter: (p, n) => (n === 0x8B86 ? pu.get(p).length : true),
  getActiveUniform: (p, i) => ({ name: pu.get(p)[i] }),
  getUniformLocation: (p, n) => ({ __u: n }), getProgramInfoLog: () => '', useProgram: () => {},
  createBuffer: () => ({}), bindBuffer: () => {}, bufferData: () => {},
  vertexAttribPointer: () => {}, enableVertexAttribArray: () => {}, disableVertexAttribArray: () => {},
  drawElements: () => { draws++; }, drawArrays: () => { draws++; },
  getExtension: (n) => (n === 'OES_element_index_uint' ? {} : null), getParameter: () => 4096,
};
const srcMap = new Map(), pu = new Map(), ps = new Map();
const GL = new Proxy(GLbase, {
  get(t, k) {
    if (k in t) return t[k];
    if (typeof k === 'string' && /^[A-Z0-9_]+$/.test(k)) return k === 'ACTIVE_UNIFORMS' ? 0x8B86 : 1;
    return () => {};
  }
});

const nodes = {};
const mkEl = (id) => ({
  id, clientWidth: 1080, clientHeight: 2160, width: 1080, height: 2160,
  style: {}, classList: { add() {}, remove() {} }, querySelector: () => ({ textContent: '', style: {} }),
  addEventListener: () => {}, getContext: (t) => (t === 'webgl' ? GL : null),
});

let clock = 1000, raf = [];
const sandbox = {
  document: { getElementById: (id) => (nodes[id] ||= mkEl(id)), addEventListener: () => {}, body: { style: {} }, hidden: false },
  window: { devicePixelRatio: 2, addEventListener: (t, f) => { if (t === 'load') sandbox.__onload = f; } },
  performance: { now: () => clock },
  requestAnimationFrame: (f) => raf.push(f),
  setTimeout, clearTimeout, Date, Math, JSON, console,
  Float32Array, Uint16Array, Uint32Array, Array, Object, Number, String, isNaN, parseInt, parseFloat, Error,
};
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
for (const f of FILES) vm.runInContext(fs.readFileSync(path.join(DIR, f), 'utf8'), sandbox, { filename: f });
sandbox.__onload();

// 顶层 function 声明是 var 作用域，会挂到 globalThis 上，可以直接包一层计数
const phases = {};
for (const name of ['drawRoom', 'drawCharacter', 'drawVolumetrics']) {
  const orig = sandbox[name];
  if (typeof orig !== 'function') { console.log(`(跳过 ${name}: 不可包装)`); continue; }
  phases[name] = 0;
  sandbox[name] = function (...a) {
    const before = draws;
    const r = orig.apply(this, a);
    phases[name] += draws - before;
    return r;
  };
}

const FRAMES = 60;
draws = 0;
for (let i = 0; i < FRAMES; i++) {
  const q = raf; raf = []; clock += 16.7;
  for (const f of q) f(clock);
}

console.log(`平均每帧 draw call: ${(draws / FRAMES).toFixed(1)}`);
for (const k of Object.keys(phases)) {
  console.log(`  ${k.padEnd(16)} ${(phases[k] / FRAMES).toFixed(1)} /帧`);
}
const other = draws / FRAMES - Object.values(phases).reduce((a, b) => a + b, 0) / FRAMES;
console.log(`  ${'(其它)'.padEnd(16)} ${other.toFixed(1)} /帧`);
