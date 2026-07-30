# Profile Page — Username Update

## Overview

Allow authenticated users to update their display/public username from a dedicated `/profile` page. The Rama module already has `$$username->id` (uniqueness index) and `$$profiles` (user profiles) — no new PStates needed.

## Scope

10 files, 6 layers. No new PStates, no new schema types, no auth middleware changes.

## Changes

### 1. Rama Module — `components/rama/src/clojure/com/ozimos/auth/rama/module.clj`

**New record:**

```clojure
(defrecord UsernameChange [user-id new-username])
```

**New depot:**

```clojure
(declare-depot setup *username-change-depot (hash-by :user-id))
```

**Dataflow — `auth` stream topology:**

```
source> *username-change-depot :> {:keys [*user-id *new-username]}
  ;; In user-id partition (depot hash-by :user-id)
  local-select> (keypath *user-id :username) $$profiles :> *old-username
  <<if (not= *old-username *new-username)
    ;; Switch to new-username partition — check uniqueness
    |hash *new-username
    local-select> (keypath *new-username) $$username->id :> *existing-id
    <<if (nil? *existing-id)
      ;; Clear old mapping
      |hash *old-username
      local-clear> (keypath *old-username) $$username->id
      ;; Set new mapping
      |hash *new-username
      local-transform> [(keypath *new-username) (termval *user-id)] $$username->id
      ;; Update profile
      |hash *user-id
      local-transform> [(keypath *user-id :username) (termval *new-username)] $$profiles
      ack-return> :ok
      (else>)
      ack-return> :taken
    (else>)
    ack-return> :ok)   ;; same username — no-op
```

Follows the existing `|hash` partition-switching pattern from registration dataflow.

### 2. Schema — `components/schema/.../interface/registration.clj`

```clojure
(def update-username-request
  [:map
   [:new-username schema/username]])

(def update-username-response
  [:map
   [:username :string]])
```

Reuses existing `schema/username` (3–32 chars, `^[a-zA-Z0-9_-]+$`).

### 3. User Component — `components/user/.../core.clj`

```clojure
(defn update-username! [{:keys [rama] :as deps} user-id new-username]
  (when-not (m/validate schema/username new-username)
    (throw (ex-info "Invalid username" {:new-username new-username})))
  (let [cmgr (:cluster-manager rama)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*username-change-depot")
        result (ramaapi/foreign-append! depot
                 (rama/->UsernameChange user-id new-username))]
    (case (get result "auth")
      :ok    [true new-username]
      :taken [false {:errors {:new-username ["Username already taken."]}}]
      [false {:errors {:new-username ["Update failed."]}}])))
```

**Interface** (`interface.clj`):

```clojure
(defn update-username!
  "Update a user's username. Returns [true new-username] on success,
   [false {:errors ...}] on failure."
  [deps user-id new-username]
  (core/update-username! deps user-id new-username))
```

### 4. Auth API — `bases/auth-api/...`

**Routes** (`routes.clj`):

```clojure
["/profile/username"
 {:post {:summary "Update username"
         :parameters {:body reg-schema/update-username-request}
         :handler (handlers/update-username deps)
         :responses {200 {:body [:map [:username :string]]}
                     409 {:body [:map [:errors [:map]]]}}}}]
```

**Handler** (`handlers.clj`):

```clojure
(defn update-username [deps]
  (fn [request]
    (let [auth-user (get-auth-user request)]
      (if auth-user
        (let [{:keys [body-params]} request
              {:keys [new-username]} body-params
              {:keys [user-store]} deps
              result (user/update-username! user-store (:user-id auth-user) new-username)]
          (if (first result)
            {:status 200 :body {:username (second result)}}
            {:status 409 :body {:errors (second result)}}))
        {:status 401 :body {:errors {:auth ["Not authenticated"]}}}))))
```

### 5. Frontend — New Profile Page

**`components/frontend/.../ui/pages/profile.cljs`**:

```clojure
(ns com.ozimos.auth.frontend.ui.pages.profile
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [a button div form h2 input label p]]
   [com.ozimos.auth.frontend.json :as json]))

(defn- update-username [this]
  (let [{:keys [new-username]} (comp/get-state this)]
    (when (and new-username (>= (.-length new-username) 3))
      (comp/set-state! this {:error-msg nil :success-msg nil})
      (-> (json/fetch-json "/api/auth/profile/username" "POST" {:new-username new-username})
          (.then (fn [{:keys [status body]}]
                   (if (= 200 status)
                     (do
                       (.setItem js/localStorage "username" (:username body))
                       (comp/set-state! this
                         {:new-username "" :success-msg "Username updated!" :error-msg nil}))
                     (comp/set-state! this
                       {:error-msg (or (-> body :errors :new-username first)
                                      "Failed to update username")}))))))))

(defsc Profile [this _props]
  {:query [:new-username :error-msg :success-msg]
   :initial-state {:new-username "" :error-msg nil :success-msg nil}}
  (let [{:keys [new-username error-msg success-msg]} (comp/get-state this)
        current-username (and (exists? js/localStorage) (.getItem js/localStorage "username"))]
    (div {:className "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
      (div {:className "sm:mx-auto sm:w-full sm:max-w-sm"}
        (h2 {:className "mt-10 text-center text-2xl font-bold leading-9 tracking-tight text-gray-900"}
          "Profile"))
      (div {:className "mt-10 sm:mx-auto sm:w-full sm:max-w-sm"}
        (when error-msg
          (div {:className "rounded-md bg-red-50 p-4 mb-4"}
            (p {:className "text-sm text-red-700"} error-msg)))
        (when success-msg
          (div {:className "rounded-md bg-green-50 p-4 mb-4"}
            (p {:className "text-sm text-green-700"} success-msg)))
        (div {:className "mb-6"}
          (label {:className "block text-sm font-medium leading-6 text-gray-900"} "Current username")
          (p {:className "mt-1 text-sm text-gray-600"} (or current-username "—")))
        (form {:onSubmit (fn [e] (.preventDefault e) (update-username this))}
          (div {:className "mt-4"}
            (label {:htmlFor "new-username" :className "block text-sm font-medium leading-6 text-gray-900"} "New username")
            (div {:className "mt-2"}
              (input {:id "new-username" :name "new-username" :type "text" :required true
                      :minLength 3 :maxLength 32
                      :pattern "[a-zA-Z0-9_-]+"
                      :value new-username
                      :onChange #(comp/set-state! this {:new-username (.. % -target -value)})
                      :className "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"})))
          (div {:className "mt-6"}
            (button {:type "submit" :className "flex w-full justify-center rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold leading-6 text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600"}
              "Save"))
          (div {:className "mt-4 text-center"}
            (a {:href "/" :className "text-sm font-semibold text-indigo-600 hover:text-indigo-500"}
              "Back to home")))))))
```

### 6. Frontend — NavBar

**`components/frontend/.../ui/components/nav.cljs`**: Add Profile link after username display:

```clojure
(a {:href "/profile" :className "text-sm font-semibold text-indigo-600 hover:text-indigo-500"} "Profile")
```

### 7. Frontend — Root

**`components/frontend/.../ui/root.cljs`**:

- Require profile page: `[com.ozimos.auth.frontend.ui.pages.profile :as profile]`
- Add route in `current-page`:
  ```clojure
  (= path "/profile") :route/profile
  ```
- Add route in `route-for-page`:
  ```clojure
  :route/profile "/profile"
  ```
- Add factory: `(def profile-factory (delay (comp/factory profile/Profile)))`
- Render in case expression:
  ```clojure
  :route/profile (@profile-factory)
  ```

### 8. Frontend — SSR

**`components/frontend/.../ssr.cljs`**: Add entries:

```clojure
(= path "/profile")          "Profile"      ;; page-title
(= path "/profile")          "Your profile" ;; page-description
```

### 9. Tests

**Rama IPC test** (`components/rama/test/.../ipc_test.clj`):

```
deftest username-change-test
  Register user with username "oldname"
  Append UsernameChange with new-username "newname"
  Assert $$username->id["oldname"] → nil (cleared)
  Assert $$username->id["newname"] → user-id
  Assert $$profiles[user-id :username] → "newname"
  Append same UsernameChange again
  Assert ack-return is :ok (same-username no-op)
```

**User IPC test** (`components/user/test/.../ipc_test.clj`):

```
deftest update-username-test
  Register user
  update-username! with new valid username → [true new-name]
  find-by-username new-name → user map with new username
  find-by-username old-name → nil

  update-username! with existing username → [false {:errors ...}]
  update-username! with same username → [true same-name] (no-op)
```

**Integration test** (`bases/auth-api/test/.../integration_test.clj`):

```
deftest profile-update-test
  Register user → login → get access-token
  POST /api/auth/profile/username with auth header → 200, new username echoed
  Login with new username → 200 (updated username works)
```

### What does NOT change

| Layer | Reason |
|---|---|
| Spring Security config | `SecurityConfig.java` stays — `/api/auth/profile/username` is under `/api/auth/*` which already requires authentication via `.anyRequest().authenticated()` |
| JWT issuance | `sub` is user-id (Long), unaffected by username changes |
| Fulcro app state | NavBar reads from `localStorage` — profile page updates it directly; no app state refactor needed |
| `wrap-spa` / SSR routing | SPA catch-all returns `index.html` for `/profile` — no server-side route change |
| Rama PState schemas | `$$username->id` and `$$profiles` schemas stay identical |

## Execution Order

1. Rama module (record + depot + dataflow)
2. Schema (request/response malli schemas)
3. User component (`update-username!` + interface export)
4. Auth API (route + handler)
5. Frontend — profile page (new file)
6. Frontend — NavBar + Root + SSR (wiring)
7. Tests — Rama IPC + user IPC + integration
8. Frontend rebuild + verify
