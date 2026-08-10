/**
 * avatar3d 无头冒烟测试。
 *
 * 用桩 WebGL 上下文在 Node 里真实跑 boot() + 若干帧，专抓「只有运行时才暴露」的问题：
 *  - ReferenceError / TypeError（跨文件的函数或全局变量拼错）
 *  - POSE / 矩阵里出现 NaN（会导致真机上模型直接消失）
 *  - drawElements 从未被调用（几何或程序创建失败，画面全空）
 *
 * 用法：node tools/avatar3d_smoke.js
 */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const DIR = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'avatar3d');
const FILES = ['engine.js', 'room.js', 'character.js', 'anim.js', 'main.js'];

/* ----------------------------------------------------------- 桩 WebGL */
const stats = { drawElements: 0, drawArrays: 0, programs: 0, buffers: 0, nanUniform: 0, nanWhere: [] };

function makeGL() {
  const shaderSrc = new Map();
  const progShaders = new Map();
  const progUniforms = new Map();
  let idSeq = 1;

  const real = {
    createShader: () => ({ __id: idSeq++ }),
    shaderSource: (sh, src) => shaderSrc.set(sh, src),
    compileShader: () => {},
    getShaderParameter: () => true,
    getShaderInfoLog: () => '',
    deleteShader: () => {},

    createProgram: () => { const p = { __id: idSeq++ }; progShaders.set(p, []); stats.programs++; return p; },
    attachShader: (p, sh) => progShaders.get(p).push(shaderSrc.get(sh) || ''),
    bindAttribLocation: () => {},
    linkProgram: (p) => {
      const names = [];
      for (const src of progShaders.get(p) || []) {
        const re = /uniform\s+\w+\s+(\w+)\s*(\[\s*\d+\s*\])?\s*;/g;
        let m; while ((m = re.exec(src))) names.push(m[1]);
      }
      progUniforms.set(p, [...new Set(names)]);
    },
    getProgramParameter: (p, pname) => (pname === 0x8B86 /* ACTIVE_UNIFORMS */ ? (progUniforms.get(p) || []).length : true),
    getActiveUniform: (p, i) => ({ name: (progUniforms.get(p) || [])[i], size: 1, type: 0 }),
    getUniformLocation: (p, n) => ({ __u: n }),
    getProgramInfoLog: () => '',
    useProgram: () => {},

    createBuffer: () => { stats.buffers++; return { __id: idSeq++ }; },
    bindBuffer: () => {},
    bufferData: (target, data) => {
      if (data && data.length && data.some && data.some(Number.isNaN)) {
        stats.nanUniform++; stats.nanWhere.push('bufferData');
      }
    },
    vertexAttribPointer: () => {},
    enableVertexAttribArray: () => {},
    disableVertexAttribArray: () => {},

    drawElements: () => { stats.drawElements++; },
    drawArrays: () => { stats.drawArrays++; },

    getExtension: (n) => (n === 'OES_element_index_uint' ? {} : null),
    getParameter: () => 4096,
    enable: () => {}, disable: () => {},
    depthMask: () => {}, depthFunc: () => {},
    blendFunc: () => {}, blendFuncSeparate: () => {},
    cullFace: () => {}, frontFace: () => {},
    clear: () => {}, clearColor: () => {}, clearDepth: () => {},
    viewport: () => {}, pixelStorei: () => {},
    lineWidth: () => {}, polygonOffset: () => {},
  };

  // uniform* 全部拦下来查 NaN
  const uniformCheck = (name) => (loc, ...args) => {
    const flat = args.flatMap(a => (a && a.length !== undefined && typeof a !== 'string') ? Array.from(a) : [a]);
    if (flat.some(v => typeof v === 'number' && !Number.isFinite(v))) {
      stats.nanUniform++;
      const key = `${name}(${loc && loc.__u})`;
      if (!stats.nanWhere.includes(key)) stats.nanWhere.push(key);
    }
  };

  return new Proxy(real, {
    get(t, k) {
      if (k in t) return t[k];
      if (typeof k === 'string' && /^uniform/.test(k)) return uniformCheck(k);
      if (typeof k === 'string' && /^[A-Z0-9_]+$/.test(k)) {
        // GL 常量：给一个稳定的伪值（ACTIVE_UNIFORMS 必须精确）
        if (k === 'ACTIVE_UNIFORMS') return 0x8B86;
        let h = 0; for (const c of k) h = (h * 31 + c.charCodeAt(0)) & 0xffff;
        return h + 0x1000;
      }
      return () => {};
    }
  });
}

/* ------------------------------------------------------------ 桩 DOM */
const listeners = {};
function el(id) {
  return {
    id,
    clientWidth: 1080, clientHeight: 2160, width: 1080, height: 2160,
    style: {}, classList: { add() {}, remove() {}, contains: () => false },
    querySelector: () => ({ textContent: '', style: {} }),
    addEventListener: (t, f) => { (listeners[id + ':' + t] ||= []).push(f); },
    getContext: (type) => (type === 'webgl' || type === 'experimental-webgl') ? GL : null,
  };
}

const GL = makeGL();
const nodes = {};
const document = {
  getElementById: (id) => (nodes[id] ||= el(id)),
  addEventListener: () => {},
  body: { style: {} },
  hidden: false,
};

let rafQueue = [];
let clock = 1000;
const sandbox = {
  document,
  window: {
    devicePixelRatio: 2,
    addEventListener: (t, f) => { if (t === 'load') sandbox.__onload = f; },
    innerWidth: 1080, innerHeight: 2160,
  },
  // 与 rAF 时间戳共用同一时钟（真实浏览器里两者同源）
  performance: { now: () => clock },
  requestAnimationFrame: (f) => { rafQueue.push(f); return rafQueue.length; },
  cancelAnimationFrame: () => {},
  setTimeout: (f, ms) => setTimeout(f, ms),
  clearTimeout: (h) => clearTimeout(h),
  Date, Math, JSON, console,
  Float32Array, Uint16Array, Uint32Array, Int32Array, Uint8Array, Array, Object, Number, String, Boolean,
  isNaN, parseInt, parseFloat, Error, TypeError, RangeError,
};
sandbox.self = sandbox;
sandbox.globalThis = sandbox;
vm.createContext(sandbox);

/* ------------------------------------------------------------ 执行 */
let failed = false;
for (const f of FILES) {
  try {
    vm.runInContext(fs.readFileSync(path.join(DIR, f), 'utf8'), sandbox, { filename: f });
  } catch (e) {
    console.error(`✗ 加载 ${f} 失败: ${e.message}\n${e.stack.split('\n').slice(0, 4).join('\n')}`);
    failed = true;
  }
}
if (failed) process.exit(1);

// boot()
try {
  sandbox.__onload();
} catch (e) {
  console.error(`✗ boot() 抛错: ${e.message}\n${e.stack.split('\n').slice(0, 6).join('\n')}`);
  process.exit(1);
}

if (!sandbox.window.__avatar || !sandbox.window.__avatar.ready) {
  console.error('✗ boot 后 window.__avatar.ready 仍为 false（WebGL 初始化失败）');
  process.exit(1);
}

// 跑 120 帧，中途注入各种外部调用，模拟真实聊天驱动
const API = sandbox.window.__avatar;
let frameNo = 0;

/** 每帧扫一次，第一时间定位是哪一帧、哪个字段先变成 NaN。 */
function firstNaN() {
  for (const [name, obj] of [['POSE', vm.runInContext('POSE', sandbox)],
                             ['STATE', vm.runInContext('STATE', sandbox)],
                             ['ENV', vm.runInContext('ENV', sandbox)]]) {
    for (const k of Object.keys(obj)) {
      const v = obj[k];
      if (typeof v === 'number' && !Number.isFinite(v)) return `${name}.${k}`;
      if (Array.isArray(v) && v.some(x => typeof x === 'number' && !Number.isFinite(x))) return `${name}.${k}[]`;
    }
  }
  return null;
}

function pump(n) {
  for (let i = 0; i < n; i++) {
    const q = rafQueue; rafQueue = [];
    clock += 16.7;
    frameNo++;
    for (const f of q) {
      try { f(clock); } catch (e) {
        console.error(`✗ 第 ${frameNo} 帧渲染抛错: ${e.message}\n${e.stack.split('\n').slice(0, 6).join('\n')}`);
        process.exit(1);
      }
    }
    const bad = firstNaN();
    if (bad) {
      console.error(`✗ 第 ${frameNo} 帧首次出现 NaN，字段: ${bad}`);
      process.exit(1);
    }
  }
}

pump(20);
API.setEmotion('happy');
API.say('你好呀！今天过得怎么样？我很喜欢你哦', 3000);
for (let i = 0; i < 40; i++) { API.setMouth(Math.abs(Math.sin(i * 0.5))); pump(1); }
API.endSpeech();
API.setStyle(1); API.setAccent('#E86A8C'); API.setScene(2); API.setShot(2);
pump(20);
API.setEmotion('sad'); API.gesture('shrug'); API.face(-1);
pump(20);
API.setStyle(2); API.setScene(3); API.setShot(0); API.setEmotion('surprised');
pump(20);

/* ------------------------------------------------------------ 断言 */
// POSE / STATE 是脚本级 const，落在 context 的全局词法作用域里，
// 不会挂到 sandbox 对象上，必须回到 context 里取。
const POSE = vm.runInContext('POSE', sandbox);
const STATE = vm.runInContext('STATE', sandbox);
const nanKeys = [];
(function scan(o, pre) {
  for (const k of Object.keys(o)) {
    const v = o[k];
    if (typeof v === 'number' && !Number.isFinite(v)) nanKeys.push(pre + k);
    else if (v && typeof v === 'object' && !Array.isArray(v)) scan(v, pre + k + '.');
  }
})(POSE, 'POSE.');
(function scanS(o, pre) {
  for (const k of Object.keys(o)) {
    const v = o[k];
    if (typeof v === 'number' && !Number.isFinite(v)) nanKeys.push(pre + k);
  }
})(STATE, 'STATE.');

console.log(`帧渲染: drawElements=${stats.drawElements}, drawArrays=${stats.drawArrays}, programs=${stats.programs}, buffers=${stats.buffers}`);
console.log(`状态: ${API.getState()}`);

let bad = false;
if (stats.drawElements < 100) { console.error(`✗ drawElements 只有 ${stats.drawElements}，几何几乎没画出来`); bad = true; }
if (nanKeys.length) { console.error(`✗ POSE 出现 NaN: ${nanKeys.join(', ')}`); bad = true; }
if (stats.nanUniform) { console.error(`✗ 有 ${stats.nanUniform} 次 NaN uniform/buffer: ${stats.nanWhere.slice(0, 8).join(', ')}`); bad = true; }

if (bad) process.exit(1);
console.log('✓ avatar3d 冒烟测试通过');
