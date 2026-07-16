"""Versioned, declarative metric catalogs embedded into exported Profiles.

These catalogs are data contracts, not executable formulas. Android and the
probe node must recognize every formula/policy id before executing a Profile.
"""

from __future__ import annotations

from copy import deepcopy
from typing import Any


def _target(
    operator: str,
    value: float,
    *,
    compliance: float = 0.95,
    provenance: str = "aneb_product_provisional_v1",
) -> dict[str, Any]:
    return {
        "operator": operator,
        "value": value,
        "required_compliance_ratio": compliance,
        "provenance": provenance,
    }


def _metric(
    metric_id: str,
    label: str,
    domain: str,
    unit: str,
    level: str,
    aggregation: str,
    direction: str,
    *,
    required: bool,
    minimum_samples: int,
    target: dict[str, Any] | None = None,
    formula_id: str | None = None,
    target_role: str = "quality",
) -> dict[str, Any]:
    return {
        "metric_id": metric_id,
        "label": label,
        "domain": domain,
        "unit": unit,
        "measurement_level": level,
        "formula_id": formula_id or metric_id.lower().replace("-", "_") + "-v1",
        "aggregation": aggregation,
        "direction": direction,
        "required_for_score": required,
        "minimum_sample_count": minimum_samples,
        "target_role": target_role,
        "quality_target": target,
    }


TOKEN_SIM_MEASUREMENTS: list[dict[str, Any]] = [
    _metric("TOK-B01", "任务成功率", "business", "ratio", "derived", "ratio", "higher_is_better", required=True, minimum_samples=20, target=_target("gte", 0.99)),
    _metric(
        "TOK-B02", "点击至节点完整接收", "business", "ms", "exact", "p95", "lower_is_better",
        required=True, minimum_samples=3,
        target={
            "operator": "lte_by_workload",
            "values": {"text": 1000, "document_5mib": 6000, "image_10mib": 10000, "video_100mib": 60000},
            "policy_id": "token-upload-deadline-v1",
            "required_compliance_ratio": 0.95,
            "provenance": "aneb_product_provisional_v1",
        },
    ),
    _metric("TOK-B03", "仿真处理时延", "business_model", "ms", "exact", "distribution", "descriptive", required=False, minimum_samples=1, target=None, target_role="model_baseline"),
    _metric(
        "TOK-B04", "端到端 TTFT", "business", "ms", "exact", "p95", "lower_is_better",
        required=False, minimum_samples=10,
        target={"operator": "lte_model_p95_plus_ms", "value": 200, "required_compliance_ratio": 0.95, "provenance": "aneb_product_provisional_v1"},
    ),
    _metric("TOK-B05", "首 Token 超额时延", "business", "ms", "derived", "p95", "lower_is_better", required=True, minimum_samples=10, target=_target("lte", 200), formula_id="ttft-excess-v1"),
    _metric("TOK-B06", "仿真 Token 到达速率", "business", "token/s", "exact", "rolling_1s", "descriptive", required=False, minimum_samples=2, target=None, target_role="live"),
    _metric("TOK-B07", "Token 准时到达率", "business", "ratio", "derived", "ratio", "higher_is_better", required=True, minimum_samples=100, target=_target("gte", 0.95), formula_id="token-on-time-200ms-v1"),
    _metric("TOK-B08", "ITL 残差 P95", "business", "ms", "derived", "p95", "lower_is_better", required=False, minimum_samples=100, target=_target("lte", 100), formula_id="itl-residual-seq-join-v1"),
    _metric("TOK-B09", "卡顿率", "business", "ratio", "derived", "ratio", "lower_is_better", required=True, minimum_samples=100, target=_target("lte", 0.02), formula_id="itl-residual-over-200ms-v1"),
    _metric("TOK-B10", "严重卡顿率", "guardrail", "ratio", "derived", "ratio", "lower_is_better", required=True, minimum_samples=100, target=_target("eq", 0.0), formula_id="itl-residual-over-1000ms-v1"),
    _metric("TOK-B11", "流完整率", "business", "ratio", "derived", "ratio", "higher_is_better", required=True, minimum_samples=100, target=_target("gte", 0.99), formula_id="unique-seq-completeness-v1"),
    _metric(
        "TOK-B12", "返回文件完成时延", "business", "ms", "exact", "p95", "lower_is_better",
        required=False, minimum_samples=3,
        target={"operator": "deadline_by_artifact_size", "policy_id": "artifact-deadline-v1", "required_compliance_ratio": 0.95, "provenance": "aneb_product_provisional_v1"},
    ),
    _metric("TOK-B13", "重连恢复时延", "business", "ms", "exact", "p95", "lower_is_better", required=False, minimum_samples=3, target=_target("lte", 3000)),
    _metric("TOK-B14", "仿真 Token 传输冗余", "efficiency", "ratio", "derived", "ratio", "lower_is_better", required=True, minimum_samples=1, target=_target("lte", 0.05), formula_id="sim-token-retry-overhead-v1"),
    _metric("TOK-B15", "仿真 Token 计划/传输/有效量", "business_model", "token", "exact", "counts", "descriptive", required=False, minimum_samples=1, target=None, target_role="descriptive"),
    _metric("TOK-N01", "DNS 时延", "network", "ms", "exact", "p95", "lower_is_better", required=False, minimum_samples=3, target=_target("lte", 500)),
    _metric("TOK-N02", "TCP/TLS 建连时延", "network", "ms", "exact", "p95_by_stage", "lower_is_better", required=False, minimum_samples=3, target={"operator": "lte_by_stage", "values": {"tcp": 500, "tls": 1000}, "required_compliance_ratio": 0.95, "provenance": "aneb_product_provisional_v1"}),
    _metric("TOK-N03", "应用层 RTT", "network", "ms", "exact", "p95", "lower_is_better", required=True, minimum_samples=20, target=_target("lte", 100)),
    _metric("TOK-N04", "RTT 变化", "network", "ms", "derived", "p95_minus_p50", "lower_is_better", required=True, minimum_samples=20, target=_target("lte", 30)),
    _metric("TOK-N05", "应用请求失败率", "network", "ratio", "derived", "ratio", "lower_is_better", required=True, minimum_samples=20, target=_target("lte", 0.02)),
    _metric(
        "TOK-N06", "上行有效速率", "network", "Mbps", "derived", "p05", "higher_is_better",
        required=True, minimum_samples=5,
        target={"operator": "gte_by_workload", "values": {"text": 1, "document": 10, "image": 12, "video": 20}, "required_compliance_ratio": 0.95, "provenance": "aneb_product_provisional_v1"},
    ),
    _metric("TOK-N07", "下行有效速率", "network", "Mbps", "derived", "p05", "higher_is_better", required=False, minimum_samples=5, target=_target("gte", 25)),
    _metric("TOK-N08", "负载中 RTT", "network", "ms", "exact", "p95", "lower_is_better", required=False, minimum_samples=20, target=_target("lte", 200)),
    _metric("TOK-N09", "负载时延增量", "network", "ms", "derived", "loaded_p95_minus_idle_p50", "lower_is_better", required=False, minimum_samples=20, target=_target("lte", 100)),
    _metric("TOK-N10", "TCP 重传协变量", "network_covariate", "count", "exact", "distribution", "descriptive", required=False, minimum_samples=1, target=None, target_role="covariate"),
    _metric("TOK-R01", "无线层协变量", "radio_covariate", "mixed", "exact", "time_series", "descriptive", required=False, minimum_samples=1, target=None, target_role="covariate"),
]


# Stress 只回答一次明确的大对象容量与负载响应性问题；它不冒充 Standard 的
# 20 个任务/95% 长期稳定性样本。全量目录不变，但评分必需项和最小样本量独立冻结。
TOKEN_STRESS_REQUIRED = {
    "TOK-B01",
    "TOK-B02",
    "TOK-B11",
    "TOK-N05",
    "TOK-N06",
    "TOK-N07",
    "TOK-N08",
    "TOK-N09",
}
TOKEN_STRESS_MEASUREMENTS: list[dict[str, Any]] = deepcopy(TOKEN_SIM_MEASUREMENTS)
for _metric_spec in TOKEN_STRESS_MEASUREMENTS:
    _metric_id = _metric_spec["metric_id"]
    _metric_spec["required_for_score"] = _metric_id in TOKEN_STRESS_REQUIRED
    if _metric_id in {"TOK-B01", "TOK-B02", "TOK-B11", "TOK-N05", "TOK-N06", "TOK-N07"}:
        _metric_spec["minimum_sample_count"] = 1
    elif _metric_id in {"TOK-N08", "TOK-N09"}:
        _metric_spec["minimum_sample_count"] = 20


REALTIME_SIM_MEASUREMENTS: list[dict[str, Any]] = [
    _metric("LIVE-B01", "会话建立成功率", "business", "ratio", "derived", "ratio", "higher_is_better", required=True, minimum_samples=10, target=_target("gte", 0.99)),
    _metric("LIVE-B02", "会话建立时延", "business", "ms", "exact", "p95", "lower_is_better", required=True, minimum_samples=10, target=_target("lte", 2000)),
    _metric("LIVE-B03", "轮次响应时延", "business", "ms", "exact", "p95", "lower_is_better", required=False, minimum_samples=10, target={"operator": "lte_model_p95_plus_ms", "value": 200, "required_compliance_ratio": 0.95, "provenance": "aneb_product_provisional_v1"}),
    _metric("LIVE-B04", "响应超额时延", "business", "ms", "derived", "p95", "lower_is_better", required=True, minimum_samples=10, target=_target("lte", 200), formula_id="realtime-response-excess-v1"),
    _metric("LIVE-B05", "音频准时帧率", "business", "ratio", "derived", "ratio", "higher_is_better", required=True, minimum_samples=500, target=_target("gte", 0.99)),
    _metric("LIVE-B06", "音频卡顿率", "business", "ratio", "derived", "ratio", "lower_is_better", required=True, minimum_samples=500, target=_target("lte", 0.01)),
    _metric("LIVE-B07", "音频掩盖样本率", "business", "ratio", "derived", "ratio", "lower_is_better", required=True, minimum_samples=500, target=_target("lte", 0.01)),
    _metric("LIVE-B08", "打断响应时延", "business", "ms", "exact", "p95", "lower_is_better", required=True, minimum_samples=2, target=_target("lte", 300)),
    _metric("LIVE-B09", "轮次成功率", "business", "ratio", "derived", "ratio", "higher_is_better", required=True, minimum_samples=10, target=_target("gte", 0.99)),
    _metric("LIVE-B10", "会话中断率", "business", "ratio", "derived", "ratio", "lower_is_better", required=True, minimum_samples=10, target=_target("lte", 0.01)),
    _metric("LIVE-B11", "恢复时延", "business", "ms", "exact", "p95", "lower_is_better", required=False, minimum_samples=2, target=_target("lte", 3000)),
    _metric("LIVE-B12", "非计划对讲重叠率", "business", "ratio", "derived", "ratio", "lower_is_better", required=False, minimum_samples=10, target=_target("lte", 0.01)),
    _metric("LIVE-N01", "WebSocket 握手时延", "network", "ms", "exact", "p95", "lower_is_better", required=True, minimum_samples=10, target=_target("lte", 1000)),
    _metric("LIVE-N02", "会话内 RTT", "network", "ms", "exact", "p95", "lower_is_better", required=True, minimum_samples=20, target=_target("lte", 100)),
    _metric("LIVE-N03", "帧到达变化", "network", "ms", "derived", "p95_minus_p50", "lower_is_better", required=True, minimum_samples=100, target=_target("lte", 30)),
    _metric("LIVE-N04", "应用音频帧未返回率", "network", "ratio", "derived", "ratio", "lower_is_better", required=True, minimum_samples=500, target=_target("lte", 0.01)),
    _metric("LIVE-N05", "连续未返回帧最大长度", "network", "frame", "derived", "max", "lower_is_better", required=False, minimum_samples=500, target=_target("lte", 3)),
    _metric("LIVE-N06", "双向持续有效速率", "network", "Mbps", "derived", "p05_by_direction", "higher_is_better", required=False, minimum_samples=20, target=_target("gte", 0.5)),
    _metric("LIVE-N07", "负载中 RTT", "network", "ms", "exact", "p95", "lower_is_better", required=False, minimum_samples=20, target=_target("lte", 150)),
    _metric("LIVE-N08", "重连/迁移事件", "network", "count", "exact", "counts", "descriptive", required=False, minimum_samples=1, target=None, target_role="diagnostic"),
    _metric("LIVE-R01", "无线层协变量", "radio_covariate", "mixed", "exact", "time_series", "descriptive", required=False, minimum_samples=1, target=None, target_role="covariate"),
]


NETWORK_COMPREHENSIVE_MEASUREMENTS: list[dict[str, Any]] = [
    _metric("NET-B01", "下载持续有效速率", "network", "Mbps", "derived", "p05", "higher_is_better", required=True, minimum_samples=10, target=_target("gte", 25)),
    _metric("NET-B02", "上传持续有效速率", "network", "Mbps", "derived", "p05", "higher_is_better", required=True, minimum_samples=10, target=_target("gte", 10)),
    _metric("NET-B03", "空闲 RTT", "network", "ms", "exact", "p95", "lower_is_better", required=True, minimum_samples=20, target=_target("lte", 100)),
    _metric("NET-B04", "负载中 RTT", "network", "ms", "exact", "p95", "lower_is_better", required=True, minimum_samples=20, target=_target("lte", 200)),
    _metric("NET-B05", "负载时延增量", "network", "ms", "derived", "loaded_p95_minus_idle_p50", "lower_is_better", required=True, minimum_samples=20, target=_target("lte", 100)),
    _metric("NET-B06", "RTT 变化", "network", "ms", "derived", "p95_minus_p50", "lower_is_better", required=True, minimum_samples=20, target=_target("lte", 30)),
    _metric("NET-B07", "吞吐稳定性", "network", "ratio", "derived", "robust_cv", "lower_is_better", required=True, minimum_samples=10, target=_target("lte", 0.20)),
    _metric("NET-B08", "低速窗口率", "network", "ratio", "derived", "ratio", "lower_is_better", required=False, minimum_samples=10, target=_target("lte", 0.05)),
    _metric("NET-B09", "应用请求失败率", "network", "ratio", "derived", "ratio", "lower_is_better", required=True, minimum_samples=20, target=_target("lte", 0.02)),
    _metric("NET-B10", "UDP 数据报未返回率", "network", "ratio", "derived", "ratio", "lower_is_better", required=True, minimum_samples=100, target=_target("lte", 0.01)),
    _metric("NET-B11", "UDP 乱序率", "network", "ratio", "derived", "ratio", "lower_is_better", required=False, minimum_samples=100, target=_target("lte", 0.005)),
    _metric("NET-B12", "DNS/TCP/TLS 时延", "network", "ms", "exact", "p95_by_stage", "lower_is_better", required=False, minimum_samples=3, target={"operator": "lte_by_stage", "values": {"dns": 500, "tcp": 500, "tls": 1000}, "required_compliance_ratio": 0.95, "provenance": "aneb_product_provisional_v1"}),
    _metric("NET-R01", "无线层协变量", "radio_covariate", "mixed", "exact", "time_series", "descriptive", required=False, minimum_samples=1, target=None, target_role="covariate"),
]


CATALOGS: dict[str, list[dict[str, Any]]] = {
    "token-sim-measurements-v1": TOKEN_SIM_MEASUREMENTS,
    "token-sim-measurements-v1-draft": TOKEN_SIM_MEASUREMENTS,
    "token-stress-measurements-v1": TOKEN_STRESS_MEASUREMENTS,
    "realtime-sim-measurements-v1": REALTIME_SIM_MEASUREMENTS,
    "realtime-sim-measurements-v1-draft": REALTIME_SIM_MEASUREMENTS,
    "network-comprehensive-measurements-v1-draft": NETWORK_COMPREHENSIVE_MEASUREMENTS,
}


def metric_catalog(catalog_id: str) -> list[dict[str, Any]]:
    try:
        return deepcopy(CATALOGS[catalog_id])
    except KeyError as error:
        raise ValueError(f"unknown measurement_catalog_id: {catalog_id}") from error
