(ns com.ozimos.auth.frontend.ssr
  (:require
   [clojure.string :as str]
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.fulcro.components :as comp]
   [com.fulcrologic.fulcro.dom :as dom :refer [div p]]
   [com.ozimos.auth.frontend.ui.root :as root]))

(def React (js/require "react"))
(def ReactDOMServer (js/require "react-dom/server"))

(defn- escape-html [s]
  (-> s
      (.replace "&" "&amp;")
      (.replace "<" "&lt;")
      (.replace ">" "&gt;")
      (.replace "\"" "&quot;")
      (.replace "'" "&#39;")))

(defn- authenticated? []
  (= js/process.env.SSR_AUTHENTICATED "true"))

(defn- setup-ssr-globals [path search]
  ;; window and localStorage are provided by ssr-server/shim.js at module-load
  ;; time (before this bundle is required), so they always exist. Here we only
  ;; patch the per-request path on the existing objects.
  (set! (.-pathname (.-location js/window)) path)
  (set! (.-search (.-location js/window)) (if (seq search) (str "?" search) "")))

(defn- page-title [path]
  (cond
    (= path "/login")            "Sign In"
    (= path "/register")         "Create Account"
    (= path "/forgot-password")  "Forgot Password"
    (= path "/reset-password")   "Reset Password"
    (= path "/verify")           "Verify Account"
    (= path "/")                 "Dashboard"
    :else                        "Best Auth"))

(defn- page-description [path]
  (cond
    (= path "/login")            "Sign in to your account"
    (= path "/register")         "Create a new account"
    (= path "/forgot-password")  "Reset your password"
    (= path "/reset-password")   "Set a new password"
    (= path "/verify")           "Verify your email address"
    (= path "/")                 "Dashboard - Best Auth"
    :else                        "Best Auth - Authentication Template"))

(defn- ssr-render [factory app-inst]
  (binding [comp/*app* app-inst]
    (.renderToString ReactDOMServer (factory nil))))

(def ssr-artifact-errors
  #{"No matching clause: "})

(defn- is-ssr-artifact? [e]
  (some #(.startsWith (.-message e) %) ssr-artifact-errors))

(defn ^:export render-page-html
  ([path] (render-page-html path "" "" ""))
  ([path search] (render-page-html path search "" ""))
  ([path search initial-data-json] (render-page-html path search initial-data-json ""))
  ([path search initial-data-json initial-data-nonce]
   (setup-ssr-globals path search)
   (let [title (str "Best Auth - " (page-title path))
         description (page-description path)
         {:keys [status html error-message]}
         (try
           (let [app-inst (app/fulcro-app {})
                 factory (comp/factory root/Root)
                 rendered (ssr-render factory app-inst)]
             {:status :ok :html rendered})
           (catch js/Error e
             (if (is-ssr-artifact? e)
               {:status :limited
                :error-message (str "SSR_LIMITED (page renders in browser but not in SSR): "
                                    (.-message e))}
               {:status :error
                :error-message (str (.-message e) "\n" (.-stack e))})))]
     (str "<!DOCTYPE html>"
          "<html lang=\"en\" class=\"h-full\">"
          "<head>"
          "<meta charset=\"UTF-8\">"
          "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
          "<title>" (escape-html title) "</title>"
          "<meta name=\"description\" content=\"" (escape-html description) "\">"
          "<meta name=\"ssr-status\" content=\"" (name status) "\">"
          (when (authenticated?)
            "<meta name=\"ssr-authenticated\" content=\"true\">")
          "<link href=\"/css/app.css\" rel=\"stylesheet\">"
          (when (seq initial-data-json)
            (str "<script"
                 (when (seq initial-data-nonce)
                   (str " nonce=\"" (escape-html initial-data-nonce) "\""))
                 ">window.__INITIAL_DATA__=" initial-data-json "</script>"))
          "</head>"
          "<body class=\"h-full bg-gray-50\">"
          (case status
            :error   (str "<div id=\"ssr-error\" "
                          "style=\"background:#fee;color:#c00;padding:1em;margin:1em;"
                          "border:2px solid #c00;white-space:pre-wrap;font-family:monospace\">"
                          (escape-html error-message)
                          "</div>")
            :limited (str "<div id=\"ssr-limited\" "
                          "style=\"background:#fff3cd;color:#856404;padding:1em;margin:1em;"
                          "border:2px solid #ffc107;font-family:monospace\">"
                          (escape-html error-message)
                          "</div>")
            "")
          "<div id=\"app\" class=\"h-full\">"
          (if (= status :ok) html "")
          "</div>"
          "<script src=\"/js/main.js\"></script>"
          "<!-- " (case status
                    :ok      "SSR OK"
                    :limited "SSR LIMITED"
                    :error   (str "SSR ERROR: " (escape-html error-message)))
          " -->"
          "</body>"
          "</html>"))))
