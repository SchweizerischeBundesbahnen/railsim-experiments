"""
analysis/pipeline.py
====================
Orchestrates the full RailSim analysis pipeline.

Steps
-----
1. Load the simulation summary CSV.
2. Compute theoretical utilisation from operational plan JSONs.
3. Compute delay quantiles from the summary CSV.
4. Merge all data into a single analysis DataFrame.
5. Export three HTML reports:
   - summary_tables.html        — KPI status table per (uc, bb, om, volume)
   - confusion_matrix.html      — theoretical vs actual utilisation
   - max_volume_tables_<kpi>.html — max volume satisfying the chosen KPI

Usage
-----
    from analysis.pipeline import run_pipeline
    from analysis import config

    run_pipeline(config)

Or directly from the command line via run_analysis.py.
"""

from __future__ import annotations

from pathlib import Path
from types import ModuleType
from typing import TYPE_CHECKING

import pandas as pd

from analysis.delay_quantiles.gzb_delay_quantiles import compute_summary_runs_delay_quantiles
from analysis.zugfolgezeit.gzb_theoretical_utilisation import compute_theoretical_utilisation_df

from analysis.reports.confusion_matrix import export_confusion_matrix_html
from analysis.reports.max_volume_tables import export_max_volume_tables_html
from analysis.reports.summary_tables import export_summary_tables_html

if TYPE_CHECKING:
    pass

# Columns used to join the three data sources.
JOIN_COLS = ["use_case", "building_block", "operating_mode", "volume"]


# ── Individual pipeline steps ─────────────────────────────────────────────────


def load_summary_runs(csv_path: Path) -> pd.DataFrame:
    """Read and normalise the simulation summary CSV.

    Returns a DataFrame with lowercase use_case / building_block columns
    and the train_volume column renamed to 'volume'.
    """
    if not csv_path.exists():
        raise FileNotFoundError(
            f"Summary CSV not found: {csv_path}\n"
            "Make sure OUTPUT_ROOT and RUN_ID in config.py are correct."
        )
    df = pd.read_csv(csv_path)
    df = df.rename(columns={"train_volume": "volume"})
    df["use_case"] = df["use_case"].str.lower()
    df["building_block"] = df["building_block"].str.lower()
    return df


def compute_theoretical(
    output_root: Path,
    capacity_by_building_block: dict[str, float],
    headway_overrides: dict | None = None,
) -> pd.DataFrame:
    """Compute theoretical utilisation from operational plan JSON files.

    Returns a DataFrame with columns including utilisation_theorical.
    The operating_mode column is normalised to lowercase with ':' → '_'.
    """
    df = compute_theoretical_utilisation_df(
        output_root,
        capacity_by_building_block=capacity_by_building_block,
        headway_overrides=headway_overrides,
    )
    df["operating_mode"] = (
        df["operating_mode"]
        .astype(str)
        .str.lower()
        .str.replace(":", "_", regex=False)
    )
    return df


def compute_delay_quantiles(
    output_root: Path,
    period_s: int,
    quantiles_pct: list[int],
) -> pd.DataFrame:
    """Compute delay quantiles from the simulation summary CSV.

    Returns a DataFrame with columns q0, q2, q5, … (one per requested quantile),
    plus utilization_mean and utilization_median.
    """
    df = compute_summary_runs_delay_quantiles(
        output_root=output_root,
        period_s=period_s,
        quantiles_pct=quantiles_pct,
    )
    df = df.rename(columns={"train_volume": "volume"})
    df["use_case"] = df["use_case"].str.lower()
    df["building_block"] = df["building_block"].str.lower()
    return df


def merge_all(
    df_q: pd.DataFrame,
    df_theorical: pd.DataFrame,
) -> pd.DataFrame:
    """Merge delay quantiles with theoretical utilisation on JOIN_COLS.

    Only rows present in both DataFrames are kept (inner join).
    Volume is converted from veh/period to veh/h using the period already
    baked into df_q (train_volume_per_hour column).
    """
    missing_left = set(JOIN_COLS) - set(df_q.columns)
    missing_right = set(JOIN_COLS) - set(df_theorical.columns)
    if missing_left or missing_right:
        raise ValueError(
            f"Missing join columns — left: {missing_left}, right: {missing_right}"
        )

    df = df_q.merge(df_theorical, on=JOIN_COLS, how="inner")
    return df


# ── Full pipeline ─────────────────────────────────────────────────────────────


def run_pipeline(cfg: ModuleType) -> None:
    """Run the complete analysis pipeline using the given config module.

    Parameters
    ----------
    cfg:
        The ``analysis.config`` module (or any object with the same attributes).
    """
    print(f"── RailSim Analysis Pipeline ────────────────")
    print(f"   Run   : {cfg.RUN_ID}")
    print(f"   Root  : {cfg.OUTPUT_ROOT}")
    print()

    # Step 1 – theoretical utilisation
    print("[1/4] Computing theoretical utilisation …")
    df_theorical = compute_theoretical(
        cfg.OUTPUT_ROOT,
        capacity_by_building_block=cfg.CAPACITY_BY_BUILDING_BLOCK,
        headway_overrides=cfg.HEADWAY_OVERRIDES,
    )

    # Step 2 – delay quantiles
    print("[2/4] Computing delay quantiles …")
    df_q = compute_delay_quantiles(
        cfg.OUTPUT_ROOT,
        period_s=cfg.PERIOD_S,
        quantiles_pct=cfg.QUANTILES_PCT,
    )

    # Step 3 – merge
    print("[3/4] Merging data …")
    df = merge_all(df_q, df_theorical)

    # Convert volume to veh/h for the reports
    df["volume"] = df["volume"] * (3600 / cfg.PERIOD_S)

    # Step 4 – export reports
    print("[4/4] Exporting HTML reports …")

    export_summary_tables_html(
        df,
        cfg.RUN_ID,
        thresholds_by_operating_mode=cfg.THRESHOLDS_BY_OPERATING_MODE,
        default_utilisation_threshold=cfg.DEFAULT_UTILISATION_THRESHOLD,
        output_path=cfg.OUTPUT_ROOT / "summary_tables.html",
    )

    export_confusion_matrix_html(
        df,
        cfg.RUN_ID,
        thresholds_by_operating_mode=cfg.THRESHOLDS_BY_OPERATING_MODE,
        default_utilisation_threshold=cfg.DEFAULT_UTILISATION_THRESHOLD,
        output_path=cfg.OUTPUT_ROOT / "confusion_matrix.html",
    )

    kpi = "q5"
    export_max_volume_tables_html(
        df,
        run_id=cfg.RUN_ID,
        kpi=kpi,
        threshold=5,
        thresholds_by_operating_mode=cfg.THRESHOLDS_BY_OPERATING_MODE,
        default_utilisation_threshold=cfg.DEFAULT_UTILISATION_THRESHOLD,
        output_path=cfg.OUTPUT_ROOT / f"max_volume_tables_{kpi}.html",
    )

    print()
    print("Done. Reports written to:", cfg.OUTPUT_ROOT)
