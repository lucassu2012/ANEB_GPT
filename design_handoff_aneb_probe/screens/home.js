(() => {
  "use strict";

  const app = document.querySelector("#app");
  const ring = document.querySelector("#test-ring");
  const score = document.querySelector("#live-score");
  const phase = document.querySelector("#live-phase");
  const caption = document.querySelector("#state-caption");
  const toast = document.querySelector("#toast");
  const closeButton = document.querySelector(".close-button");
  const liveMetrics = document.querySelector(".live-metrics");
  const networkSheet = document.querySelector("#network-sheet");
  const sheetHandle = networkSheet.querySelector("[data-sheet-handle]");
  const nodeName = document.querySelector("#node-name");
  const nodeMeta = document.querySelector("#node-meta");
  const sheetNodeName = document.querySelector("#sheet-node-name");
  const nodeModal = document.querySelector("#node-modal");
  const bottomNav = document.querySelector(".bottom-nav");
  const resultPanel = document.querySelector(".result-panel");
  const ringCopies = [...document.querySelectorAll("[data-copy]")];
  const detailControls = [...networkSheet.querySelectorAll("button, [role='button']")];
  const navControls = [...bottomNav.querySelectorAll("button, a")];
  const resultControls = [...resultPanel.querySelectorAll("button")];

  const metricEls = {
    token: document.querySelector("#token-rate"),
    upload: document.querySelector("#upload-rate"),
    ping: document.querySelector("#ping-value"),
    jitter: document.querySelector("#jitter-value"),
    loss: document.querySelector("#loss-value"),
    pingLine: document.querySelector("#ping-line"),
    jitterLine: document.querySelector("#jitter-line"),
    lossLine: document.querySelector("#loss-line"),
  };

  const nodes = [
    { name: "深圳 · 中国电信", meta: "Node-SZ-03 · 12ms" },
    { name: "广州 · 中国移动", meta: "Node-GZ-06 · 16ms" },
    { name: "香港 · Cloud Edge", meta: "Node-HK-02 · 28ms" },
  ];

  const state = {
    mode: "idle",
    progress: 0,
    nodeIndex: 0,
    timers: [],
    interval: null,
    toastTimer: null,
    previousFocus: null,
    series: {
      ping: [17, 16, 18, 15, 16, 17, 15, 16],
      jitter: [3.1, 2.5, 3.8, 2.1, 2.8, 2.4, 3.2, 2.7],
      loss: [0, 0, 0, .1, 0, 0, 0, 0],
    },
    drag: { active: false, startY: 0, startOffset: 0, moved: false, lastY: 0, lastTime: 0, velocity: 0 },
  };

  function clearTestTimers() {
    state.timers.forEach((timer) => window.clearTimeout(timer));
    state.timers = [];
    window.clearInterval(state.interval);
    state.interval = null;
  }

  function setInteractive(elements, enabled) {
    elements.forEach((element) => { element.tabIndex = enabled ? 0 : -1; });
  }

  function setMode(mode) {
    state.mode = mode;
    app.dataset.state = mode;
    if (mode !== "running") delete app.dataset.phase;
    ring.disabled = mode === "connecting" || mode === "running";
    ring.dataset.action = mode === "idle" || mode === "result" ? "start" : "noop";

    const labels = {
      idle: "开始 AI 网络体验测试",
      connecting: "正在连接测试节点",
      running: "正在测试 AI 网络体验",
      result: "体验分 89，优秀；点击重新测试",
    };
    ring.setAttribute("aria-label", labels[mode]);

    const isTesting = mode === "connecting" || mode === "running";
    const showIdleControls = mode === "idle";
    const showNavigation = mode === "idle" || mode === "result";
    closeButton.setAttribute("aria-hidden", String(!isTesting));
    closeButton.tabIndex = isTesting ? 0 : -1;
    liveMetrics.setAttribute("aria-hidden", String(mode !== "running"));
    networkSheet.setAttribute("aria-hidden", String(!showIdleControls));
    bottomNav.setAttribute("aria-hidden", String(!showNavigation));
    resultPanel.setAttribute("aria-hidden", String(mode !== "result"));
    setInteractive(detailControls, showIdleControls);
    setInteractive(navControls, showNavigation);
    setInteractive(resultControls, mode === "result");
    ringCopies.forEach((copy) => copy.setAttribute("aria-hidden", String(copy.dataset.copy !== mode)));

    if (mode === "idle") caption.textContent = "评估网络是否适合 AI 对话、编码和文件上传";
    if (mode === "connecting") caption.textContent = "正在连接测试节点";
    if (mode === "result") caption.textContent = "你的网络很适合 AI 助手";
  }

  function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
  }

  function jitter(amount) {
    return (Math.random() - .5) * amount;
  }

  function appendSeries(name, value) {
    const values = state.series[name];
    values.push(value);
    if (values.length > 18) values.shift();
  }

  function sparkPath(values, min, max) {
    return values.map((value, index) => {
      const x = values.length === 1 ? 0 : index * (96 / (values.length - 1));
      const y = 22 - clamp((value - min) / (max - min), 0, 1) * 18;
      return `${index ? "L" : "M"}${x.toFixed(1)} ${y.toFixed(1)}`;
    }).join("");
  }

  function updateMetrics(progress) {
    const warmup = clamp(progress / 20, 0, 1);
    const tokenBase = progress < 70 ? 148 * (1 - Math.exp(-progress / 17)) : 142;
    const uploadBase = progress < 70 ? 7 + warmup * 5 : 12 + ((progress - 70) / 30) * 31;
    const ping = clamp(17 - warmup * 2 + jitter(5.4), 10, 26);
    const jitterValue = clamp(3.2 - warmup * .6 + jitter(2.8), .7, 7.5);
    const loss = Math.random() > .92 ? .2 : 0;

    appendSeries("ping", ping);
    appendSeries("jitter", jitterValue);
    appendSeries("loss", loss);

    metricEls.token.textContent = String(Math.max(0, Math.round(tokenBase + jitter(6))));
    metricEls.upload.textContent = (uploadBase + jitter(2)).toFixed(1);
    metricEls.ping.textContent = String(Math.round(ping));
    metricEls.jitter.textContent = jitterValue.toFixed(1);
    metricEls.loss.textContent = loss.toFixed(1);
    metricEls.pingLine.setAttribute("d", sparkPath(state.series.ping, 8, 32));
    metricEls.jitterLine.setAttribute("d", sparkPath(state.series.jitter, 0, 9));
    metricEls.lossLine.setAttribute("d", sparkPath(state.series.loss, 0, 1));
  }

  function runningCopy(progress) {
    if (progress < 18) return { label: "响应 Mbps", caption: "正在测量 Ping、抖动与丢包", value: Math.max(0, progress * .085) };
    if (progress < 70) return { label: "Token /秒", caption: "正在检查 AI 持续输出与稳定性", value: 148 * (1 - Math.exp(-progress / 17)) };
    return { label: "上行 Mbps", caption: "正在检查文件上传与请求稳定性", value: 12 + ((progress - 70) / 30) * 31 };
  }

  function beginRunning() {
    state.progress = 0;
    setMode("running");
    app.dataset.phase = "download";
    app.style.setProperty("--progress", "0");
    app.style.setProperty("--gauge-progress", "0");
    score.textContent = "0.00";
    updateMetrics(0);

    state.interval = window.setInterval(() => {
      state.progress = Math.min(100, state.progress + 1.8);
      const isUpload = state.progress >= 70;
      const gaugeProgress = isUpload ? ((state.progress - 70) / 30) * 100 : (state.progress / 70) * 100;
      const copy = runningCopy(state.progress);

      app.dataset.phase = isUpload ? "upload" : "download";
      app.style.setProperty("--progress", gaugeProgress.toFixed(2));
      app.style.setProperty("--gauge-progress", gaugeProgress.toFixed(2));
      score.textContent = copy.value < 10 ? copy.value.toFixed(2) : copy.value.toFixed(1);
      phase.textContent = copy.label;
      caption.textContent = copy.caption;
      updateMetrics(state.progress);

      if (state.progress >= 100) {
        window.clearInterval(state.interval);
        state.interval = null;
        state.timers.push(window.setTimeout(() => setMode("result"), 460));
      }
    }, 110);
  }

  function startTest() {
    clearTestTimers();
    closeNodeModal(false);
    setSheetSnap("collapsed");
    setMode("connecting");
    state.timers.push(window.setTimeout(beginRunning, 1850));
  }

  function cancelTest() {
    clearTestTimers();
    state.progress = 0;
    app.style.setProperty("--progress", "0");
    app.style.setProperty("--gauge-progress", "0");
    setMode("idle");
  }

  function chooseNode(index) {
    state.nodeIndex = index;
    const node = nodes[index];
    nodeName.textContent = node.name;
    sheetNodeName.textContent = node.name;
    nodeMeta.textContent = node.meta;
    closeNodeModal(false);
    showToast(`已切换至 ${node.name}`);
  }

  function openNodeModal() {
    state.previousFocus = document.activeElement;
    nodeModal.classList.add("open");
    nodeModal.setAttribute("aria-hidden", "false");
    window.setTimeout(() => nodeModal.querySelector("[data-node]").focus(), 80);
  }

  function closeNodeModal(restoreFocus = true) {
    if (!nodeModal.classList.contains("open")) return;
    nodeModal.classList.remove("open");
    nodeModal.setAttribute("aria-hidden", "true");
    if (restoreFocus && state.previousFocus) state.previousFocus.focus();
  }

  function showToast(message) {
    window.clearTimeout(state.toastTimer);
    toast.textContent = message;
    toast.classList.add("show");
    state.toastTimer = window.setTimeout(() => toast.classList.remove("show"), 1700);
  }

  function sheetOffsets() {
    const height = networkSheet.getBoundingClientRect().height;
    const collapsed = Math.max(0, height - 71);
    return { expanded: 0, half: collapsed / 2, collapsed };
  }

  function currentSheetOffset() {
    const offsets = sheetOffsets();
    return offsets[networkSheet.dataset.snap] ?? offsets.collapsed;
  }

  function setSheetSnap(snap) {
    networkSheet.style.removeProperty("transform");
    networkSheet.dataset.snap = snap;
    const expanded = snap !== "collapsed";
    sheetHandle.setAttribute("aria-expanded", String(expanded));
    sheetHandle.setAttribute("aria-label", expanded ? "收起网络详情" : "展开网络详情");
  }

  function toggleSheet() {
    if (state.drag.moved) return;
    setSheetSnap(networkSheet.dataset.snap === "expanded" ? "collapsed" : "expanded");
  }

  function beginSheetDrag(event) {
    if (state.mode !== "idle") return;
    event.preventDefault();
    state.drag.active = true;
    state.drag.startY = event.clientY;
    state.drag.startOffset = currentSheetOffset();
    state.drag.lastY = event.clientY;
    state.drag.lastTime = performance.now();
    state.drag.velocity = 0;
    state.drag.moved = false;
    networkSheet.classList.add("dragging");
    try { sheetHandle.setPointerCapture?.(event.pointerId); } catch (_) { /* Window listeners keep the drag active. */ }
  }

  function moveSheet(event) {
    if (!state.drag.active) return;
    event.preventDefault();
    const now = performance.now();
    const elapsed = Math.max(1, now - state.drag.lastTime);
    state.drag.velocity = (event.clientY - state.drag.lastY) / elapsed;
    state.drag.lastY = event.clientY;
    state.drag.lastTime = now;
    const offsets = sheetOffsets();
    const next = clamp(state.drag.startOffset + event.clientY - state.drag.startY, 0, offsets.collapsed);
    if (Math.abs(event.clientY - state.drag.startY) > 5) state.drag.moved = true;
    networkSheet.style.transform = `translateY(${next}px)`;
  }

  function endSheetDrag(event) {
    if (!state.drag.active) return;
    state.drag.active = false;
    networkSheet.classList.remove("dragging");
    if (sheetHandle.hasPointerCapture?.(event.pointerId)) sheetHandle.releasePointerCapture(event.pointerId);
    const matrix = new DOMMatrixReadOnly(getComputedStyle(networkSheet).transform);
    const current = matrix.m42;
    const offsets = sheetOffsets();
    const points = [
      { snap: "expanded", value: offsets.expanded },
      { snap: "half", value: offsets.half },
      { snap: "collapsed", value: offsets.collapsed },
    ];
    let target = points.reduce((best, point) => Math.abs(point.value - current) < Math.abs(best.value - current) ? point : best);
    if (Math.abs(state.drag.velocity) > .45) {
      const currentIndex = points.findIndex((point) => point.snap === target.snap);
      const nextIndex = clamp(currentIndex + (state.drag.velocity > 0 ? 1 : -1), 0, points.length - 1);
      target = points[nextIndex];
    }
    setSheetSnap(target.snap);
    window.setTimeout(() => { state.drag.moved = false; }, 40);
  }

  sheetHandle.addEventListener("pointerdown", beginSheetDrag);
  window.addEventListener("pointermove", moveSheet, { passive: false });
  window.addEventListener("pointerup", endSheetDrag);
  window.addEventListener("pointercancel", endSheetDrag);
  sheetHandle.addEventListener("keydown", (event) => {
    if (event.key === "Enter" || event.key === " ") { event.preventDefault(); toggleSheet(); }
    if (event.key === "ArrowUp") { event.preventDefault(); setSheetSnap("expanded"); }
    if (event.key === "ArrowDown") { event.preventDefault(); setSheetSnap("collapsed"); }
  });

  nodeModal.addEventListener("click", (event) => {
    if (event.target === nodeModal) closeNodeModal();
    const option = event.target.closest("[data-node]");
    if (option) chooseNode(Number(option.dataset.node));
  });

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && nodeModal.classList.contains("open")) closeNodeModal();
  });

  app.addEventListener("click", (event) => {
    const target = event.target.closest("[data-action]");
    if (!target) return;
    const action = target.dataset.action;
    if (action === "start") startTest();
    if (action === "cancel") cancelTest();
    if (action === "toggle-sheet") toggleSheet();
    if (action === "open-node-modal") openNodeModal();
    if (action === "close-node-modal") closeNodeModal();
    if (action === "nav") showToast(`${target.dataset.target}模块将在后续版本接入真实数据`);
  });

  setMode("idle");
  setSheetSnap("collapsed");
})();
