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
# source(file.path(utilsDir, "analysis_utils.R"))
source(file.path(utilsDir, "weg_zeit_diagramme_utils.R"))




filer <- '//filer22l/K-UE220L/IFI/FTO/SAM.A13783/'
run_id <- "output_20251117_it2_n100"
baseDirectory <- paste0(filer, "04_projects/42_gzb_railsim/", run_id, "/")



#  Weg-Zeit-Diagramm für ein Sample.
weg_zeit_diagram(
  simulationRunPath = "C:/Users/u204352/_temp/run_outputs/bb1/uc1_bb1_km1_fv_pass.4_sample_5"
  #simulationRunPath = "//filer22l/K-UE220L/IFI/FTO/SAM.A13783/04_projects/42_gzb_railsim/output_20251117_it2_n100/uc_1/uc1_bb2/04_simulation_run_output/km1_fv_stop.12/uc1_bb2_km1_fv_stop.12_sample_1",
  # baseDirectory = baseDirectory, 
  # usecase = "uc_1", 
  # buildingBlock = "uc1_bb2", 
  # subvariant = "KM1_FV_STOP.12", 
  # sample = "99"
)

