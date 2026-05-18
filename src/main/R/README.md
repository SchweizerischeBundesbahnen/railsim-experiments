# R Analysis Scripts

R scripts for post-processing and visualizing simulation results.

## Files

- **`analyze_usecases.R`** — Main analysis script with ECDF, boxplot, and quantile visualizations.
- **`utils/result_reader.R`** — Reader functions for loading detailed and summary result CSV files.
- **`utils/time_distance_diagram.R`** — Utility functions for generating interactive time-distance (graphical schedule)
  diagrams.

## Requirements

Install the following R packages:

```r
install.packages(c(
  "data.table", "tidyverse", "ggplot2", "plotly", "htmlwidgets",
  "xml2", "lubridate", "scales", "patchwork", "jsonlite"
))
```

## Usage

1. Run a simulation experiment (see main README).
2. Open `analyze_usecases.R` in RStudio.
3. Adjust the `base_directory` and `usecase` variables in the configuration section.
4. Run the script interactively to generate plots.
