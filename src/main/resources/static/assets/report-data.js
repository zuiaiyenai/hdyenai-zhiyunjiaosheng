(() => {
  "use strict";

  const REPORT_TITLE = "我的近一周口语测评报告";
  let loading = false;

  function apiBaseUrl() {
    const fallback = window.location.port === "5173" ? "http://localhost:8081" : window.location.origin;
    return (localStorage.getItem("zyjs_api") || fallback).replace(/\/$/, "");
  }

  function numberFrom(record, ...names) {
    const values = Object.fromEntries(
      Object.entries(record || {}).map(([key, value]) => [key.toLowerCase(), value])
    );
    for (const name of names) {
      const value = Number(values[name.toLowerCase()]);
      if (Number.isFinite(value)) return value;
    }
    return null;
  }

  function normalizeHistory(payload) {
    const history = payload?.history ?? payload?.data ?? payload ?? [];
    if (Array.isArray(history)) {
      return history.map((record, index) => ({
        fluency: numberFrom(record, "fluency_score", "fluency"),
        pronunciation: numberFrom(record, "pronunciation_score", "pronunciation"),
        accuracy: numberFrom(record, "accuracy_score", "accuracy", "correctness_rate"),
        createdAt: record.created_at || record.createdAt || null,
        sequence: index + 1
      })).filter(record => [record.fluency, record.pronunciation, record.accuracy].every(Number.isFinite));
    }

    const fluency = history?.fluencyScores || history?.fluency_scores || [];
    const pronunciation = history?.pronunciationScores || history?.pronunciation_scores || [];
    const accuracy = history?.accuracyScores || history?.accuracy_scores || [];
    return fluency.map((value, index) => ({
      fluency: Number(value),
      pronunciation: Number(pronunciation[index]),
      accuracy: Number(accuracy[index]),
      createdAt: null,
      sequence: index + 1
    })).filter(record => [record.fluency, record.pronunciation, record.accuracy].every(Number.isFinite));
  }

  function recordsFromLastWeek(records) {
    const cutoff = Date.now() - 7 * 24 * 60 * 60 * 1000;
    return records.filter(record => {
      if (!record.createdAt) return true;
      const timestamp = new Date(record.createdAt).getTime();
      return Number.isFinite(timestamp) && timestamp >= cutoff;
    });
  }

  function average(values) {
    return values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : 0;
  }

  function dateLabel(value) {
    const date = new Date(value);
    return `${String(date.getMonth() + 1).padStart(2, "0")}/${String(date.getDate()).padStart(2, "0")}`;
  }

  function trendPoints(records) {
    if (records.every(record => record.createdAt)) {
      const groups = new Map();
      records.slice().sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt)).forEach(record => {
        const label = dateLabel(record.createdAt);
        const group = groups.get(label) || [];
        group.push(record);
        groups.set(label, group);
      });
      return [...groups].map(([label, group]) => ({
        label,
        score: average(group.map(record => average([record.fluency, record.pronunciation, record.accuracy]))),
        accuracy: average(group.map(record => record.accuracy))
      }));
    }

    return records.map((record, index) => ({
      label: `第${index + 1}次`,
      score: average([record.fluency, record.pronunciation, record.accuracy]),
      accuracy: record.accuracy
    }));
  }

  function reportSection() {
    return [...document.querySelectorAll(".report-title")]
      .find(title => title.textContent.trim() === REPORT_TITLE)?.closest("section");
  }

  function renderChart(chart, points, valueName) {
    chart.replaceChildren();
    if (!points.length) {
      const empty = document.createElement("p");
      empty.className = "report-empty";
      empty.textContent = "近一周暂无有效评测记录";
      chart.append(empty);
      return;
    }
    points.forEach(point => {
      const value = Math.max(0, Math.min(100, point[valueName]));
      const bar = document.createElement("i");
      bar.style.height = `${value}%`;
      bar.title = `${point.label}：${value.toFixed(1)} 分`;
      const score = document.createElement("strong");
      score.textContent = value.toFixed(1);
      const label = document.createElement("small");
      label.textContent = point.label;
      bar.append(score, label);
      chart.append(bar);
    });
  }

  function suggestion(fluency, pronunciation, accuracy) {
    const weakest = Math.min(fluency, pronunciation, accuracy);
    if (weakest >= 85) return "表现稳定，建议继续保持每日朗读练习";
    if (weakest === fluency) return "优先练习连续朗读，减少停顿并保持语速稳定";
    if (weakest === pronunciation) return "优先练习音节清晰度，并跟读纠正发音";
    return "优先对照原文复读，重点纠正漏读和错读";
  }

  function renderMetrics(container, records) {
    container.replaceChildren();
    const rows = records.length ? [
      ["平均流利度", `${average(records.map(record => record.fluency)).toFixed(1)} 分`],
      ["平均发音得分", `${average(records.map(record => record.pronunciation)).toFixed(1)} 分`],
      ["完成评测", `${records.length} 次`],
      ["提升建议", suggestion(
        average(records.map(record => record.fluency)),
        average(records.map(record => record.pronunciation)),
        average(records.map(record => record.accuracy))
      )]
    ] : [
      ["平均流利度", "--"],
      ["平均发音得分", "--"],
      ["完成评测", "0 次"],
      ["提升建议", "完成一次有效口语评测后生成"]
    ];
    rows.forEach(([label, value]) => {
      const row = document.createElement("p");
      const name = document.createElement("span");
      const result = document.createElement("b");
      name.textContent = label;
      result.textContent = value;
      row.append(name, result);
      container.append(row);
    });
  }

  function renderMessage(section, message) {
    section.querySelectorAll(".chart").forEach(chart => {
      chart.replaceChildren();
      const text = document.createElement("p");
      text.className = "report-empty";
      text.textContent = message;
      chart.append(text);
    });
    const metrics = section.querySelector(".metrics");
    if (metrics) renderMetrics(metrics, []);
  }

  async function refreshReport() {
    const section = reportSection();
    if (!section || loading) return;
    const token = localStorage.getItem("token");
    if (!token) {
      renderMessage(section, "请先登录后查看真实评测数据");
      return;
    }

    loading = true;
    renderMessage(section, "正在加载真实评测数据…");
    try {
      const response = await fetch(`${apiBaseUrl()}/speaking_practice/history`, {
        headers: { Accept: "application/json", Authorization: `Bearer ${token}` }
      });
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(payload.message || `加载失败（${response.status}）`);
      const records = recordsFromLastWeek(normalizeHistory(payload));
      const points = trendPoints(records);
      const charts = section.querySelectorAll(".chart");
      if (charts[0]) renderChart(charts[0], points, "score");
      if (charts[1]) renderChart(charts[1], points, "accuracy");
      const metrics = section.querySelector(".metrics");
      if (metrics) renderMetrics(metrics, records);
    } catch (error) {
      renderMessage(section, error.message || "评测数据加载失败，请稍后重试");
    } finally {
      loading = false;
    }
  }

  function start() {
    const section = reportSection();
    if (!section) return;
    new MutationObserver(() => {
      if (section.style.display !== "none") refreshReport();
    }).observe(section, { attributes: true, attributeFilter: ["style"] });
    if (section.style.display !== "none") refreshReport();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start, { once: true });
  } else {
    start();
  }
})();
