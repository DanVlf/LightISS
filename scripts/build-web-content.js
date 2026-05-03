const { mkdirSync, readFileSync, writeFileSync } = require("node:fs");
const { resolve } = require("node:path");

const root = resolve(__dirname, "..");
const webRoot = resolve(root, "src", "web");
const html = readFileSync(resolve(webRoot, "index.html"), "utf8");
const css = readFileSync(resolve(webRoot, "styles.css"), "utf8");
const js = readFileSync(resolve(webRoot, "app.js"), "utf8");
const body = html.match(/<body>([\s\S]*)<\/body>/)?.[1] || "";
const content = `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>LightISS</title>
    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" crossorigin="">
    <style>${css}</style>
  </head>
  <body>${body.replace(/<script[\s\S]*?<\/script>/g, "")}
    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js" crossorigin=""></script>
    <script>${js}</script>
  </body>
</html>`;

mkdirSync(resolve(root, "src"), { recursive: true });
writeFileSync(resolve(root, "src", "webContent.ts"), `export const webContent = ${JSON.stringify(content)};\n`);
