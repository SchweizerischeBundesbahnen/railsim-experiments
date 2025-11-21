

# --- 1. Reader Functions ---

read_trainState_file <- function(trainStatesFile){
  if (!file.exists(trainStatesFile)) stop("Train states file not found at: ", trainStatesFile)
  message("Reading train states from: ", trainStatesFile)
  train_data <- fread(trainStatesFile) %>%
    mutate(train_type = sub("_[0-9]+$", "", vehicle))
}
read_transitSchedule_file <- function(transitScheduleFile){
  if (!file.exists(transitScheduleFile)) stop("Transit schedule file not found at: ", transitScheduleFile)
  message("Reading transit schedule from: ", transitScheduleFile)
  schedule_xml <- read_xml(transitScheduleFile)
  return(schedule_xml)
}


# --- 2. Helper Functions ---

time_to_seconds <- function(time_str) {
  period_to_seconds(hms(time_str, quiet = TRUE))
}

format_time_hms <- function(seconds) {
  seconds <- as.numeric(seconds)
  hours <- floor(seconds / 3600)
  minutes <- floor((seconds %% 3600) / 60)
  secs <- floor(seconds %% 60)
  sprintf("%02d:%02d:%02d", hours, minutes, secs)
}

extract_stop_info <- function(sched){
  stop_nodes <- xml_find_all(sched, ".//stopFacility")
  stops_info <- tibble(
    id = xml_attr(stop_nodes, "id"),
    name = xml_attr(stop_nodes, "name"),
    x_coord = as.numeric(xml_attr(stop_nodes, "x"))
  ) %>% arrange(x_coord)
  message("Successfully processed stop information.")
  return(stops_info)
}

extract_theoretical_schedule <- function(sched, stops_info){
  all_schedule_points <- list()
  transit_lines <- xml_find_all(sched, ".//transitLine")
  for (line_node in transit_lines) {
    route_nodes <- xml_find_all(line_node, ".//transitRoute")
    for (route_node in route_nodes) {
      stop_profile_nodes <- xml_find_all(route_node, ".//routeProfile/stop")
      route_stops <- tibble(
        refId = xml_attr(stop_profile_nodes, "refId"),
        arrivalOffset = time_to_seconds(xml_attr(stop_profile_nodes, "arrivalOffset")),
        departureOffset = time_to_seconds(xml_attr(stop_profile_nodes, "departureOffset"))
      )
      departure_nodes <- xml_find_all(route_node, ".//departures/departure")
      for (dep_node in departure_nodes) {
        vehicle_id <- xml_attr(dep_node, "vehicleRefId")
        start_time_sec <- time_to_seconds(xml_attr(dep_node, "departureTime"))
        for (i in 1:nrow(route_stops)) {
          stop_id <- route_stops$refId[i]
          arrival_time <- start_time_sec + route_stops$arrivalOffset[i]
          departure_time <- start_time_sec + route_stops$departureOffset[i]
          all_schedule_points[[length(all_schedule_points) + 1]] <- list(vehicle = vehicle_id, time = arrival_time, stop_id = stop_id)
          if (arrival_time != departure_time) {
            all_schedule_points[[length(all_schedule_points) + 1]] <- list(vehicle = vehicle_id, time = departure_time, stop_id = stop_id)
          }
        }
      }
    }
  }
  theoretical_schedule <- bind_rows(all_schedule_points) %>%
    left_join(stops_info, by = c("stop_id" = "id")) %>%
    rename(headX = x_coord) %>%
    select(vehicle, time, headX) %>%
    arrange(vehicle, time) %>%
    mutate(train_type = sub("_[0-9]+$", "", vehicle))
  message("Successfully built theoretical schedule data frame.")
  return(theoretical_schedule)
}




#library(htmlwidgets)
#library(fs)   # für saubere File-Operationen (optional)


# We need a clean saving-function because saveWidget with selfcontained = TRUE makes problems on network-storage
save_interactive_widget <- function(widget, target_file) {
  
  # 1️⃣ Lokalen temporären Speicherort erzeugen
  temp_dir  <- tempfile(pattern = "widget_tmp_")
  dir.create(temp_dir)
  
  temp_html <- file.path(temp_dir, "widget.html")
  
  message("Saving widget temporarily to: ", temp_html)
  
  # 2️⃣ HTML zuerst lokal speichern (funktioniert IMMER)
  saveWidget(
    widget,
    file = temp_html,
    selfcontained = TRUE
  )
  
  # 3️⃣ Zielverzeichnis sicherstellen
  target_dir <- dirname(target_file)
  if (!dir.exists(target_dir)) {
    dir.create(target_dir, recursive = TRUE)
  }
  
  # 4️⃣ Datei ans Ziel kopieren
  message("Copying widget to final destination: ", target_file)
  file.copy(temp_html, target_file, overwrite = TRUE)
  
  # 5️⃣ TEMP-Ordner sauber löschen
  message("Cleaning up temporary files...")
  unlink(temp_dir, recursive = TRUE, force = TRUE)
  
  message("✔ Done! File saved to: ", target_file)
}


# --- 3. Generate Plot ---

weg_zeit_diagram <- function(baseDirectory = "//filer22l/K-UE220L/IFI/FTO/SAM.A13783/04_projects/42_gzb_railsim/output_20251030_ik/", 
                             usecase       = "uc_1", 
                             buildingBlock = "uc1_bb2", 
                             subvariant    = "km1.1", 
                             sample        = "1"){
  
  # Remove any trailing slash or backslash at the end of baseDirectory
  baseDirectory <- sub("[/\\\\]+$", "", baseDirectory)

    # Construct the path to the specific simulation run output
  simulationRunPath <- file.path(baseDirectory, 
                                 usecase, 
                                 buildingBlock, 
                                 "04_simulation_run_output", 
                                 subvariant, 
                                 paste0(buildingBlock, "_", subvariant, "_sample_", sample)
  )
  
  # Full path to the train states CSV file
  trainStatesFile <- file.path(simulationRunPath,
                               "ITERS",
                               "it.0",
                               paste0(buildingBlock, "_", subvariant, "_sample_", sample, ".0.railsimTrainStates.csv.gz")
  )
  # Full path to the transit schedule XML file
  transitScheduleFile <- file.path(simulationRunPath,
                                   paste0(buildingBlock, "_", subvariant, "_sample_", sample, ".output_transitSchedule.xml.gz")
  )
  
  
  
  # --- 2. Load and Process Simulation Data ---
  
  
  train_data <- read_trainState_file(trainStatesFile)
  schedule_xml <- read_transitSchedule_file(transitScheduleFile)
  
  
  # --- 4. Parse Schedule for Stops and Theoretical Paths ---
  
  stops_info <- extract_stop_info(schedule_xml)
  
  theoretical_schedule <- extract_theoretical_schedule(schedule_xml, stops_info)
  
  # --- 5. Generate the Static Graphical Schedule ---
  
  message("Generating static ggplot object with text annotations for stops...")
  
  # Determine the y-position for the stop labels (at the very top of the plot)
  # Since the y-axis is reversed, we use the minimum time value.
  # A small offset is subtracted to give it some padding from the first trajectory.
  y_label_position <- min(train_data$time, theoretical_schedule$time) - 150
  
  graphical_schedule_plot <- ggplot() +
    # Vertical lines for stations
    geom_vline(data = stops_info, aes(xintercept = x_coord), linetype = "dashed", color = "grey50") +
    
    # Add stop names as text labels at the top of the plot
    geom_text(
      data = stops_info,
      aes(x = x_coord, y = y_label_position, label = name),
      angle = 60,
      hjust = 0,
      vjust = 0.5,
      inherit.aes = FALSE,
      size = 2.5,
      color = "grey20"
    ) +
    
    # Theoretical (scheduled) train paths
    geom_line(data = theoretical_schedule, aes(x = headX, y = time, group = vehicle, color = train_type), linetype = "dashed", linewidth = 0.7) +
    
    # Actual (simulated) train paths
    geom_line(data = train_data, aes(x = headX, y = time, group = vehicle, color = train_type), linetype = "solid", linewidth = 0.8) +
    
    # Simplify the x-axis scale
    scale_x_continuous(name = "Position (meters)") +
    
    # Reverse Y-axis and format time labels
    scale_y_reverse(labels = format_time_hms) +
    
    # Labels and Title
    labs(title = "Graphical Schedule: Simulated (Solid) vs. Theoretical (Dashed)", subtitle = paste("Showing trajectories from use case:", usecase), y = "Time", color = "Train Type") +
    
    # Theme
    theme_bw() +
    theme(legend.position = "bottom", plot.title = element_text(hjust = 0.5), plot.subtitle = element_text(hjust = 0.5)) +
    
    # --- SOLUTION: Override legend aesthetics to show solid lines ---
    guides(color = guide_legend(override.aes = list(linetype = "solid")))
  # --- 6. Convert to an Interactive Plot and Save as HTML ---
  
  message("Converting ggplot object to an interactive plotly object...")
  interactive_plot <- ggplotly(graphical_schedule_plot)
  
  output_html_file <- file.path(simulationRunPath, "interactive_graphical_schedule_with_stops.html")
  
  
  # Save the interactive plot as a self-contained HTML file
  #saveWidget(interactive_plot, file = output_html_file, selfcontained = TRUE)
  save_interactive_widget(interactive_plot, output_html_file)
  
  
  return(interactive_plot)
}
