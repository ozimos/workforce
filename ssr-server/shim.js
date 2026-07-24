// SSR environment shims — must be required before ssr-output/ssr.js so that
// modules whose top-level forms touch window.location / localStorage do not
// throw at import time. The actual per-request path is set later from
// ssr.cljs's render-page-html via (set!) on these objects.

if (typeof globalThis.window === "undefined") {
  globalThis.window = {
    location: {
      pathname: "/",
      search: "",
      hash: "",
      href: "/",
    },
  };
}

if (typeof globalThis.localStorage === "undefined") {
  const store = {};
  globalThis.localStorage = {
    getItem: (k) => (Object.prototype.hasOwnProperty.call(store, k) ? store[k] : null),
    setItem: (k, v) => { store[k] = String(v); },
    removeItem: (k) => { delete store[k]; },
    clear: () => { for (const k in store) delete store[k]; },
  };
}

// When SSR_AUTHENTICATED=true, pre-seed tokens so Root's logged-in? returns true.
if (process.env.SSR_AUTHENTICATED === "true") {
  globalThis.localStorage.setItem("access-token", "ssr-fake-access-token");
  globalThis.localStorage.setItem("refresh-token", "ssr-fake-refresh-token");
  globalThis.localStorage.setItem("username", "ssr-user");
}
