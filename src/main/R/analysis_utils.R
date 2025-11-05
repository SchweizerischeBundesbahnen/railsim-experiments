

#  --- 1. READER FUNCTIONS ------

read_detailed_Results <- function(builidingBlocks){
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
  return(big_dt)
}


read_summaryResults <- function(buildingBlocks){
  all_tables <- list()
  for (bb in buildingBlocks) {
  
    analysis_dir <- file.path(bb, "05_analysis")
    if (!dir.exists(analysis_dir)) next
    
    # alle csv-Dateien mit summary train delays im dem Sample-Verzeichnis (rekursiv)
    files <- list.files(path = analysis_dir, pattern = "summary_train_delays\\.csv$", recursive = FALSE, full.names = TRUE)
    if (length(files) == 0) next
    
    for (f in files){
      try({
        dt <- fread(f)
        # Optional: Zusatzspalten, damit du später Quelle nachvollziehen kannst
        dt[, source_file := f]
        dt[, building_block := basename(bb)]
        #dt[, schedule := basename(sc)]
        #dt[, sample := basename(sa)]
        all_tables[[length(all_tables) + 1L]] <- dt
      }, silent = TRUE)
    }
  }
  # Alle gelesenen Tables zu einem grossen data.table zusammenfügen
  if (length(all_tables) > 0) {
    big_dt <- rbindlist(all_tables, use.names = TRUE, fill = TRUE)
  } else {
    big_dt <- data.table()  # leere data.table, falls keine Dateien gefunden wurden
  }
  return(big_dt)
}

