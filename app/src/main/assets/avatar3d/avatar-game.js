/*
 * avatar-game.js — 栖 视频形象引导（离线）
 * ----------------------------------------------------------------------------
 * 渲染方式：<video> 全屏播放预生成情绪视频（每个视频人物在场景里动态说话/表情）。
 * LLM 通过 window.__avatar 驱动（契约不变）：
 *   setEmotion(k)  → 切换对应情绪视频（带淡入淡出）
 *   setSpeaking(b) → 说话时播放、沉默时暂停
 * 视频自带完整房间场景，object-fit:cover 全屏，物理上无双层背景、动态口形。
 */
(function () {
  'use strict';
  var video = document.getElementById('avatarVideo');
  if (!video) return;

  // 情绪视频映射（LLM 输出 key → 本地视频文件）
  var VIDEOS = {
    neutral:   'videos/neutral_talk.mp4',
    happy:     'videos/happy_talk.mp4',
    sad:       'videos/sad_talk.mp4',
    angry:     'videos/angry_talk.mp4',
    surprised: 'videos/surprised_talk.mp4',
    calm:      'videos/calm_talk.mp4'
  };

  var STATE = { emotion: 'neutral', speaking: false, accent: '#ff9bb3' };
  var ready = false;
  var currentSrc = '';

  // 预加载所有视频（隐藏 video 元素缓冲，切换不卡顿）
  var pool = {};
  Object.keys(VIDEOS).forEach(function (k) {
    var v = document.createElement('video');
    v.src = VIDEOS[k]; v.preload = 'auto'; v.muted = true; v.loop = true;
    v.playsInline = true;
    pool[k] = v;
  });

  function playEmotion(k) {
    if (!VIDEOS[k]) k = 'neutral';
    var next = VIDEOS[k];
    STATE.emotion = k;
    if (currentSrc === next) { video.play().catch(function () {}); return; }
    // 淡出 → 换源 → 淡入
    video.style.opacity = '0';
    setTimeout(function () {
      video.src = next;
      video.dataset.src = next;
      currentSrc = next;
      video.play().catch(function () {});
      video.style.opacity = '1';
    }, 260);
  }

  function onReady() {
    if (ready) return;
    ready = true;
    if (window.__avatar) window.__avatar.ready = true;
    var ld = document.getElementById('loading');
    if (ld) ld.classList.add('gone');
    playEmotion('neutral');
  }

  video.addEventListener('canplay', function () { if (!ready) onReady(); });
  video.addEventListener('playing', function () { if (!ready) onReady(); });
  video.addEventListener('error', function () {
    if (window.QiBridge && QiBridge.onError) QiBridge.onError('video');
    if (!ready) onReady();
  });
  setTimeout(function () { if (!ready) onReady(); }, 3000);

  // —— LLM 驱动契约（Kotlin 经 evaluateJavascript 调用）——
  var AvatarAPI = {
    // 口型幅度（视频模式由视频自身呈现，此接口保留兼容）
    setMouth: function () {},
    // 情绪切换 → 切视频
    setEmotion: function (k) { playEmotion(k); },
    // 手势（视频模式暂不支持，保留接口）
    gesture: function () {},
    // 开始说话 → 播放当前情绪视频
    say: function (text, dur) {
      STATE.speaking = true;
      video.play().catch(function () {});
    },
    // 结束说话 → 暂停在末帧
    endSpeech: function () {
      STATE.speaking = false;
      video.pause();
    },
    // 发型切换（视频模式暂固定形象，保留接口）
    setStyle: function () {},
    setAccent: function (hex) { if (hex) STATE.accent = hex; },
    setScene: function () {},
    setShot: function () {},
    face: function () {},
    getState: function () { return JSON.parse(JSON.stringify(STATE)); },
    ready: false
  };
  window.__avatar = AvatarAPI;

  video.addEventListener('pointerdown', function () {
    if (window.QiBridge && QiBridge.onTap) QiBridge.onTap();
  });
})();
