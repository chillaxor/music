// Generate GD音乐 launcher icons: SVG -> PNG via @resvg/resvg-js
// Run: NODE_PATH=C:\Users\linghit\.workbuddy\binaries\node\workspace\node_modules <managed-node> tools/gen_icon.js
const fs = require("fs");
const path = require("path");
const { Resvg } = require("@resvg/resvg-js");

const RES = path.join(__dirname, "..", "app", "src", "main", "res");
const C_TOP = "#5B4BFF";
const C_BOT = "#3A2BD6";

// Note geometry designed in a 108x108 viewport (adaptive-icon foreground space), centered in safe zone.
// Heads rotated -18deg; stems + slanted beam. Bounds x:31-77, y:33-75 -> center (54,54).
function notePaths(k) {
  const heads = [
    [39.5, 73],
    [65.5, 67],
  ]
    .map(
      ([cx, cy]) =>
        `<ellipse cx="${cx * k}" cy="${cy * k}" rx="${8.5 * k}" ry="${6.8 * k}" fill="#FFFFFF" transform="rotate(-18 ${cx * k} ${cy * k})"/>`
    )
    .join("");
  const stems =
    `<rect x="${47 * k}" y="${39 * k}" width="${4 * k}" height="${36 * k}" fill="#FFFFFF"/>` +
    `<rect x="${73 * k}" y="${33 * k}" width="${4 * k}" height="${36 * k}" fill="#FFFFFF"/>`;
  const beam = `<polygon points="${47 * k},${39 * k} ${77 * k},${33 * k} ${77 * k},${41.5 * k} ${47 * k},${47.5 * k}" fill="#FFFFFF"/>`;
  return heads + stems + beam;
}

function svgLegacy(size, shape) {
  const k = size / 108;
  const bg =
    shape === "circle"
      ? `<circle cx="${size / 2}" cy="${size / 2}" r="${size / 2}" fill="url(#g)"/>`
      : `<rect width="${size}" height="${size}" rx="${0.22 * size}" fill="url(#g)"/>`;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}">
<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
<stop offset="0" stop-color="${C_TOP}"/><stop offset="1" stop-color="${C_BOT}"/>
</linearGradient></defs>
${bg}${notePaths(k)}
</svg>`;
}

function render(svgStr, outPath, width) {
  const resvg = new Resvg(svgStr, { fitTo: { mode: "width", value: width } });
  fs.writeFileSync(outPath, resvg.render().asPng());
}

const dpiMap = {
  "mipmap-mdpi": 48,
  "mipmap-hdpi": 72,
  "mipmap-xhdpi": 96,
  "mipmap-xxhdpi": 144,
  "mipmap-xxxhdpi": 192,
};
for (const [folder, size] of Object.entries(dpiMap)) {
  const out = path.join(RES, folder);
  fs.mkdirSync(out, { recursive: true });
  render(svgLegacy(size, "squircle"), path.join(out, "ic_launcher.png"), size);
  render(svgLegacy(size, "circle"), path.join(out, "ic_launcher_round.png"), size);
  console.log(`${folder}: ${size}px done`);
}
render(svgLegacy(512, "squircle"), path.join(RES, "..", "..", "..", "icon_preview.png"), 512);
console.log("preview saved");
