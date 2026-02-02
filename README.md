# MATSim Railsim Experiments

This project provides a configurable command-line tool for running railway simulations with `railsim` (a MATSim
contrib). It is designed to analyze and compare the performance of different infrastructure layouts
(**Building Blocks**) under consistent traffic operatingModes (**Operational Plans**).

The project is structured around **Use Cases**. A Use Case groups a set of related Building Blocks and defines a single
Operational Plan compatible with all of them, ensuring a consistent basis for comparison.

## Glossary

The simulation is structured around these three key concepts, from general to specific:

* **Use Case**: Concept that groups a set of related **Building Blocks** and defines a single **Operational Plan** that
  is compatible with every Building Block within it. This ensures consistent operational logic across different
  infrastructure variations. For example, all train lines (for products like FV, RV, GV) defined in the plan must follow
  the same fundamental sequence of potential stops, even if individual trains don't service every stop.
* **Building Block**: A specific infrastructure operatingMode. It defines the physical railway network (tracks and block
  resources) and a template transit schedule (routes and stops), but does not contain specific departure times on the
  routes. It is defined by a set of MATSim XML files (`network.xml`, `schedule.xml`, and `vehicles.xml`).
* **Operational Plan**: A generic timetable (`Mengengerüst`) that specifies the desired train volumes. It defines how
  many trains of each product type (e.g., FV, RV, GV) should run on specific, pre-defined traffic flows (mapped to
  MATSim routes) within a given period. It is provided as a JSON file with hierarchical structure.

## Simulation Pipeline

The automated pipeline proceeds from configuration, simulation to analysis for each Building Block combined with all
sub-variants of the Operational Plan.

```mermaid
graph LR
    %% Phase 1: Inputs
    subgraph Inputs
        direction LR
        A["📄 Project Configuration<br/>(N Samples, Random Seed,<br/>Sampling Strategy, ...)"]:::input
        B["🏗️ Building Block<br/>(Network & Base Schedule)"]:::input
        C["📋 Operational Plan<br/>(Volumes per Product)"]:::input
    end

    %% Phase 2: Preparation
    subgraph Preparation
        direction LR
        D["1. Train Run Calculation<br/>(Sequential, railsim)"]:::process
        E["2. Schedule Sampling<br/>(Parallel per Sub-Variant)"]:::parallel
    end
    
    %% Phase 3: Simulation
    subgraph Simulation
        direction LR
        F["3. railsim Simulation<br/>(Parallel per Job)"]:::parallel
    end

    %% Phase 4: Analysis (Post-Processing)
    subgraph Analysis
        direction LR
        G["4. Per-Run Delay Analysis<br/>(Parallel per Job)"]:::parallel
        H["5. Result Aggregation<br/>(Sequential)"]:::process
    end

    %% Phase 5: Outputs
    subgraph Outputs
        direction LR
        I["📈 Detailed Delay Reports<br/>analysis_train_delays.csv"]:::output
        J["📊 Final Summary Report<br/>summary_train_delays.csv"]:::output
    end

    %% Define the connections between all phases
    A & B & C --> D
    D --> E
    E --> F
    F --> G
    G --> H
    G --> I
    H --> J
```

1. **Train Run Calculation**: Minimum, unconstrained travel times are calculated for each route in the base schedule of
   the Building Block using the MATSim `railsim` engine.
2. **Schedule Sampling**: Departure times are generated based on the train volumes from the Operational Plan. This step
   creates *n* schedule samples following a sampling strategy (RANDOM or HEADWAY).
3. **Railsim Simulation**: Each schedule sample is run through the MATSim `railsim` engine on the constrained network of
   the Building Block where rerouting is enabled.
4. **Delay Analysis**: Delays (departure and arrival) are calculated for every train at each stop.
5. **Result Aggregation**: Delay data is aggregated into a summary report showing the cumulative arrival delays of all
   trains at the terminal stop for each run.

## Operational Plan Structure

The Operational Plan is built using the following concepts:

* **Traffic Flow**: A logical connection between two points in the network (e.g., "Trunk A-B"). The Flow maps this
  abstract concept to the physical **Route IDs** (`forward` and `reverse`) defined per product in the Building Block's
  schedule.
* **Flow Pattern**: Defines the distribution of a product on flows. For example, a `BALANCED` pattern might distribute
  traffic 50/50 between two branches, while a `TRUNK_ONLY` pattern routes everything onto the main line.
* **Product Mix**: Defines the ratio of different train types (e.g., "40% Intercity, 40% Regional, 20% Cargo").
* **Operating Mode**: The top-level configuration. It pairs a **Product Mix** with one or more **Flow Patterns**. The
  simulation generates a sub-variant for every valid combination of mix and pattern defined here.
* **Volumes**: Defines the global scaling of traffic for each defined operating mode. The simulation iterates from `min`
  to `max` total trains per `period`.

JSON structure:

```json
{
  "volumes": {
    "period": 1800,
    "min": 4,
    "max": 12,
    "step": 1,
    "bidirectional": false
  },
  "products": {
    "FV": {
      "description": "Fernverkehr",
      "minHeadway": 120
    },
    "RV": {
      "description": "Regionalverkehr",
      "minHeadway": 90
    },
    "GV": {
      "description": "Güterverkehr",
      "minHeadway": 180
    }
  },
  "flows": {
    "LMR": {
      "description": "Verkehrsstrom mit Halt bei M (L-M-R)",
      "routes": {
        "FV": {
          "forward": "FV_LMR"
        },
        "RV": {
          "forward": "RV_LMR"
        }
      }
    },
    "LR": {
      "description": "Verkehrsstrom ohne Halt bei M (L-R)",
      "routes": {
        "FV": {
          "forward": "FV_LR"
        },
        "GV": {
          "forward": "GV_LR"
        }
      }
    }
  },
  "patterns": {
    "FV_PASS": {
      "description": "FV direkt; RV hält; GV direkt",
      "shares": {
        "FV": {
          "LR": 1.0
        },
        "RV": {
          "LMR": 1.0
        },
        "GV": {
          "LR": 1.0
        }
      }
    },
    "FV_STOP": {
      "description": "FV hält; RV hält; GV direkt",
      "shares": {
        "FV": {
          "LMR": 1.0
        },
        "RV": {
          "LMR": 1.0
        },
        "GV": {
          "LR": 1.0
        }
      }
    }
  },
  "mixes": {
    "MAINLINE": {
      "description": "Kernnetz-Mischverkehr (Ref: Lausanne–Genf)",
      "shares": {
        "FV": 0.4,
        "RV": 0.4,
        "GV": 0.2
      }
    },
    "TRANSIT": {
      "description": "Transit-Korridor Güter/Fernverkehr (Ref: Lugano)",
      "shares": {
        "FV": 0.5,
        "GV": 0.5
      }
    }
  },
  "modes": [
    {
      "mix": "MAINLINE",
      "patterns": [
        "FV_PASS",
        "FV_STOP"
      ]
    },
    {
      "mix": "TRANSIT",
      "patterns": [
        "FV_STOP"
      ]
    }
  ]
}
```

## Output Structure

All simulation results are written to a structured output directory.

```txt
 <output_directory>/
    ├── output_project_config.json
    └── <use_case_name>/
        └── <building_block_name>/
            ├── 01_train_run_calculation/
            ├── 02_schedule_sampling/
            ├── 03_simulation_job_config/
            ├── 04_simulation_run_output/
            └── 05_analysis/
                ├── <sub_variant_id>/
                │   └── <run_id>/
                │       └── analysis_train_delays.csv
                └── summary_train_delays.csv
```

## Running the Simulation

The simulation can be launched from command line or by running the `org.matsim.project.RunRailsimScenario` class with
program arguments in an IDE.

### Parameters

| Flag                         | Description                                                          | Default  | Required |
|------------------------------|----------------------------------------------------------------------|----------|:--------:|
| `--output`                   | Output directory for simulation results.                             |          |   Yes    |
| `--building-blocks`          | Comma-separated list of building blocks (e.g., `UC1_BB1,UC1_BB2`).   | `*`      |          |
| `-s`, `--samples`            | Number of samples per sub-variant.                                   | `5`      |          |
| `-t`, `--simulation-time`    | Total simulation time in seconds (should match the sampling period). | `10800`  |          |
| `-d`, `--departure-sampling` | Departure sampling strategy. (`RANDOM` or `HEADWAY`)                 | `RANDOM` |          |
| `--overwrite`                | Overwrite the output directory if it exists.                         | `false`  |          |

### Example Execution

```sh
# reduce MATSim verbosity
MATSIM_LOG_LEVEL='ERROR'

# program arguments
ARGS_ARRAY=(
    --output "/path/to/your/output_directory"
    --building-blocks "UC1_BB1,UC1_BB2,UC1_BB3"
    --samples "5"
    --simulation-time "10800"
    --departure-sampling "RANDOM"
    --overwrite
)

./mvnw exec:java -Dmatsim.log.level=${MATSIM_LOG_LEVEL} -Dexec.args="${ARGS_ARRAY[*]}"
```

## Licenses

- **Source Code**: The Java source code in the `src` directory is licensed under
  the [GNU General Public License v2.0](https://www.gnu.org/licenses/old-licenses/gpl-2.0.en.html).
- **Data and Visualizations**: All input files, output files, and analysis data are licensed under
  the [Creative Commons Attribution 4.0 International License](http://creativecommons.org/licenses/by/4.0/).

[![Creative Commons License](https://i.creativecommons.org/l/by/4.0/88x31.png)](http://creativecommons.org/licenses/by/4.0/)
