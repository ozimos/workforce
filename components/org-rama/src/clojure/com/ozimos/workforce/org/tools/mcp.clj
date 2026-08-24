(ns com.ozimos.workforce.org.tools.mcp
  "Model Context Protocol (MCP) server implementation for Workforce agents and external LLMs."
  (:require
   [clojure.walk :as walk]
   [com.ozimos.workforce.org.tools.escapement :as esc]
   [jsonista.core :as json]))

(defn- format-tools-manifest []
  (mapv (fn [t]
          {:name (:name t)
           :description (:description t)
           :inputSchema (:parameters t)})
        esc/tool-definitions))

(defn handle-mcp-request
  "Handles an incoming JSON-RPC 2.0 MCP request map and returns a JSON-RPC 2.0 response map."
  [deps ctx request-body]
  (let [body (if (string? request-body)
               (json/read-value request-body json/keyword-keys-object-mapper)
               (walk/keywordize-keys request-body))
        jsonrpc (get body :jsonrpc "2.0")
        req-id (get body :id)
        method (get body :method)
        params (get body :params {})]
    (try
      (case method
        ;; Protocol Handshake
        "initialize"
        {:jsonrpc jsonrpc
         :id req-id
         :result {:protocolVersion "2024-11-05"
                  :capabilities {:tools {:listChanged false}}
                  :serverInfo {:name "workforce-mcp-server"
                               :version "1.0.0"}}}

        "notifications/initialized"
        {:jsonrpc jsonrpc :id req-id :result {}}

        ;; Tools Listing
        "tools/list"
        {:jsonrpc jsonrpc
         :id req-id
         :result {:tools (format-tools-manifest)}}

        ;; Tool Execution
        "tools/call"
        (let [tool-name (or (get params :name) (get params "name"))
              args (or (get params :arguments) (get params "arguments") {})
              res (esc/call-tool deps ctx tool-name args)
              is-error? (not (get res :ok true))
              res-text (json/write-value-as-string res)]
          {:jsonrpc jsonrpc
           :id req-id
           :result {:content [{:type "text" :text res-text}]
                    :isError is-error?}})

        ;; Ping / Liveness
        "ping"
        {:jsonrpc jsonrpc :id req-id :result {}}

        ;; Unknown method
        {:jsonrpc jsonrpc
         :id req-id
         :error {:code -32601
                 :message (str "Method not found: " method)}})
      (catch Exception e
        {:jsonrpc jsonrpc
         :id req-id
         :error {:code -32603
                 :message (.getMessage e)}}))))
