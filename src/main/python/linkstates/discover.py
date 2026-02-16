# discover.py
from __future__ import annotations

import json
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Iterator, Optional


@dataclass(frozen=True)
class LinkStateFile:
    """
    Metadata + path for one LinkStates file discovered in a simulation output tree.
    """
    usecase: str
    building_block: str
    operating_mode: str
    train_volume: str
    sample: str
    period_s: Optional[int]
    path: Path
    operational_plan_path: Optional[Path]

    def to_dict(self) -> dict:
        d = asdict(self)
        d["path"] = str(self.path)
        d["operational_plan_path"] = str(self.operational_plan_path) if self.operational_plan_path else None
        return d


def _find_operational_plan(usecase_dir: Path) -> Optional[Path]:
    """
    Find the operational_plan.json inside a usecase folder.
    Rule given: it is in the usecase folder and ends with operational_plan.json.
    """
    candidates = sorted(usecase_dir.glob("*operational_plan.json"))
    if not candidates:
        return None
    # If multiple, pick the shortest name (usually the intended one) deterministically.
    return sorted(candidates, key=lambda p: (len(p.name), p.name))[0]


def _read_period_s(operational_plan_path: Optional[Path]) -> Optional[int]:
    """
    Extract volumes.period from the operational plan JSON, if present.
    Expected structure (example):
      { "volumes": { "period": 1800, ... }, ... }
    """
    if operational_plan_path is None:
        return None
    try:
        with operational_plan_path.open("r", encoding="utf-8") as f:
            data = json.load(f)
        period = data.get("volumes", {}).get("period", None)
        if period is None:
            return None
        return int(period)
    except Exception:
        # Keep discovery robust; period is optional metadata.
        return None


def iter_linkstate_files(
    run_root: str | Path,
    *,
    output_dir_name: str = "04_simulation_run_output",
    it_dir: str = "it.0",
    linkstates_suffix: str = "railsimLinkStates.csv.gz",
) -> Iterator[LinkStateFile]:
    """
    Discover LinkStates files in the Railsim output folder tree.

    Expected structure (as you described):
      run_root/
        <usecase>/
          *operational_plan.json
          <building_block>/
            04_simulation_run_output/
              <operating_mode>/
                <volume>/
                  <sample>/
                    ITERS/it.0/*railsimLinkStates.csv.gz

    Yields:
      LinkStateFile objects (metadata + file path). This function does NOT read the CSVs.

    Notes:
      - period_s is read once per usecase (from operational_plan.json) and attached to each yielded file.
      - train_volume/sample are taken from folder names (kept as strings to avoid wrong casting).
    """
    run_root = Path(run_root)
    if not run_root.exists():
        raise FileNotFoundError(f"run_root does not exist: {run_root}")

    # We treat every directory directly under run_root as a potential usecase.
    for usecase_dir in sorted([p for p in run_root.iterdir() if p.is_dir()]):
        usecase = usecase_dir.name

        op_plan_path = _find_operational_plan(usecase_dir)
        period_s = _read_period_s(op_plan_path)

        # Building blocks under each usecase (directories)
        for bb_dir in sorted([p for p in usecase_dir.iterdir() if p.is_dir()]):
            building_block = bb_dir.name

            sim_out = bb_dir / output_dir_name
            if not sim_out.exists() or not sim_out.is_dir():
                continue

            # operating modes
            for mode_dir in sorted([p for p in sim_out.iterdir() if p.is_dir()]):
                operating_mode = mode_dir.name

                # volumes
                for vol_dir in sorted([p for p in mode_dir.iterdir() if p.is_dir()]):
                    train_volume = vol_dir.name

                    # samples
                    for sample_dir in sorted([p for p in vol_dir.iterdir() if p.is_dir()]):
                        sample = sample_dir.name

                        it_path = sample_dir / "ITERS" / it_dir
                        if not it_path.exists() or not it_path.is_dir():
                            continue

                        # There may be multiple LinkStates files; yield them all.
                        for f in sorted(it_path.glob(f"*{linkstates_suffix}")):
                            if f.is_file():
                                yield LinkStateFile(
                                    usecase=usecase,
                                    building_block=building_block,
                                    operating_mode=operating_mode,
                                    train_volume=train_volume,
                                    sample=sample,
                                    period_s=period_s,
                                    path=f,
                                    operational_plan_path=op_plan_path,
                                )


def discover_linkstate_files(
    run_root: str | Path,
    *,
    output_dir_name: str = "04_simulation_run_output",
    it_dir: str = "it.0",
    linkstates_suffix: str = "railsimLinkStates.csv.gz",
) -> list[LinkStateFile]:
    """
    Convenience wrapper that returns a list instead of an iterator.
    """
    return list(
        iter_linkstate_files(
            run_root,
            output_dir_name=output_dir_name,
            it_dir=it_dir,
            linkstates_suffix=linkstates_suffix,
        )
    )
