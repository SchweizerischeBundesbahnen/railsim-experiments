#!/usr/bin/env python3
"""
run_analysis.py
===============
Command-line entry point for the RailSim analysis pipeline.

Usage
-----
    python run_analysis.py

Edit analysis/config.py to change the run ID, data paths, capacities,
quantiles, and utilisation thresholds before running.

What it produces (in OUTPUT_ROOT)
----------------------------------
  summary_tables.html          — KPI status per (use case, building block, operating mode, volume)
  confusion_matrix.html        — theoretical utilisation vs delay-based KPIs
  max_volume_tables_q5.html    — maximum volume satisfying q5 ≤ 5 s
"""

import sys
from pathlib import Path

# Make sure the script works when launched from outside the python/ directory.
sys.path.insert(0, str(Path(__file__).parent))

import analysis.config as config
from analysis.pipeline import run_pipeline

if __name__ == "__main__":
    run_pipeline(config)
