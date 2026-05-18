# =============================================================================
# Railsim Experiments - Use Case Analysis
#
# This script reads simulation results and generates diagnostic plots:
# - ECDF plots of delay distributions
# - Boxplots of delay per train
# - Quantile heatmaps
# - Punctuality probability charts
# - Time-distance diagrams
#
# Usage: Adjust the configuration section below to point to your output directory,
#        then run interactively in RStudio or source the file.
# =============================================================================

rm(list = ls())

# --- Libraries ---
library(patchwork)
library(jsonlite)
library(plotly)
library(htmlwidgets)
library(data.table)
library(tidyverse)
library(ggplot2)
library(xml2)
library(lubridate)
library(scales)

# --- Load utility functions ---
utils_dir <- rstudioapi::getSourceEditorContext()$path |> dirname()
source(file.path(utils_dir, "utils", "result_reader.R"))
source(file.path(utils_dir, "utils", "time_distance_diagram.R"))


# =============================================================================
# CONFIGURATION - Adjust these paths and parameters
# =============================================================================

base_directory <- "/path/to/your/experiment/output/"
usecase <- "uc_1"

config <- fromJSON(file.path(base_directory, "output_project_config.json"))

usecase_directory <- file.path(base_directory, usecase)
building_blocks <- list.dirs(path = usecase_directory, recursive = FALSE)


# =============================================================================
# READ RESULTS
# =============================================================================

# Read summary results (fast)
sample_sums <- read_summary_results(building_blocks)

sample_sums <- sample_sums |>
  mutate(n_trains = train_volume * 2) |>
  mutate(operating_mode = str_to_upper(operating_mode)) |>
  mutate(total_delay_per_train = mid_hour_delay_at_destination / n_trains) |>
  mutate(schedule = paste0(operating_mode, "_", sprintf("%02d", train_volume)))


# =============================================================================
# ECDF PLOT FUNCTION
# =============================================================================

#' Plot empirical cumulative distribution function (ECDF) for a given variable.
#'
#' @param data Data frame with simulation results.
#' @param var Variable to plot on x-axis (unquoted).
#' @param color Grouping variable for color aesthetic.
#' @param group Grouping variable for line grouping.
#' @param x_facet Variable for column facets.
#' @param y_facet Variable for row facets.
#' @param x_label X-axis label.
#' @param y_label Y-axis label.
#' @param title Plot title.
#' @param subtitle Plot subtitle.
#' @param logarithmic Use log10 x-axis.
#' @param x_lim Optional x-axis limits.
plot_ecdf_variable <- function(data, var,
                               color = "operating_mode",
                               group = "n_trains",
                               x_facet = "building_block",
                               y_facet = "operating_mode",
                               x_label = NULL,
                               y_label = "Cumulative Distribution (ECDF)",
                               title = NULL,
                               subtitle = NULL,
                               logarithmic = FALSE,
                               x_lim = NULL) {
  var_sym <- rlang::ensym(var)
  var_color <- rlang::ensym(color)
  var_group <- rlang::ensym(group)
  var_x_facet <- rlang::ensym(x_facet)
  var_y_facet <- rlang::ensym(y_facet)

  ecdf_data <- data %>%
    group_by(building_block, operating_mode, schedule, n_trains) %>%
    arrange(!!var_sym) %>%
    mutate(ecdf_y = ecdf(!!var_sym)(!!var_sym)) %>%
    ungroup() %>%
    group_by(building_block, operating_mode, n_trains, schedule,
             as.factor(!!var_x_facet), !!var_sym, ecdf_y) %>%
    summarise(sample_list = paste(sample_index, collapse = "<br>"), .groups = "drop")

  facet_formula <- rlang::new_formula(var_y_facet, var_x_facet)

  p <- ggplot(ecdf_data, aes(x = !!var_sym, y = ecdf_y, group = !!var_group)) +
    geom_line(aes(color = !!var_color)) +
    geom_point(
      aes(color = !!var_color, text = paste0(
        "Schedule: ", schedule, "<br>",
        "Building block: ", building_block, "<br>",
        "Samples: ", sample_list, "<br>",
        "Value: ", round(!!var_sym, 2)
      )),
      size = .8, alpha = 0.6
    ) +
    facet_grid(facet_formula) +
    theme_minimal(base_size = 12) +
    labs(x = x_label, y = y_label, title = title, subtitle = subtitle, color = color) +
    theme(legend.position = "bottom", axis.text.x = element_text(angle = 45, hjust = 1))

  if (logarithmic) {
    p <- p + scale_x_log10(limits = c(1, 1e4))
  } else if (!is.null(x_lim)) {
    p <- p + scale_x_continuous(limits = x_lim)
  }

  p
}


# =============================================================================
# BOXPLOT FUNCTION
# =============================================================================

#' Boxplot for comparing delay distributions across operating modes and building blocks.
plot_box_variable <- function(data, x_var, y_var,
                              x_label = NULL, y_label = NULL,
                              title = NULL, subtitle = NULL,
                              logarithmic = FALSE) {
  x_sym <- rlang::ensym(x_var)
  y_sym <- rlang::ensym(y_var)

  p <- ggplot(data, aes(x = as.factor(!!x_sym), y = !!y_sym)) +
    geom_boxplot(outlier.shape = NA) +
    geom_jitter(alpha = 0.5, size = 0.5, color = "gray40") +
    facet_grid(operating_mode ~ building_block, scales = "free_x") +
    theme_minimal(base_size = 12) +
    labs(x = x_label, y = y_label, title = title, subtitle = subtitle) +
    theme(axis.text.x = element_text(angle = 45, hjust = 1))

  if (logarithmic) {
    p <- p + scale_y_log10()
  }

  p
}


# =============================================================================
# EXAMPLE USAGE
# =============================================================================

# ECDF of delay per train
p_ecdf <- plot_ecdf_variable(
  data = sample_sums,
  var = total_delay_per_train,
  x_label = "Arrival Delay per Train [s]",
  title = "ECDF of Arrival Delay per Train (Mid-Hour)"
)
p_ecdf

# Boxplot of delay per train
p_box <- plot_box_variable(
  data = sample_sums,
  x_var = n_trains,
  y_var = total_delay_per_train,
  x_label = "Trains per Period",
  y_label = "Arrival Delay per Train [s]",
  title = "Delay Distribution by Volume"
)
p_box

# Interactive ECDF
ggplotly(p_ecdf, tooltip = "text")


# =============================================================================
# TIME-DISTANCE DIAGRAM EXAMPLE
# =============================================================================

line_colors <- c(
  "FV_LR"  = "palegreen4",
  "FV_LMR" = "palegreen2",
  "RV_LMR" = "deepskyblue4",
  "GV_LR"  = "gray30"
)

# time_distance_diagram(
#   baseDirectory = base_directory,
#   usecase = "uc_1",
#   buildingBlock = "uc1_bb1",
#   operating_mode = "KM_FV_PASS",
#   volume = "12",
#   sample = "001",
#   line_colors = line_colors
# )
