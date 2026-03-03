"""
analysis/config.py
==================
Central configuration for the RailSim analysis pipeline.

Edit the values below to match your run, then launch via:
    python run_analysis.py
or by running the notebook run_python_analysis_summary_runs.ipynb.
"""

from pathlib import Path

# ── Data location ─────────────────────────────────────────────────────────────

# Base folder that contains the run output directories.
# Switch to the filer path when working from the network drive.
# FILER = Path(r"/Users/nicolasdulex/devsbb/GZB_analysis")
FILER = Path(r"/Volumes/SAM.A13783/04_projects/42_gzb_railsim")

# ID of the run to analyse (name of the output sub-folder).
# RUN_ID = "output_20260223_it5_n100" # when local
RUN_ID = "output_20260224_it5_n1000" # when on filer

# Derived paths (no need to edit).
OUTPUT_ROOT = FILER / RUN_ID
SUMMARY_RUNS_CSV = OUTPUT_ROOT / "output_run_summary.csv"

# ── Time window ────────────────────────────────────────────────────────────────

# Simulation period in seconds used to normalise volumes (veh / period → veh/h).
PERIOD_S = 1800  # 30 minutes

# ── Delay quantiles ────────────────────────────────────────────────────────────

# Percentiles to compute from the summary-run delay distribution.
QUANTILES_PCT = [0, 2, 5, 10, 50, 90, 100]

# ── Theoretical utilisation ────────────────────────────────────────────────────

# Normalised track capacity per building block.
# A value of 1 = single-track capacity, 2 = double, etc.
CAPACITY_BY_BUILDING_BLOCK: dict[str, float] = {
    "uc0_bb1": 1,
    "uc0_bb2": 1,
    "uc1_bb1": 1,
    "uc1_bb2": 2,
    "uc1_bb3": 3,
    "uc2_bb1": 1,
    "uc2_bb2": 1,
}

# Optional headway overrides (use_case → product → route → headway_s).
# Set to None to use the headways from the operational plan JSON directly.
HEADWAY_OVERRIDES: dict | None = {
    "uc_2": {
        "FV": {"A_C": 210.0},
        "RV": {"A_C": 210.0},
        "GV": {"A_C": 240.0},
    }
}

# ── Utilisation thresholds by operating mode ──────────────────────────────────

# These define the maximum acceptable theoretical utilisation for each mode.
# Used to colour-code cells in the summary and confusion-matrix reports.
THRESHOLDS_BY_OPERATING_MODE: dict[str, float] = {
    "express_pass":      0.70,
    "express_fv_stop":   0.60,
    "mainline_fv_pass":  0.50,
    "mainline_fv_stop":  0.50,
    "metro_rv_stop":     0.70,
    "metro_balanced":    0.70,
    "metro_trunk":       0.70,
    "transit_fv_stop":   0.60,
    "transit_balanced":  0.60,
    "transit_trunk":     0.60,
}

DEFAULT_UTILISATION_THRESHOLD = 0.70
