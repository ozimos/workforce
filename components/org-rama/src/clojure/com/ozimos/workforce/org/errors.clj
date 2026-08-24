(ns com.ozimos.workforce.org.errors
  "Structured error schemas and constructor helpers for AI agent friendliness
   and machine-readable error communication across GraphQL/EQL, HTTP, and MCP."
  (:require
   [malli.core :as m]))

(def error-code-schema
  [:enum
   :unauthorized
   :invalid_transition
   :quota_exceeded
   :rule_violation
   :step_not_found
   :duplicate_event
   :missing_field
   :not_found
   :bad_request
   :internal_error])

(def error-schema
  [:map
   [:error-code error-code-schema]
   [:message :string]
   [:details {:optional true} [:map-of :keyword :any]]])

(defn make-error
  "Constructs a structured error map conforming to error-schema."
  ([error-code message]
   (make-error error-code message nil))
  ([error-code message details]
   (cond-> {:error-code error-code
            :message message}
     (some? details) (assoc :details details))))

(defn error-result
  "Returns a [false {:error ...}] tuple suitable for interface functions."
  ([error-code message]
   [false {:error (make-error error-code message nil)}])
  ([error-code message details]
   [false {:error (make-error error-code message details)}]))

(defn valid-error?
  "Validates whether an error map conforms to error-schema."
  [err]
  (m/validate error-schema err))
