# RailSim Analysis Pipeline

Python analysis pipeline for RailSim simulation outputs.
Reads a run's output CSV, computes theoretical utilisation and delay quantiles,
and exports three interactive HTML reports.

---

## Quick start

```bash
cd src/main/python

# 1. Edit the run ID and data paths
#    → analysis/config.py

# 2. Run the pipeline
python run_analysis.py
```

Three HTML reports are written to the `OUTPUT_ROOT` folder:

| Report | Content |
|--------|---------|
| `summary_tables.html` | KPI status (green / red) per use case, building block, operating mode and volume |
| `confusion_matrix.html` | Theoretical utilisation vs delay-based KPIs |
| `max_volume_tables_q5.html` | Maximum volume satisfying q5 ≤ 5 s per scenario |

---

## Folder structure

```
src/main/python/
│
├── run_analysis.py              ← Entry point: run this to launch the full pipeline
│
├── analysis/                    ← All analysis code lives here
│   ├── config.py                ← Configuration (paths, capacities, thresholds)
│   ├── pipeline.py              ← Orchestrates the 4 pipeline steps
│   ├── reports/                 ← HTML report generators
│   │   ├── summary_tables.py
│   │   ├── confusion_matrix.py
│   │   └── max_volume_tables.py
│   ├── zugfolgezeit/            ← Theoretical utilisation from operational plans
│   │   └── gzb_theoretical_utilisation.py
│   └── delay_quantiles/         ← Delay quantile computation from summary CSV
│       └── gzb_delay_quantiles.py
│
├── notebooks/
│   ├── run_python_analysis_summary_runs.ipynb   ← Interactive pipeline trace
│   └── archive/                                 ← Superseded / exploratory notebooks
│
└── archive/                     ← Archived standalone tools (linkstate analysis)
```

---

## Configuration (`analysis/config.py`)

All parameters live in one file. The most common edits:

```python
# Which run to analyse
RUN_ID = "output_20260223_it5_n100"

# Where the run output folders live (local vs filer)
FILER = Path(r"/Users/nicolasdulex/devsbb/GZB_analysis")
# FILER = Path(r"/Volumes/SAM.A13783/04_projects/42_gzb_railsim")

# Normalised track capacity per building block (1 = single track, 2 = double, …)
CAPACITY_BY_BUILDING_BLOCK = {"uc0_bb1": 1, "uc1_bb2": 2, ...}

# Utilisation thresholds per operating mode (used to colour-code report cells)
THRESHOLDS_BY_OPERATING_MODE = {"express_pass": 0.70, "metro_balanced": 0.70, ...}
```

---

## Pipeline steps (`analysis/pipeline.py`)

```
[1] compute_theoretical()      Reads operational_plan.json files → df_theorical
                                Columns: use_case, building_block, operating_mode,
                                         volume, utilisation_theorical

[2] compute_delay_quantiles()   Reads output_run_summary.csv → df_q
                                Columns: …, q0, q2, q5, q10, q50, q90, q100,
                                         utilization_mean, utilization_median

[3] merge_all()                 Inner-joins both datasets on
                                (use_case, building_block, operating_mode, volume)

[4] export reports              summary_tables.html
                                confusion_matrix.html
                                max_volume_tables_q5.html
```

---

## Notebook

Open [notebooks/run_python_analysis_summary_runs.ipynb](notebooks/run_python_analysis_summary_runs.ipynb)
to run the same pipeline interactively and explore scatter plots comparing
theoretical vs actual utilisation.

The notebook imports from the `analysis/` package, so it always stays in sync
with the CLI.

```python
# Override the run from inside the notebook without editing config.py:
run_id = "output_20260224_it5_n1000"
OUTPUT_ROOT = config.FILER / run_id
```

---

## Expected output folder structure

```
<OUTPUT_ROOT>/                          e.g. output_20260223_it5_n100/
  output_run_summary.csv                ← required for delay quantiles
  <use_case>/
    <building_block>/
      operational_plan.json             ← required for theoretical utilisation
      04_simulation_run_output/
        <operating_mode>/
          <volume>/
            <sample>/
              ITERS/it.0/
                railsimLinkStates.csv.gz
                train_run_calculation.output_transitschedule.xml.gz
```

---

## Dependencies

Managed via the project-local `.venv/`:

```bash
pip install pandas numpy plotly itables
```

In VS Code, select the interpreter at `python/.venv/bin/python`.