(ns com.ozimos.workforce.org.tools.escapement
  "Custom Escapement tool declarations for workforce organizational operations.")

(defn register-tools
  "Registration entry point called by Escapement on startup."
  []
  ;; Tool definitions will be loaded when Escapement initializes.
  {:name "workforce-tools"
   :version "0.1.0"})
