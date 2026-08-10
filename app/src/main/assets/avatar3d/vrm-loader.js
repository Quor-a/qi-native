/*
 * vrm-loader.js — 自包含 VRM 0.0/1.0 加载与驱动层（不依赖 three-vrm 包）
 * 依赖：全局 THREE（UMD）+ THREE.GLTFLoader + THREE.DRACOLoader（均来自 CDN r0.136）
 * 暴露 window.VRMKit： load / setEmotion / setMouth / stepBlink
 *
 * 说明：VRM 本质是带 extensions.VRM 的 glTF。GLTFLoader 把几何体/骨骼/蒙皮/形变目标正常载入，
 * 我们只需手动解析 extensions.VRM 拿到 humanoid 骨骼映射与 blendshape（表情）分组，
 * 并做 VRM(+Z 朝向)→ three(-Z 朝向) 的坐标修正。
 */
(function () {
  'use strict';
  var THREE = window.THREE;

  // 情绪预设 → VRM 0.0 presetName 枚举（VRM 0.0 无 surprised，用 fun 近似 + 张嘴）
  var PRESET = {
    neutral:   ['neutral'],
    calm:      ['neutral'],
    happy:     ['joy', 'fun'],
    sad:       ['sorrow'],
    angry:     ['angry'],
    surprised: ['fun'],
    blink:     ['blink']
  };
  // 设情绪时清掉这些组（不含 blink / a / o，避免互相覆盖）
  var EMOTION_CLEAR = ['neutral', 'joy', 'angry', 'sorrow', 'fun'];
  var MOUTH_A = ['a', 'A'];
  var MOUTH_O = ['o', 'O'];

  function byName(root, name) {
    if (!root || !name) return null;
    return root.getObjectByName(name) || null;
  }

  function load(url) {
    return new Promise(function (resolve, reject) {
      var loader = new THREE.GLTFLoader();
      try {
        var draco = new THREE.DRACOLoader();
        draco.setDecoderPath('https://www.gstatic.com/draco/v1/decoders/');
        loader.setDRACOLoader(draco);
      } catch (e) { /* DRACOLoader 不存在则忽略（非 Draco 模型仍可加载） */ }

      loader.load(url, function (gltf) {
        try {
          var scene = gltf.scene || (gltf.scenes && gltf.scenes[0]);
          var json = gltf.parser.json;
          var ext = (json.extensions && json.extensions.VRM) || null;

          var vrm = {
            scene: scene, json: json, ext: ext,
            bones: {}, expressions: {}, height: 1.6,
            _emotion: 'neutral', _mouth: 0
          };

          // ---- humanoid 骨骼映射 ----
          if (ext && ext.humanoid && ext.humanoid.humanBones) {
            ext.humanoid.humanBones.forEach(function (hb) {
              var node = json.nodes[hb.node];
              var nm = node && node.name;
              var o = nm ? byName(scene, nm) : null;
              if (o) vrm.bones[hb.bone] = o;
            });
          } else {
            // 退化：直接按常见骨骼名查找
            ['head', 'neck', 'spine', 'chest', 'upperChest', 'hips',
             'leftShoulder', 'leftUpperArm', 'leftLowerArm', 'leftHand',
             'rightShoulder', 'rightUpperArm', 'rightLowerArm', 'rightHand',
             'leftUpperLeg', 'leftLowerLeg', 'leftFoot',
             'rightUpperLeg', 'rightLowerLeg', 'rightFoot'
            ].forEach(function (b) {
              var o = byName(scene, b);
              if (o) vrm.bones[b] = o;
            });
          }

          // ---- 表情（blendshape）分组 ----
          if (ext && ext.blendShapeMaster && ext.blendShapeMaster.blendShapeGroups) {
            ext.blendShapeMaster.blendShapeGroups.forEach(function (g) {
              var key = g.presetName || g.name;
              var binds = [];
              (g.binds || []).forEach(function (b) {
                var node = json.nodes[b.mesh];
                var nm = node && node.name;
                var m = nm ? byName(scene, nm) : null;
                if (m && m.morphTargetInfluences && b.index < m.morphTargetInfluences.length) {
                  binds.push({ mesh: m, index: b.index, weight: (b.weight || 100) / 100 });
                }
              });
              if (binds.length) vrm.expressions[key] = binds;
            });
          }

          // ---- 坐标修正：VRM 面朝 +Z，旋转根节点 180° 使其面朝相机(-Z) ----
          scene.rotation.y = Math.PI;

          // 计算身高用于相机取景
          try {
            var box = new THREE.Box3().setFromObject(scene);
            var size = new THREE.Vector3();
            box.getSize(size);
            vrm.height = size.y || 1.6;
          } catch (e) { vrm.height = 1.6; }

          resolve(vrm);
        } catch (err) {
          reject(err);
        }
      }, undefined, function (err) { reject(err); });
    });
  }

  function zeroGroups(vrm, names) {
    names.forEach(function (n) {
      var b = vrm.expressions[n];
      if (b) b.forEach(function (x) {
        if (x.mesh.morphTargetInfluences) x.mesh.morphTargetInfluences[x.index] = 0;
      });
    });
  }

  function applyNamed(vrm, name, w) {
    var b = vrm.expressions[name];
    if (!b) return false;
    b.forEach(function (x) {
      if (x.mesh.morphTargetInfluences) x.mesh.morphTargetInfluences[x.index] = x.weight * w;
    });
    return true;
  }

  function setEmotion(vrm, key, w) {
    if (!vrm) return;
    if (w == null) w = 1;
    zeroGroups(vrm, EMOTION_CLEAR);
    var list = PRESET[key] || [key];
    var ok = false;
    for (var i = 0; i < list.length; i++) {
      if (applyNamed(vrm, list[i], w)) ok = true;
    }
    vrm._emotion = key;
    return ok;
  }

  function setMouth(vrm, amp) {
    if (!vrm) return;
    amp = Math.max(0, Math.min(1, amp || 0));
    zeroGroups(vrm, MOUTH_A.concat(MOUTH_O));
    applyNamed(vrm, 'a', amp);
    applyNamed(vrm, 'o', amp * 0.25);
    vrm._mouth = amp;
  }

  // 自动眨眼：每 ~4s 一次，闭合约 0.12s
  function stepBlink(vrm, dt, t) {
    if (!vrm) return;
    var period = 4.0, close = 0.12;
    var ph = t % period;
    var b = 0;
    if (ph < close) b = Math.sin(ph / close * Math.PI);
    zeroGroups(vrm, ['blink']);
    applyNamed(vrm, 'blink', b);
  }

  window.VRMKit = {
    load: load,
    setEmotion: setEmotion,
    setMouth: setMouth,
    stepBlink: stepBlink,
    PRESET: PRESET,
    EMOTION_CLEAR: EMOTION_CLEAR,
    MOUTH_A: MOUTH_A,
    MOUTH_O: MOUTH_O
  };
})();
