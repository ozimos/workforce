const http = require("http");

function testApiProxy() {
  const data = JSON.stringify({
    identifier: "tovieye.ozi@gmail.com",
    password: "overtake-septum-thesis-confusing-chest-eaten"
  });

  const options = {
    hostname: "localhost",
    port: 3000,
    path: "/api/auth/login",
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Content-Length": Buffer.byteLength(data)
    }
  };

  const req = http.request(options, (res) => {
    let body = "";
    res.on("data", (chunk) => (body += chunk));
    res.on("end", () => {
      console.log("HTTP Status:", res.statusCode);
      console.log("Content-Type:", res.headers["content-type"]);
      
      const isJson = (res.headers["content-type"] || "").includes("application/json");
      if (!isJson) {
        console.error("FAIL: Expected Content-Type to contain application/json, but got:", res.headers["content-type"]);
        console.error("Body snippet:", body.slice(0, 200));
        process.exit(1);
      } else {
        console.log("SUCCESS: Received JSON response from API proxy!");
        process.exit(0);
      }
    });
  });

  req.on("error", (err) => {
    console.error("Request Error:", err.message);
    process.exit(1);
  });

  req.write(data);
  req.end();
}

testApiProxy();
