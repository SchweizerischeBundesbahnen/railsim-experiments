"""Pipeline helper for delay quantiles based on output_run_summary.csv."""

from __future__ import annotations

from pathlib import Path
from typing import Sequence

import numpy as np
import pandas as pd


DEFAULT_QUANTILES_PCT: tuple[float, ...] = (0, 1, 2, 5, 10, 25, 50, 100)


def compute_summary_runs_delay_quantiles(
    output_root: str | Path,
    period_s: float,
    *,
    quantiles_pct: Sequence[float] = DEFAULT_QUANTILES_PCT,
    filename: str = "output_run_summary.csv",
) -> pd.DataFrame:
    """
    Build a wide quantile table per (usecase, building_block, operating_mode, train_volume).

    Quantiles are computed on:
      normalized_mid_hour_delay = mid_hour_delay_at_destination / (train_volume * (3600 / period_s))

    Parameters
    ----------
    output_root:
        Full simulation output path (e.g. OUTPUT_ROOT).
    period_s:
        Period in seconds used for normalization.
    quantiles_pct:
        Quantiles in percent (0..100). Default: 0, 1, 2, 5, 10, 25, 50, 100.
    filename:
        Name of the summary csv file at the root of output_root.

    Returns
    -------
    pd.DataFrame
        Columns:
        usecase, building_block, operating_mode, train_volume,
        train_volume_per_hour, q0, q1, ... (based on quantiles_pct)
    """

    base = Path(output_root).resolve()
    if not base.exists():
        raise FileNotFoundError(f"Base path does not exist: {base}")

    if period_s <= 0:
        raise ValueError(f"period_s must be > 0. Got period_s={period_s}")

    if not quantiles_pct:
        raise ValueError("quantiles_pct cannot be empty")

    quantiles_pct_clean: list[float] = []
    for q in quantiles_pct:
        qf = float(q)
        if not (0.0 <= qf <= 100.0):
            raise ValueError(f"Each quantile must be between 0 and 100. Got {q}")
        quantiles_pct_clean.append(qf)

    quantiles_frac = [q / 100.0 for q in quantiles_pct_clean]

    quantile_cols: list[tuple[str, float]] = []
    seen = set()
    for q_pct, q_frac in zip(quantiles_pct_clean, quantiles_frac):
        q_label = (
            f"q{int(q_pct)}"
            if float(q_pct).is_integer()
            else f"q{str(q_pct).replace('.', '_')}"
        )
        if q_label in seen:
            continue
        seen.add(q_label)
        quantile_cols.append((q_label, q_frac))

    csv_path = base / filename
    if not csv_path.exists():
        raise FileNotFoundError(f"Summary file not found: {csv_path}")

    df_all = pd.read_csv(csv_path)

    required = {
        "use_case",
        "building_block",
        "operating_mode",
        "train_volume",
        "analysis_window_delay_at_destination",
        "analysis_window_utilization",  # added
    }
    missing = required - set(df_all.columns)
    if missing:
        raise KeyError(f"Missing required columns: {sorted(missing)}")

    df_all["train_volume"] = pd.to_numeric(df_all["train_volume"], errors="coerce")
    df_all["analysis_window_delay_at_destination"] = pd.to_numeric(
        df_all["analysis_window_delay_at_destination"], errors="coerce"
    )

    df_all["train_volume_per_hour"] = df_all["train_volume"] * (
        3600.0 / float(period_s)
    )
    df_all["normalized_analysis_window_delay_at_destination"] = (
        df_all["analysis_window_delay_at_destination"] / df_all["train_volume_per_hour"]
    )

    group_cols = [
        "use_case",
        "building_block",
        "operating_mode",
        "train_volume",
        "train_volume_per_hour",
    ]

    def quantile_row(g: pd.DataFrame) -> pd.Series:
        s = g["normalized_analysis_window_delay_at_destination"].dropna()
        u = g["analysis_window_utilization"].dropna()

        delay_quantiles = (
         {label: s.quantile(q_frac) for label, q_frac in quantile_cols}
         if not s.empty
          else {label: np.nan for label, _ in quantile_cols}
        )
        utilization_stats = {
           "utilization_mean": u.mean() if not u.empty else np.nan,
           "utilization_median": u.median() if not u.empty else np.nan,
        }
        return pd.Series({**delay_quantiles, **utilization_stats})

    out = df_all.groupby(group_cols, dropna=False).apply(quantile_row).reset_index()

    ordered_cols = (
        group_cols
        + [label for label, _ in quantile_cols]
        + ["utilization_mean", "utilization_median"]
    )
    out = out[ordered_cols]

    return out
