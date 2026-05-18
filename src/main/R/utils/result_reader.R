# --- Reader Functions ---

#' Read detailed train delay results from all building blocks.
#'
#' Scans the 05_analysis directories for train delay CSV files and combines them
#' into a single data.table with metadata columns (source_file, building_block, schedule, sample).
read_detailed_results <- function(buildingBlocks) {
  all_tables <- list()

  for (bb in buildingBlocks) {
    analysis_dir <- file.path(bb, "05_analysis")
    if (!dir.exists(analysis_dir)) next

    schedules <- list.dirs(path = analysis_dir, recursive = FALSE, full.names = TRUE)
    if (length(schedules) == 0) next

    for (sc in schedules) {
      if (!dir.exists(sc)) next

      samples <- list.dirs(path = sc, recursive = FALSE, full.names = TRUE)
      if (length(samples) == 0) next

      for (sa in samples) {
        if (!dir.exists(sa)) next

        files <- list.files(path = sa, pattern = "analysis_train_delays\\.csv$", recursive = TRUE, full.names = TRUE)
        if (length(files) == 0) next

        for (f in files) {
          message("Reading file: ", f)
          try({
            dt <- fread(f)
            dt[, source_file := f]
            dt[, building_block := basename(bb)]
            dt[, schedule := basename(sc)]
            dt[, sample := basename(sa)]
            all_tables[[length(all_tables) + 1L]] <- dt
          }, silent = TRUE)
        }
      }
    }
  }

  if (length(all_tables) > 0) {
    rbindlist(all_tables, use.names = TRUE, fill = TRUE)
  } else {
    data.table()
  }
}


#' Read summary results from all building blocks.
#'
#' Looks for summary_runs.csv files in the 05_analysis directories and combines them.
read_summary_results <- function(buildingBlocks) {
  all_tables <- list()

  for (bb in buildingBlocks) {
    analysis_dir <- file.path(bb, "05_analysis")
    if (!dir.exists(analysis_dir)) next

    files <- list.files(path = analysis_dir, pattern = "summary_runs\\.csv$", recursive = FALSE, full.names = TRUE)
    if (length(files) == 0) next

    for (f in files) {
      try({
        dt <- fread(f)
        dt[, source_file := f]
        dt[, building_block := basename(bb)]
        all_tables[[length(all_tables) + 1L]] <- dt
      }, silent = TRUE)
    }
  }

  if (length(all_tables) > 0) {
    rbindlist(all_tables, use.names = TRUE, fill = TRUE)
  } else {
    data.table()
  }
}

