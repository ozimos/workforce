const express = require("express");
const path = require("path");
const fs = require("fs");
const { createProxyMiddleware } = require("http-proxy-middleware");

// Install window/localStorage shims BEFORE requiring the SSR bundle, so page
// modules whose top-level forms touch window.location / localStorage do not
// throw at import time.
require("./shim");

const app = express();

function getApiTarget() {
  if (process.env.API_TARGET) {
    return process.env.API_TARGET;
  }
  if (process.env.JETTY_DEV_PORT) {
    return `http://localhost:${process.env.JETTY_DEV_PORT}`;
  }
  try {
    const depsLocalPath = path.resolve(__dirname, "..", "deps.local.edn");
    if (fs.existsSync(depsLocalPath)) {
      const content = fs.readFileSync(depsLocalPath, "utf-8");
      const match = content.match(/:jetty\/port\s*\{[^}]*:dev\s*(\d+)/);
      if (match && match[1]) {
        return `http://localhost:${match[1]}`;
      }
    }
  } catch (err) {
    // Fall back to default if reading/parsing fails
  }
  return "http://localhost:8080";
}

const PORT = process.env.SSR_PORT || 3000;
const API_TARGET = getApiTarget();

const PUBLIC_DIR = path.resolve(__dirname, "..", "bases", "web", "resources", "public");
const SSR_OUTPUT_DIR = path.resolve(__dirname, "..", "ssr-output");

let ssrModule = null;

function loadSsrModule() {
  const ssrPath = path.resolve(SSR_OUTPUT_DIR, "ssr.js");
  if (fs.existsSync(ssrPath)) {
    try {
      delete require.cache[require.resolve(ssrPath)];
      ssrModule = require(ssrPath);
      console.log("SSR module loaded from", ssrPath);
      return true;
    } catch (err) {
      console.error("Failed to load SSR module:", err.message);
      ssrModule = null;
      return false;
    }
  }
  return false;
}

function getSpaRoutes() {
  return ["/", "/login", "/register", "/forgot-password", "/reset-password", "/verify"];
}

function isSpaRoute(url) {
  const pathname = url.split("?")[0];
  return getSpaRoutes().includes(pathname) || /^\/[^/]*$/.test(pathname);
}

function renderSsrPage(req, res) {
  const pathname = req.path;
  const search = (req.url.split("?")[1] || "");

  if (!ssrModule || !ssrModule.render_page_html) {
    serveIndexHtml(res);
    return;
  }

  try {
    const html = ssrModule.render_page_html(pathname, search, "", "");
    if (html.includes("<!-- SSR ERROR")) {
      const match = html.match(/<!-- SSR ERROR: (.+?) -->/);
      const errorDetail = match ? match[1].replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&").replace(/&quot;/g, "\"").replace(/&#39;/g, "'") : "unknown error";
      console.error(`\n=== SSR RENDER ERROR for ${pathname} ===`);
      console.error(errorDetail);
      console.error("========================================\n");
    } else if (html.includes("<!-- SSR LIMITED")) {
      console.log(`SSR LIMITED: ${pathname}`);
    } else {
      console.log(`SSR OK: ${pathname}`);
    }
    res.status(200).type("html").send(html);
  } catch (err) {
    console.error(`\n=== SSR RENDER CRASH for ${pathname} ===`);
    console.error(err.stack);
    console.error("=================================\n");
    serveIndexHtml(res);
  }
}

function serveIndexHtml(res) {
  const indexPath = path.resolve(PUBLIC_DIR, "index.html");
  if (fs.existsSync(indexPath)) {
    res.status(200).type("html").send(fs.readFileSync(indexPath, "utf-8"));
  } else {
    res.status(404).send("Not found");
  }
}

function setupMiddleware() {
  app.use((req, res, next) => {
    res.setHeader("X-SSR-Server", "best-auth-ssr");
    next();
  });

  // maxAge=0 so the browser revalidates on every request (sends
  // If-Modified-Since). Shadow recompiles overwrite the same filenames, so
  // long-lived caching freezes dev on a stale bundle.
  app.use("/js", express.static(path.resolve(PUBLIC_DIR, "js"), { maxAge: 0 }));
  app.use("/css", express.static(path.resolve(PUBLIC_DIR, "css"), { maxAge: 0 }));

  app.get(getSpaRoutes(), (req, res, next) => {
    if (req.path === "/" && req.accepts("html")) {
      renderSsrPage(req, res);
    } else if (req.path !== "/") {
      renderSsrPage(req, res);
    } else {
      next();
    }
  });

  const apiProxy = createProxyMiddleware({
    target: API_TARGET,
    changeOrigin: true,
    proxyTimeout: 10000,
    timeout: 10000,
    on: {
      error: (err, req, res) => {
        console.error("API proxy error:", err.message);
        res.status(502).json({ error: "API gateway error", message: err.message });
      },
    },
  });
  // Express app.use("/api", ...) strips the "/api" prefix from req.url.
  // Restore the "/api" prefix before proxying so Jetty receives the full path (e.g. /api/auth/login).
  app.use("/api", (req, res, next) => {
    req.url = "/api" + req.url;
    apiProxy(req, res, next);
  });

  app.use(express.static(PUBLIC_DIR, { index: false, maxAge: 0 }));

  app.use((req, res) => {
    renderSsrPage(req, res);
  });
}

function startServer() {
  loadSsrModule();

  app.listen(PORT, () => {
    console.log(`SSR server listening on http://localhost:${PORT}`);
    console.log(`Proxying /api/* to ${API_TARGET}`);
    console.log(`Serving static files from ${PUBLIC_DIR}`);
  });
}

setupMiddleware();
startServer();
