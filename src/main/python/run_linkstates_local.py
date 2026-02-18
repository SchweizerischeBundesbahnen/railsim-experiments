from pathlib import Path

from linkstates.analyse import collect_linkstate_utilisation_from_output_folder


# ============================================================
# CONFIG – change these and hit ▶ Run
# ============================================================

filer = Path(r"/Users/nicolasdulex/devsbb/GZB_analysis")
run_id = "output_20260217_it5_n5"

OUTPUT_ROOT = filer / run_id

WINDOW_START_S = 3600
WINDOW_END_S = 7200

output_filer = Path("/Users/nicolasdulex/devsbb/GZB_analysis/test")
OUT_CSV = output_filer / f"linkstate_utilisation_{run_id}.csv"

PRINT_EVERY_S = 30

# ============================================================
# RUN
# ============================================================

if __name__ == "__main__":
    OUT_CSV.parent.mkdir(parents=True, exist_ok=True)

    df = collect_linkstate_utilisation_from_output_folder(
        OUTPUT_ROOT,
        window_start=WINDOW_START_S,
        window_end=WINDOW_END_S,
        print_every_s=PRINT_EVERY_S,
    )

    df.to_csv(OUT_CSV, index=False)

    print(f"[DONE] Wrote {len(df):,} rows to {OUT_CSV.resolve()}")


