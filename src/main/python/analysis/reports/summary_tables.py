"""
analysis/reports/summary_tables.py
====================================
Export an interactive HTML summary table.

One table per (use_case, building_block, operating_mode) combination.
Rows = KPI metrics; columns = tested volumes.
Cells are green (within threshold) or red (exceeds threshold).
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from pathlib import Path

import pandas as pd

# ── KPI row definitions ────────────────────────────────────────────────────────
# Each entry maps a DataFrame column to a label, a green-condition, and a formatter.

ROW_CONFIG = [
    {
        "key": "q2",
        "label": "q2d0 — Delay 2nd pct = 0 (s)",
        "is_green": lambda v, t=None: v == 0,
        "format": lambda v: f"{v:.1f}" if pd.notna(v) else "—",
    },
    {
        "key": "q2",
        "label": "q2d2 — Delay 2nd pct ≤ 2 (s)",
        "is_green": lambda v, t=None: v <= 2,
        "format": lambda v: f"{v:.1f}" if pd.notna(v) else "—",
    },
    {
        "key": "q5",
        "label": "q5d5 — Delay 5th pct ≤ 5 (s)",
        "is_green": lambda v, t=None: v <= 5,
        "format": lambda v: f"{v:.1f}" if pd.notna(v) else "—",
    },
    {
        "key": "q10",
        "label": "q10d5 — Delay 10th pct ≤ 5 (s)",
        "is_green": lambda v, t=None: v <= 5,
        "format": lambda v: f"{v:.1f}" if pd.notna(v) else "—",
    },
    {
        "key": "utilisation_theorical",
        "label": "Utilisation théorique",
        "is_green": lambda v, t=0.7: v <= t,
        "format": lambda v: f"{v * 100:.1f}%" if pd.notna(v) else "—",
    },
    {
        "key": "utilization_median",
        "label": "Utilisation médiane",
        "is_green": lambda v, t=0.7: v <= t,
        "format": lambda v: f"{v * 100:.1f}%" if pd.notna(v) else "—",
    },
]


# ── Public function ────────────────────────────────────────────────────────────


def export_summary_tables_html(
    df: pd.DataFrame,
    run_id: str,
    *,
    thresholds_by_operating_mode: Mapping[str, float] | None = None,
    default_utilisation_threshold: float = 0.7,
    output_path: str | Path = "summary_tables.html",
) -> None:
    """Export an interactive HTML report with filterable summary tables.

    Parameters
    ----------
    df:
        DataFrame with columns: use_case, building_block, operating_mode,
        volume, q2, q5, q10, utilisation_theorical, utilization_median.
    run_id:
        Run identifier shown in the page title.
    thresholds_by_operating_mode:
        Optional dict mapping operating_mode → utilisation threshold.
    default_utilisation_threshold:
        Fallback threshold when the operating mode is not in the dict above.
    output_path:
        Destination path for the HTML file.
    """

    def _get_threshold(operating_mode: str) -> float:
        if thresholds_by_operating_mode and operating_mode in thresholds_by_operating_mode:
            return thresholds_by_operating_mode[operating_mode]
        return default_utilisation_threshold

    # Build serialisable records for the embedded JS DATA array.
    records = []
    for _, group_df in df.groupby(["use_case", "building_block", "operating_mode"]):
        group_df = group_df.sort_values("volume")
        rec = group_df.iloc[0]
        operating_mode = rec["operating_mode"]
        threshold = _get_threshold(operating_mode)

        rows = []
        for row_conf in ROW_CONFIG:
            cells = []
            for _, r in group_df.iterrows():
                val = r[row_conf["key"]]
                cells.append(
                    {
                        "text": row_conf["format"](val),
                        "green": bool(pd.notna(val) and row_conf["is_green"](val, threshold)),
                    }
                )
            rows.append({"label": row_conf["label"], "cells": cells})

        records.append(
            {
                "use_case": rec["use_case"],
                "building_block": rec["building_block"],
                "operating_mode": operating_mode,
                "threshold": f"{threshold:.0%}",
                "volumes": group_df["volume"].tolist(),
                "rows": rows,
            }
        )

    use_cases = sorted(df["use_case"].unique().tolist())
    building_blocks = sorted(df["building_block"].unique().tolist())
    operating_modes = sorted(df["operating_mode"].unique().tolist())

    data_json = json.dumps(records)
    use_cases_json = json.dumps(use_cases)
    building_blocks_json = json.dumps(building_blocks)
    operating_modes_json = json.dumps(operating_modes)

    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<title>RailSim — {run_id} — Delay & Utilisation Summary</title>
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
  .filter-group select:focus {{ outline: none; border-color: #6366f1; box-shadow: 0 0 0 3px #6366f133; }}

  .tables-container {{ display: flex; flex-direction: column; gap: 28px; }}

  .table-block {{ background: white; border-radius: 12px; overflow: hidden;
    box-shadow: 0 1px 3px rgba(0,0,0,0.08), 0 4px 16px rgba(0,0,0,0.06);
    border: 1px solid #e5e7eb; }}
  .table-header {{ background: #1e293b; padding: 14px 20px; display: flex; gap: 12px;
    align-items: center; flex-wrap: wrap; }}
  .tag {{ display: flex; align-items: center; gap: 6px; }}
  .tag-label {{ color: #94a3b8; font-size: 11px; font-weight: 600;
    letter-spacing: 0.06em; text-transform: uppercase; }}
  .tag-value {{ border-radius: 6px; padding: 2px 10px; font-size: 13px; font-weight: 700; }}
  .tag-uc    {{ background: #6366f122; color: #818cf8; border: 1px solid #6366f144; }}
  .tag-bb    {{ background: #0ea5e922; color: #38bdf8; border: 1px solid #0ea5e944; }}
  .tag-om    {{ background: #f59e0b22; color: #fbbf24; border: 1px solid #f59e0b44; }}
  .tag-threshold {{ background: #10b98122; color: #34d399; border: 1px solid #10b98144; }}

  .table-wrap {{ overflow-x: auto; }}
  table {{ width: 100%; border-collapse: collapse; }}
  thead th {{ background: #f8fafc; color: #1e293b; font-size: 12px; font-weight: 700;
    text-align: center; padding: 10px 14px; border: 1px solid #e5e7eb; }}
  thead th.vol-header {{ min-width: 90px; }}
  thead th.metric-header {{ text-align: left; min-width: 210px; color: #64748b;
    font-size: 11px; letter-spacing: 0.08em; text-transform: uppercase; }}
  tbody td {{ border: 1px solid #e5e7eb; padding: 10px 14px; font-size: 13px; }}
  tbody td.row-label {{ background: #f8fafc; color: #334155; font-weight: 500; }}
  tbody td.cell-green {{ background: #d1fae5; color: #065f46; font-weight: 700;
    text-align: center; font-family: monospace; }}
  tbody td.cell-red {{ background: #fee2e2; color: #991b1b; font-weight: 700;
    text-align: center; font-family: monospace; }}

  .legend {{ display: flex; gap: 16px; padding: 10px 16px; background: #f8fafc;
    border-top: 1px solid #e5e7eb; }}
  .legend-item {{ display: flex; align-items: center; gap: 6px; font-size: 12px; color: #475569; }}
  .legend-dot {{ width: 12px; height: 12px; border-radius: 3px; }}

  .no-results {{ text-align: center; padding: 48px; color: #94a3b8; font-size: 15px; }}
</style>
</head>
<body>

<h1>RailSim — {run_id} — Delay & Utilisation Summary</h1>
<p class="subtitle">One table per (use case, building block, operating mode) combination</p>

<div class="filters">
  <div class="filter-group">
    <label>Use Case</label>
    <select id="filter-uc" onchange="render()">
      <option value="all">All</option>
    </select>
  </div>
  <div class="filter-group">
    <label>Building Block</label>
    <select id="filter-bb" onchange="render()">
      <option value="all">All</option>
    </select>
  </div>
  <div class="filter-group">
    <label>Operating Mode</label>
    <select id="filter-om" onchange="render()">
      <option value="all">All</option>
    </select>
  </div>
</div>

<div class="tables-container" id="tables"></div>

<script>
const DATA = {data_json};
const USE_CASES = {use_cases_json};
const BUILDING_BLOCKS = {building_blocks_json};
const OPERATING_MODES = {operating_modes_json};

function populateSelect(id, values) {{
  const sel = document.getElementById(id);
  values.forEach(v => {{
    const opt = document.createElement("option");
    opt.value = v;
    opt.textContent = v;
    sel.appendChild(opt);
  }});
}}

function render() {{
  const uc = document.getElementById("filter-uc").value;
  const bb = document.getElementById("filter-bb").value;
  const om = document.getElementById("filter-om").value;

  const filtered = DATA.filter(d =>
    (uc === "all" || d.use_case === uc) &&
    (bb === "all" || d.building_block === bb) &&
    (om === "all" || d.operating_mode === om)
  );

  const container = document.getElementById("tables");

  if (filtered.length === 0) {{
    container.innerHTML = '<div class="no-results">No results match the selected filters.</div>';
    return;
  }}

  container.innerHTML = filtered.map(d => `
    <div class="table-block">
      <div class="table-header">
        <div class="tag"><span class="tag-label">Use Case</span>
          <span class="tag-value tag-uc">${{d.use_case}}</span></div>
        <div class="tag"><span class="tag-label">Building Block</span>
          <span class="tag-value tag-bb">${{d.building_block}}</span></div>
        <div class="tag"><span class="tag-label">Operating Mode</span>
          <span class="tag-value tag-om">${{d.operating_mode}}</span></div>
        <div class="tag"><span class="tag-label">Util. Threshold</span>
          <span class="tag-value tag-threshold">${{d.threshold}}</span></div>
      </div>
      <div style="display:flex;justify-content:flex-end;padding:8px 16px;background:#f8fafc;border-bottom:1px solid #e5e7eb;">
        <button onclick="exportTable(this)"
          style="font-size:11px;font-weight:600;padding:5px 12px;border-radius:6px;
                 border:1px solid #cbd5e1;background:white;color:#334155;cursor:pointer;">
          ⬇ Export PNG
        </button>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th class="metric-header">Metric</th>
              ${{d.volumes.map(v => `<th class="vol-header">
                <div style="color:#94a3b8;font-size:10px;font-weight:600;text-transform:uppercase;">Volume</div>
                <div style="font-size:15px;margin-top:2px">${{v}}</div>
              </th>`).join("")}}
            </tr>
          </thead>
          <tbody>
            ${{d.rows.map(row => `
              <tr>
                <td class="row-label">${{row.label}}</td>
                ${{row.cells.map(cell => `<td class="${{cell.green ? 'cell-green' : 'cell-red'}}">${{cell.text}}</td>`).join("")}}
              </tr>
            `).join("")}}
          </tbody>
        </table>
      </div>
      <div class="legend">
        <div class="legend-item"><div class="legend-dot" style="background:#d1fae5;border:1.5px solid #10b981"></div>Within threshold</div>
        <div class="legend-item"><div class="legend-dot" style="background:#fee2e2;border:1.5px solid #ef4444"></div>Exceeds threshold</div>
      </div>
    </div>
  `).join("");
}}

populateSelect("filter-uc", USE_CASES);
populateSelect("filter-bb", BUILDING_BLOCKS);
populateSelect("filter-om", OPERATING_MODES);
render();
</script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/html2canvas/1.4.1/html2canvas.min.js"></script>
<script>
function exportTable(btn) {{
  const container = btn.closest(".table-block");
  html2canvas(container, {{ backgroundColor: "#ffffff", scale: 2 }}).then(canvas => {{
    const link = document.createElement("a");
    link.download = "summary_table.png";
    link.href = canvas.toDataURL("image/png");
    link.click();
  }});
}}
</script>
</body>
</html>"""

    Path(output_path).write_text(html, encoding="utf-8")
    print(f"  → summary_tables.html saved to {output_path}")
