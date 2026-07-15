(() => {
  "use strict";

  const app = document.querySelector(".suite-app");
  const toast = document.querySelector("#suite-toast");
  let toastTimer = null;

  const nav = document.querySelector("[data-suite-nav]");
  if (nav) {
    const active = nav.dataset.active || "";
    const items = [
      ["home", "home.html", "测试", '<path d="M4 16a8 8 0 0 1 16 0"/><path d="m12 16 4-5"/><circle cx="12" cy="16" r="1.35" fill="currentColor" stroke="none"/>'],
      ["probe", "probe.html", "探针", '<path d="M5 18V9m5 9V5m5 13v-7m4 7V8"/><circle cx="5" cy="7" r="1.5"/><circle cx="10" cy="3" r="1.5"/><circle cx="15" cy="9" r="1.5"/><circle cx="19" cy="6" r="1.5"/>'],
      ["history", "history.html", "结果", '<circle cx="12" cy="12" r="8"/><path d="m8 12 2.4 2.4L16 8.8"/>'],
      ["map", "map.html", "地图", '<path d="m4 6 5-2 6 2 5-2v14l-5 2-6-2-5 2Z"/><path d="M9 4v14m6-12v14"/>'],
      ["settings", "settings.html", "设置", '<circle cx="12" cy="12" r="3"/><path d="M4 12h2m12 0h2M12 4v2m0 12v2M6.3 6.3l1.4 1.4m8.6 8.6 1.4 1.4m0-11.4-1.4 1.4m-8.6 8.6-1.4 1.4"/>'],
    ];
    nav.setAttribute("aria-label", "主导航");
    nav.innerHTML = items.map(([key, href, label, icon]) => `<a class="nav-link${active === key ? " active" : ""}" href="${href}"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${icon}</svg><span>${label}</span></a>`).join("");
  }

  function showToast(message) {
    if (!toast) return;
    window.clearTimeout(toastTimer);
    toast.textContent = message;
    toast.classList.add("show");
    toastTimer = window.setTimeout(() => toast.classList.remove("show"), 1700);
  }

  document.addEventListener("click", (event) => {
    const messageTarget = event.target.closest("[data-toast]");
    if (messageTarget) showToast(messageTarget.dataset.toast);

    const switchControl = event.target.closest(".switch");
    if (switchControl) {
      const enabled = switchControl.classList.toggle("on");
      switchControl.setAttribute("aria-checked", String(enabled));
      showToast(enabled ? "已开启" : "已关闭");
    }

    const segment = event.target.closest("[data-segment]");
    if (segment) {
      segment.parentElement.querySelectorAll("[data-segment]").forEach((item) => item.classList.toggle("active", item === segment));
    }

    const node = event.target.closest(".node-row");
    if (node) {
      node.parentElement.querySelectorAll(".node-row").forEach((item) => item.classList.toggle("selected", item === node));
      showToast(`已选择 ${node.dataset.node || "测试节点"}`);
    }

    const sheetDemo = event.target.closest("[data-sheet-demo]");
    if (sheetDemo) sheetDemo.classList.toggle("open");
  });

  const livePath = document.querySelector("#suite-live-line");
  const liveToken = document.querySelector("#suite-live-token");
  const liveScore = document.querySelector("#suite-live-score");
  const testingRing = document.querySelector(".testing-ring");
  if (livePath && liveToken && liveScore && testingRing) {
    const values = [124, 132, 129, 140, 136, 145, 143, 148, 139, 146, 142, 147];
    let tick = 0;
    window.setInterval(() => {
      const next = Math.max(92, Math.min(168, 142 + (Math.random() - .5) * 18));
      values.push(next);
      values.shift();
      const points = values.map((value, index) => `${(index * 96 / (values.length - 1)).toFixed(1)},${(25 - (value - 90) / 78 * 20).toFixed(1)}`);
      livePath.setAttribute("d", `M${points.join(" L")}`);
      liveToken.textContent = String(Math.round(next));
      tick = Math.min(89, tick + .6);
      const score = Math.round(62 + tick * .3);
      liveScore.textContent = String(score);
      testingRing.style.setProperty("--score", String(score));
    }, 420);
  }

  document.querySelectorAll("[data-copy-color]").forEach((button) => {
    button.addEventListener("click", () => {
      const value = button.dataset.copyColor;
      navigator.clipboard?.writeText(value).catch(() => {});
      showToast(`已复制 ${value}`);
    });
  });

  const motionRange = document.querySelector("#motion-range");
  const motionDot = document.querySelector("#motion-dot");
  if (motionRange && motionDot) {
    motionRange.addEventListener("input", () => {
      motionDot.style.transform = `translateX(${Number(motionRange.value) * 1.55}px)`;
    });
  }

  if (app) app.classList.add("ready");
})();
