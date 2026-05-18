# --- 1. File Handling Functions ---

#' Create a temporary local copy of a file (Windows-specific, uses robocopy).
#' Useful when reading from network storage with very long paths.
create_temp_copy <- function(tmp, target) {
  tmp_dir <- file.path(
    normalizePath(tmp, winslash = "\\", mustWork = FALSE),
    paste0("tmp_", Sys.getpid(), "_", as.integer(Sys.time()))
  )
  dir.create(tmp_dir, recursive = TRUE, showWarnings = FALSE)

  target_dir <- dirname(target)
  file_name <- basename(target)

  tmp_dir_win <- gsub("/", "\\\\", tmp_dir)
  target_dir_win <- gsub("/", "\\\\", target_dir)

  cmd <- sprintf(
    'robocopy "%s" "%s" "%s" /NFL /NDL /NJH /NJS /COPY:DAT /IS /IT',
    target_dir_win, tmp_dir_win, file_name
  )
  shell(cmd, intern = TRUE, wait = TRUE)

  local_file <- file.path(tmp_dir, file_name)
  if (!file.exists(local_file)) stop("Temp copy failed: ", target)
  local_file
}

#' Remove a temporary copy created by create_temp_copy.
remove_temp_copy <- function(temp_file) {
  temp_dir <- dirname(temp_file)
  if (dir.exists(temp_dir)) {
    unlink(temp_dir, recursive = TRUE)
  }
  invisible(!dir.exists(temp_dir))
}


# --- 2. Data Reading Functions ---

read_train_state_file <- function(trainStatesFile) {
  if (!file.exists(trainStatesFile)) stop("Train states file not found at: ", trainStatesFile)
  message("Reading train states from: ", trainStatesFile)
  fread(trainStatesFile) %>%
    mutate(train_type = sub("_[0-9]+$", "", vehicle))
}

read_transit_schedule_file <- function(transitScheduleFile) {
  if (!file.exists(transitScheduleFile)) stop("Transit schedule file not found at: ", transitScheduleFile)
  message("Reading transit schedule from: ", transitScheduleFile)
  read_xml(transitScheduleFile)
}


# --- 3. Helper Functions ---

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

extract_stop_info <- function(sched) {
  stop_nodes <- xml_find_all(sched, ".//stopFacility")
  tibble(
    id = xml_attr(stop_nodes, "id"),
    name = xml_attr(stop_nodes, "name"),
    x_coord = as.numeric(xml_attr(stop_nodes, "x"))
  ) %>% arrange(x_coord)
}

extract_theoretical_schedule <- function(sched, stops_info) {
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

  bind_rows(all_schedule_points) %>%
    left_join(stops_info, by = c("stop_id" = "id")) %>%
    rename(headX = x_coord) %>%
    select(vehicle, time, headX) %>%
    arrange(vehicle, time) %>%
    mutate(train_type = sub("_[0-9]+$", "", vehicle))
}


# --- 4. Widget Saving ---

#' Save an interactive htmlwidget to a file.
#' Uses a local temp directory first to avoid issues with network storage paths.
save_interactive_widget <- function(widget, target_file) {
  temp_dir <- tempfile(pattern = "widget_tmp_")
  dir.create(temp_dir)
  temp_html <- file.path(temp_dir, "widget.html")

  message("Saving widget temporarily to: ", temp_html)
  saveWidget(widget, file = temp_html, selfcontained = TRUE)

  target_dir <- dirname(target_file)
  if (!dir.exists(target_dir)) {
    dir.create(target_dir, recursive = TRUE)
  }

  message("Copying widget to final destination: ", target_file)
  file.copy(temp_html, target_file, overwrite = TRUE)

  unlink(temp_dir, recursive = TRUE, force = TRUE)
  message("Done! File saved to: ", target_file)
}


# --- 5. Time-Distance Diagram ---

#' Generate an interactive time-distance (graphical schedule) diagram.
#'
#' @param baseDirectory Path to the experiment output directory.
#' @param usecase Use case identifier (e.g., "uc_1").
#' @param buildingBlock Building block identifier (e.g., "uc1_bb2").
#' @param operating_mode Operating mode (e.g., "KM_FV_PASS").
#' @param volume Volume level (e.g., "12").
#' @param sample Sample index (e.g., "001").
#' @param line_colors Optional named vector of colors per route (e.g., c("FV_AB" = "red")).
#' @return An interactive plotly object.
time_distance_diagram <- function(baseDirectory,
                                  usecase,
                                  buildingBlock,
                                  operating_mode,
                                  volume,
                                  sample,
                                  line_colors = NULL) {

  operating_mode <- tolower(operating_mode)
  baseDirectory <- sub("[/\\\\]+$", "", baseDirectory)

  run_id <- paste0(buildingBlock, "_", operating_mode, "_volume_", volume, "_sample_", sample)

  simulationRunPath <- file.path(
    baseDirectory, usecase, buildingBlock,
    "04_simulation_run_output", operating_mode,
    paste0("volume_", volume), run_id
  )

  trainStatesFile <- file.path(
    simulationRunPath, "ITERS", "it.0",
    paste0(run_id, ".0.railsimTrainStates.csv.gz")
  )
  transitScheduleFile <- file.path(
    simulationRunPath,
    paste0(run_id, ".output_transitSchedule.xml.gz")
  )

  # Handle long Windows paths via temp copy
  maxlen <- 240
  if (nchar(trainStatesFile) > maxlen || nchar(transitScheduleFile) > maxlen) {
    tmp_trainStatesFile <- create_temp_copy(tmp = "C:/temp", target = trainStatesFile)
    tmp_transitScheduleFile <- create_temp_copy(tmp = "C:/temp", target = transitScheduleFile)
    train_data <- read_train_state_file(tmp_trainStatesFile)
    schedule_xml <- read_transit_schedule_file(tmp_transitScheduleFile)
    remove_temp_copy(tmp_trainStatesFile)
    remove_temp_copy(tmp_transitScheduleFile)
  } else {
    train_data <- read_train_state_file(trainStatesFile)
    schedule_xml <- read_transit_schedule_file(transitScheduleFile)
  }

  stops_info <- extract_stop_info(schedule_xml)
  theoretical_schedule <- extract_theoretical_schedule(schedule_xml, stops_info)

  # Generate plot
  y_label_position <- min(train_data$time, theoretical_schedule$time) - 150

  graphical_schedule_plot <- ggplot() +
    geom_vline(data = stops_info, aes(xintercept = x_coord), linetype = "dashed", color = "grey50") +
    geom_text(
      data = stops_info,
      aes(x = x_coord, y = y_label_position, label = name),
      angle = 60, hjust = 0, vjust = 0.5, inherit.aes = FALSE, size = 2.5, color = "grey20"
    ) +
    geom_line(
      data = theoretical_schedule %>% mutate(train_line = sub("^[^_]*_(.*)_[^_]*$", "\\1", train_type)),
      aes(x = headX, y = time, group = vehicle, color = train_line),
      linetype = "dashed", linewidth = 0.7
    ) +
    geom_line(
      data = train_data %>% mutate(train_line = sub("^[^_]*_(.*)_[^_]*$", "\\1", train_type)),
      aes(x = headX, y = time, group = vehicle, color = train_line),
      linetype = "solid", linewidth = 0.8
    ) +
    (if (is.null(line_colors)) scale_color_brewer(palette = "Set2") else scale_color_manual(values = line_colors)) +
    scale_x_continuous(name = "Position (meters)") +
    scale_y_reverse(labels = format_time_hms) +
    labs(
      title = "Time-Distance Diagram: Simulated (Solid) vs. Scheduled (Dashed)",
      subtitle = paste("Use case:", usecase, "| Building block:", buildingBlock, "| Mode:", operating_mode),
      y = "Time", color = "Route"
    ) +
    theme_bw() +
    theme(legend.position = "bottom", plot.title = element_text(hjust = 0.5), plot.subtitle = element_text(hjust = 0.5)) +
    guides(color = guide_legend(override.aes = list(linetype = "solid")))

  interactive_plot <- ggplotly(graphical_schedule_plot)

  output_html_file <- file.path(simulationRunPath, "interactive_time_distance_diagram.html")
  save_interactive_widget(interactive_plot, output_html_file)

  interactive_plot
}
