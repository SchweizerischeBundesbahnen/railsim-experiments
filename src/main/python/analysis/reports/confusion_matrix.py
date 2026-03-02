"""
analysis/reports/confusion_matrix.py
======================================
Export an interactive HTML confusion matrix report.

Compares theoretical utilisation (reference) against each delay/utilisation KPI.
One section per use case (aggregated) and one per (use_case, building_block,
operating_mode) combination.
"""

from __future__ import annotations

from collections.abc import Mapping
from pathlib import Path

import pandas as pd

# ── Constants ──────────────────────────────────────────────────────────────────

REF_KEY = "utilisation_theorical"
REF_LABEL = "Utilisation théorique"

# KPIs compared against the theoretical utilisation reference.
KPI_CONFIG = [
    {
        "key": "q2",
        "label": "q2d0 — Delay 2nd pct = 0",
        "is_green": lambda v, t=None: v == 0,
    },
    {
        "key": "q2",
        "label": "q2d2 — Delay 2nd pct ≤ 2s",
        "is_green": lambda v, t=None: v <= 2,
    },
    {
        "key": "q5",
        "label": "q5d5 — Delay 5th pct ≤ 5s",
        "is_green": lambda v, t=None: v <= 5,
    },
    {
        "key": "q10",
        "label": "q10d5 — Delay 10th pct ≤ 5s",
        "is_green": lambda v, t=None: v <= 5,
    },
    {
        "key": "utilization_median",
        "label": "Utilisation médiane",
        "is_green": lambda v, t=0.7: v <= t,
    },
]


# ── Helpers ────────────────────────────────────────────────────────────────────


def _confusion_data(df: pd.DataFrame, kpi: dict, threshold: float = 0.7) -> dict:
    """Compute TP / FP / FN / TN counts for one KPI vs the reference."""
    ref_ok = df[REF_KEY].apply(lambda v: pd.notna(v) and v <= threshold)
    kpi_ok = df.apply(
        lambda r: pd.notna(r[kpi["key"]]) and kpi["is_green"](r[kpi["key"]], threshold),
        axis=1,
    )
    tp = int((ref_ok & kpi_ok).sum())
    fp = int((~ref_ok & kpi_ok).sum())
    fn = int((ref_ok & ~kpi_ok).sum())
    tn = int((~ref_ok & ~kpi_ok).sum())
    total = tp + fp + fn + tn
    pct = lambda n: f"{100 * n / total:.1f}%" if total > 0 else "—"
    return {
        "tp": tp, "fp": fp, "fn": fn, "tn": tn,
        "tp_pct": pct(tp), "fp_pct": pct(fp),
        "fn_pct": pct(fn), "tn_pct": pct(tn),
        "total": total,
    }


def _matrix_html(cm: dict, kpi_label: str, threshold: float = 0.7) -> str:
    """Render one confusion matrix as an HTML snippet."""

    def cell(count, pct, bg, text_color, sublabel):
        return f"""
        <td style="background:{bg};color:{text_color};padding:14px 18px;text-align:center;
                   border:1px solid #e5e7eb;min-width:130px;vertical-align:top;">
          <div style="font-size:11px;font-weight:700;text-transform:uppercase;
                      letter-spacing:0.07em;opacity:0.7;margin-bottom:4px;">{sublabel}</div>
          <div style="font-size:22px;font-weight:700;font-family:monospace;">{count}</div>
          <div style="font-size:12px;margin-top:3px;opacity:0.8;">{pct}</div>
        </td>"""

    return f"""
    <div style="margin-bottom:20px;">
      <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px;">
        <div style="font-size:13px;font-weight:700;color:#334155;">
          vs <span style="color:#6366f1">{kpi_label}</span>
          <span style="font-size:11px;color:#94a3b8;font-weight:400;margin-left:8px;">
            threshold = {threshold:.0%}
          </span>
        </div>
        <button onclick="exportTable(this)"
          style="font-size:11px;font-weight:600;padding:5px 12px;border-radius:6px;
                 border:1px solid #cbd5e1;background:#f8fafc;color:#334155;cursor:pointer;">
          ⬇ Export PNG
        </button>
      </div>
      <div style="overflow-x:auto;">
        <table style="border-collapse:collapse;font-family:'Segoe UI',sans-serif;">
          <thead>
            <tr>
              <th style="background:#f8fafc;border:1px solid #e5e7eb;padding:8px 14px;
                         font-size:11px;color:#64748b;text-transform:uppercase;letter-spacing:0.07em;">
                Predicted \\ Actual
              </th>
              <th style="background:#f8fafc;border:1px solid #e5e7eb;padding:8px 14px;
                         font-size:11px;color:#065f46;text-transform:uppercase;letter-spacing:0.07em;">
                {REF_LABEL} ✓
              </th>
              <th style="background:#f8fafc;border:1px solid #e5e7eb;padding:8px 14px;
                         font-size:11px;color:#991b1b;text-transform:uppercase;letter-spacing:0.07em;">
                {REF_LABEL} ✗
              </th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td style="background:#f8fafc;border:1px solid #e5e7eb;padding:8px 14px;
                         font-size:11px;color:#065f46;font-weight:700;text-transform:uppercase;
                         letter-spacing:0.07em;white-space:nowrap;">{kpi_label} ✓</td>
              {cell(cm['tp'], cm['tp_pct'], '#d1fae5', '#065f46', 'True Positive')}
              {cell(cm['fp'], cm['fp_pct'], '#fee2e2', '#713f12', 'False Positive')}
            </tr>
            <tr>
              <td style="background:#f8fafc;border:1px solid #e5e7eb;padding:8px 14px;
                         font-size:11px;color:#991b1b;font-weight:700;text-transform:uppercase;
                         letter-spacing:0.07em;white-space:nowrap;">{kpi_label} ✗</td>
              {cell(cm['fn'], cm['fn_pct'], '#fee2e2', '#713f12', 'False Negative')}
              {cell(cm['tn'], cm['tn_pct'], '#d1fae5', '#065f46', 'True Negative')}
            </tr>
          </tbody>
        </table>
      </div>
    </div>"""


# ── Public function ────────────────────────────────────────────────────────────


def export_confusion_matrix_html(
    df: pd.DataFrame,
    run_id: str,
    *,
    thresholds_by_operating_mode: Mapping[str, float] | None = None,
    default_utilisation_threshold: float = 0.7,
    output_path: str | Path = "confusion_matrix.html",
) -> None:
    """Export an interactive HTML confusion matrix report.

    Parameters
    ----------
    df:
        DataFrame with columns: use_case, building_block, operating_mode,
        q2, q5, q10, utilization_median, utilisation_theorical.
    run_id:
        Run identifier shown in the page title.
    thresholds_by_operating_mode:
        Optional dict mapping operating_mode → utilisation threshold.
    default_utilisation_threshold:
        Fallback threshold when the operating mode is not in the dict above.
    output_path:
        Destination path for the HTML file.
    """

    def _get_threshold(om: str) -> float:
        if thresholds_by_operating_mode and om in thresholds_by_operating_mode:
            return thresholds_by_operating_mode[om]
        return default_utilisation_threshold

    sections_html = []

    # Aggregated section per use case
    for uc, uc_df in df.groupby("use_case"):
        threshold = default_utilisation_threshold
        matrices = "".join(
            _matrix_html(_confusion_data(uc_df, kpi, threshold), kpi["label"], threshold)
            for kpi in KPI_CONFIG
        )
        sections_html.append(
            f"""
        <div class="section" data-uc="{uc}" data-bb="__uc__" data-om="__uc__">
          <div class="section-header">
            <div class="tag"><span class="tag-label">Use Case</span>
              <span class="tag-value tag-uc">{uc}</span></div>
            <div class="tag"><span class="tag-label">Scope</span>
              <span class="tag-value tag-scope">All blocks &amp; modes</span></div>
          </div>
          <div class="section-body">{matrices}</div>
        </div>"""
        )

    # Detailed section per (use_case, building_block, operating_mode)
    for (uc, bb, om), grp in df.groupby(["use_case", "building_block", "operating_mode"]):
        threshold = _get_threshold(om)
        matrices = "".join(
            _matrix_html(_confusion_data(grp, kpi, threshold), kpi["label"], threshold)
            for kpi in KPI_CONFIG
        )
        sections_html.append(
            f"""
        <div class="section" data-uc="{uc}" data-bb="{bb}" data-om="{om}">
          <div class="section-header">
            <div class="tag"><span class="tag-label">Use Case</span>
              <span class="tag-value tag-uc">{uc}</span></div>
            <div class="tag"><span class="tag-label">Building Block</span>
              <span class="tag-value tag-bb">{bb}</span></div>
            <div class="tag"><span class="tag-label">Operating Mode</span>
              <span class="tag-value tag-om">{om}</span></div>
            <div class="tag"><span class="tag-label">Threshold</span>
              <span class="tag-value tag-scope">{threshold:.0%}</span></div>
          </div>
          <div class="section-body">{matrices}</div>
        </div>"""
        )

    use_cases = sorted(df["use_case"].unique().tolist())
    building_blocks = sorted(df["building_block"].unique().tolist())
    operating_modes = sorted(df["operating_mode"].unique().tolist())
    all_sections = "\n".join(sections_html)

    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<title>RailSim — {run_id} — Confusion Matrices</title>
<style>
  * {{ box-sizing: border-box; margin: 0; padding: 0; }}
  body {{ font-family: 'Segoe UI', sans-serif; background: #f1f5f9; color: #0f172a; padding: 32px 24px; }}
  h1 {{ font-size: 22px; font-weight: 700; margin-bottom: 6px; }}
  .subtitle {{ color: #64748b; font-size: 14px; margin-bottom: 24px; }}

  .filters {{ display: flex; gap: 16px; flex-wrap: wrap; margin-bottom: 28px; }}
  .filter-group {{ display: flex; flex-direction: column; gap: 4px; }}
  .filter-group label {{ font-size: 11px; font-weight: 700; text-transform: uppercase;
    letter-spacing: 0.07em; color: #64748b; }}
  .filter-group select {{ padding: 8px 12px; border: 1px solid #cbd5e1; border-radius: 8px;
    font-size: 13px; background: white; color: #0f172a; cursor: pointer; min-width: 160px; }}
  .filter-group select:focus {{ outline: none; border-color: #6366f1;
    box-shadow: 0 0 0 3px #6366f133; }}
  .filter-group input[type=checkbox] {{ cursor: pointer; width: 15px; height: 15px; }}
  .checkbox-label {{ display: flex; align-items: center; gap: 8px; font-size: 13px;
    color: #334155; cursor: pointer; padding: 8px 0; }}

  .sections-container {{ display: flex; flex-direction: column; gap: 28px; }}
  .section {{ background: white; border-radius: 12px; overflow: hidden;
    box-shadow: 0 1px 3px rgba(0,0,0,0.08), 0 4px 16px rgba(0,0,0,0.06);
    border: 1px solid #e5e7eb; }}
  .section-header {{ background: #1e293b; padding: 14px 20px; display: flex; gap: 12px;
    align-items: center; flex-wrap: wrap; }}
  .section-body {{ padding: 20px; display: flex; gap: 28px; flex-wrap: wrap; }}

  .tag {{ display: flex; align-items: center; gap: 6px; }}
  .tag-label {{ color: #94a3b8; font-size: 11px; font-weight: 600;
    letter-spacing: 0.06em; text-transform: uppercase; }}
  .tag-value {{ border-radius: 6px; padding: 2px 10px; font-size: 13px; font-weight: 700; }}
  .tag-uc    {{ background: #6366f122; color: #818cf8; border: 1px solid #6366f144; }}
  .tag-bb    {{ background: #0ea5e922; color: #38bdf8; border: 1px solid #0ea5e944; }}
  .tag-om    {{ background: #f59e0b22; color: #fbbf24; border: 1px solid #f59e0b44; }}
  .tag-scope {{ background: #10b98122; color: #34d399; border: 1px solid #10b98144; }}

  .no-results {{ text-align: center; padding: 48px; color: #94a3b8; font-size: 15px; }}
  .legend {{ display: flex; gap: 16px; flex-wrap: wrap; margin-bottom: 24px; }}
  .legend-item {{ display: flex; align-items: center; gap: 7px; font-size: 12px; color: #475569; }}
  .legend-dot {{ width: 14px; height: 14px; border-radius: 3px; }}
</style>
</head>
<body>

<h1>RailSim — {run_id} — Confusion Matrices</h1>
<p class="subtitle">Comparing <strong>{REF_LABEL}</strong> against each KPI — threshold varies per operating mode</p>

<div class="filters">
  <div class="filter-group">
    <label>Use Case</label>
    <select id="filter-uc" onchange="applyFilters()">
      <option value="all">All</option>
      {"".join(f'<option value="{v}">{v}</option>' for v in use_cases)}
    </select>
  </div>
  <div class="filter-group">
    <label>Building Block</label>
    <select id="filter-bb" onchange="applyFilters()">
      <option value="all">All</option>
      {"".join(f'<option value="{v}">{v}</option>' for v in building_blocks)}
    </select>
  </div>
  <div class="filter-group">
    <label>Operating Mode</label>
    <select id="filter-om" onchange="applyFilters()">
      <option value="all">All</option>
      {"".join(f'<option value="{v}">{v}</option>' for v in operating_modes)}
    </select>
  </div>
  <div class="filter-group">
    <label>Scope</label>
    <label class="checkbox-label">
      <input type="checkbox" id="show-uc-only" onchange="applyFilters()" checked>
      Show per use case (aggregated)
    </label>
    <label class="checkbox-label">
      <input type="checkbox" id="show-detailed" onchange="applyFilters()" checked>
      Show per use case / block / mode
    </label>
  </div>
</div>

<div class="legend">
  <div class="legend-item"><div class="legend-dot" style="background:#d1fae5;border:1.5px solid #10b981"></div>Both within threshold</div>
  <div class="legend-item"><div class="legend-dot" style="background:#fee2e2;border:1.5px solid #ef4444"></div>Both exceed threshold</div>
  <div class="legend-item"><div class="legend-dot" style="background:#fef9c3;border:1.5px solid #ca8a04"></div>Disagreement</div>
</div>

<div class="sections-container" id="sections">
{all_sections}
</div>

<script src="https://cdnjs.cloudflare.com/ajax/libs/html2canvas/1.4.1/html2canvas.min.js"></script>
<script>
function applyFilters() {{
  const uc = document.getElementById("filter-uc").value;
  const bb = document.getElementById("filter-bb").value;
  const om = document.getElementById("filter-om").value;
  const showUcOnly   = document.getElementById("show-uc-only").checked;
  const showDetailed = document.getElementById("show-detailed").checked;

  const sections = document.querySelectorAll(".section");
  let visible = 0;

  sections.forEach(s => {{
    const sUc = s.dataset.uc;
    const sBb = s.dataset.bb;
    const sOm = s.dataset.om;
    const isUcLevel = sBb === "__uc__";

    const ucMatch    = uc === "all" || sUc === uc;
    const bbMatch    = isUcLevel || bb === "all" || sBb === bb;
    const omMatch    = isUcLevel || om === "all" || sOm === om;
    const scopeMatch = isUcLevel ? showUcOnly : showDetailed;

    const show = ucMatch && bbMatch && omMatch && scopeMatch;
    s.style.display = show ? "" : "none";
    if (show) visible++;
  }});

  const container = document.getElementById("sections");
  const existing = container.querySelector(".no-results");
  if (visible === 0) {{
    if (!existing) {{
      const div = document.createElement("div");
      div.className = "no-results";
      div.textContent = "No results match the selected filters.";
      container.appendChild(div);
    }}
  }} else if (existing) {{
    existing.remove();
  }}
}}

function exportTable(btn) {{
  const container = btn.closest("div[style*='margin-bottom:20px']");
  html2canvas(container, {{ backgroundColor: "#ffffff", scale: 2 }}).then(canvas => {{
    const link = document.createElement("a");
    link.download = "confusion_matrix.png";
    link.href = canvas.toDataURL("image/png");
    link.click();
  }});
}}
</script>
</body>
</html>"""

    Path(output_path).write_text(html, encoding="utf-8")
    print(f"  → confusion_matrix.html saved to {output_path}")
