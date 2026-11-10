import fs from 'node:fs';
import path from 'node:path';

const [, , url, out] = process.argv;
if (!url || !out) {
  console.error('usage: node dl.mjs <url> <outfile>');
  process.exit(1);
}
fs.mkdirSync(path.dirname(out), { recursive: true });

const res = await fetch(url, { redirect: 'follow' });
if (!res.ok) {
  console.error('HTTP ' + res.status + ' for ' + url);
  process.exit(2);
}
const total = Number(res.headers.get('content-length') || 0);
const file = fs.createWriteStream(out);
let got = 0;
let last = 0;
const reader = res.body.getReader();
for (;;) {
  const { done, value } = await reader.read();
  if (done) break;
  file.write(Buffer.from(value));
  got += value.length;
  if (got - last > 8 * 1024 * 1024) {
    last = got;
    console.log(`${path.basename(out)} ${(got / 1048576).toFixed(1)}MB` + (total ? ` / ${(total / 1048576).toFixed(1)}MB` : ''));
  }
}
await new Promise((r) => file.end(r));
console.log('DONE ' + out + ' ' + (got / 1048576).toFixed(1) + 'MB');
