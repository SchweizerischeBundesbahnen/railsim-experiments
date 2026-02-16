
# Python Analysis – READ ME

# 1. LinkStates Utilisation

This folder contains a lightweight Python analysis pipeline to post-process
Railsim simulation outputs, with a focus on **LinkStates exhausted utilisation**
and exploratory visualisation.

---

## Folder structure

```text
python/
  linkstates/
    discover.py     # find LinkStates files + extract metadata from folder structure
    analyse.py      # compute exhausted utilisation per link
    visualise.py    # helper functions for Plotly visualisations
  run_linkstates_local.py   # local runner (edit paths + hit Run)
  notebooks/
    run_visualisation.ipynb
````

---

## What the analysis does

For each `railsimLinkStates.csv(.gz)` file, the analysis:

1. Reads LinkStates events
2. Computes **EXHAUSTED time per link** within a time window
3. Computes utilisation as:

```text
utilisation = exhausted_time_s / window_length_s
```

4. Adds metadata derived from the folder structure:

   * `use_case`
   * `building_block`
   * `operating_mode`
   * `volume`
   * `sample`

5. Derives an additional column:

```text
departures = volume * (3600 / period_s)
```

Where:

* `volume` comes from folder names like `volume_08`
* `period_s` is read from `operational_plan.json`
* `departures` expresses trains per hour

---

## Assumptions (important)

* Folder structure follows:

```text
<run_root>/
  <use_case>/
    <building_block>/
      04_simulation_run_output/
        <operating_mode>/
          <volume>/
            <sample>/
              ITERS/it.0/railsimLinkStates.csv.gz
```

* `volume` folders are named `volume_XX`
* `period_s` is constant per use_case
* LinkStates CSV contains at least these columns:

  * `time`
  * `link`
  * `vehicle`
  * `state` (expects `"EXHAUSTED"`)

If any of these assumptions change, the analysis will fail **explicitly**
instead of producing silent wrong results.

---

## How to run the analysis (local)

### 1. Activate / select the virtual environment

The project expects a project-local virtual environment, for example:

```text
python/.venv/
```

In VS Code:

* Select interpreter:
  `python/.venv/bin/python`

Required packages:

* `pandas`
* `plotly` (for visualisation)

---

### 2. Run the analysis

Edit paths directly in:

```text
run_linkstates_local.py
```

Then:

* Open the file
* Click **Run ▶** in VS Code

This will:

* scan the output tree
* compute utilisation
* write a CSV with all results

---

## Visualisation

Interactive plots are generated in Jupyter:

```text
notebooks/linkstates_visualisation.ipynb
```

Current plots:

* Utilisation vs departures
* Box + points
* Filters for:

  * link
  * building_block

The visualisation reads the CSV produced by the analysis step.