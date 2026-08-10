/*
 * vrm-engine.js — 栖 · VRM 3D 引擎入口
 * 依赖：全局 THREE（CDN r0.136 UMD）+ THREE.GLTFLoader + THREE.DRACOLoader + vrm-loader.js(VMRKit)
 *
 * 完整复用原 main.js 的 window.__avatar 契约（Kotlin 侧 AvatarActivity.kt 零改动）：
 *   setMouth(v) / setEmotion(key) / gesture(name) / say(text,dur) / endSpeech()
 *   / setStyle(i) / setAccent(hex) / setScene(m) / setShot(i) / face(dir) / getState()
 *
 * AvatarBus 五通道映射（Kotlin 已 emit）：
 *   ampListener   → __avatar.setMouth(amp)        → VRM 口型(a) 随音节开合
 *   emoListener   → __avatar.setEmotion(key)      → VRM 表情预设(happy/sad/angry/surprised/neutral)
 *   sayListener   → __avatar.say(text,dur)        → 标记说话中（口形由 amp 驱动）
 *   gestureListener→ __avatar.gesture(name)       → humanoid 骨骼姿态
 *   endListener   → __avatar.endSpeech()          → 结束说话
 *   setStyle(i)   → 切换 model0/1/2.vrm（对应原 style 0/1/2 三种发型）
 */
(function () {
  'use strict';
  var THREE = window.THREE;
  var Kit = window.VRMKit;
  var canvas = document.getElementById('glcanvas');

  var STATE = {
    style: 0,
    emotion: 'neutral',
    speaking: false,
    accent: '#FF8FAE',
    scene: 0,
    shot: 0,
    faceDir: 'center',
    mouth: 0
  };

  var renderer, scene, camera, clock;
  var current = null;       // 当前 VRM
  var room = null;
  var gesture = null;       // { name, t, dur }
  var elapsed = 0;

  var MODELS = ['model0.vrm', 'model1.vrm', 'model2.vrm'];

  // ---- 手势定义：VRM 骨骼名 → 目标欧拉角(弧度)；osc 为正弦摆动 ----
  var GESTURES = {
    wave:    { bones: { rightUpperArm: [0, -0.2, -1.1], rightLowerArm: [0, 0, -0.3] }, osc: { rightLowerArm: [0, 0, 0.5, 3] } },
    nod:     { bones: {}, osc: { head: [0.35, 0, 0, 2.2] } },
    shake:   { bones: {}, osc: { head: [0, 0.4, 0, 2.6] } },
    bow:     { bones: { spine: [0.35, 0, 0], head: [0.2, 0, 0] } },
    heart:   { bones: { rightUpperArm: [0, -0.4, -1.5], rightLowerArm: [-1.6, 0, 0], leftUpperArm: [0, 0.4, 1.5], leftLowerArm: [-1.6, 0, 0] } },
    think:   { bones: { rightUpperArm: [0, -0.9, -0.9], rightLowerArm: [-1.6, 0, 0] } },
    point:   { bones: { rightUpperArm: [0, -0.3, -1.0], rightLowerArm: [-1.4, 0, 0] } },
    cheer:   { bones: { rightUpperArm: [0, -0.3, -2.4], rightLowerArm: [-0.3, 0, 0], leftUpperArm: [0, 0.3, 2.4], leftLowerArm: [-0.3, 0, 0] } },
    shy:     { bones: { rightUpperArm: [0, -0.5, -0.7], rightLowerArm: [-2.0, 0, 0], leftUpperArm: [0, 0.5, 0.7], leftLowerArm: [-2.0, 0, 0] } },
    stretch: { bones: { rightUpperArm: [0, -0.2, -2.8], leftUpperArm: [0, 0.2, 2.8] } },
    tilt:    { bones: { head: [0, 0, 0.18] } },
    explain: { bones: { rightUpperArm: [0, -0.4, -1.1], rightLowerArm: [-1.0, 0, 0] }, osc: { rightLowerArm: [0.6, 0, 0, 3] } },
    hair:    { bones: { rightUpperArm: [0, -0.3, -0.6], rightLowerArm: [-2.2, 0, 0], head: [0, 0.1, 0.1] }, osc: { head: [0, 0.1, 0, 1.5] } },
    thumb:   { bones: { rightUpperArm: [0, -0.2, -1.6], rightLowerArm: [-1.5, 0, 0] } },
    shrug:   { bones: { rightShoulder: [0, 0, -0.5], leftShoulder: [0, 0, 0.5], rightUpperArm: [0, 0, -0.3], leftUpperArm: [0, 0, 0.3] } }
  };

  // ---- 房间/场景（轻量 three 重建，沿用电影感取景）----
  function buildRoom(accentHex) {
    if (room) scene.remove(room);
    room = new THREE.Group();
    var acc = new THREE.Color(accentHex || '#FF8FAE');

    var floor = new THREE.Mesh(
      new THREE.CircleGeometry(7, 48),
      new THREE.MeshStandardMaterial({ color: 0x2a2630, roughness: 0.95 })
    );
    floor.rotation.x = -Math.PI / 2;
    room.add(floor);

    var wall = new THREE.Mesh(
      new THREE.PlaneGeometry(14, 9),
      new THREE.MeshStandardMaterial({ color: 0x201d28, roughness: 1 })
    );
    wall.position.set(0, 3.2, -4.2);
    room.add(wall);

    var win = new THREE.Mesh(
      new THREE.PlaneGeometry(3.4, 2.6),
      new THREE.MeshBasicMaterial({ color: acc.clone().lerp(new THREE.Color(0xffffff), 0.5) })
    );
    win.position.set(-1.8, 3.4, -4.15);
    room.add(win);

    var desk = new THREE.Mesh(
      new THREE.BoxGeometry(2.6, 0.12, 1.0),
      new THREE.MeshStandardMaterial({ color: 0x3a3340, roughness: 0.8 })
    );
    desk.position.set(1.6, 1.0, -2.4);
    room.add(desk);

    scene.add(room);
  }

  function frameCamera() {
    if (!camera) return;
    var h = current ? current.height : 1.6;
    var dist = h * 2.4;
    if (STATE.shot === 1) dist = h * 1.7;   // 半身
    if (STATE.shot === 2) dist = h * 1.2;   // 特写
    camera.position.set(0, h * 0.95, dist);
    camera.lookAt(0, h * 0.9, 0);
  }

  // ---- 手势驱动 ----
  function applyGesture(name, dt) {
    var g = GESTURES[name];
    if (!g || !current) return;
    var targets = g.bones || {};
    var osc = g.osc || {};
    var speed = 6.0, k = Math.min(1, speed * dt);
    Object.keys(targets).forEach(function (bn) {
      var bone = current.bones[bn];
      if (!bone) return;
      var t = targets[bn];
      bone.rotation.x += (t[0] - bone.rotation.x) * k;
      bone.rotation.y += (t[1] - bone.rotation.y) * k;
      bone.rotation.z += (t[2] - bone.rotation.z) * k;
    });
    Object.keys(osc).forEach(function (bn) {
      var bone = current.bones[bn];
      if (!bone) return;
      var o = osc[bn];
      var val = Math.sin(elapsed * o[3]) * o[0];
      bone.rotation.x += (val - bone.rotation.x) * k;
    });
  }

  function relaxBones(dt) {
    if (!current) return;
    var speed = 4.0, k = Math.min(1, speed * dt);
    // 跳过呼吸相关骨骼，避免与呼吸动画打架
    var skip = { spine: 1, chest: 1, upperChest: 1 };
    Object.keys(current.bones).forEach(function (bn) {
      if (skip[bn]) return;
      var bone = current.bones[bn];
      bone.rotation.x += (0 - bone.rotation.x) * k;
      bone.rotation.y += (0 - bone.rotation.y) * k;
      bone.rotation.z += (0 - bone.rotation.z) * k;
    });
  }

  function applyFace() {
    if (!current) return;
    var d = {
      center: [0, 0], left: [-0.3, 0], right: [0.3, 0],
      up: [0, -0.2], down: [0, 0.2]
    }[STATE.faceDir] || [0, 0];
    var head = current.bones.head;
    if (head) {
      head.rotation.y += (d[0] - head.rotation.y) * 0.1;
      head.rotation.x += (d[1] - head.rotation.x) * 0.1;
    }
  }

  function doGesture(name) {
    if (!GESTURES[name]) return;
    gesture = { name: name, t: 0, dur: (name === 'bow' || name === 'heart' || name === 'thumb') ? 1.4 : 1.0 };
  }

  // ---- 主循环 ----
  function animate() {
    requestAnimationFrame(animate);
    if (!renderer) return;
    var dt = Math.min(clock.getDelta(), 0.05);
    elapsed += dt;

    if (current) {
      if (gesture) {
        applyGesture(gesture.name, dt);
        gesture.t += dt;
        if (gesture.dur && gesture.t > gesture.dur) gesture = null;
      } else {
        relaxBones(dt);
      }
      // 呼吸（在 relax 之后施加，确保生效）
      var breath = Math.sin(elapsed * 1.2) * 0.02;
      var chest = current.bones.chest || current.bones.spine;
      if (chest) chest.rotation.x += (breath - chest.rotation.x) * 0.2;

      applyFace();
      Kit.setMouth(current, STATE.mouth);
      Kit.stepBlink(current, dt, elapsed);
    }
    renderer.render(scene, camera);
  }

  function loadModel(i) {
    var url = MODELS[i];
    return Kit.load(url).then(function (vrm) {
      if (current && current.scene) scene.remove(current.scene);
      current = vrm;
      STATE.style = i;
      scene.add(vrm.scene);
      Kit.setEmotion(vrm, STATE.emotion, 1);
      frameCamera();
      return vrm;
    });
  }

  function onResize() {
    if (!renderer) return;
    renderer.setSize(window.innerWidth, window.innerHeight, false);
    if (camera) {
      camera.aspect = window.innerWidth / window.innerHeight;
      camera.updateProjectionMatrix();
    }
  }

  function hideLoading() {
    var l = document.getElementById('loading');
    if (l) l.classList.add('gone');
  }
  function showFallback() {
    var f = document.getElementById('fallback');
    var l = document.getElementById('loading');
    if (l) l.classList.add('gone');
    if (f) f.style.display = 'flex';
  }

  function init() {
    try {
      renderer = new THREE.WebGLRenderer({ canvas: canvas, antialias: true, alpha: true });
    } catch (e) {
      showFallback();
      return;
    }
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    renderer.setSize(window.innerWidth, window.innerHeight, false);
    if ('outputEncoding' in renderer && THREE.sRGBEncoding) renderer.outputEncoding = THREE.sRGBEncoding;

    scene = new THREE.Scene();
    camera = new THREE.PerspectiveCamera(35, window.innerWidth / window.innerHeight, 0.1, 100);

    scene.add(new THREE.HemisphereLight(0xffffff, 0x404048, 1.0));
    var dir = new THREE.DirectionalLight(0xffffff, 1.1);
    dir.position.set(2, 5, 4);
    scene.add(dir);
    var rim = new THREE.DirectionalLight(0xffd9c0, 0.6);
    rim.position.set(-3, 3, -2);
    scene.add(rim);

    buildRoom(STATE.accent);
    clock = new THREE.Clock();
    window.addEventListener('resize', onResize);

    loadModel(STATE.style).then(function () {
      hideLoading();
      AvatarAPI.ready = true;
      if (window.QiBridge && window.QiBridge.onReady) window.QiBridge.onReady();
    }).catch(function (err) {
      console.error('[vrm-engine] model load failed:', err);
      showFallback();
    });

    animate();
  }

  // ---- window.__avatar 契约（与原 main.js 完全一致）----
  var AvatarAPI = {
    ready: false,
    setMouth: function (v) { STATE.mouth = Math.max(0, Math.min(1, v || 0)); },
    setEmotion: function (key) {
      STATE.emotion = key || 'neutral';
      if (current) Kit.setEmotion(current, STATE.emotion, 1);
    },
    gesture: function (name) { doGesture(name); },
    say: function (text, durMs) { STATE.speaking = true; /* 口形由 amp 通道驱动；Kotlin 会在结束调 endSpeech */ },
    endSpeech: function () { STATE.speaking = false; },
    setStyle: function (i) {
      var idx = Math.max(0, Math.min(2, (i | 0)));
      loadModel(idx).then(function () { frameCamera(); }).catch(function (e) { console.error(e); });
    },
    setAccent: function (hex) { if (hex) { STATE.accent = hex; buildRoom(hex); } },
    setScene: function (m) { STATE.scene = (m | 0); },
    setShot: function (i) { STATE.shot = (i | 0); frameCamera(); },
    face: function (dir) { STATE.faceDir = dir || 'center'; },
    getState: function () {
      return {
        style: STATE.style, emotion: STATE.emotion, speaking: STATE.speaking,
        accent: STATE.accent, scene: STATE.scene, shot: STATE.shot
      };
    }
  };
  window.__avatar = AvatarAPI;

  if (document.readyState === 'complete' || document.readyState === 'interactive') {
    setTimeout(init, 0);
  } else {
    window.addEventListener('DOMContentLoaded', init);
  }
})();
