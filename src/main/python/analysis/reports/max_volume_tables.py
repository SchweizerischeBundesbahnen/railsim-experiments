"""
analysis/reports/max_volume_tables.py
=======================================
Export an interactive HTML report showing the maximum accepted volume
per (operating_mode, building_block), one table per use case.

"Max accepted volume" = the highest volume where the chosen KPI still
meets its threshold, assuming monotonic degradation as volume increases.
"""

from __future__ import annotations

from collections.abc import Mapping
from pathlib import Path

import pandas as pd


def export_max_volume_tables_html(
    df: pd.DataFrame,
    run_id: str,
    *,
    kpi: str = "q5",
    threshold: float = 5.0,
    thresholds_by_operating_mode: Mapping[str, float] | None = None,
    default_utilisation_threshold: float = 0.7,
    output_path: str | Path = "max_volume_tables.html",
) -> None:
    """Export an HTML report with the maximum accepted volume per scenario.

    Parameters
    ----------
    df:
        DataFrame with columns: use_case, building_block, operating_mode, volume, {kpi}.
    run_id:
        Run identifier shown in the page title.
    kpi:
        KPI column to evaluate (default: "q5").
    threshold:
        Threshold for delay-based KPIs (default: 5.0 s).
    thresholds_by_operating_mode:
        Optional dict mapping operating_mode → utilisation threshold.
        Only used when *kpi* is a utilisation column.
    default_utilisation_threshold:
        Fallback utilisation threshold (default: 0.7).
    output_path:
        Destination path for the HTML file.
    """

    UTILISATION_KPIS = {"utilisation_theorical", "utilization_median"}

    def _get_threshold(om: str) -> float:
        if kpi in UTILISATION_KPIS:
            if thresholds_by_operating_mode and om in thresholds_by_operating_mode:
                return thresholds_by_operating_mode[om]
            return default_utilisation_threshold
        return threshold

    def max_accepted_volume(group: pd.DataFrame, thr: float) -> int | None:
        """Return the highest contiguous volume where kpi ≤ thr, or None."""
        accepted = group[group[kpi] <= thr].sort_values("volume")
        if accepted.empty:
            return None
        all_volumes = sorted(group["volume"].unique())
        accepted_volumes = sorted(accepted["volume"].unique())
        max_vol = accepted_volumes[-1]
        for v in reversed(all_volumes):
            if v > max_vol:
                continue
            if v not in accepted_volumes:
                max_vol = None
                break
            max_vol = v
            break
        return max_vol

    use_cases = sorted(df["use_case"].unique())
    sections = []

    for uc in use_cases:
        uc_df = df[df["use_case"] == uc]
        building_blocks = sorted(uc_df["building_block"].unique())
        operating_modes = sorted(uc_df["operating_mode"].unique())

        rows_html = ""
        for om in operating_modes:
            thr = _get_threshold(om)
            cells_html = ""
            for bb in building_blocks:
                subset = uc_df[
                    (uc_df["operating_mode"] == om) & (uc_df["building_block"] == bb)
                ]
                if subset.empty:
                    cells_html += """
                    <td style="background:#f1f5f9;color:#94a3b8;text-align:center;
                               padding:12px 16px;border:1px solid #e5e7eb;
                               font-size:13px;font-style:italic;">—</td>"""
                else:
                    max_vol = max_accepted_volume(subset, thr)
                    if max_vol is None:
                        bg, text, value = "#fee2e2", "#991b1b", "None"
                    else:
                        all_vols = sorted(subset["volume"].unique())
                        is_max = max_vol == max(all_vols)
                        bg = "#fee2e2" if is_max else "#f1f5f9"
                        text = "#991b1b" if is_max else "#475569"
                        value = str(int(max_vol))

                    cells_html += f"""
                    <td style="background:{bg};color:{text};text-align:center;
                               padding:12px 16px;border:1px solid #e5e7eb;
                               font-size:15px;font-weight:700;font-family:monospace;">
                      {value}
                    </td>"""

            rows_html += f"""
            <tr>
              <td style="background:#f8fafc;color:#334155;padding:10px 16px;
                         border:1px solid #e5e7eb;font-size:13px;font-weight:600;
                         white-space:nowrap;">
                {om}
                <span style="font-size:11px;color:#94a3b8;font-weight:400;margin-left:6px;">
                  (≤ {thr})
                </span>
              </td>
              {cells_html}
            </tr>"""

        header_cells = "".join(
            f"""
            <th style="background:#1e293b;color:white;text-align:center;padding:10px 16px;
                       border:1px solid #2d3f55;font-size:12px;font-weight:700;
                       min-width:110px;">{bb}</th>"""
            for bb in building_blocks
        )

        sections.append(
            f"""
    <div class="table-block">
      <div class="table-header">
        <div class="tag"><span class="tag-label">Use Case</span>
          <span class="tag-value tag-uc">{uc}</span></div>
        <div class="tag"><span class="tag-label">Metric</span>
          <span class="tag-value tag-scope">Max volume where {kpi} ≤ {threshold}</span></div>
        <button onclick="exportTable(this)"
          style="margin-left:auto;font-size:11px;font-weight:600;padding:5px 12px;
                 border-radius:6px;border:1px solid #475569;background:transparent;
                 color:white;cursor:pointer;">
          ⬇ Export PNG
        </button>
      </div>
      <div style="overflow-x:auto;">
        <table style="width:100%;border-collapse:collapse;">
          <thead>
            <tr>
              <th style="background:#1e293b;color:#94a3b8;text-align:left;padding:10px 16px;
                         border:1px solid #2d3f55;font-size:11px;font-weight:700;
                         text-transform:uppercase;letter-spacing:0.07em;white-space:nowrap;">
                Mode \\ Block
              </th>
              {header_cells}
            </tr>
          </thead>
          <tbody>{rows_html}</tbody>
        </table>
      </div>
      <div style="display:flex;gap:16px;padding:14px 16px;background:#f8fafc;
                  border-top:1px solid #e5e7eb;flex-wrap:wrap;">
        <div style="display:flex;align-items:center;gap:7px;font-size:12px;color:#475569;">
          <div style="width:14px;height:14px;border-radius:3px;background:#fee2e2;
                      border:1.5px solid #ef4444;"></div>Max tested volume accepted — test higher
        </div>
        <div style="display:flex;align-items:center;gap:7px;font-size:12px;color:#475569;">
          <div style="width:14px;height:14px;border-radius:3px;background:#f1f5f9;
                      border:1.5px solid #94a3b8;"></div>Accepted but not at max tested volume
        </div>
        <div style="display:flex;align-items:center;gap:7px;font-size:12px;color:#475569;">
          <div style="width:14px;height:14px;border-radius:3px;background:#fee2e2;
                      border:1.5px solid #ef4444;"></div>No volume accepted
        </div>
      </div>
    </div>"""
        )

    all_sections = "\n".join(sections)

    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<title>RailSim — {run_id} — Max Accepted Volume</title>
<style>
  * {{ box-sizing: border-box; margin: 0; padding: 0; }}
  body {{ font-family: 'Segoe UI', sans-serif; background: #f1f5f9;
          color: #0f172a; padding: 32px 24px; }}
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

  .tables-container {{ display: flex; flex-direction: column; gap: 48px; }}
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
  .tag-scope {{ background: #10b98122; color: #34d399; border: 1px solid #10b98144; }}

  .no-results {{ text-align: center; padding: 48px; color: #94a3b8; font-size: 15px; }}
</style>
</head>
<body>

<h1>RailSim — {run_id} — Max Accepted Volume</h1>
<p class="subtitle">Maximum volume where <strong>{kpi} ≤ {threshold}</strong>, per operating mode and building block</p>

<div class="filters">
  <div class="filter-group">
    <label>Use Case</label>
    <select id="filter-uc" onchange="applyFilters()">
      <option value="all">All</option>
      {"".join(f'<option value="{v}">{v}</option>' for v in use_cases)}
    </select>
  </div>
</div>

<div class="tables-container" id="tables">
{all_sections}
</div>

<script src="https://cdnjs.cloudflare.com/ajax/libs/html2canvas/1.4.1/html2canvas.min.js"></script>
<script>
function applyFilters() {{
  const uc = document.getElementById("filter-uc").value;
  const blocks = document.querySelectorAll(".table-block");
  let visible = 0;
  blocks.forEach(b => {{
    const match = uc === "all" || b.querySelector(".tag-uc").textContent.trim() === uc;
    b.style.display = match ? "" : "none";
    if (match) visible++;
  }});
  const container = document.getElementById("tables");
  const existing = container.querySelector(".no-results");
  if (visible === 0) {{
    if (!existing) {{
      const div = document.createElement("div");
      div.className = "no-results";
      div.textContent = "No results match the selected filters.";
      container.appendChild(div);
    }}
  }} else if (existing) existing.remove();
}}

function exportTable(btn) {{
  const container = btn.closest(".table-block");
  html2canvas(container, {{ backgroundColor: "#ffffff", scale: 2 }}).then(canvas => {{
    const link = document.createElement("a");
    link.download = "max_volume_table.png";
    link.href = canvas.toDataURL("image/png");
    link.click();
  }});
}}
</script>
</body>
</html>"""

    Path(output_path).write_text(html, encoding="utf-8")
    print(f"  → max_volume_tables_{kpi}.html saved to {output_path}")
