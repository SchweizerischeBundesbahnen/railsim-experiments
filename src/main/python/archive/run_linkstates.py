from __future__ import annotations

import argparse
from pathlib import Path

from linkstates.analyse import collect_linkstate_utilisation_from_output_folder



def main() -> int:
    parser = argparse.ArgumentParser(
        description="Compute LinkStates exhausted utilisation for all runs under an output root."
    )
    parser.add_argument("output_root", type=Path, help="Path to run output root (contains usecase folders)")
    parser.add_argument("--window-start", type=int, default=3600, help="Window start in seconds (default: 3600)")
    parser.add_argument("--window-end", type=int, default=7200, help="Window end in seconds (default: 7200)")
    parser.add_argument("--print-every-s", type=int, default=30, help="Progress print cadence (default: 30)")
    parser.add_argument("--out", type=Path, default=Path("linkstate_utilisation.csv"), help="Output CSV path")
    args = parser.parse_args()

    df = collect_linkstate_utilisation_from_output_folder(
        args.output_root,
        window_start=args.window_start,
        window_end=args.window_end,
        print_every_s=args.print_every_s,
    )

    args.out.parent.mkdir(parents=True, exist_ok=True)
    df.to_csv(args.out, index=False)
    print(f"[INFO] Wrote {len(df):,} rows to {args.out.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
