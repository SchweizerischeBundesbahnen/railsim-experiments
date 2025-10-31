rm(list = ls())
library(data.table)
library(tidyverse)
library(ggplot2)


filer <- '//filer22l/K-UE220L/IFI/FTO/SAM.A13783/'

#baseDirectory = paste0(filer, "04_projects/26_vivaldi_phase3/")
baseDirectory = paste0(filer, "04_projects/42_gzb_railsim/output_20251030_ik/")

usecase <- "uc_1"
usecaseDirectory <- paste0(baseDirectory, usecase, "/")

buildingBlocks <- list.dirs(path = usecaseDirectory, recursive = FALSE)


all_tables <- list()

for (bb in buildingBlocks) {
  # Pfad für Analysis-Verzeichnis (nur wenn es existiert)
  analysis_dir <- file.path(bb, "05_analysis")
  if (!dir.exists(analysis_dir)) next
  
  # ggf. nur direkte Unterverzeichnisse der Analysis-Ordner nehmen
  schedules <- list.dirs(path = analysis_dir, recursive = FALSE, full.names = TRUE)
  if (length(schedules) == 0) next
  
  for (sc in schedules) {
    if (!dir.exists(sc)) next
    
    # nehme direkte Unterverzeichnisse (Samples)
    samples <- list.dirs(path = sc, recursive = FALSE, full.names = TRUE)
    if (length(samples) == 0) next
    
    for (sa in samples) {
      if (!dir.exists(sa)) next
      
      # alle csv-Dateien unter dem Sample-Verzeichnis (rekursiv)
      files <- list.files(path = sa, pattern = "\\.csv$", recursive = TRUE, full.names = TRUE)
      if (length(files) == 0) next
      
      # Einlesen mit fread; Fehler abfangen, damit ein fehlerhaftes File nicht alles stoppt
      for (f in files) {
        try({
          dt <- fread(f)
          # Optional: Zusatzspalten, damit du später Quelle nachvollziehen kannst
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

# Alle gelesenen Tables zu einem grossen data.table zusammenfügen
if (length(all_tables) > 0) {
  big_dt <- rbindlist(all_tables, use.names = TRUE, fill = TRUE)
} else {
  big_dt <- data.table()  # leere data.table, falls keine Dateien gefunden wurden
}


#Filtere auf den jeweils letzten Stop um die Verspätung am ziel zu identifizieren.
df <- big_dt |> 
  group_by(sample, route_id, departure_id) |> mutate(n_stop = max(stop_sequence)) |> ungroup() |> 
  filter(n_stop == stop_sequence) |> 
  mutate(arrival_delay = if_else(is.na(arrival_delay), 3600*24, arrival_delay))

# 1) Pro sample die Summe der arrival_delay berechnen
sample_sums <- df %>%
  group_by(building_block, schedule, sample) %>%   # group_by nur schedule+sample wäre auch ok
  summarise(total_arrival_delay = sum(arrival_delay, na.rm = TRUE), .groups = "drop")

# 2) Boxplot: x = schedule, y = total_arrival_delay; jeder Punkt = ein sample
p <- ggplot(sample_sums, aes(x = schedule, y = total_arrival_delay)) +
  geom_boxplot(outlier.shape = NA) +                # Boxplot ohne outlier-punkte
  geom_jitter(width = 0.2, height = 0, alpha = 0.6, size = 1.5, color = "steelblue") + # einzelne samples
  facet_wrap(~ building_block, scales = "free_x", nrow = 1) +
  theme_minimal() +
  labs(
    x = "Schedule",
    y = "Summe der Arrival-Delays pro Sample",
    title = "Verteilung der Arrival Delays nach Schedule \ngetrennt nach Building Block"
  ) +
  theme(
    axis.text.x = element_text(angle = 45, hjust = 1)
  )

print(p)


schedule_stats <- sample_sums |> 
  group_by(building_block, schedule) |> 
  summarise(
    delay_min   = min(total_arrival_delay, na.rm = TRUE),
    delay_5pct  = quantile(total_arrival_delay, probs = 0.05, na.rm = TRUE, type = 7),
    delay_median= median(total_arrival_delay, na.rm = TRUE),
    delay_mean  = mean(total_arrival_delay, na.rm = TRUE),
    delay_95pct = quantile(total_arrival_delay, probs = 0.95, na.rm = TRUE, type = 7),
    delay_max   = max(total_arrival_delay, na.rm = TRUE),
    n_samples   = n(),
    .groups = "drop"
  )

