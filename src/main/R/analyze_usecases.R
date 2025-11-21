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

# Load uitls
utilsDir <- rstudioapi::getSourceEditorContext()$path |> dirname()
source(file.path(utilsDir, "analysis_utils.R"))
source(file.path(utilsDir, "weg_zeit_diagramme_utils.R"))


filer <- '//filer22l/K-UE220L/IFI/FTO/SAM.A13783/'

run_id <- "output_20251030_ik"
run_id <- "output_20251031_uc1_n100_th" 
run_id <- "output_20251102_uc1_n100_mu"
run_id <- "output_20251117_it2_n100"

baseDirectory <- paste0(filer, "04_projects/42_gzb_railsim/", run_id, "/")

# for local analysis
filer <- "C://devsbb/git/railsim-experiments/"
run_id <- "results"
baseDirectory <- paste0(filer, run_id, "/")



config <- fromJSON(file.path(baseDirectory,"output_project_config.json"))
config$run_id <- run_id


usecase <- "uc_1"
usecaseDirectory <- paste0(baseDirectory, usecase, "/")

buildingBlocks <- list.dirs(path = usecaseDirectory, recursive = FALSE)




# --- READ RESULTS FROM CSV FILES---

read_detailed <- TRUE
earliest_arrival <- 3600
latest_arrival <- earliest_arrival + 3600

if (read_detailed){
  #Read all detailed Tables
  big_dt <- read_detailed_Results(buildingBlocks)
  
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
  
  sample_sums <- df |> 
    rename(schedule = subvariant_id)
  
}

sample_sums <- sample_sums |> 
  separate(schedule, into = c("schedule_type", "schedule_scaling"), sep = "\\.", extra = "merge", remove = FALSE) |> 
  mutate(schedule_scaling_int = as.integer(schedule_scaling),
         schedule_type = str_to_upper(schedule_type)) |> 
  mutate(total_delay_per_train = total_delay_at_destination / n_trains)

# --- ECDF WITH PLOTLY ---

# Customized ECDF's



schedules <- sort(unique(sample_sums$schedule_type))

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


sample_sums_plot <- sample_sums |> 
  left_join(line_specs, by = c("schedule_type" = "schedule"))


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
color_map <- setNames(line_specs$color, line_specs$schedule)
linetype_map <- setNames(line_specs$linetype, line_specs$schedule)
linewidth_map <- setNames(line_specs$linewidth, line_specs$schedule)

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
    x_label = NULL,
    title = NULL,
    subtitle = NULL
) {
  # gernereischer Umgang mit Variablenname
  var_sym <- rlang::ensym(var)
  
  # ECDF-Daten vorbereiten
  ecdf_data <- data %>%
    group_by(building_block, schedule_type, schedule_scaling, schedule) %>%
    arrange(!!var_sym) %>%
    mutate(
      ecdf_y = ecdf(!!var_sym)(!!var_sym)
    ) %>%
    ungroup() %>% 
    group_by(
      building_block, schedule_type, schedule_scaling,
      schedule, !!var_sym, ecdf_y
    ) %>% 
    summarise(sample_list = paste(sample, collapse = "<br>"), .groups = "drop")
  
  # Plot
  p <- ggplot(
    ecdf_data, 
    aes(x = !!var_sym, y = ecdf_y, group = schedule_scaling)
  ) +
    geom_line(aes(color = schedule_type)) +
    geom_point(
      aes(color = schedule_type, text = paste0(
        "Schedule: ", schedule, "<br>",
        "Building block: ", building_block, "<br>",
        "Samples: ", sample_list, "<br>",
        "Value: ", round(!!var_sym, 2)
      )),
      size = .8, alpha = 0.6
    ) +
    facet_grid(schedule_type ~ building_block, scales = "free_x") +
    scale_color_manual(values = color_map) +
    # Falls gewünscht aktivierbar:
    # scale_linetype_manual(values = linetype_map) +
    # scale_linewidth_manual(values = linewidth_map) +
    theme_minimal(base_size = 12) +
    labs(
      x = x_label,
      y = "Kumulative Verteilungsfunktion (ECDF)",
      title = title,
      subtitle = subtitle,
      color = "Schedule Type",
      linetype = "Schedule",
      linewidth = "Schedule"
    ) +
    theme(
      legend.position = "bottom",
      axis.text.x = element_text(angle = 45, hjust = 1)
    )
  
  return(p)
}

p_ecdf_base <- plot_ecdf_variable(
  data = sample_sums_plot,
  var = total_delay_at_destination,
  x_label = "Arrival Delay",
  title = paste0("ECDF Total Arrival Delay, (Run ID = ", config$run_id), ")"
)

p_ecdf_base <- plot_ecdf_variable(
  data = sample_sums_plot,
  var = total_delay_per_train,
  x_label = "Delay per Train",
  title = paste0("ECDF Delay per Train, (Run ID = ", config$run_id), ")"
)




# 4️⃣ ggplot → plotly konvertieren
p_ecdf_plotly <- ggplotly(p_ecdf_base, tooltip = "text")

# 5️⃣ Plot anzeigen
p_ecdf_plotly






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
    facet_grid(schedule_type ~ building_block, scales = "free_x") +
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
  x_var = schedule_scaling_int,
  y_var = total_delay_at_destination,
  x_label = "Schedule",
  y_label = "Arrival Delays",
  title = "Arrival Delay Distriution",
  subtitle = paste("Run ID:", config$run_id)
)
plot_box_variable(
  data = sample_sums_plot,
  x_var = schedule_scaling_int,
  y_var = total_delay_per_train,
  x_label = "Schedule",
  y_label = "Arrival Delays",
  title = "Arrival Delay per Train",
  subtitle = paste("Run ID:", config$run_id)
)
plot_box_variable(
  data = sample_sums_plot,
  x_var = schedule_scaling_int,
  y_var = total_delay_per_train,
  x_label = "Schedule",
  y_label = "Arrival Delays",
  title = "Arrival Delay per Train",
  subtitle = paste("Run ID:", config$run_id),
  logarithmic = TRUE
)



# --- SHOW AND SAVE WEG-ZEIT-DIAGRAMM FROM ONE SPECIFIC SAMPLE ---


#  Weg-Zeit-Diagramm für ein Sample.
weg_zeit_diagram(baseDirectory = baseDirectory, 
                 usecase = "uc_1", 
                 buildingBlock = "uc1_bb2", 
                 subvariant = "KM1_FV_STOP.12", 
                 sample = "99")







# --- MORE DIAGRAMS OF DELAY DISTRIBUTIONS ---

# --- FIRST DRAFT OF ECDF-DIAGRAM ---





# Distribution Statistics
quantiles <- c(0, .02, .05, .1, .5, 1)

schedule_stats <- sample_sums |> 
  group_by(building_block, schedule_type, schedule_scaling, schedule_scaling_int, schedule) |> 
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
      total_delay_at_destination,
      probs = quantiles,
      na.rm = TRUE
    )),
    delay_tt_mean = mean(total_delay_at_destination, na.rm = TRUE),
    delay_tt_prob = {
      F <- ecdf(total_delay_at_destination)
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





quantile_plot <- ggplot(schedule_stats_long |> filter(grepl("^qq_", stat)),
                        aes(x = as.factor(schedule_scaling_int),
                            #y = value_tt,
                            y = value_pt,
                            group = stat_factor,
                            color = stat_factor)) + 
  geom_line() +
  facet_grid(schedule_type ~ building_block, scales = "free_x") +
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
quantile_table_plot <- ggplot(schedule_stats_long |> 
                                filter(grepl("^qq_", stat)),
                              aes(
                                x = as.factor(schedule_scaling_int),
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
  facet_grid(schedule_type ~ building_block, scales = "free_x") +
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


quality_plot <- ggplot(schedule_stats_long |> filter(grepl("^prob_p", stat)),
                       aes(x = as.factor(schedule_scaling_int), 
                           y = value_pt,
                           group = stat,
                           color = stat)) +
  geom_line() +
  facet_grid(schedule_type ~ building_block, scales = "free_x") +
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
quality_plot

ggplotly(quality_plot)



quality_table_plot <- ggplot(schedule_stats_long |> 
                               filter(grepl("^prob_p", stat)),
                             aes(
                               x = as.factor(schedule_scaling_int),
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
  facet_grid(schedule_type ~ building_block, scales = "free_x") +
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






################################
#  ALTE ANALYSEN
################################




# Boxplot (aus deinem ersten Plot)
bb <- "uc1_bb3"
ss <- sample_sums |> filter(building_block==bb)
schst_long <- schedule_stats_long |> filter(building_block==bb)

p_box <- ggplot(ss, aes(x = schedule, y = total_delay_at_destination)) +
  geom_boxplot(outlier.shape = NA) +
  geom_jitter(width = 0.2, height = 0, alpha = 0.6, size = 0.5, color = "steelblue") +
  facet_wrap(~ building_block, scales = "free_x", ncol = 1) +
  theme_minimal() +
  labs(x = NULL, y = "Arrival Delays", title = "Boxplots pro Schedule") +
  theme(axis.text.x = element_blank())
  

# Minima-Lineplot
p_min <- schst_long |> 
  filter(statistic == "Min") |> 
  ggplot(aes(x = schedule, y = delay_value, group = 1)) +
  geom_line(color = "#1b9e77", linewidth = 1.2) +
  geom_point(color = "#1b9e77", size = 2) +
  geom_text(aes(label = round(delay_value, 1)), vjust = -0.3, size = 3, check_overlap = TRUE) +
  facet_wrap(~ building_block, scales = "free_x", ncol = 1) +
  theme_minimal() +
  labs(x = "Schedule", y = "Min Delay", title = "Minimale Arrival-Delays") +
  theme(axis.text.x = element_text(angle = 45, hjust = 1))

# Kombinierter Plot (oberer Teil = Linie, unterer Teil = Boxplot)
combined_plot <- p_box / p_min + plot_layout(heights = c(2, 1))

print(combined_plot)


p_grouped_boxplots <- ggplot(sample_sums, 
                             aes(x = schedule, 
                                 y = total_delay_at_destination, 
                                 fill = building_block)) +
  geom_boxplot(position = position_dodge(width = 0.8), outlier.shape = NA) +
  geom_jitter(position = position_jitterdodge(jitter.width = 0.1, dodge.width = 0.8),
              alpha = 0.5, size = 0.5, color = "gray40") +
  theme_minimal(base_size = 12) +
  labs(
    x = "Schedule",
    y = "Summe der Arrival-Delays pro Sample",
    title = "Verteilung der Arrival Delays nach Schedule und Building Block",
    fill = "Building Block"
  ) +
  theme(
    axis.text.x = element_text(angle = 45, hjust = 1)
  )
print(p_grouped_boxplots)







library(grid)
library(gridExtra)

# Farbpalette wie zuvor
my_colors <- RColorBrewer::brewer.pal(length(unique(sample_sums$building_block)), "Set2")

# 1️⃣ Gruppierte Boxplots
p_box_grouped <- ggplot(sample_sums, 
                        aes(x = schedule, 
                            y = total_delay_at_destination, 
                            fill = building_block)) +
  geom_boxplot(position = position_dodge(width = 0.8), outlier.shape = NA) +
  geom_jitter(position = position_jitterdodge(jitter.width = 0.2, dodge.width = 0.8),
              alpha = 0.4, size = 0.5, color = "gray40") +
  scale_fill_manual(values = my_colors) +
  theme_minimal(base_size = 12) +
  labs(
    x = "Schedule",
    y = "Summe der Arrival-Delays pro Sample",
    title = "Verteilung der Arrival Delays nach Schedule und Building Block",
    fill = "Building Block"
  ) +
  theme(axis.text.x = element_text(angle = 45, hjust = 1)) +
  theme(legend.position = "bottom", legend.key.size = unit(0.8, "lines"))

# falls du die Schedule-Reihenfolge wie im Plot behalten willst (optional)
schedule_levels <- unique(sample_sums$schedule)

# Wide-Format: rows = building_block, cols = schedules
table_data_wide <- schedule_stats %>%
  mutate(schedule = factor(schedule, levels = schedule_levels)) %>%
  select(building_block, schedule, delay_min) %>%
  pivot_wider(names_from = schedule, values_from = delay_min) %>%
  arrange(building_block) %>%
  mutate(across(-building_block, ~ round(., 1)))

# Erstelle TableGrob und kombiniere wie zuvor
# Tabelle mit Titel & besserer Breite
table_grob <- tableGrob(table_data_wide, rows = NULL)

# Titel-Zeile hinzufügen
title_grob <- textGrob("Minimale Arrival-Delays pro Schedule", 
                       gp = gpar(fontsize = 12, fontface = "bold"))

# Breite der Tabelle an Plot anpassen (gleiche Gesamtbreite)
table_grob$widths <- unit(rep(1, ncol(table_data_wide)), "null")

# Kombinieren: Plot oben, Titel + Tabelle unten
grid.arrange(
  p_box_grouped,
  title_grob,
  table_grob,
  heights = c(3, 0.3, 1)
)

