(() => {
  "use strict";

  const app = document.querySelector("#future-app");
  const toast = document.querySelector("#future-toast");
  let toastTimer = null;

  function showToast(message) {
    if (!toast) return;
    window.clearTimeout(toastTimer);
    toast.textContent = message;
    toast.classList.add("show");
    toastTimer = window.setTimeout(() => toast.classList.remove("show"), 1700);
  }

  document.addEventListener("click", (event) => {
    const target = event.target.closest("[data-future-toast]");
    if (target) showToast(target.dataset.futureToast);
  });

  const probeStart = document.querySelector("#probe-start");
  if (probeStart) {
    const score = document.querySelector("#probe-score");
    const status = document.querySelector("#probe-status");
    const endpointCards = [...document.querySelectorAll("[data-endpoint]")];
    let scanInterval = null;

    probeStart.addEventListener("click", () => {
      if (app.classList.contains("scanning")) return;
      app.classList.remove("complete");
      app.classList.add("scanning");
      probeStart.disabled = true;
      score.textContent = "···";
      status.textContent = "正在探测 4 个端点";
      let progress = 0;
      window.clearInterval(scanInterval);
      scanInterval = window.setInterval(() => {
        progress = Math.min(94, progress + 7 + Math.random() * 10);
        probeStart.style.setProperty("--probe-progress", `${progress * 3.6}deg`);
      }, 150);

      window.setTimeout(() => {
        window.clearInterval(scanInterval);
        probeStart.style.setProperty("--probe-progress", "331deg");
        app.classList.remove("scanning");
        app.classList.add("complete");
        probeStart.disabled = false;
        score.textContent = "92";
        status.textContent = "综合可用性";
        document.querySelector("#available-count").textContent = "3/4";
        document.querySelector("#average-latency").innerHTML = "116<small>ms</small>";
        document.querySelector("#incident-count").textContent = "1";
        endpointCards.forEach((card, index) => {
          card.animate(
            [{ opacity: .35, transform: "translateX(-5px)" }, { opacity: 1, transform: "translateX(0)" }],
            { duration: 380, delay: index * 90, easing: "cubic-bezier(.22,1,.36,1)" }
          );
        });
      }, 1850);
    });
  }

  const mapControls = [...document.querySelectorAll("[data-map-layer]")];
  mapControls.forEach((control) => {
    control.addEventListener("click", () => {
      mapControls.forEach((item) => item.classList.toggle("active", item === control));
      app.dataset.layer = control.dataset.mapLayer;
      showToast(control.dataset.mapLayer === "quality" ? "已显示 AI 体验评分" : "已显示网络延迟热力");
    });
  });

  const mapSheet = document.querySelector("#map-sheet");
  const mapSheetButton = document.querySelector("[data-map-sheet]");
  if (mapSheet && mapSheetButton) {
    mapSheetButton.addEventListener("click", () => {
      const expanded = mapSheet.classList.toggle("expanded");
      mapSheetButton.setAttribute("aria-label", expanded ? "收起地图详情" : "展开地图详情");
      mapSheetButton.setAttribute("aria-expanded", String(expanded));
    });
  }
})();
