# analyse.py
from __future__ import annotations

from pathlib import Path
import time
from typing import Optional, Callable

import pandas as pd

from .discover import iter_linkstate_files


def hhmmss_to_seconds(x: str) -> int:
    """
    Convert 'HH:MM:SS' (or 'H:MM:SS') to seconds.
    Raises ValueError for invalid formats.
    """
    parts = str(x).strip().split(":")
    if len(parts) != 3:
        raise ValueError(f"Invalid time format (expected HH:MM:SS): {x!r}")
    h, m, s = parts
    return int(h) * 3600 + int(m) * 60 + int(s)

def _parse_volume_to_int(volume: str) -> int:
    """
    Parse volume strings like 'volume_08' -> 8.

    Raises ValueError if the format is unexpected.
    """
    try:
        prefix, num = volume.split("_", 1)
    except ValueError:
        raise ValueError(f"Invalid volume format (expected 'volume_XX'): {volume!r}")

    if not num.isdigit():
        raise ValueError(f"Invalid volume number in {volume!r}")

    return int(num)



def analyse_linkstate_exhausted_utilisation(
    linkstate_path: str | Path,
    window_start: int = 3600,
    window_end: int = 7200,
) -> pd.DataFrame:
    """
    Reads one railsimLinkStates.csv(.gz) and returns utilisation per link in the time window.
    Utilisation = (vehicle-weighted exhausted time within window) / window_length

    Returns columns:
      link, exhausted_time_s, utilisation, utilisation_pct
    """
    linkstate_path = Path(linkstate_path)
    window_len = window_end - window_start
    if window_len <= 0:
        raise ValueError("window_end must be > window_start")

    # pandas can read .csv and .csv.gz; compression="infer" is simplest
    df = pd.read_csv(linkstate_path, compression="infer", low_memory=False)

    # Validate required columns (fail fast, clear error)
    required = {"time", "link", "vehicle", "state"}
    missing = required - set(df.columns)
    if missing:
        raise ValueError(f"Missing required columns {sorted(missing)} in {linkstate_path.name}")

    # time -> seconds
    if df["time"].dtype == object:
        df["time_s"] = df["time"].astype(str).map(hhmmss_to_seconds)
    else:
        df["time_s"] = df["time"]

    # compute intervals per (link, vehicle)
    df = df.sort_values(["link", "vehicle", "time_s"]).reset_index(drop=True)
    df["t_start"] = df["time_s"]
    df["t_end"] = df.groupby(["link", "vehicle"])["time_s"].shift(-1)

    df = df.dropna(subset=["t_end"]).copy()
    df["duration_s"] = df["t_end"] - df["t_start"]
    if (df["duration_s"] < 0).any():
        raise ValueError(f"Negative durations detected in {linkstate_path}")

    # filter exhausted only
    df_exh = df[df["state"] == "EXHAUSTED"].copy()

    # clip to window
    df_exh["t_start_clip"] = df_exh["t_start"].clip(lower=window_start)
    df_exh["t_end_clip"] = df_exh["t_end"].clip(upper=window_end)

    # keep overlaps only
    df_exh = df_exh[df_exh["t_end_clip"] > df_exh["t_start_clip"]].copy()
    df_exh["duration_clip_s"] = df_exh["t_end_clip"] - df_exh["t_start_clip"]

    # utilisation per link
    util_by_link = (
        df_exh.groupby("link", as_index=False)["duration_clip_s"]
        .sum()
        .rename(columns={"duration_clip_s": "exhausted_time_s"})
    )
    util_by_link["utilisation"] = util_by_link["exhausted_time_s"] / window_len
    util_by_link["utilisation_pct"] = 100.0 * util_by_link["utilisation"]

    return util_by_link


def collect_linkstate_utilisation_from_output_folder(
    output_root: str | Path,
    window_start: int = 3600,
    window_end: int = 7200,
    print_every_s: int = 30,
    *,
    it_dir: str = "it.0",
    output_dir_name: str = "04_simulation_run_output",
    linkstates_suffix: str = "railsimLinkStates.csv.gz",
    per_file_fn: Optional[Callable[[Path], pd.DataFrame]] = None,
) -> pd.DataFrame:
    """
    Uses discover.iter_linkstate_files() to scan the output tree and compute per-link utilisation.

    Returns a concatenated dataframe with metadata columns:
      use_case, building_block, operating_mode, volume, sample, linkstate_file
    plus the per-file analysis columns (e.g. link, exhausted_time_s, utilisation, utilisation_pct).
    """
    output_root = Path(output_root).resolve()
    if not output_root.exists():
        raise FileNotFoundError(output_root)

    files = list(
        iter_linkstate_files(
            output_root,
            output_dir_name=output_dir_name,
            it_dir=it_dir,
            linkstates_suffix=linkstates_suffix,
        )
    )
    total = len(files)
    print(f"[INFO] Found {total} railsimLinkStates files under {output_root}")

    base_cols = [
        "use_case",
        "building_block",
        "operating_mode",
        "volume",
        "sample",
        "linkstate_file",
    ]

    if total == 0:
        return pd.DataFrame(columns=base_cols)

    rows: list[pd.DataFrame] = []

    t_global_start = time.time()
    t_last_print = t_global_start
    ema_sec_per_file: Optional[float] = None
    alpha = 0.15

    for i, item in enumerate(files, start=1):
        t0 = time.time()
        rel = item.path.relative_to(output_root)

        try:
            if per_file_fn is not None:
                util_df = per_file_fn(item.path)
            else:
                util_df = analyse_linkstate_exhausted_utilisation(
                    item.path,
                    window_start=window_start,
                    window_end=window_end,
                )

            # --- metadata ---
            util_df.insert(0, "use_case", item.usecase)
            util_df.insert(1, "building_block", item.building_block)
            util_df.insert(2, "operating_mode", item.operating_mode)
            util_df.insert(3, "volume", item.train_volume)
            util_df.insert(4, "sample", item.sample)

            # --- departures per hour ---
            if item.period_s is None:
                raise ValueError(
                f"Cannot compute departures: period_s is missing "
                    f"(usecase={item.usecase})"
                )

            volume_int = _parse_volume_to_int(item.train_volume)
            departures = volume_int * (3600.0 / item.period_s)

            util_df["departures"] = departures

            # --- provenance ---
            util_df["linkstate_file"] = str(rel)


            rows.append(util_df)

        except Exception as e:
            print(f"[ERROR] {rel} -> {type(e).__name__}: {e}")

        dt = time.time() - t0
        ema_sec_per_file = dt if ema_sec_per_file is None else (alpha * dt + (1 - alpha) * ema_sec_per_file)

        now = time.time()
        if (now - t_last_print) >= print_every_s or i == total:
            elapsed = now - t_global_start
            remaining = total - i
            eta_s = remaining * (ema_sec_per_file or 0.0)
            print(
                f"[PROGRESS] {i}/{total} | last={dt:.2f}s | avg~={ema_sec_per_file:.2f}s/file | "
                f"elapsed={int(elapsed)//60:02d}:{int(elapsed)%60:02d} | ETA={int(eta_s)//60:02d}:{int(eta_s)%60:02d} | {rel}"
            )
            t_last_print = now

    if not rows:
        return pd.DataFrame(columns=base_cols)

    return pd.concat(rows, ignore_index=True)
