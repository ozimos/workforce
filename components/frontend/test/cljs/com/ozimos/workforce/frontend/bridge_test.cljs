(ns com.ozimos.workforce.frontend.bridge-test
  (:require-macros
   [com.ozimos.workforce.frontend.defrc :as drc :refer [defrc]])
  (:require
   [cljs.test :refer [deftest is testing]]
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.fulcro.components :as comp]
   [com.fulcrologic.fulcro.mutations :refer [defmutation]]
   [com.ozimos.workforce.frontend.bridge :as bridge]
   [replicant.string :as rs]))

(defrc demo-root
  {:query [:app/thing]
   :ident :demo/root}
  [props]
  [:div {:class "demo-root"} (str "value=" (:app/thing props))])

(defmutation set-thing
  [{:keys [v]}]
  (action [{:keys [state]}]
    (swap! state assoc :app/thing v)))

(deftest defrc-creates-pure-view-with-fulcro-metadata
  (testing "defrc produces a plain fn, not a React component"
    (is (fn? demo-root)))
  (testing "Fulcro query/ident are preserved as metadata"
    (is (= [:app/thing] (:query (meta demo-root))))
    (is (= :demo/root (:ident (meta demo-root)))))
  (testing "the view is a pure function of props -> hiccup data"
    (is (= [:div {:class "demo-root"} "value=7"]
           (demo-root {:app/thing 7}))))
  (testing "view output renders to HTML without a DOM"
    (is (let [html (rs/render (demo-root {:app/thing 7}))]
          (.includes html "value=7")))))

(deftest bridge-renders-root-tree-and-rerenders-on-transact
  (let [app-inst (app/headless-synchronous-app demo-root)
        rendered (atom nil)
        _ (bridge/install-replicant-root! app-inst
                                          demo-root
                                          (js/Object.)
                                          {}
                                          (fn [_node hiccup] (reset! rendered hiccup)))]
    (testing "install renders the root query tree from the db immediately"
      (is (= [:div {:class "demo-root"} "value="] @rendered)))
    (testing "transact! updates the normalized db and triggers a re-render"
      (comp/transact! app-inst [(set-thing {:v 99})])
      (is (= [:div {:class "demo-root"} "value=99"] @rendered)))
    (testing "re-rendered output stays pure data"
      (is (let [html (rs/render @rendered)]
            (.includes html "value=99"))))))

(deftest dispatch-routes-action-data-to-registered-handler
  ;; ---------------------------------------------------------------------------
  ;; WHY ADDED / UPDATED:
  ;; In Replicant 2026.07.1, `*dispatch*` receives two arguments: `(fn [event-map handler-data])`.
  ;; When DOM events (clicks, inputs) fire, Replicant passes its event map containing
  ;; `:replicant/js-event`, `:replicant/trigger`, etc. as the first argument.
  ;;
  ;; WHAT IT PREVENTS:
  ;; Prevents dispatch arity mismatch errors and verifies that handler callbacks
  ;; can extract DOM event properties (e.g. `.-target.value` for text input)
  ;; as well as pure payload arguments.
  ;; ---------------------------------------------------------------------------
  (testing "Dispatch adapter handles Replicant 2-arg signature and passes event-map + remaining args to handler"
    (let [received-event (atom nil)
          received-args  (atom nil)
          adapter (bridge/dispatch!
                    {::do-action
                     (fn [ev-map & args]
                       (reset! received-event ev-map)
                       (reset! received-args args))})
          mock-event-map {:replicant/trigger :replicant.trigger/dom-event
                          :replicant/js-event #js {:target #js {:value "typed-query"}}}]
      ;; Invoke dispatch with Replicant 2026.07.1 contract:
      (adapter mock-event-map [::do-action 100 "arg2"])
      (is (= [100 "arg2"] @received-args)
          "Handler must receive the payload arguments after the event keyword")
      (is (= "typed-query" (.. (:replicant/js-event @received-event) -target -value))
          "Handler must have access to the raw JS event target value from the event map"))))

(deftest dispatch-auto-transacts-fulcro-mutations
  (testing "Dispatch adapter automatically transacts mutation expressions without registered handler"
    (let [app-inst (app/headless-synchronous-app demo-root)
          state-atom (::app/state-atom app-inst)
          adapter (bridge/dispatch! app-inst {})
          mock-event-map {:replicant/trigger :replicant.trigger/dom-event}]
      (is (nil? (:app/thing @state-atom)))
      (adapter mock-event-map [(list 'com.ozimos.workforce.frontend.bridge-test/set-thing {:v 42})])
      (is (= 42 (:app/thing @state-atom))))))

(deftest dispatch-handles-sequence-of-action-vectors
  (testing "Dispatch adapter processes sequence of action vectors e.g. [[:action-1] [:action-2]]"
    (let [calls (atom [])
          adapter (bridge/dispatch!
                    {::action-a (fn [_ & args] (swap! calls conj [::action-a (first args)]))
                     ::action-b (fn [_ & args] (swap! calls conj [::action-b (first args)]))})
          mock-event-map {:replicant/trigger :replicant.trigger/dom-event}]
      (adapter mock-event-map [[::action-a {:x 1}] [::action-b {:y 2}]])
      (is (= [[::action-a {:x 1}] [::action-b {:y 2}]] @calls)
          "Both action vectors in sequence must be invoked in order"))))

