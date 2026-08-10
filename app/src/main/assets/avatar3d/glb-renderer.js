/*
 * glb-renderer.js v2 — 精简 WebGL GLB 渲染器（离线 Android WebView）
 * ──────────────────────────────────────────────────────────────
 *
 * v2 修复清单（v1 黑屏根因）：
 *   ① Canvas 尺寸防御：WebView 布局未完成时 clientW/H=0 → 延迟重试 + 最小尺寸保底
 *   ② 相机目标校正：rotateX(-90°) 后模型中心在 Y≈+0.6，lookAt 目标需同步修正
 *   ③ 背面剔除容错：Blender 导出 winding 可能与 WebGL 默认相反 → 先尝试双面渲染
 *   ④ Alpha 阈值放宽：PBR 贴图 alpha 边缘可能 < 0.5 → 降低到 0.1 防止误 discard
 *   ⑤ 错误可视化：初始化异常时在屏幕上显示错误信息（无需 adb）
 *   ⑥ 非 POT 贴图安全处理：Blender PBR 贴图常非 2 的幂 → CLAMP_TO_EDGE + LINEAR
 *
 * 驱动接口（window.__avatar）：
 *   setMouth(v)   → 口型幅度 0..1
 *   setEmotion(k) → 情绪切换
 *   say/endSpeech → 说话状态
 */
(function () {
  'use strict';

  // 全局错误捕获 → 经 console.error 透出（AvatarActivity 会写入 Download 日志）
  window.onerror = function (msg, src, line, col, err) {
    try {
      console.error('[glb][onerror] ' + msg + ' @' + line + ':' + col +
        (err && err.stack ? ' | ' + err.stack : ''));
    } catch (e) {}
    return false;
  };

  // ── 错误可视化 ──
  var loadingEl = document.getElementById('loading');
  function showError(msg) {
    console.error('[glb]', msg);
    if (loadingEl) {
      loadingEl.textContent = msg;
      loadingEl.style.opacity = '1';
      loadingEl.style.color = '#c44';
    }
  }
  function showStatus(msg) {
    if (loadingEl) loadingEl.textContent = msg;
  }

  var canvas = document.getElementById('stage');
  if (!canvas) { showError('错误：找不到 #stage 画布'); return; }

  // 强制 canvas 可见且有背景色（防止透明后透出 Activity 黑底）
  canvas.style.display = 'block';
  canvas.style.background = '#f5e6d3';

  var gl = canvas.getContext('webgl', {
    alpha: false, antialias: true, preserveDrawingBuffer: false,
    premultipliedAlpha: false, stencil: false
  }) || canvas.getContext('experimental-webgl', {
    alpha: false, antialias: true, preserveDrawingBuffer: false
  });
  if (!gl) { showError('错误：此设备不支持 WebGL'); return; }

  showStatus('正在解析 3D 模型...');

  // ════════════════════════════════════════════
  // ① GLB 解析器
  // ════════════════════════════════════════════
  function parseGLB(ab) {
    var dv = new DataView(ab);
    var magic = String.fromCharCode(dv.getUint8(0), dv.getUint8(1),
                                   dv.getUint8(2), dv.getUint8(3));
    if (magic !== 'glTF') throw new Error('不是有效的 GLB 文件 (magic=' + magic + ')');
    var version = dv.getUint32(4, true);
    var length = dv.getUint32(8, true);

    var jsonChunk = null, binChunk = null;
    var off = 12;
    while (off < length) {
      var clen = dv.getUint32(off, true);
      var ctype = String.fromCharCode(dv.getUint8(off+4), dv.getUint8(off+5),
                                       dv.getUint8(off+6), dv.getUint8(off+7));
      var cdata = new Uint8Array(ab, off + 8, clen);
      if (ctype === 'JSON') jsonChunk = JSON.parse(new TextDecoder().decode(cdata));
      else if (ctype === 'BIN\x00') binChunk = cdata;
      off += 8 + clen;
      if (clen % 4 !== 0) off += 4 - (clen % 4);
    }
    return { gltf: jsonChunk, bin: binChunk };
  }

  function getAccessorData(gltf, bin, ai) {
    var acc = gltf.accessors[ai];
    var bv = gltf.bufferViews[acc.bufferView];
    var byteOffset = (bv.byteOffset || 0) + (acc.byteOffset || 0);
    var compType = acc.componentType;
    var type = acc.type;
    var count = acc.count;

    var compMap = { 5120:1, 5121:1, 5122:2, 5123:2, 5125:4, 5126:4 };
    var typeMap = { SCALAR:1, VEC2:2, VEC3:3, VEC4:4, MAT4:16 };
    var nComp = typeMap[type] || 1;
    var stride = bv.byteStride || (compMap[compType] || 4) * nComp;

    var data;
    if (compType === 5126) {
      data = new Float32Array(bin.buffer, bin.byteOffset + byteOffset, count * nComp);
      if (stride && stride !== nComp * 4) {
        var tmp = new Float32Array(count * nComp);
        for (var i = 0; i < count; i++)
          for (var j = 0; j < nComp; j++) tmp[i*nComp+j] = data[i*(stride/4)+j];
        data = tmp;
      }
    } else if (compType === 5123) {
      data = new Uint16Array(bin.buffer, bin.byteOffset + byteOffset, count * nComp);
      if (stride && stride !== nComp * 2) {
        var tmp = new Uint16Array(count * nComp);
        for (var i = 0; i < count; i++)
          for (var j = 0; j < nComp; j++) tmp[i*nComp+j] = data[i*(stride/2)+j];
        data = tmp;
      }
    } else if (compType === 5125) {
      data = new Uint32Array(bin.buffer, bin.byteOffset + byteOffset, count * nComp);
    } else {
      throw new Error('不支持的 componentType: ' + compType);
    }
    return { data: data, count: count, type: type };
  }

  // ════════════════════════════════════════════
  // ② Shader
  // ════════════════════════════════════════════
  var VS_SRC = [
    'precision highp float;',
    'attribute vec3 aPosition;',
    'attribute vec3 aNormal;',
    'attribute vec2 aTexCoord;',
    '',
    'uniform mat4 uMVP;',
    'uniform mat4 uModel;',
    'uniform mat3 uNormalMat;',
    '',
    'uniform float uMouthAmp;',
    'uniform float uEyeClose;',
    'uniform float uBrowAng;',
    '',
    'uniform vec3 uMouthCenter;',
    'uniform float uMouthRadius;',
    'uniform vec3 uEyeLCenter;',
    'uniform vec3 uEyeRCenter;',
    'uniform float uEyeRadius;',
    'uniform vec3 uBrowLCenter;',
    'uniform vec3 uBrowRCenter;',
    'uniform float uBrowRadius;',
    '',
    'varying vec3 vNormal;',
    'varying vec2 vTexCoord;',
    'varying vec3 vWorldPos;',
    '',
    'void main() {',
    '  vec3 pos = aPosition;',
    '',
    '  float md = distance(pos, uMouthCenter);',
    '  if (md < uMouthRadius) {',
    '    float w = 1.0 - smoothstep(0.0, uMouthRadius, md);',
    '    pos.y += uMouthAmp * w * 0.025;',
    '  }',
    '',
    '  float eld = min(distance(pos, uEyeLCenter), distance(pos, uEyeRCenter));',
    '  if (eld < uEyeRadius) {',
    '    float ew = 1.0 - smoothstep(0.0, uEyeRadius, eld);',
    '    pos.z -= uEyeClose * ew * 0.008;',
    '  }',
    '',
    '  float bld = min(distance(pos, uBrowLCenter), distance(pos, uBrowRCenter));',
    '  if (bld < uBrowRadius) {',
    '    float bw = 1.0 - smoothstep(0.0, uBrowRadius, bld);',
    '    pos.y += uBrowAng * bw * 0.012;',
    '    pos.x += sign(pos.x) * (-uBrowAng) * bw * 0.004;',
    '  }',
    '',
    '  vNormal = normalize(uNormalMat * aNormal);',
    '  vTexCoord = aTexCoord;',
    '  vWorldPos = (uModel * vec4(pos, 1.0)).xyz;',
    '  gl_Position = uMVP * vec4(pos, 1.0);',
    '}'
  ].join('\n');

  var FS_SRC = [
    'precision highp float;',
    '',
    'varying vec3 vNormal;',
    'varying vec2 vTexCoord;',
    'varying vec3 vWorldPos;',
    '',
    'uniform sampler2D uBaseColor;',
    'uniform sampler2D uNormalMap;',
    'uniform sampler2D uMRMap;',
    'uniform vec3 uLightDir;',
    'uniform vec3 uLightColor;',
    'uniform vec3 uAmbient;',
    'uniform float uAlpha;',
    'uniform float uUseColor;',     // 0=纹理模式, 1=纯色诊断模式
    'uniform vec3 uDiagColor;',
    '',
    'void main() {',
    '  vec4 base;',
    '  if (uUseColor > 0.5) {',
    '    base = vec4(uDiagColor, 1.0);',       // 诊断纯色（无纹理依赖）
    '  } else {',
    '    base = texture2D(uBaseColor, vTexCoord);',
    '    if (base.a < 0.1) discard;',            // v1 是 0.5，放宽防误杀
    '  }',
    '',
    '  vec3 N = normalize(vNormal);',
    '  vec3 L = normalize(uLightDir);',
    '  vec3 V = normalize(-vWorldPos);',
    '  vec3 H = normalize(L + V);',
    '',
    '  float diff = max(dot(N, L), 0.0);',
    '  float spec = pow(max(dot(N, H), 0.0), 32.0);',
    '',
    '  vec4 mr = texture2D(uMRMap, vTexCoord);',
    '  float roughness = mr.b;',
    '  float metallic = mr.g;',
    '  spec *= (1.0 - roughness) * (1.0 - metallic);',
    '',
    '  vec3 color = base.rgb * (uAmbient + diff * uLightColor) + spec * uLightColor * 0.4;',
    '  gl_FragColor = vec4(color, base.a * uAlpha);',
    '}'
  ].join('\n');

  function compileShader(src, type) {
    var sh = gl.createShader(type);
    gl.shaderSource(sh, src);
    gl.compileShader(sh);
    if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
      var info = gl.getShaderInfoLog(sh);
      showError('Shader 编译失败: ' + info);
      gl.deleteShader(sh); return null;
    }
    return sh;
  }

  function createProgram(vs, fs) {
    var p = gl.createProgram();
    gl.attachShader(p, vs);
    gl.attachShader(p, fs);
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) {
      var info = gl.getProgramInfoLog(p);
      showError('Shader 链接失败: ' + info);
      return null;
    }
    return p;
  }

  // ════════════════════════════════════════════
  // ③ 纹理加载（非 POT 安全）
  // ════════════════════════════════════════════
  function loadTexture(gltf, bin, texIndex) {
    var img = gltf.images[texIndex];
    var bv = gltf.bufferViews[img.bufferView];
    var blob = new Blob([bin.slice(bv.byteOffset, bv.byteOffset + bv.byteLength)],
                         { type: img.mimeType || 'image/png' });
    var url = URL.createObjectURL(blob);

    var tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE,
                   new Uint8Array([255,255,255,255]));

    var image = new Image();
    image.onload = function () {
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, image);
      var w = image.width, h = image.height;
      var isPOT = (w > 0 && (w & (w - 1)) === 0) && (h > 0 && (h & (h - 1)) === 0);
      if (isPOT) {
        gl.generateMipmap(gl.TEXTURE_2D);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.REPEAT);
      } else {
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      }
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      URL.revokeObjectURL(url);
      console.log('[glb] texture ' + texIndex + ' loaded: ' + w + 'x' + h + ' POT=' + isPOT);
      tex._loaded = true;
      checkReady();
    };
    image.onerror = function () {
      console.warn('[glb] texture ' + texIndex + ' load FAILED');
      tex._loaded = true; checkReady();
    };
    image.src = url;
    return tex;
  }

  // ════════════════════════════════════════════
  // ④ 相机 & 变换矩阵
  // ════════════════════════════════════════════
  var camDist = 2.2;
  var camYaw = 0;
  var camPitch = 0.2;          // v1=0.15，略增俯视角度让全身入镜
  var modelRotX = -Math.PI / 2; // Z-up → Y-up

  function mat4Perspective(fov, aspect, near, far) {
    var f = 1.0 / Math.tan(fov / 2);
    var nf = 1 / (near - far);
    return new Float32Array([
      f/aspect, 0, 0, 0,
      0, f, 0, 0,
      0, 0, (far+near)*nf, -1,
      0, 0, 2*far*near*nf, 0
    ]);
  }

  function mat4LookAt(eye, target, up) {
    var zx = eye[0]-target[0], zy = eye[1]-target[1], zz = eye[2]-target[2];
    var zl = Math.sqrt(zx*zx+zy*zy+zz*zz); zx/=zl; zy/=zl; zz/=zl;
    var xx = up[1]*zz-up[2]*zy, xy = up[2]*zx-up[0]*zz, xz = up[0]*zy-up[1]*zx;
    var xl = Math.sqrt(xx*xx+xy*xy+xz*xz); xx/=xl; xy/=xl; xz/=xl;
    var yx = zy*xz-zz*xy, yy = zz*xx-zx*xz, yz = zx*xy-zy*xx;
    return new Float32Array([
      xx,yx,zx,0, xy,yy,zy,0, xz,yz,zz,0,
      -(xx*eye[0]+yx*eye[1]+zx*eye[2]),
      -(xy*eye[0]+yy*eye[1]+zy*eye[2]),
      -(xz*eye[0]+yz*eye[1]+zz*eye[2]), 1
    ]);
  }

  function mat4Multiply(a, b) {
    var r = new Float32Array(16);
    for (var i = 0; i < 4; i++)
      for (var j = 0; j < 4; j++)
        r[j*4+i] = a[i]*b[j*4] + a[i+4]*b[j*4+1] + a[i+8]*b[j*4+2] + a[i+12]*b[j*4+3];
    return r;
  }

  function mat4RotateX(rad) {
    var c = Math.cos(rad), s = Math.sin(rad);
    return new Float32Array([1,0,0,0, 0,c,s,0, 0,-s,c,0, 0,0,0,1]);
  }
  function mat4RotateY(rad) {
    var c = Math.cos(rad), s = Math.sin(rad);
    return new Float32Array([c,0,-s,0, 0,1,0,0, s,0,c,0, 0,0,0,1]);
  }
  function mat4Identity() {
    return new Float32Array([1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]);
  }
  function mat3FromMat4(m) {
    return [m[0],m[1],m[2], m[4],m[5],m[6], m[8],m[9],m[10]];
  }

  // ════════════════════════════════════════════
  // ⑤ 主流程
  // ════════════════════════════════════════════
  var STATE = {
    ready: false,
    speaking: false,
    mouthAmp: 0,
    targetMouth: 0,
    emotion: 'neutral',
    eyeClose: 0,
    browAng: 0,
    autoRotate: true,
    lastFrame: 0,
    diagMode: false,          // v2: 诊断模式（纯色无纹理）
    frameCount: 0
  };

  var VG = {
    mouthCenter: [0, 0, 0],
    mouthRadius: 0.05,
    eyeL: [0, 0, 0], eyeR: [0, 0, 0],
    eyeRadius: 0.04,
    browL: [0, 0, 0], browR: [0, 0, 0],
    browRadius: 0.04
  };

  // 模型世界中心（相机对正用）。rotateX(-90°) 下 Blender(x,y,z) → 世界(x, z, -y)，
  // 故 POSITION 包围盒中心经此变换后得到；默认 [0,0,0] 兜底。
  var MODEL_CENTER = [0, 0, 0];

  var program = null, textures = {};
  var indexCount = 0;
  var vertBuf, normBuf, uvBuf, idxBuf;
  var totalTextures = 0, loadedTextures = 0;

  function hideLoading() {
    if (loadingEl) loadingEl.classList.add('gone');
  }

  function checkReady() {
    loadedTextures++;
    console.log('[glb] texture ready: ' + loadedTextures + '/' + totalTextures);
    if (loadedTextures >= totalTextures && !STATE.ready) {
      STATE.ready = true;
      if (window.__avatar) window.__avatar.ready = true;
      hideLoading();
    }
  }

  // ═══ Canvas 尺寸防御（v2 关键修复）═══
  var resizeRetryCount = 0;
  function resize() {
    var dpr = Math.min(window.devicePixelRatio || 1, 2);
    var w = canvas.clientWidth;
    var h = canvas.clientHeight;
    // 防御：WebView 布局未完成时 clientW/H 可能为 0
    if (!w || !h || w < 10 || h < 10) {
      if (resizeRetryCount < 20) {
        resizeRetryCount++;
        setTimeout(resize, 100);  // 延迟重试
      }
      return;
    }
    resizeRetryCount = 0;
    canvas.width = w * dpr;
    canvas.height = h * dpr;
    gl.viewport(0, 0, canvas.width, canvas.height);
    console.log('[glb] viewport: ' + canvas.width + 'x' + canvas.height);
  }

  // v3: 彻底弃用 XHR。Android WebView 在 file:// 下默认禁止 XHR/fetch
  // （AvatarActivity.kt 未设 allowFileAccessFromFileURLs），故通过 <script>
  // 内嵌的 base64 字符串解码，避免任何网络请求。
  function init() {
    // 顶点组：直接读内嵌 JS 变量
    try {
      if (window.__VERTEX_GROUPS__) {
        VG = window.__VERTEX_GROUPS__;
        console.log('[glb] VG loaded:', VG);
      }
    } catch (e) {}

    showStatus('解析模型数据...');
    var b64 = window.__MODEL_GLB_B64__;
    if (!b64) {
      showError('未找到内嵌模型数据（model_glb.js 未加载）');
      return;
    }

    showStatus('解码模型 (' + Math.round(b64.length / 1048576) + 'MB)...');

    // base64 → 二进制（同步一次性）
    var binStr;
    try {
      binStr = atob(b64);
    } catch (e) {
      showError('base64 解码失败: ' + e.message);
      return;
    }
    var len = binStr.length;
    var bytes = new Uint8Array(len);
    for (var i = 0; i < len; i++) bytes[i] = binStr.charCodeAt(i);
    var ab = bytes.buffer;

    try {
      var parsed = parseGLB(ab);
      console.log('[glb] parsed: meshes=' + parsed.gltf.meshes.length +
                  ' images=' + parsed.gltf.images.length + ' accessors=' + parsed.gltf.accessors.length);
      setupMesh(parsed.gltf, parsed.bin);
    } catch (e) {
      showError('模型解析错误: ' + e.message);
    }
  }

  function setupMesh(gltf, bin) {
    if (!gltf.meshes || !gltf.meshes[0]) {
      showError('模型中没有 mesh 数据');
      return;
    }
    var mesh = gltf.meshes[0];
    var prim = mesh.primitives[0];

    showStatus('构建几何体...');

    // 属性数据
    var posAcc = getAccessorData(gltf, bin, prim.attributes.POSITION);
    var norAcc = getAccessorData(gltf, bin, prim.attributes.NORMAL);

    // 按真实世界包围盒中心对正相机。模型矩阵为 mat4RotateX(-π/2)，
    // 将本地坐标 (x,y,z) 映射为世界 (x,-z,y)。直接遍历 POSITION 顶点求真实
    // 世界包围盒（不依赖 accessor min/max 的变换符号，杜绝写反方向）。
    var pa = gltf.accessors[prim.attributes.POSITION];
    var tMin = [1e9, 1e9, 1e9], tMax = [-1e9, -1e9, -1e9];
    for (var vi = 0; vi < posAcc.count; vi++) {
      var px = posAcc.data[vi * 3], py = posAcc.data[vi * 3 + 1], pz = posAcc.data[vi * 3 + 2];
      var wx = px, wy = -pz, wz = py; // mat4RotateX(-π/2): (x,y,z) -> (x,-z,y)
      if (wx < tMin[0]) tMin[0] = wx; if (wx > tMax[0]) tMax[0] = wx;
      if (wy < tMin[1]) tMin[1] = wy; if (wy > tMax[1]) tMax[1] = wy;
      if (wz < tMin[2]) tMin[2] = wz; if (wz > tMax[2]) tMax[2] = wz;
    }
    MODEL_CENTER = [(tMin[0] + tMax[0]) / 2, (tMin[1] + tMax[1]) / 2, (tMin[2] + tMax[2]) / 2];
    console.log('[glb] model center (world, real): ' + JSON.stringify(MODEL_CENTER));
    var txcAcc = prim.attributes.TEXCOORD_0 !== undefined
      ? getAccessorData(gltf, bin, prim.attributes.TEXCOORD_0)
      : { data: new Float32Array(posAcc.count * 2), count: posAcc.count, type: 'VEC2' };

    console.log('[glb] positions=' + posAcc.count + ' normals=' + norAcc.count +
                ' uvs=' + txcAcc.count);

    // 创建缓冲区
    vertBuf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, vertBuf);
    gl.bufferData(gl.ARRAY_BUFFER, posAcc.data, gl.STATIC_DRAW);

    normBuf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, normBuf);
    gl.bufferData(gl.ARRAY_BUFFER, norAcc.data, gl.STATIC_DRAW);

    uvBuf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, uvBuf);
    gl.bufferData(gl.ARRAY_BUFFER, txcAcc.data, gl.STATIC_DRAW);

    // 索引
    if (prim.indices !== undefined) {
      var idxAcc = getAccessorData(gltf, bin, prim.indices);
      indexCount = idxAcc.count;
      idxBuf = gl.createBuffer();
      gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, idxBuf);
      gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, idxAcc.data, gl.STATIC_DRAW);
      console.log('[glb] indices=' + indexCount);
    } else {
      showError('模型没有索引数据');
      return;
    }

    // 材质 & 纹理
    showStatus('加载贴图...');

    // 容错：materials 数组可能缺失或空
    if (!gltf.materials || !gltf.materials[0]) {
      showError('模型没有材质定义');
      // 切换诊断模式（纯色渲染）
      STATE.diagMode = true;
      totalTextures = 0;
      loadedTextures = 0;
      finishSetup();
      return;
    }

    var mat = gltf.materials[0];
    var pbr = mat.pbrMetallicRoughness || {};

    // 容错：baseColorTexture 可能缺失
    if (!pbr.baseColorTexture || pbr.baseColorTexture.index === undefined) {
      showError('材质缺少基础颜色贴图，使用诊断模式');
      STATE.diagMode = true;
      totalTextures = 0;
      loadedTextures = 0;
      finishSetup();
      return;
    }

    var baseTexIdx = pbr.baseColorTexture.index;
    var normTexIdx = mat.normalTexture ? mat.normalTexture.index : -1;
    var mrTexIdx = pbr.metallicRoughnessTexture ? pbr.metallicRoughnessTexture.index : -1;

    totalTextures = 1 + (normTexIdx >= 0 ? 1 : 0) + (mrTexIdx >= 0 ? 1 : 0);
    textures.base = loadTexture(gltf, bin, baseTexIdx);
    if (normTexIdx >= 0) textures.normal = loadTexture(gltf, bin, normTexIdx);
    if (mrTexIdx >= 0) textures.mr = loadTexture(gltf, bin, mrTexIdx);

    // 编译 shader
    var vs = compileShader(VS_SRC, gl.VERTEX_SHADER);
    var fs = compileShader(FS_SRC, gl.FRAGMENT_SHADER);
    if (!vs || !fs) return;
    program = createProgram(vs, fs);
    if (!program) return;

    finishSetup();
  }

  function finishSetup() {
    showStatus('准备渲染...');

    // 延迟启动渲染循环（确保 DOM 布局完成）
    setTimeout(function () {
      resize();
      window.addEventListener('resize', resize);
      // 再延迟一帧确保 canvas 尺寸已生效
      requestAnimationFrame(function () {
        resize();  // 二次确认
        requestAnimationFrame(render);
      });
    }, 200);
  }

  // ════════════════════════════════════════════
  // ⑥ 渲染循环（v2: 双面 + 诊断模式 + 目标修正）
  // ════════════════════════════════════════════
  function render(now) {
    requestAnimationFrame(render);
    if (!program) return;

    var dt = STATE.lastFrame ? (now - STATE.lastFrame) / 1000 : 0.016;
    STATE.lastFrame = now;
    STATE.frameCount++;

    // 每 60 帧检查一次 canvas 尺寸（应对 WebView 迟迟布局）
    if (STATE.frameCount % 60 === 1) {
      var cw = canvas.clientWidth, ch = canvas.clientHeight;
      if ((cw > 0 && ch > 0) && (Math.abs(cw * 2 - canvas.width) > 4 || Math.abs(ch * 2 - canvas.height) > 4)) {
        resize();
      }
    }

    // 清屏
    gl.clearColor(0.96, 0.90, 0.83, 1.0);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    gl.enable(gl.DEPTH_TEST);
    // v2: 先禁用背面剔除（Blender winding 可能反了）
    // 如果能看到模型但法线反了再开回来并翻转 cullFace
    gl.disable(gl.CULL_FACE);

    gl.useProgram(program);

    // 相机
    var aspect = canvas.width / Math.max(canvas.height, 1);
    if (!isFinite(aspect) || aspect <= 0) aspect = 1;  // 防止除零
    var proj = mat4Perspective(Math.PI / 4, aspect, 0.01, 100);

    // 自动缓慢旋转
    if (STATE.autoRotate && !STATE.speaking) {
      camYaw += dt * 0.15;
    }

    // 相机位置（球坐标）
    var cy = Math.cos(camPitch) * Math.cos(camYaw) * camDist;
    var cx = Math.cos(camPitch) * Math.sin(camYaw) * camDist;
    var cz = Math.sin(camPitch) * camDist;
    // 相机对正到模型真实世界中心（动态计算，见 setupMesh 中的 MODEL_CENTER）
    var view = mat4LookAt([cx, cy, cz], MODEL_CENTER, [0, 1, 0]);

    // 模型变换
    var model = mat4Multiply(mat4RotateX(modelRotX), mat4Identity());
    var mvp = mat4Multiply(proj, mat4Multiply(view, model));
    var normalMat = mat3FromMat4(model);

    // Uniforms — 矩阵
    gl.uniformMatrix4fv(gl.getUniformLocation(program, 'uMVP'), false, mvp);
    gl.uniformMatrix4fv(gl.getUniformLocation(program, 'uModel'), false, model);
    gl.uniformMatrix3fv(gl.getUniformLocation(program, 'uNormalMat'), false, normalMat);

    // Uniforms — 光照
    gl.uniform3f(gl.getUniformLocation(program, 'uLightDir'), 0.5, 0.8, 1.0);
    gl.uniform3f(gl.getUniformLocation(program, 'uLightColor'), 1.0, 0.95, 0.88);
    gl.uniform3f(gl.getUniformLocation(program, 'uAmbient'), 0.50, 0.47, 0.44);  // v1=0.45，提亮环境光

    // Uniforms — 变形参数
    STATE.mouthAmp += (STATE.targetMouth - STATE.mouthAmp) * Math.min(1, dt * 18);
    gl.uniform1f(gl.getUniformLocation(program, 'uMouthAmp'), STATE.mouthAmp);
    gl.uniform1f(gl.getUniformLocation(program, 'uEyeClose'), STATE.eyeClose);
    gl.uniform1f(gl.getUniformLocation(program, 'uBrowAng'), STATE.browAng);

    // Uniforms — 顶点区域中心
    gl.uniform3f(gl.getUniformLocation(program, 'uMouthCenter'),
                 VG.mouthCenter[0], VG.mouthCenter[1], VG.mouthCenter[2]);
    gl.uniform1f(gl.getUniformLocation(program, 'uMouthRadius'), VG.mouthRadius);
    gl.uniform3f(gl.getUniformLocation(program, 'uEyeLCenter'),
                 VG.eyeL[0], VG.eyeL[1], VG.eyeL[2]);
    gl.uniform3f(gl.getUniformLocation(program, 'uEyeRCenter'),
                 VG.eyeR[0], VG.eyeR[1], VG.eyeR[2]);
    gl.uniform1f(gl.getUniformLocation(program, 'uEyeRadius'), VG.eyeRadius);
    gl.uniform3f(gl.getUniformLocation(program, 'uBrowLCenter'),
                 VG.browL[0], VG.browL[1], VG.browL[2]);
    gl.uniform3f(gl.getUniformLocation(program, 'uBrowRCenter'),
                 VG.browR[0], VG.browR[1], VG.browR[2]);
    gl.uniform1f(gl.getUniformLocation(program, 'uBrowRadius'), VG.browRadius);
    gl.uniform1f(gl.getUniformLocation(program, 'uAlpha'), 1.0);

    // v2: 诊断模式 uniform
    gl.uniform1f(gl.getUniformLocation(program, 'uUseColor'), STATE.diagMode ? 1.0 : 0.0);
    gl.uniform3f(gl.getUniformLocation(program, 'uDiagColor'), 0.85, 0.65, 0.55);  // 暖肤色

    // 绑定纹理
    if (!STATE.diagMode && textures.base) {
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, textures.base);
      gl.uniform1i(gl.getUniformLocation(program, 'uBaseColor'), 0);

      if (textures.normal) {
        gl.activeTexture(gl.TEXTURE1);
        gl.bindTexture(gl.TEXTURE_2D, textures.normal);
        gl.uniform1i(gl.getUniformLocation(program, 'uNormalMap'), 1);
      }
      if (textures.mr) {
        gl.activeTexture(gl.TEXTURE2);
        gl.bindTexture(gl.TEXTURE_2D, textures.mr);
        gl.uniform1i(gl.getUniformLocation(program, 'uMRMap'), 2);
      }
    }

    // 绑定属性
    var locPos = gl.getAttribLocation(program, 'aPosition');
    gl.bindBuffer(gl.ARRAY_BUFFER, vertBuf);
    gl.enableVertexAttribArray(locPos);
    gl.vertexAttribPointer(locPos, 3, gl.FLOAT, false, 0, 0);

    var locNorm = gl.getAttribLocation(program, 'aNormal');
    gl.bindBuffer(gl.ARRAY_BUFFER, normBuf);
    gl.enableVertexAttribArray(locNorm);
    gl.vertexAttribPointer(locNorm, 3, gl.FLOAT, false, 0, 0);

    var locUV = gl.getAttribLocation(program, 'aTexCoord');
    gl.bindBuffer(gl.ARRAY_BUFFER, uvBuf);
    gl.enableVertexAttribArray(locUV);
    gl.vertexAttribPointer(locUV, 2, gl.FLOAT, false, 0, 0);

    // 绘制
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, idxBuf);
    gl.drawElements(gl.TRIANGLES, indexCount, gl.UNSIGNED_SHORT, 0);
  }

  // ════════════════════════════════════════════
  // ⑦ LLM 驱动契约
  // ════════════════════════════════════════════
  var AvatarAPI = {
    setMouth: function (v) {
      STATE.targetMouth = Math.max(0, Math.min(1, parseFloat(v) || 0));
    },
    setEmotion: function (k) {
      STATE.emotion = k;
      var browMap = { neutral:0, calm:-0.03, happy:-0.08, sad:0.06, angry:0.10, surprised:-0.12 };
      STATE.browAng = (k in browMap) ? browMap[k] : 0;
    },
    say: function (text, dur) {
      STATE.speaking = true;
      STATE.autoRotate = false;
    },
    endSpeech: function () {
      STATE.speaking = false;
      STATE.targetMouth = 0;
      STATE.eyeClose = 0;
      setTimeout(function () { STATE.autoRotate = true; }, 3000);
    },
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

  // 安全超时
  setTimeout(function () {
    if (!STATE.ready) {
      STATE.ready = true;
      if (window.__avatar) window.__avatar.ready = true;
      console.warn('[glb] safety timeout reached, force-ready');
    }
    hideLoading();
  }, 15000);

  // 启动（v3: 无参数，数据来自内嵌 JS）
  init();

})();
