"""Pipeline helper for theoretical utilisation from GZB operational plans."""

from __future__ import annotations

import gzip
import json
import math
import re
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Mapping

import pandas as pd


def compute_theoretical_utilisation_df(
    output_root: str | Path,
    use_case: str | None = None,
    *,
    capacity_by_building_block: Mapping[str, float],
    headway_overrides: Mapping[str, Mapping[str, Mapping[str, float]]] | None = None,
    # e.g. {"uc_2": {"FV": {"LMR": 110.0, "LR": 130.0}, "RV": {"LMR": 85.0}}}
    # values in seconds
    print_every_s: int = 30,
) -> pd.DataFrame:
    """
    Compute theoretical utilisation for one or all use cases.

    The function reproduces the logic from
    ``GZB_ZugfolgeZeit_Auslastung_quick_20260206.ipynb`` and returns a dataframe
    at the granularity requested for pipeline use:
    ``use_case, building_block, operating_mode, volume``.

    Parameters
    ----------
    output_root:
        Root folder that contains ``<use_case>/`` with plan and building blocks.
    use_case:
        Use case folder name (e.g. ``"uc_1"``). If ``None``, all use cases under
        ``output_root`` that contain ``*_operational_plan.json`` are processed.
    capacity_by_building_block:
        Capacity per building block used for utilisation normalization.
    print_every_s:
        Progress print interval in seconds.

    Returns
    -------
    pd.DataFrame
        Columns:
        ``use_case, building_block, operating_mode, mix, pattern, volume,
        period_s, departures, transit_time_min, stop_time_min, total_time_min,
        utilisation``.
    """

    def _fmt_hms(seconds: float) -> str:
        seconds = max(0, int(seconds))
        h = seconds // 3600
        m = (seconds % 3600) // 60
        s = seconds % 60
        return f"{h:d}:{m:02d}:{s:02d}" if h > 0 else f"{m:02d}:{s:02d}"

    def _hhmmss_to_seconds(value: str) -> int:
        h, m, s = map(int, value.split(":"))
        return h * 3600 + m * 60 + s

    def _largest_remainder_allocate(total: int, weights: Mapping[str, float]) -> dict[str, int]:
        w = {k: float(v) for k, v in weights.items() if float(v) > 0}
        s = sum(w.values())
        if total == 0 or s <= 0:
            return {k: 0 for k in weights.keys()}

        w = {k: v / s for k, v in w.items()}
        raw = {k: total * w.get(k, 0.0) for k in weights.keys()}
        base = {k: int(math.floor(raw[k])) for k in raw.keys()}
        rem = total - sum(base.values())

        frac = sorted(
            ((k, raw[k] - base[k]) for k in base.keys()),
            key=lambda x: x[1],
            reverse=True,
        )
        for i in range(rem):
            base[frac[i % len(frac)][0]] += 1
        return base

    def _parse_flow_from_route_id(line_id: str, route_id: str) -> str:
        prefix = f"{line_id}_"
        if route_id.startswith(prefix):
            rest = route_id[len(prefix) :]
            return rest.split("_", 1)[0]
        m = re.match(r"^[A-Za-z0-9]+_([A-Za-z0-9]+)_", route_id)
        return m.group(1) if m else "UNKNOWN"

    def _dwell_minutes_for_route(route_elem: ET.Element) -> float:
        rp = route_elem.find("routeProfile")
        if rp is None:
            return 0.0

        stops = rp.findall("stop")
        if len(stops) <= 2:
            return 0.0

        total_sec = 0
        for st in stops[1:-1]:
            msd = st.attrib.get("minimumStopDuration")
            if msd:
                total_sec += _hhmmss_to_seconds(msd)
            else:
                arr = st.attrib.get("arrivalOffset")
                dep = st.attrib.get("departureOffset")
                if arr and dep:
                    total_sec += max(0, _hhmmss_to_seconds(dep) - _hhmmss_to_seconds(arr))
        return total_sec / 60.0

    def _stop_times_by_product_and_flow(transitschedule_path: Path) -> dict[str, dict[str, float]]:
        if not transitschedule_path.exists():
            raise FileNotFoundError(f"Transit schedule not found: {transitschedule_path}")

        if transitschedule_path.suffix == ".gz":
            with gzip.open(transitschedule_path, "rb") as f:
                tree = ET.parse(f)
        else:
            tree = ET.parse(transitschedule_path)

        root = tree.getroot()
        acc: dict[str, dict[str, list[float]]] = {}
        for line in root.findall("transitLine"):
            prod = line.attrib["id"]
            for route in line.findall("transitRoute"):
                flow = _parse_flow_from_route_id(prod, route.attrib["id"])
                dwell = _dwell_minutes_for_route(route)
                acc.setdefault(prod, {}).setdefault(flow, []).append(dwell)

        out: dict[str, dict[str, float]] = {}
        for prod, by_flow in acc.items():
            out[prod] = {flow: sum(vals) / len(vals) for flow, vals in by_flow.items()}
        return out

    root = Path(output_root).resolve()
    if not root.exists():
        raise FileNotFoundError(f"Output root not found: {root}")

    if use_case is None:
        use_case_dirs = sorted(
            p for p in root.iterdir() if p.is_dir() and any(p.glob("*_operational_plan.json"))
        )
        if not use_case_dirs:
            raise FileNotFoundError(f"No use case folders with '*_operational_plan.json' found in {root}")
    else:
        uc_dir = root / use_case
        if not uc_dir.exists():
            raise FileNotFoundError(f"Use case folder not found: {uc_dir}")
        use_case_dirs = [uc_dir]

    discovered_building_blocks = sorted(
        {bb_dir.name for uc_dir in use_case_dirs for bb_dir in uc_dir.iterdir() if bb_dir.is_dir()}
    )
    missing_caps = [bb for bb in discovered_building_blocks if bb not in capacity_by_building_block]
    if missing_caps:
        raise KeyError(
            "Missing capacity for building blocks: "
            f"{missing_caps}. Add each building block to capacity_by_building_block."
        )

    t_global_start = time.time()
    t_last_print = t_global_start
    ema_sec_per_block: float | None = None
    alpha = 0.2
    rows_done = 0

    rows: list[dict] = []
    expected_rows_total = 0

    for use_case_dir in use_case_dirs:
        current_use_case = use_case_dir.name

        plan_files = sorted(use_case_dir.glob("*_operational_plan.json"))
        if not plan_files:
            raise FileNotFoundError(f"No '*_operational_plan.json' found in {use_case_dir}")
        with plan_files[0].open("r", encoding="utf-8") as f:
            plan = json.load(f)

        volumes = plan["volumes"]
        period_s = int(volumes["period"])
        vmin = int(volumes["min"])
        vmax = int(volumes["max"])
        vstep = int(volumes.get("step", 1))

        products = plan["products"]
        # Base headway per product from JSON (in minutes)
        min_headway_min: dict[str, dict[str, float]] = {}
        for product, product_data in products.items():
            base = float(product_data["minHeadway"]) / 60.0
            min_headway_min[product] = {"__default__": base}
            # Apply overrides only for the current use case
            uc_overrides = (
                headway_overrides.get(current_use_case, {}) if headway_overrides else {}
            )
            if product in uc_overrides:
                for route, hw_s in uc_overrides[product].items():
                    min_headway_min[product][route] = float(hw_s) / 60.0

        mixes = plan["mixes"]
        patterns = plan["patterns"]
        modes = plan["modes"]
        mix_to_patterns = {mode["mix"]: list(mode["patterns"]) for mode in modes}

        building_blocks = sorted([path for path in use_case_dir.iterdir() if path.is_dir()])
        if not building_blocks:
            raise FileNotFoundError(f"No building block folders found in {use_case_dir}")

        volumes_list = list(range(vmin, vmax + 1, vstep))
        expected_rows = (
            sum(len(mix_to_patterns.get(mix_name, [])) for mix_name in mix_to_patterns.keys())
            * len(volumes_list)
            * len(building_blocks)
        )
        expected_rows_total += expected_rows

        print(
            f"[INFO] use_case='{current_use_case}' | plan='{plan_files[0].name}' | "
            f"period_s={period_s} | volumes={vmin}..{vmax} step={vstep} | "
            f"building_blocks={len(building_blocks)} | expected_rows~={expected_rows}"
        )

        for b_idx, bb_dir in enumerate(building_blocks, start=1):
            t0 = time.time()

            ts_path = bb_dir / "01_train_run_calculation" / "train_run_calculation.output_transitschedule.xml.gz"
            stop_min = _stop_times_by_product_and_flow(ts_path)

            for mix_name, pat_list in mix_to_patterns.items():
                mix_shares = mixes[mix_name]["shares"]

                for pat_name in pat_list:
                    pat_shares = patterns[pat_name]["shares"]
                    operating_mode = f"{mix_name}:{pat_name}"

                    for volume in volumes_list:
                        dep_prod = _largest_remainder_allocate(volume, mix_shares)

                        dep_prod_flow: dict[str, dict[str, int]] = {}
                        for prod, ndep in dep_prod.items():
                            flow_weights = pat_shares.get(prod, {})
                            if not flow_weights:
                                dep_prod_flow[prod] = {}
                                continue
                            dep_prod_flow[prod] = _largest_remainder_allocate(ndep, flow_weights)

                        transit_total = 0.0
                        stop_total = 0.0
                        departures_total = 0

                        for prod, ndep in dep_prod.items():
                            departures_total += int(ndep)
                            for flow, nflow in dep_prod_flow.get(prod, {}).items():
                                # Use route-specific headway if available, else fall back to default
                                hw = min_headway_min.get(prod, {})
                                headway = hw.get(flow, hw.get("__default__", 0.0))
                                transit_total += float(nflow) * headway
                                stop_total += float(nflow) * float(stop_min.get(prod, {}).get(flow, 0.0))

                        # Add headway for products with no flow allocation (dep_prod_flow empty)
                        for prod, ndep in dep_prod.items():
                            if not dep_prod_flow.get(prod):
                                hw = min_headway_min.get(prod, {})
                                headway = hw.get("__default__", 0.0)
                                transit_total += float(ndep) * headway

                        rows.append(
                            {
                                "use_case": current_use_case,
                                "building_block": bb_dir.name,
                                "operating_mode": operating_mode,
                                "mix": mix_name,
                                "pattern": pat_name,
                                "period_s": period_s,
                                "volume": volume,
                                "departures": departures_total,
                                "transit_time_min": transit_total,
                                "stop_time_min": stop_total,
                                "total_time_min": transit_total + stop_total,
                            }
                        )
                        rows_done += 1

            dt = time.time() - t0
            ema_sec_per_block = (
                dt if ema_sec_per_block is None else (alpha * dt + (1 - alpha) * ema_sec_per_block)
            )

            now = time.time()
            if (now - t_last_print) >= print_every_s or b_idx == len(building_blocks):
                elapsed = now - t_global_start
                print(
                    f"[PROGRESS] use_case={current_use_case} | blocks {b_idx}/{len(building_blocks)} | "
                    f"last_block={dt:.2f}s | avg~={ema_sec_per_block:.2f}s/block | "
                    f"rows={rows_done}/{expected_rows_total} | elapsed={_fmt_hms(elapsed)} | "
                    f"bb='{bb_dir.name}'"
                )
                t_last_print = now

    result = pd.DataFrame(rows)

    scale = 3600.0 / result["period_s"]
    for col in ["departures", "transit_time_min", "stop_time_min", "total_time_min"]:
        result[col] = result[col] * scale

    result["_capacity"] = result["building_block"].map(capacity_by_building_block)
    if result["_capacity"].isna().any():
        missing = result[result["_capacity"].isna()]["building_block"].unique().tolist()
        raise KeyError(f"Missing capacity for building blocks: {missing}")

    result["utilisation_theorical"] = result["total_time_min"] / (result["_capacity"] * 60.0)
    result.drop(columns=["_capacity"], inplace=True)

    # Explicit group-level dataframe for pipeline consumption.
    grouped = result.groupby(
        ["use_case", "building_block", "operating_mode", "volume"], as_index=False
    ).agg(
        departures=("departures", "sum"),
        transit_time_min=("transit_time_min", "sum"),
        stop_time_min=("stop_time_min", "sum"),
        total_time_min=("total_time_min", "sum"),
        utilisation_theorical=("utilisation_theorical", "sum"),
    )

    return grouped
