rm(list = ls())

library(patchwork)
library(jsonlite)

# Load libraries for interactivity and saving HTML
library(plotly)
library(htmlwidgets)

# Load necessary libraries for data handling, XML parsing, and plotting
library(data.table)
library(tidyverse)
library(ggplot2)
library(xml2)
library(lubridate)
library(scales)

# Load uitls
utilsDir <- rstudioapi::getSourceEditorContext()$path |> dirname()
source(file.path(utilsDir, "analysis_utils.R"))
source(file.path(utilsDir, "weg_zeit_diagramme_utils.R"))


filer <- '//filer22l/K-UE220L/IFI/FTO/SAM.A13783/'

# run_id <- "output_20251030_ik"
# run_id <- "output_20251031_uc1_n100_th" 
# run_id <- "output_20251102_uc1_n100_mu"
# run_id <- "output_20251117_it2_n100"
# run_id <- "output_20260127_it3_n5"
run_id <- "output_20260126_it3_n100"

baseDirectory <- paste0(filer, "04_projects/42_gzb_railsim/", run_id, "/")

# for local analysis
filer <- "C://devsbb/git/railsim-experiments/"
run_id <- "output_20251117_it2_n100"
baseDirectory <- paste0(filer, run_id, "/")



config <- fromJSON(file.path(baseDirectory,"output_project_config.json"))
config$run_id <- run_id


usecase <- "uc_2"
usecaseDirectory <- paste0(baseDirectory, usecase, "/")

buildingBlocks <- list.dirs(path = usecaseDirectory, recursive = FALSE)




# --- READ RESULTS FROM CSV FILES---

read_detailed <- TRUE
read_detailed <- FALSE
earliest_arrival <- 3600
latest_arrival <- earliest_arrival + 3600

if (read_detailed){
  # TODO: read_detailed-Funktion auf die neue Struktur anpassen.
  
  #Read all detailed Tables
  #This may take several hours when reading from filer.
  big_dt <- read_detailed_Results(buildingBlocks)
  
  #big_dt <- fread(paste0(baseDirectory, "big_dt.csv"))
  
  #Filtere auf den jeweils letzten Stop um die Verspätung am ziel zu identifizieren.
  df <- big_dt |> 
    group_by(building_block, sample, route_id, departure_id) |> mutate(n_stop = max(stop_sequence)) |> ungroup() |> 
    filter(n_stop == stop_sequence) |> 
    filter(planned_arrival > earliest_arrival & planned_arrival <= latest_arrival)
  
  # Pro sample die Summe der arrival_delay berechnen
  sample_sums <- df %>%
    group_by(building_block, schedule, sample) %>%   # group_by nur schedule+sample wäre auch ok
    summarise(total_arrival_delay = sum(arrival_delay, na.rm = TRUE),
              n_trains = n(),
              .groups = "drop") |> 
    rename(total_delay_at_destination = total_arrival_delay)
} else {
  
  
  #Read summary results
  df <- read_summaryResults(buildingBlocks)
  
  sample_sums <- df # |> 
  #   rename(schedule = subvariant_id)
  
}

sample_sums <- sample_sums |> 
  #separate(schedule, into = c("schedule_type", "schedule_scaling"), sep = "\\.", extra = "merge", remove = FALSE) |> 
  #mutate(schedule_scaling_int = as.integer(schedule_scaling),
  #       schedule_type = str_to_upper(schedule_type)) |> 
  mutate(n_trains = train_volume * 2) |>  #TODO: read train_volume-time (eg 1800 sec from operational-plan.json (use-case-specific))
  mutate(operating_mode = str_to_upper(operating_mode)) |> 
  mutate(total_delay_per_train = mid_hour_delay_at_destination / n_trains) |> 
  mutate(schedule = paste0(operating_mode, "_", sprintf("%02d", train_volume)))

# --- ECDF WITH PLOTLY ---

# Customized ECDF's



#schedules <- sort(unique(sample_sums$schedule_type))
operating_modes <- sort(unique(sample_sums$operating_mode))

#Define visual properties
line_specs <- tibble(
  #schedule = schedules,
  schedule = c("KM1.1", "KM1.2", "KM2.1", "KM2.2", "KM3.1", "KM3.2",
               "M1.1", "M1.2", "R1.1", "R1.2", "R2.1", "R2.2"),
  color = c("darkred", "darkred", "red", "red", "pink", "pink",
            "darkgreen", "darkgreen", "blue", "blue", "violet", "violet"),
  linetype = c("solid", "twodash", "solid", "twodash", "solid", "twodash", 
               "solid", "twodash", "solid", "twodash", "solid", "twodash"),
  linewidth = c(.8, 1, .8, 1, .8, 1, .8, 1, .8, 1, .8, 1)
)
line_specs <- tibble(
  schedule = str_to_upper(c("KM1_FV_PASS", "KM1_FV_STOP", "KM2_FV_PASS", "KM2_FV_STOP", "M1",          "R1",          "R2" )),
  color =    c("darkred",     "red",         "darkgray",    "lightgray",   "green",       "blue",       "violet"),
  linetype = rep("solid", length(schedules)),
  linewidth = rep(1, length(schedules))
)

if(usecase == "uc_1"){
  # line specs for UC1 
  line_specs <- tibble(
    om = str_to_upper(c("KM_FV_PASS", "KM_FV_STOP", "M_STANDARD", "R_FV_STOP")),
    color = c(           "darkgreen", "green",      "darkgray",   "blue"),
    linetype = rep("solid", length(operating_modes)),
    linewidth = rep(1, length(operating_modes))
  )
} else if(usecase == "uc_2") {
  # line specs for UC2 
  line_specs <- tibble(
    om = str_to_upper(c("M_BALANCED", "M_TRUNK", "R_BALANCED", "R_TRUNK")),
    color = c(           "gray30", "gray66",      "blue",     "lightblue"),
    linetype = rep("solid", length(operating_modes)),
    linewidth = rep(1, length(operating_modes))
  )
}

sample_sums_plot <- sample_sums |> 
  left_join(line_specs, by = c("operating_mode" = "om"))


# # 1️⃣ ECDF-Daten vorbereiten
# ecdf_data <- sample_sums_plot %>%
#   group_by(building_block, schedule_type, schedule_scaling, schedule) %>%
#   arrange(total_delay_at_destination) %>%
#   mutate(
#     ecdf_y = ecdf(total_delay_at_destination)(total_delay_at_destination)
#   ) %>%
#   ungroup() |> 
#   group_by(building_block, schedule_type, schedule_scaling, schedule, total_delay_at_destination, ecdf_y) |> 
#   summarise(sample_list = paste(sample, collapse = "<br>")) |> 
#   ungroup()

# 2️⃣ Farben und Linientypen aus line_specs übernehmen
color_map <- setNames(line_specs$color, line_specs$om)
linetype_map <- setNames(line_specs$linetype, line_specs$om)
linewidth_map <- setNames(line_specs$linewidth, line_specs$om)

# # 3️⃣ Mit ggplot vorbereiten (optional, für konsistentes Layout)
# p_ecdf_base <- ggplot(ecdf_data, aes(x = total_delay_at_destination, y = ecdf_y, group = schedule_scaling)) +
#   geom_line(aes(color = schedule_type)) +
#   geom_point(aes(color = schedule_type, text = paste0(
#     "Schedule: ", schedule, "<br>",
#     "Building block: ", building_block, "<br>",
#     "Samples: ", sample_list, "<br>",
#     "Delay: ", round(total_delay_at_destination, 2)
#   )), size = .8, alpha = 0.6) +
#   #facet_wrap(~ building_block, scales = "free_x", ncol = 2) +
#   facet_grid(schedule_type ~ building_block, scales = "free_x") +
#   scale_color_manual(values = color_map) +
#   #scale_linetype_manual(values = linetype_map) +
#   #scale_linewidth_manual(values = linewidth_map) +
#   theme_minimal(base_size = 12) +
#   labs(
#     x = "Arrival Delay",
#     y = "Kumulative Verteilungsfunktion (ECDF)",
#     title = paste0("ECDF of arrival delays (Run-ID = ", config$run_id, ")"),
#     color = "Schedule_type",
#     linetype = "Schedule",
#     linewidth = "Schedule"
#   ) +
#   theme(
#     legend.position = "bottom",
#     axis.text.x = element_text(angle = 45, hjust = 1)
#   )
# 


plot_ecdf_variable <- function(
    data,
    var,                # z.B. total_delay_at_destination
    color = "operating_mode", #"schedule_type",
    group = "n_trains", #"schedule_scaling_int",
    x_facet = "building_block",
    y_facet = "operating_mode", #"schedule_type",
    x_label = NULL,
    y_label = "cumulative distribution (ECDF)",
    title = NULL,
    subtitle = NULL,
    logarithmic = FALSE,
    x_lim = NULL
) {
  # gernereischer Umgang mit Variablenname
  var_sym <- rlang::ensym(var)
  var_color <- rlang::ensym(color)
  var_group <- rlang::ensym(group)
  var_x_facet <- rlang::ensym(x_facet)
  var_y_facet <- rlang::ensym(y_facet)
  
  # ECDF-Daten vorbereiten
  ecdf_data <- data %>%
    group_by(building_block, operating_mode, schedule, n_trains) %>%
    arrange(!!var_sym) %>%
    mutate(
      ecdf_y = ecdf(!!var_sym)(!!var_sym)
    ) %>%
    ungroup() %>% 
    group_by(
      building_block, operating_mode, n_trains,
      schedule, as.factor(!!var_x_facet), !!var_sym, ecdf_y
    ) %>% 
    summarise(sample_list = paste(sample_index, collapse = "<br>"), .groups = "drop")
  
  facet_formula <- rlang::new_formula(var_y_facet, var_x_facet)
  
  # Plot
  p <- ggplot(
    ecdf_data, 
    aes(x = !!var_sym, y = ecdf_y, group = !!var_group)
  ) +
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
    #facet_grid(facet_formula, scales = "free_x") +
    facet_grid(facet_formula)
  if(color == "operating_mode"){
    p <- p +
    scale_color_manual(values = color_map)
  }
  p <- p +
    # Falls gewünscht aktivierbar:
    # scale_linetype_manual(values = linetype_map) +
    # scale_linewidth_manual(values = linewidth_map) +
    theme_minimal(base_size = 12) +
    labs(
      x = x_label,
      y = y_label,
      title = title,
      subtitle = subtitle,
      color = color,
      linetype = "Schedule",
      linewidth = "Schedule"
    ) +
    theme(
      legend.position = "bottom",
      axis.text.x = element_text(angle = 45, hjust = 1)
    )
  
  if (logarithmic){
    p <- p + scale_x_log10(limits = c(1,1e4))
  } else {
    if(!is.null(x_lim)){
      p <- p + scale_x_continuous(limits = x_lim)
    }
    
  }
  
  
  return(p)
}

# p_ecdf_base <- plot_ecdf_variable(
#   data = sample_sums_plot,
#   var = total_delay_at_destination,
#   x_label = "Arrival Delay",
#   title = paste0("ECDF Total Arrival Delay, (Run ID = ", config$run_id, ")")
# )
# p_ecdf_base

p_ecdf_base <- plot_ecdf_variable(
  data = sample_sums_plot,
  var = total_delay_per_train,
  #var = mid_hour_delay_at_destination,
  x_label = "Arrival Delay",
  title = paste0("ECDF Arrival Delay per Train (mid-hour), (Run ID = ", config$run_id, ")"),
  #x_lim = c(0,600)
)
p_ecdf_base

# Comparison of Building Blocks
p_ecdf_base <- plot_ecdf_variable(
  data = sample_sums_plot |> mutate(n_trains = as.factor(n_trains)),
  color = "building_block",
  group = "building_block",
  #x_facet = "schedule_scaling_int",
  #y_facet = "schedule_type",
  x_facet = "n_trains",
  y_facet = "operating_mode",
  var = total_delay_per_train,
  #var = mid_hour_delay_at_destination,
  x_label = "Arrival Delay",
  title = paste0("ECDF Arrival Delay per Train, (Run ID = ", config$run_id, ")"),
  logarithmic = FALSE,
  #x_lim = c(0,600)
)
p_ecdf_base

# p_ecdf_base <- plot_ecdf_variable(
#   data = sample_sums_plot,
#   var = total_delay_per_train,
#   x_label = "Delay per Train",
#   title = paste0("ECDF Arrival Delay per Train, (Run ID = ", config$run_id, ")")
# )
# p_ecdf_base



# 4️⃣ ggplot → plotly konvertieren
p_ecdf_plotly <- ggplotly(p_ecdf_base, tooltip = "text")

# 5️⃣ Plot anzeigen
p_ecdf_plotly


bb_to_filter <- "uc1_bb1"
schedtype_to_filter <- "M1"

sample_sums_plot_filtered <- sample_sums_plot |> filter(building_block == bb_to_filter & schedule_type == schedtype_to_filter)



# --- SHOW AND SAVE WEG-ZEIT-DIAGRAMM FROM ONE SPECIFIC SAMPLE ---


#  Weg-Zeit-Diagramm für ein Sample.

line_colors <- c(
  # for use_case_1
  "FV_LR"  = "palegreen4",
  "FV_LMR" = "palegreen2",
  "RV_LMR" = "deepskyblue4",
  "GV_LR" = "gray30",
  
  # for use_case_2
  "FV_AB" = "palegreen4",
  "FV_BA" = "palegreen4",
  "FV_AC" = "palegreen2",
  "FV_CA" = "palegreen2",
  "GV_AB" = "gray30",
  "GV_BA" = "gray30",
  "GV_AC" = "gray70",
  "GV_CA" = "gray70"
)

weg_zeit_diagram(baseDirectory = baseDirectory, 
                 
                 usecase = "uc_2",
                 buildingBlock = "uc2_bb1",
                 operating_mode = "R_BALANCED",
                 volume = "12",
                 sample = "074",
                 
                 # usecase = "uc_1",
                 # buildingBlock = "uc1_bb1",
                 # operating_mode = "R_FV_STOP",
                 # volume = "09",
                 # sample = "069",
                 
                 
                 #line_colors = NULL
                 line_color = line_colors
                 )






# --- SAME, BUT AS BOXPLOT ---  

plot_box_variable <- function(
    data,
    x_var,                 # z.B. schedule_scaling_int
    y_var,                 # z.B. total_delay_at_destination
    x_label = NULL,
    y_label = NULL,
    title = NULL,
    subtitle = NULL,
    logarithmic = FALSE
) {
  x_sym <- rlang::ensym(x_var)
  y_sym <- rlang::ensym(y_var)
  
  p <- ggplot(
    data,
    aes(x = as.factor(!!x_sym), y = !!y_sym)
  ) +
    geom_boxplot(outlier.shape = NA) +
    geom_jitter(alpha = 0.5, size = 0.5, color = "gray40") +
    facet_grid(operating_mode ~ building_block, scales = "free_x") +
    theme_minimal(base_size = 12) +
    labs(
      x = x_label,
      y = y_label,
      title = title,
      subtitle = subtitle
    ) +
    theme(
      axis.text.x = element_text(angle = 45, hjust = 1)
    )
  
  if (logarithmic){
    p <- p + scale_y_log10()
  }
  
  return(p)
}

plot_box_variable(
  data = sample_sums_plot,
  x_var = n_trains,
  y_var = total_delay_per_train,
  x_label = "Operating MOde",
  y_label = "Arrival Delays",
  title = "Arrival Delay Distriution",
  subtitle = paste("Run ID:", config$run_id)
)

plot_box_variable(
  data = sample_sums_plot,
  x_var = n_trains,
  y_var = total_delay_per_train,
  x_label = "Operating MOde",
  y_label = "Arrival Delays",
  title = "Arrival Delay Distriution",
  subtitle = paste("Run ID:", config$run_id),
  logarithmic = TRUE
)


# plot_box_variable(
#   data = sample_sums_plot,
#   x_var = schedule_scaling_int,
#   y_var = total_delay_per_train,
#   x_label = "Schedule",
#   y_label = "Arrival Delays",
#   title = "Arrival Delay per Train",
#   subtitle = paste("Run ID:", config$run_id)
# )
# plot_box_variable(
#   data = sample_sums_plot,
#   x_var = schedule_scaling_int,
#   y_var = total_delay_per_train,
#   x_label = "Schedule",
#   y_label = "Arrival Delays",
#   title = "Arrival Delay per Train",
#   subtitle = paste("Run ID:", config$run_id),
#   logarithmic = TRUE
# )





# --- MORE DIAGRAMS OF DELAY DISTRIBUTIONS ---

# --- FIRST DRAFT OF ECDF-DIAGRAM ---





# Distribution Statistics
quantiles <- c(0, .02, .05, .1, .5, 1)

schedule_stats <- sample_sums |> 
  #group_by(building_block, schedule_type, schedule_scaling, schedule_scaling_int, schedule) |> 
  group_by(building_block, operating_mode, n_trains, schedule) |> 
  
  # summarise(
  #   delay_min   = min(total_delay_at_destination, na.rm = TRUE),
  #   delay_5pct  = quantile(total_delay_at_destination, probs = 0.05, na.rm = TRUE, type = 7),
  #   delay_median= median(total_delay_at_destination, na.rm = TRUE),
  #   delay_mean  = mean(total_delay_at_destination, na.rm = TRUE),
  #   delay_95pct = quantile(total_delay_at_destination, probs = 0.95, na.rm = TRUE, type = 7),
  #   delay_max   = max(total_delay_at_destination, na.rm = TRUE),
  #   n_samples   = n(),
  #   .groups = "drop"
  # ) 
  summarise(
    # --- Delay statistics for total delay ---
    delay_tt_qq = list(quantile(
      mid_hour_delay_at_destination,
      probs = quantiles,
      na.rm = TRUE
    )),
    delay_tt_mean = mean(mid_hour_delay_at_destination, na.rm = TRUE),
    delay_tt_prob = {
      F <- ecdf(mid_hour_delay_at_destination)
      tibble(
        p_00sec = F(00),
        p_10sec = F(10),
        p_30sec = F(30),
        p_60sec = F(60)
      )
    },
    # --- Delay statistics for delay per train ---
    delay_pt_qq = list(quantile(
      total_delay_per_train,
      probs = quantiles,
      na.rm = TRUE
    )),
    delay_pt_mean = mean(total_delay_per_train, na.rm = TRUE),
    delay_pt_prob = {
      F <- ecdf(total_delay_per_train)
      tibble(
        p_00sec = F(00),
        p_10sec = F(10),
        p_30sec = F(30),
        p_60sec = F(60)
      )
    },
    n_samples = dplyr::n(),
    .groups = "drop"
  ) |> 
  unnest_wider(delay_tt_qq, names_sep = "_") |> 
  unnest_wider(delay_pt_qq, names_sep = "_") |> 
  # unnest_wider(delay_tt_prob, names_sep = "tt_prob_") |> 
  # unnest_wider(delay_pt_prob, names_sep = "pt_prob_")
  unnest_wider(delay_tt_prob, names_sep = "_") |> 
  unnest_wider(delay_pt_prob, names_sep = "_")




# Long-Format
schedule_stats_long <- schedule_stats |>
  pivot_longer(
    cols = starts_with("delay_"),
    names_to = c("which", "stat"),
    values_to = "value",
    # capture group 1: tt oder pt
    # capture group 2: restlicher name (stat)
    names_pattern = "^delay_(tt|pt)_(.*)$"
  ) |>
  pivot_wider(
    names_from = which,
    values_from = value,
    names_prefix = "value_"
  )
schedule_stats_long <- schedule_stats_long |> 
  mutate(stat_factor = factor(stat, 
                              levels = schedule_stats_long |> 
                                filter(grepl("^qq_", stat)) |> 
                                mutate(quantile_number = as.numeric(gsub("qq_|_|%", "", stat))) |> 
                                arrange(quantile_number) |> 
                                distinct(stat) |> 
                                pull(stat)
                                ))


schedule_stats_long_filter <- schedule_stats_long |> filter(building_block == bb_to_filter & schedule_type == schedtype_to_filter)


quantile_plot <- ggplot(
  schedule_stats_long |> 
    filter(grepl("^qq_", stat)),
  aes(x = as.factor(n_trains),
      #value_tt -> total (summed) arrival delay. value_pt -> arrival delay PER TRAIN
      #y = value_tt, 
      y = value_pt,
      group = stat_factor,
      color = stat_factor)) + 
  geom_line() +
  facet_grid(operating_mode ~ building_block, scales = "free_x") +
  theme_minimal(base_size = 12) +
  labs(
    x = "Schedule",
    # y = "Arrival Delay per Train",
    y = "Arrival Delay",
    # title = "Quantiles for Total Delay",
    title = "Quantiles for Delay per Train",
    color = "Statistik",
    linetype = "Statistik"
  ) +
  theme(
    axis.text.x = element_text(angle = 45, hjust = 1),
    legend.position = "bottom"
  )
quantile_plot


# Plot als „Tabellen“:
quantile_table_plot <- ggplot(
  schedule_stats_long |> 
    filter(grepl("^qq_", stat)),
  aes(
    x = as.factor(n_trains),
    y = stat_factor  # jede Statistik auf einer eigenen y-Position
  )) +
  geom_tile(aes(fill = value_pt), color = "gray50", linewidth = 0.5) + 
  geom_text(aes(label = round(value_pt, 0)), size = 3) +  # Werte als Text
  # Farbverlauf definieren:
  # - Werte = 0 sollen grün sein
  # - Werte > 0 bis 180 sollen von Gelb nach Rot verlaufen
  scale_fill_gradientn(
    colors = c("green", "yellow", "orange", "red", "darkred"), # Definieren Sie die Zwischenfarben
    values = scales::rescale(c(0, 0.01, 90, 180, 600)), # Setzt die Positionen der Farben im Verlauf
    limits = c(0, 600), # Stellen Sie sicher, dass die Skala bis 180 geht
    oob = scales::squish, # Stellt sicher, dass Werte > 180 auf den Wert 180 (Rot) projiziert werden
    na.value = "grey" # Für fehlende Werte
  ) +
  facet_grid(operating_mode ~ building_block, scales = "free_x") +
  theme_minimal(base_size = 12) +
  labs(
    x = "Schedule",
    y = "Quantile",
    title = "Quantiles for Delay per Train"
  ) +
  theme(
    axis.text.x = element_text(angle = 45, hjust = 1),
    axis.text.y = element_text(face = "bold")
  )
quantile_table_plot

p02_quantile_plot <- ggplot(
  schedule_stats_long |> 
    filter(grepl("^qq_2%", stat)),
  aes(
    x = as.factor(n_trains),
    y = operating_mode  
  )) +
  scale_y_discrete(limits = rev) +
  geom_tile(aes(fill = value_pt), color = "gray50", linewidth = 0.5) + 
  geom_text(aes(label = round(value_pt, 0)), size = 3) +  # Werte als Text
  # Farbverlauf definieren:
  # - Werte = 0 sollen grün sein
  # - Werte > 0 bis 180 sollen von Gelb nach Rot verlaufen
  scale_fill_gradientn(
    name = "Delay [s]\n(2%-Quantile)",
    colors = c("green", "yellow", "orange", "red", "darkred"), # Definieren Sie die Zwischenfarben
    values = scales::rescale(c(0, 0.01, 90, 180, 600)), # Setzt die Positionen der Farben im Verlauf
    limits = c(0, 600), # Stellen Sie sicher, dass die Skala bis 180 geht
    oob = scales::squish, # Stellt sicher, dass Werte > 180 auf den Wert 180 (Rot) projiziert werden
    na.value = "grey" # Für fehlende Werte
  ) +
  facet_wrap( ~ building_block, scales = "free_x", nrow = 1) +
  theme_minimal(base_size = 12) +
  labs(
    x = "Trains per Hour",
    y = "Operating Mode",
    title = "2%-Quantiles for Delay per Train (in seconds)"
  ) +
  theme(
    axis.text.x = element_text(angle = 45, hjust = 1),
    axis.text.y = element_text(face = "bold")
  )
p02_quantile_plot

quality_line_plot <- ggplot(
  schedule_stats_long |> 
    #filter(grepl("^prob_p", stat)),  #alle probabilities 
    filter(stat == "prob_p_00sec"),  #nur die Wahrscheinlichkeit für Delay == 0 Sec
  aes(x = as.factor(n_trains), 
      y = value_pt, #value_tt -> total (summed) arrival delay. value_pt -> arrival delay PER TRAIN
      group = stat,
      color = stat)) +
  geom_line() +
  geom_hline(
    yintercept = 0.02,
    linetype = "dashed",
    color = "grey80",
    linewidth = .7,
    alpha = .7
  ) + 
  annotate(
    "text",
    x = -Inf,
    y = 0.02,
    label = "2%",
    hjust = -.5,
    vjust = -0.3,
    color = "grey50",
    size = 3
  ) +
  facet_grid(operating_mode ~ building_block, scales = "free_x") +
  theme_minimal(base_size = 12) +
  labs(
    x = "Schedule",
    y = "Probabilty for less Delay",
    title = "Probability of Punctuality",
    color = "Statistik",
    linetype = "Statistik"
  ) +
  theme(
    axis.text.x = element_text(angle = 45, hjust = 1),
    legend.position = "bottom"
  )
quality_line_plot

quality_bar_plot <-  ggplot(
  schedule_stats_long |> 
    #filter(grepl("^prob_p", stat)),  #alle probabilities 
    filter(stat == "prob_p_00sec"),  #nur die Wahrscheinlichkeit für Delay == 0 Sec
  aes(x = as.factor(n_trains), 
      y = value_pt, #value_tt -> total (summed) arrival delay. value_pt -> arrival delay PER TRAIN
      fill = value_pt)) +
  geom_col(color = "white", linewidth = 0.2) +
  geom_text(
    aes(label = scales::percent(value_pt, accuracy = 0.1)),
    vjust = -0.5,     # Schiebt den Text nach oben
    size = 3,
    fontface = "bold"
  ) +
  scale_fill_gradientn(
    colors = c("red", "orange", "yellow", "green", "darkgreen"),
    values = scales::rescale(c(0, 0.025, 0.05, 0.1, 1)),
    limits = c(0, 1),
    oob = scales::squish,
    na.value = "grey"
  ) +
  geom_hline(
    yintercept = 0.02,
    linetype = "dashed",
    color = "grey80",
    linewidth = .7,
    alpha = .7
  ) + 
  annotate(
    "text",
    x = -Inf,
    y = 0.02,
    label = "2%",
    hjust = -.5,
    vjust = -0.3,
    color = "grey50",
    size = 3
  ) +
  facet_grid(operating_mode ~ building_block, scales = "free_x") +
  
  # Y-Achse als Prozent formatieren und Platz für Labels oben schaffen
  scale_y_continuous(labels = scales::percent, expand = expansion(mult = c(0, 0.15))) +
  
  theme_minimal(base_size = 12) +
  labs(
    x = "Schedule",
    y = "Probabilty for Punctuality",
    title = "Probability of Punctuality (0 sec delay)",
    fill = "Wahrscheinlichkeit"
  ) +
  theme(
    axis.text.x = element_text(angle = 45, hjust = 1),
    legend.position = "right",
    panel.grid.major.x = element_blank() # Optionale Verschönerung für Bar-Plots
  )
quality_bar_plot


quality_table_plot <- ggplot(
  schedule_stats_long |> 
    filter(grepl("^prob_p", stat)),
  aes(
    x = as.factor(n_trains),
    y = stat  # jede Statistik auf einer eigenen y-Position
  )) +
  geom_tile(aes(fill = value_pt), color = "gray50", linewidth = 0.5) + 
  geom_text(aes(label = round(value_pt, 2)), size = 3) +  # Werte als Text
  # Farbverlauf definieren:
  # - Werte = 0 sollen grün sein
  # - Werte > 0 bis 180 sollen von Gelb nach Rot verlaufen
  scale_fill_gradientn(
    colors = c("red", "orange", "yellow", "green", "darkgreen"), # Definieren Sie die Zwischenfarben
    values = scales::rescale(c(0, 0.025, 0.05, 0.1, 1)), # Setzt die Positionen der Farben im Verlauf
    limits = c(0, 1), # Stellen Sie sicher, dass die Skala bis 180 geht
    oob = scales::squish, # Stellt sicher, dass Werte > 180 auf den Wert 180 (Rot) projiziert werden
    na.value = "grey" # Für fehlende Werte
  ) +
  facet_grid(operating_mode ~ building_block, scales = "free_x") +
  theme_minimal(base_size = 12) +
  labs(
    x = "Schedule",
    y = "Delay Quality",
    title = "Probability of Mean Delay lower than Quality-Benchmark",
    subtitle = "Numbers > 0.00 indicate Existance of a Schedule that meet this Quality Benchmark"
  ) +
  theme(
    axis.text.x = element_text(angle = 45, hjust = 1),
    axis.text.y = element_text(face = "bold")
  )
quality_table_plot






