from __future__ import annotations

from pathlib import Path
from typing import Iterable, Optional

import pandas as pd
import plotly.express as px
import plotly.graph_objects as go


def plot_utilisation_by_departures(
    csv_path: str | Path,
    *,
    x_col: str = "departures",
    y_col: str = "utilisation",
    color_col: str = "building_block",
    link_col: str = "link",
    hover_cols: Optional[Iterable[str]] = None,
    title: str = "Utilisation by Departures",
) -> go.Figure:
    """
    Box+points plot of utilisation vs departures, colored by building_block,
    with dropdown filters for link (and building_block).

    Dropdown behavior:
    - Link dropdown: selects which link to display (filters traces to that link)
    - Building_block dropdown: further filters the currently selected link

    Returns
    -------
    plotly.graph_objects.Figure
    """
    csv_path = Path(csv_path)
    if not csv_path.exists():
        raise FileNotFoundError(f"CSV not found: {csv_path}")

    df = pd.read_csv(csv_path)

    needed = {x_col, y_col, color_col, link_col}
    missing = needed - set(df.columns)
    if missing:
        raise ValueError(f"Missing required columns in {csv_path.name}: {sorted(missing)}")

    df_plot = df.copy()

    # numeric coercion
    df_plot[x_col] = pd.to_numeric(df_plot[x_col], errors="coerce")
    df_plot[y_col] = pd.to_numeric(df_plot[y_col], errors="coerce")
    df_plot = df_plot.dropna(subset=[x_col, y_col, color_col, link_col])

    # sorted x-axis via categorical
    dep_order = sorted(df_plot[x_col].unique())
    x_cat_col = f"{x_col}_cat"
    df_plot[x_cat_col] = pd.Categorical(df_plot[x_col], categories=dep_order, ordered=True)

    if hover_cols is None:
        hover_cols = [link_col, color_col, x_col, y_col]

    # IMPORTANT: make one trace per (link, building_block) so dropdowns can toggle visibility
    df_plot["_trace_group"] = df_plot[link_col].astype(str) + " | " + df_plot[color_col].astype(str)

    fig = px.box(
        df_plot,
        x=x_cat_col,
        y=y_col,
        color="_trace_group",
        points="all",
        hover_data=list(hover_cols),
        title=title,
    )

    fig.update_layout(
        xaxis_title=x_col.replace("_", " ").title(),
        yaxis_title=y_col.replace("_", " ").title(),
        boxmode="group",
    )

    # Build trace lookup: trace.name == "_trace_group" (usually)
    trace_names = [t.name for t in fig.data]

    links = sorted(df_plot[link_col].astype(str).unique().tolist())
    bbs = sorted(df_plot[color_col].astype(str).unique().tolist())

    # Helper to build visibility mask
    def vis_mask(selected_link: Optional[str], selected_bb: Optional[str]) -> list[bool]:
        mask = []
        for name in trace_names:
            # name like "<link> | <bb>"
            # robust parse:
            if " | " in name:
                link_val, bb_val = name.split(" | ", 1)
            else:
                link_val, bb_val = "", name

            ok = True
            if selected_link is not None:
                ok = ok and (link_val == selected_link)
            if selected_bb is not None:
                ok = ok and (bb_val == selected_bb)
            mask.append(ok)
        return mask

    # Default selections: All links + All building blocks
    default_visible = [True] * len(fig.data)

    # --- Link dropdown (updatemenu 0) ---
    link_buttons = [
        dict(
            label="All",
            method="update",
            args=[
                {"visible": vis_mask(None, None)},
                {"title": f"{title} (All links)"},
            ],
        )
    ]
    for lk in links:
        link_buttons.append(
            dict(
                label=str(lk),
                method="update",
                args=[
                    {"visible": vis_mask(str(lk), None)},
                    {"title": f"{title} (Link {lk})"},
                ],
            )
        )

    # --- Building block dropdown (updatemenu 1) ---
    # Note: This dropdown does NOT “remember” the link selection by itself (Plotly limitation).
    # It filters from the full set again. We handle this by offering BB filters that work
    # regardless, and the link dropdown is the primary one.
    bb_buttons = [
        dict(
            label="All",
            method="update",
            args=[
                {"visible": vis_mask(None, None)},
                {"title": f"{title} (All building blocks)"},
            ],
        )
    ]
    for bb in bbs:
        bb_buttons.append(
            dict(
                label=str(bb),
                method="update",
                args=[
                    {"visible": vis_mask(None, str(bb))},
                    {"title": f"{title} ({bb})"},
                ],
            )
        )

    fig.update_layout(
        updatemenus=[
            dict(
                buttons=link_buttons,
                direction="down",
                x=0.0,
                xanchor="left",
                y=1.18,
                yanchor="top",
                showactive=True,
            ),
            dict(
                buttons=bb_buttons,
                direction="down",
                x=0.2,
                xanchor="left",
                y=1.18,
                yanchor="top",
                showactive=True,
            ),
            
        ],
        margin=dict(r=280),
        
    )
    

    return fig



def save_plot_html(fig: go.Figure, out_html: str | Path) -> Path:
    """
    Convenience helper: save the plotly figure to HTML.
    """
    out_html = Path(out_html)
    out_html.parent.mkdir(parents=True, exist_ok=True)
    fig.write_html(out_html, include_plotlyjs="cdn")
    return out_html
