(ns com.ozimos.workforce.frontend.ui.pages.unconnected-side-tree
  (:require
   [clojure.string :as str]))

(defn- render-unconnected-emp-card [emp is-root? has-children? child-count]
  (let [id (:person/id emp)
        name (:person/name emp)
        title (:person/title emp "Employee")
        dept (:person/department-name emp "Unassigned")
        loc (:person/location emp)]
    [:div {:class "bg-white rounded-xl border border-amber-200 p-3 shadow-xs hover:shadow-sm transition"}
     [:div {:class "flex items-start justify-between gap-2"}
      [:div {:class "flex-1 min-w-0"}
       [:div {:class "flex items-center gap-1.5 flex-wrap"}
        [:span {:class "font-bold text-xs text-gray-900 truncate"} name]
        (if is-root?
          [:span {:class "px-1.5 py-0.5 rounded text-[10px] font-bold bg-amber-100 text-amber-800"}
           (if has-children? "Orphan Subtree Root" "Unassigned")]
          [:span {:class "px-1.5 py-0.5 rounded text-[10px] font-medium bg-gray-100 text-gray-600"} "Subtree Report"])]
       [:p {:class "text-[11px] text-gray-600 truncate mt-0.5"} title]
       [:div {:class "flex items-center gap-2 mt-1 text-[10px] text-gray-400"}
        (when dept [:span {:class "truncate"} (str "📁 " dept)])
        (when loc [:span "📍 " loc])]]]

     ;; Action buttons
     [:div {:class "mt-2 pt-2 border-t border-gray-100 flex items-center justify-between gap-2"}
      (if has-children?
        [:span {:class "text-[10px] font-semibold text-amber-700"}
         (str child-count " direct report" (when (> child-count 1) "s"))]
        [:span {:class "text-[10px] text-gray-400 italic"} "No reporting manager"])

      [:button {:class "inline-flex items-center gap-1 rounded bg-indigo-50 px-2 py-0.5 text-[11px] font-semibold text-indigo-700 hover:bg-indigo-100 transition ring-1 ring-inset ring-indigo-200 shadow-2xs cursor-pointer"
                :title "Set as root to inspect this subtree in My Org"
                :on {:click [[:com.ozimos.workforce.frontend.ui.pages.workforce-chart/set-custom-root {:id id}]]}}
       "🎯 Set as Root"]]]))

(defn- render-unconnected-hc-card [hc is-root? has-children? child-count]
  (let [id (:headcount/id hc)
        title (:headcount/title hc "Open Position")
        level (:headcount/job-level hc)
        loc (:headcount/location hc)
        status (:headcount/status hc "open")]
    [:div {:class "bg-amber-50/50 rounded-xl border border-dashed border-amber-300 p-3 shadow-xs hover:shadow-sm transition"}
     [:div {:class "flex items-start justify-between gap-2"}
      [:div {:class "flex-1 min-w-0"}
       [:div {:class "flex items-center gap-1.5 flex-wrap"}
        [:span {:class "font-bold text-xs text-amber-950 truncate"} title]
        (if is-root?
          [:span {:class "px-1.5 py-0.5 rounded text-[10px] font-bold uppercase bg-amber-200/70 text-amber-900"} status]
          [:span {:class "px-1.5 py-0.5 rounded text-[10px] font-medium bg-gray-100 text-gray-600"} "Subtree Report"])]
       [:div {:class "flex items-center gap-2 mt-1 text-[10px] text-amber-700"}
        (when level [:span (str "Lvl: " level)])
        (when loc [:span (str "📍 " loc)])
        [:span {:class "font-mono"} id]]]]

     ;; Action buttons
     [:div {:class "mt-2 pt-2 border-t border-amber-200/60 flex items-center justify-between gap-2"}
      (if has-children?
        [:span {:class "text-[10px] font-semibold text-amber-800"}
         (str child-count " report" (when (> child-count 1) "s"))]
        [:span {:class "text-[10px] text-amber-600 italic"} "Unassigned requisition"])

      [:button {:class "inline-flex items-center gap-1 rounded bg-white px-2 py-0.5 text-[11px] font-semibold text-amber-800 hover:bg-amber-100 transition ring-1 ring-inset ring-amber-300 shadow-2xs cursor-pointer"
                :title "Set as root to inspect this subtree in My Org"
                :on {:click [[:com.ozimos.workforce.frontend.ui.pages.workforce-chart/set-custom-root {:id id}]]}}
       "🎯 Set as Root"]]]))

(defn render-unconnected-drawer
  [{:keys [open? unconnected-workforce unconnected-headcounts unconnected-hierarchy unconnected-roots unconnected-count]}]
  (let [count-val (or unconnected-count
                      (+ (count unconnected-workforce) (count unconnected-headcounts)))]
    (if-not open?
      ;; Collapsed Floating Tab
      [:button {:class "fixed right-0 top-1/2 -translate-y-1/2 z-30 bg-amber-600 hover:bg-amber-700 text-white font-semibold text-xs py-2.5 px-3 rounded-l-xl shadow-lg flex items-center gap-2 cursor-pointer transition transform hover:-translate-x-1"
                :title "View nodes not connected to the executive root hierarchy"
                :on {:click [[:com.ozimos.workforce.frontend.ui.pages.workforce-chart/toggle-unconnected-drawer {}]]}}
       [:span "⚠️ Disconnected"]
       [:span {:class "bg-amber-800 px-1.5 py-0.5 rounded-full text-[10px] font-bold"} (str count-val)]]

      ;; Expanded Slide-over Panel
      [:div {:class "fixed inset-0 z-40 overflow-hidden pointer-events-none"}
       ;; Backdrop
       [:div {:class "absolute inset-0 bg-gray-900/20 backdrop-blur-2xs transition-opacity pointer-events-auto"
              :on {:click [[:com.ozimos.workforce.frontend.ui.pages.workforce-chart/close-unconnected-drawer {}]]}}]

       ;; Slide-over drawer
       [:div {:class "absolute inset-y-0 right-0 max-w-full flex pl-10 pointer-events-auto"}
        [:div {:class "w-96 max-w-screen-md bg-white shadow-2xl border-l border-gray-200 flex flex-col"}
         ;; Header
         [:div {:class "p-4 bg-amber-50 border-b border-amber-200 flex items-center justify-between"}
          [:div
           [:div {:class "flex items-center gap-2"}
            [:span {:class "text-base"} "⚠️"]
            [:h3 {:class "text-sm font-bold text-amber-950"} "Disconnected Nodes"]
            [:span {:class "px-2 py-0.5 text-xs font-bold bg-amber-200 text-amber-900 rounded-full"}
             (str count-val)]]
           [:p {:class "text-[11px] text-amber-700 mt-1"}
            "Workers & requisitions not reachable from the executive root."]]

          [:button {:class "p-1 rounded-lg text-amber-700 hover:text-amber-950 hover:bg-amber-100 transition cursor-pointer"
                    :title "Close panel"
                    :on {:click [[:com.ozimos.workforce.frontend.ui.pages.workforce-chart/close-unconnected-drawer {}]]}}
           [:span {:class "text-lg font-bold"} "✕"]]]

         ;; Explanatory Callout
         [:div {:class "px-4 py-2.5 bg-amber-50/60 border-b border-amber-100 text-[11px] text-amber-800"}
          "💡 Click " [:span {:class "font-bold text-indigo-700"} "Set as Root"] " on any node below to inspect that disconnected subtree in " [:span {:class "font-semibold"} "My Org"] " view."]

         ;; Scrollable List of Disconnected Nodes
         ;; Scrollable List of Disconnected Nodes
         [:div {:class "flex-1 overflow-y-auto p-4 space-y-4"}
          (if (zero? count-val)
            [:div {:class "text-center py-12 text-gray-400 text-xs"}
             "No disconnected nodes found! All nodes are properly connected to the org chart."]

            (let [emp-map (into {} (map (fn [e] [(:person/id e) e])) unconnected-workforce)
                  hc-map (into {} (map (fn [h] [(:headcount/id h) h])) unconnected-headcounts)
                  roots (or (seq unconnected-roots) (sort (keys emp-map)))]
              (into [:div {:class "space-y-3"}]
                    (for [rid roots]
                      (let [is-hc? (or (str/starts-with? (str rid) "req-") (contains? hc-map rid))
                            node (if is-hc? (get hc-map rid) (get emp-map rid))
                            children (get unconnected-hierarchy rid [])
                            has-children? (boolean (seq children))]
                        [:div {:key (str "unconnected-root-" rid) :class "space-y-2"}
                         (if is-hc?
                           (render-unconnected-hc-card node true has-children? (count children))
                           (render-unconnected-emp-card node true has-children? (count children)))

                         (when has-children?
                           (into [:div {:class "pl-4 border-l-2 border-amber-200 ml-3 space-y-2 pt-1"}]
                                 (for [cid children]
                                   (let [c-is-hc? (or (str/starts-with? (str cid) "req-") (contains? hc-map cid))
                                         c-node (if c-is-hc? (get hc-map cid) (get emp-map cid))]
                                     [:div {:key (str "unconnected-child-" cid)}
                                      (if c-is-hc?
                                        (render-unconnected-hc-card c-node false false 0)
                                        (render-unconnected-emp-card c-node false false 0))]))))])))))]]]])))
