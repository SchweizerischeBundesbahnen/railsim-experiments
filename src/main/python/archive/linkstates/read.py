# read.py
from __future__ import annotations

from pathlib import Path
import pandas as pd


# Adjust this list if needed, but keep it explicit.
REQUIRED_COLUMNS = {
    "time",
    "vehicle_id",
    "link_id",
    # add others if you rely on them later
}


def read_linkstates(path: str | Path) -> pd.DataFrame:
    """
    Read a Railsim LinkStates CSV (optionally gzipped).

    Parameters
    ----------
    path : Path or str
        Path to railsimLinkStates.csv or railsimLinkStates.csv.gz

    Returns
    -------
    pd.DataFrame
        Raw LinkStates dataframe.

    Raises
    ------
    FileNotFoundError
        If the file does not exist.
    ValueError
        If required columns are missing.
    """
    path = Path(path)

    if not path.exists():
        raise FileNotFoundError(f"LinkStates file not found: {path}")

    # pandas handles .csv and .csv.gz transparently
    df = pd.read_csv(path)

    missing = REQUIRED_COLUMNS - set(df.columns)
    if missing:
        raise ValueError(
            f"Missing required columns in {path.name}: {sorted(missing)}"
        )

    return df
