#!/usr/bin/env node
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');
const rendererDir = path.resolve(root, '../electron/src/renderer');
const wwwDir = path.join(root, 'www');

const copyFiles = ['index.html', 'styles.css', 'renderer.js'];

fs.mkdirSync(wwwDir, { recursive: true });

for (const name of copyFiles) {
  fs.copyFileSync(path.join(rendererDir, name), path.join(wwwDir, name));
}

let html = fs.readFileSync(path.join(wwwDir, 'index.html'), 'utf-8');
html = html.replace(
  '<script src="renderer.js"></script>',
  `<script src="capacitor-api.js"></script>\n    <script src="renderer.js"></script>`,
);
fs.writeFileSync(path.join(wwwDir, 'index.html'), html, 'utf-8');

console.log('[sync-www] Updated mobile/www from electron renderer');
