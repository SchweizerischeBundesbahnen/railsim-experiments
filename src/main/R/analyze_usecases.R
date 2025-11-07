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

read_detailed <- FALSE

if (read_detailed){
  #Read all detailed Tables
  big_dt <- read_detailed_Results(buildingBlocks)
  
  #Filtere auf den jeweils letzten Stop um die Verspätung am ziel zu identifizieren.
  df <- big_dt |> 
    group_by(building_block, sample, route_id, departure_id) |> mutate(n_stop = max(stop_sequence)) |> ungroup() |> 
    filter(n_stop == stop_sequence)
  
  # Pro sample die Summe der arrival_delay berechnen
  sample_sums <- df %>%
    group_by(building_block, schedule, sample) %>%   # group_by nur schedule+sample wäre auch ok
    summarise(total_arrival_delay = sum(arrival_delay, na.rm = TRUE), .groups = "drop") |> 
    rename(total_delay_at_destination = total_arrival_delay)
} else {
  
  
  #Read summary results
  df <- read_summaryResults(buildingBlocks)
  
  sample_sums <- df |> 
    rename(schedule = subvariant_id)
  
}




# --- ECDF WITH PLOTLY ---

# Customized ECDF's
schedules <- sort(unique(sample_sums$schedule))

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
  schedule = schedules,
  color = rep("red", length(schedules)),
  linetype = rep("solid", length(schedules)),
  linewidth = rep(1, length(schedules))
)

sample_sums_plot <- sample_sums |> 
  left_join(line_specs, by = "schedule")


# 1️⃣ ECDF-Daten vorbereiten
ecdf_data <- sample_sums_plot %>%
  group_by(building_block, schedule) %>%
  arrange(total_delay_at_destination) %>%
  mutate(
    ecdf_y = ecdf(total_delay_at_destination)(total_delay_at_destination)
  ) %>%
  ungroup() |> 
  group_by(building_block, schedule, total_delay_at_destination, ecdf_y) |> 
  summarise(sample_list = paste(sample, collapse = "<br>")) |> 
  ungroup()

# 2️⃣ Farben und Linientypen aus line_specs übernehmen
color_map <- setNames(line_specs$color, line_specs$schedule)
linetype_map <- setNames(line_specs$linetype, line_specs$schedule)
linewidth_map <- setNames(line_specs$linewidth, line_specs$schedule)

# 3️⃣ Mit ggplot vorbereiten (optional, für konsistentes Layout)
p_ecdf_base <- ggplot(ecdf_data, aes(x = total_delay_at_destination, y = ecdf_y, group = schedule)) +
  geom_line(aes(color = schedule, linetype = schedule, linewidth = schedule)) +
  geom_point(aes(color = schedule, text = paste0(
    "Schedule: ", schedule, "<br>",
    "Building block: ", building_block, "<br>",
    "Samples: ", sample_list, "<br>",
    "Delay: ", round(total_delay_at_destination, 2)
  )), size = 1.3, alpha = 0.6) +
  facet_wrap(~ building_block, scales = "free_x", ncol = 2) +
  scale_color_manual(values = color_map) +
  scale_linetype_manual(values = linetype_map) +
  scale_linewidth_manual(values = linewidth_map) +
  theme_minimal(base_size = 12) +
  labs(
    x = "Arrival Delay",
    y = "Kumulative Verteilungsfunktion (ECDF)",
    title = paste0("ECDF of arrival delays\nRun-ID = ", config$run_id),
    color = "Schedule",
    linetype = "Schedule",
    linewidth = "Schedule"
  ) +
  theme(
    legend.position = "bottom",
    axis.text.x = element_text(angle = 45, hjust = 1)
  )

# 4️⃣ ggplot → plotly konvertieren
p_ecdf_plotly <- ggplotly(p_ecdf_base, tooltip = "text")

# 5️⃣ Plot anzeigen
p_ecdf_plotly



# --- SHOW AND SAVE WEG-ZEIT-DIAGRAMM FROM ONE SPECIFIC SAMPLE ---


#  Weg-Zeit-Diagramm für ein Sample.
weg_zeit_diagram(baseDirectory = baseDirectory, 
                 usecase = "uc_1", 
                 buildingBlock = "uc1_bb3", 
                 subvariant = "KM2.24", 
                 sample = "98")







# --- MORE DIAGRAMS OF DELAY DISTRIBUTIONS ---

# --- FIRST DRAFT OF ECDF-DIAGRAM ---

# Customized ECDF's
schedules <- sort(unique(sample_sums$schedule))

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
  schedule = schedules,
  color = rep("red", length(schedules)),
  linetype = rep("solid", length(schedules)),
  linewidth = rep(1, length(schedules))
)

sample_sums_plot <- sample_sums |> 
  left_join(line_specs, by = "schedule")



p_ecdf_legend <- ggplot(sample_sums_plot, aes(x = total_delay_at_destination, group = schedule)) +
  stat_ecdf(aes(color = schedule, linetype = schedule, size = schedule)) +
  facet_wrap(~ building_block, scales = "free_x", ncol = 2) +
  scale_color_manual(values = setNames(line_specs$color, line_specs$schedule)) +
  scale_linetype_manual(values = setNames(line_specs$linetype, line_specs$schedule)) +
  scale_size_manual(values = setNames(line_specs$linewidth, line_specs$schedule)) +
  theme_minimal(base_size = 12) +
  labs(
    x = "Arrival Delay",
    y = "Kumulative Verteilungsfunktion (ECDF)",
    title = paste0("ECDF of arrival delays\nRun-ID = ", config$run_id),
    color = "Schedule",
    linetype = "Schedule",
    size = "Schedule"
  ) +
  theme(
    legend.position = "bottom",
    axis.text.x = element_text(angle = 45, hjust = 1)
  )

print(p_ecdf_legend)





# Distribution Statistics

schedule_stats <- sample_sums |> 
  group_by(building_block, schedule) |> 
  summarise(
    delay_min   = min(total_delay_at_destination, na.rm = TRUE),
    delay_5pct  = quantile(total_delay_at_destination, probs = 0.05, na.rm = TRUE, type = 7),
    delay_median= median(total_delay_at_destination, na.rm = TRUE),
    delay_mean  = mean(total_delay_at_destination, na.rm = TRUE),
    delay_95pct = quantile(total_delay_at_destination, probs = 0.95, na.rm = TRUE, type = 7),
    delay_max   = max(total_delay_at_destination, na.rm = TRUE),
    n_samples   = n(),
    .groups = "drop"
  ) |> 
  ungroup()

# Long-Format (wie zuvor)
schedule_stats_long <- schedule_stats |> 
  pivot_longer(
    cols = starts_with("delay_"), 
    names_to = "statistic", 
    values_to = "delay_value"
  )

# Reihenfolge & Labels der Statistik-Kategorien
schedule_stats_long <- schedule_stats_long |> 
  mutate(
    statistic = factor(statistic,
                       levels = c("delay_min", "delay_5pct", "delay_median", 
                                  "delay_mean", "delay_95pct", "delay_max"),
                       labels = c("Min", "5. Perzentil", "Median", 
                                  "Mittelwert", "95. Perzentil", "Max"))
  )

# Plot mit abgestuften Farben & Linienstilen
p_lineplot_all <- ggplot(schedule_stats_long, 
                         aes(x = schedule, 
                             y = delay_value, 
                             group = statistic, 
                             color = statistic,
                             linetype = statistic)) +
  geom_line(linewidth = 1) +
  geom_point(size = 1.8) +
  scale_color_manual(values = c(
    "Min" = "#1b9e77",
    "5. Perzentil" = "#66a61e",
    "Median" = "#7570b3",
    "Mittelwert" = "#e6ab02",
    "95. Perzentil" = "#d95f02",
    "Max" = "#e7298a"
  )) +
  scale_linetype_manual(values = c(
    "Min" = "solid",
    "5. Perzentil" = "dotted",
    "Median" = "solid",
    "Mittelwert" = "longdash",
    "95. Perzentil" = "dotted",
    "Max" = "solid"
  )) +
  facet_wrap(~ building_block, scales = "free_x", ncol = 1) +
  theme_minimal(base_size = 12) +
  labs(
    x = "Schedule",
    y = "Summe der Arrival-Delays pro Sample",
    title = "Delay-Statistiken nach Schedule\ngetrennt nach Building Block",
    color = "Statistik",
    linetype = "Statistik"
  ) +
  theme(
    axis.text.x = element_text(angle = 45, hjust = 1),
    legend.position = "bottom"
  )

print(p_lineplot_all)


p_lineplot_min <- schedule_stats_long |> 
  filter(statistic == "Min") |> 
  ggplot(aes(x = schedule, y = delay_value, group = 1)) +
  geom_line(color = "#1b9e77", linewidth = 1.2) +
  geom_point(color = "#1b9e77", size = 2) +
  geom_text(aes(label = round(delay_value, 1)), vjust = -0.5, size = 3, check_overlap = TRUE) +
  coord_cartesian(clip = "off", expand = TRUE) +
  facet_wrap(~ building_block, scales = "free_y", ncol = 1) +
  theme_minimal(base_size = 12) +
  labs(
    x = "Schedule",
    y = "Minimaler Arrival-Delay",
    title = "Minimale Arrival-Delays nach Schedule\ngetrennt nach Building Block"
  ) +
  theme(
    axis.text.x = element_text(angle = 45, hjust = 1)
  )

print(p_lineplot_min)


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

