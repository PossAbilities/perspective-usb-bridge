#!/usr/bin/env node
/**
 * Renders the Perspective Studio mark into the raster icon formats Windows
 * needs, straight from branding/ps-mark-primary.svg.
 *
 * Android's launcher icon is an adaptive vector drawable and needs no
 * generation, but legacy square/round PNG mipmaps are emitted alongside it so
 * the icon is correct on every launcher.
 *
 *   npm --prefix tools/branding install
 *   node tools/branding/build-icons.mjs
 *
 * Outputs (all committed, so a normal build needs no image tooling):
 *   windows/build/icon.ico            app + installer icon, 16-256px
 *   windows/build/installerHeader.bmp NSIS header strip,  150x57
 *   windows/build/installerSidebar.bmp NSIS welcome page, 164x314
 *   windows/src/assets/icon-256.png   BrowserWindow icon
   android res mipmap-<density>   legacy launcher PNG fallbacks
 */
import sharp from 'sharp';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const markPath = resolve(root, 'branding/ps-mark-primary.svg');
const MIDNIGHT = '#1A1546';

const out = p => resolve(root, p);
const write = async (path, data) => {
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, data);
  console.log(`  ${path.replace(root + '/', '')}  ${data.length} bytes`);
};

/** Render the mark to a square RGBA PNG buffer with a transparent background. */
async function renderMark(size) {
  const svg = await readFile(markPath);
  return sharp(svg, { density: 384 }).resize(size, size, {
    fit: 'contain',
    background: { r: 0, g: 0, b: 0, alpha: 0 }
  }).png().toBuffer();
}

// ------------------------------------------------------------------------ ICO

/**
 * 32-bit BGRA DIB entry. Sizes up to 64px are stored this way because some
 * Windows shell surfaces still refuse PNG-compressed entries at small sizes;
 * 128 and 256 are stored as PNG to keep the file small.
 */
function dibEntry(rgba, size) {
  const rowMask = Math.ceil(size / 32) * 4; // 1bpp AND mask, rows padded to 4 bytes
  const header = Buffer.alloc(40);
  header.writeUInt32LE(40, 0);
  header.writeInt32LE(size, 4);
  header.writeInt32LE(size * 2, 8); // XOR bitmap plus AND mask
  header.writeUInt16LE(1, 12);
  header.writeUInt16LE(32, 14);
  header.writeUInt32LE(0, 16); // BI_RGB
  header.writeUInt32LE(size * size * 4 + rowMask * size, 20);

  const xor = Buffer.alloc(size * size * 4);
  for (let y = 0; y < size; y++) {
    const source = (size - 1 - y) * size * 4; // DIBs are stored bottom-up
    for (let x = 0; x < size; x++) {
      const s = source + x * 4;
      const d = (y * size + x) * 4;
      xor[d] = rgba[s + 2];
      xor[d + 1] = rgba[s + 1];
      xor[d + 2] = rgba[s];
      xor[d + 3] = rgba[s + 3];
    }
  }
  // Alpha lives in the XOR bitmap, so the AND mask is fully opaque (all zero).
  return Buffer.concat([header, xor, Buffer.alloc(rowMask * size)]);
}

async function buildIco(path, sizes) {
  const images = [];
  for (const size of sizes) {
    const png = await renderMark(size);
    if (size >= 128) {
      images.push({ size, data: png });
    } else {
      const { data } = await sharp(png).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
      images.push({ size, data: dibEntry(data, size) });
    }
  }

  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0);
  header.writeUInt16LE(1, 2); // type 1 = icon
  header.writeUInt16LE(images.length, 4);

  let offset = 6 + images.length * 16;
  const directory = [];
  for (const image of images) {
    const entry = Buffer.alloc(16);
    entry.writeUInt8(image.size >= 256 ? 0 : image.size, 0); // 0 means 256
    entry.writeUInt8(image.size >= 256 ? 0 : image.size, 1);
    entry.writeUInt8(0, 2); // truecolour, no palette
    entry.writeUInt8(0, 3);
    entry.writeUInt16LE(1, 4);
    entry.writeUInt16LE(32, 6);
    entry.writeUInt32LE(image.data.length, 8);
    entry.writeUInt32LE(offset, 12);
    directory.push(entry);
    offset += image.data.length;
  }

  await write(path, Buffer.concat([header, ...directory, ...images.map(i => i.data)]));
}

// ------------------------------------------------------------------------ BMP

/**
 * NSIS wants uncompressed 24-bit BMPs for its header and sidebar images.
 *
 * `channels` is read from sharp rather than assumed: flatten() drops the alpha
 * channel on a plain pipeline but keeps a 4-channel layout once a composite is
 * involved, and guessing wrong shears the image.
 */
function bmp24(raw, width, height, channels) {
  const rowSize = Math.ceil((width * 3) / 4) * 4;
  const pixels = Buffer.alloc(rowSize * height);
  for (let y = 0; y < height; y++) {
    const source = (height - 1 - y) * width * channels;
    let d = y * rowSize;
    for (let x = 0; x < width; x++) {
      const s = source + x * channels;
      pixels[d++] = raw[s + 2];
      pixels[d++] = raw[s + 1];
      pixels[d++] = raw[s];
    }
  }
  const header = Buffer.alloc(54);
  header.write('BM', 0, 'ascii');
  header.writeUInt32LE(54 + pixels.length, 2);
  header.writeUInt32LE(54, 10);
  header.writeUInt32LE(40, 14);
  header.writeInt32LE(width, 18);
  header.writeInt32LE(height, 22);
  header.writeUInt16LE(1, 26);
  header.writeUInt16LE(24, 28);
  header.writeUInt32LE(pixels.length, 34);
  return Buffer.concat([header, pixels]);
}

async function buildBmp(path, width, height, markSize, left, top) {
  const mark = await renderMark(markSize);
  const { data, info } = await sharp({
    create: { width, height, channels: 4, background: MIDNIGHT }
  })
    .composite([{ input: mark, left, top }])
    .flatten({ background: MIDNIGHT })
    .raw()
    .toBuffer({ resolveWithObject: true });
  await write(path, bmp24(data, width, height, info.channels));
}

// ----------------------------------------------------------------------- main

console.log('Building Perspective USB Bridge icons from branding/ps-mark-primary.svg');
await buildIco(out('windows/build/icon.ico'), [16, 24, 32, 48, 64, 128, 256]);
await write(out('windows/src/assets/icon-256.png'), await renderMark(256));
// Header strip sits to the right of the wizard title; sidebar fills the left column.
await buildBmp(out('windows/build/installerHeader.bmp'), 150, 57, 44, 96, 6);
await buildBmp(out('windows/build/installerSidebar.bmp'), 164, 314, 108, 28, 103);

// Legacy launcher fallbacks. minSdk is 26 so adaptive icons cover every
// supported device, but a launcher that ignores them still gets brand artwork.
const DENSITIES = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
for (const [density, size] of Object.entries(DENSITIES)) {
  const inset = Math.round(size * 0.16);
  const mark = await renderMark(size - inset * 2);
  const dir = `android/app/src/main/res/mipmap-${density}`;

  const square = await sharp({ create: { width: size, height: size, channels: 4, background: MIDNIGHT } })
    .composite([{ input: mark, left: inset, top: inset }]).png().toBuffer();
  await write(out(`${dir}/ic_launcher.png`), square);

  const circle = Buffer.from(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}">` +
    `<circle cx="${size / 2}" cy="${size / 2}" r="${size / 2}" fill="${MIDNIGHT}"/></svg>`
  );
  const round = await sharp(circle)
    .composite([{ input: mark, left: inset, top: inset }]).png().toBuffer();
  await write(out(`${dir}/ic_launcher_round.png`), round);
}

console.log('Done.');
