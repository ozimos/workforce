(ns com.ozimos.workforce.rama.extension)

(defprotocol RamaModuleExtension
  "Protocol for extending the Rama AuthModule with external domain depots,
   PStates, and stream topologies without modifying core module code."
  (declare-depots [this setup]
    "Declare additional depots on the module setup object.")
  (declare-pstates [this topology]
    "Declare additional PStates on the stream topology.")
  (build-topology [this topology]
    "Attach stream event handlers / ETL pipeline logic to the topology."))
