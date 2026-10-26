/**
 * 品牌图标（favicon.ico）生成脚本
 *
 * 用法：pnpm run gen:favicon
 *
 * 输出：public/favicon.ico（多尺寸 16/32/48/64，PNG 压缩的 ICO）
 * 图形：蓝紫渐变圆角底 + 白色“四向光芒”标记，与界面中的品牌标记保持一致
 */
const zlib = require('node:zlib')
const fs = require('node:fs')
const path = require('node:path')

const OUT = process.argv[2] || path.resolve(__dirname, '../public/favicon.ico')
const SIZES = [16, 32, 48, 64]

// ---- 图形定义（100x100 设计稿坐标，圆角矩形） ----
const R = 12
const marks = [
  [42, 4, 58, 28], // 上
  [42, 72, 58, 96], // 下
  [4, 42, 28, 58], // 左
  [72, 42, 96, 58], // 右
  [38, 38, 62, 62], // 中心
]

function inRoundRect(px, py, [x1, y1, x2, y2]) {
  const cx = (x1 + x2) / 2
  const cy = (y1 + y2) / 2
  const hx = Math.abs(x2 - x1) / 2
  const hy = Math.abs(y2 - y1) / 2
  const qx = Math.abs(px - cx) - (hx - R)
  const qy = Math.abs(py - cy) - (hy - R)
  if (qx <= 0 && qy <= 0) return true
  const ox = Math.max(qx, 0)
  const oy = Math.max(qy, 0)
  return Math.sqrt(ox * ox + oy * oy) <= R
}

const lerp = (a, b, t) => a + (b - a) * t

function renderRGBA(size) {
  const SS = 4 // 超采样抗锯齿
  const px = Buffer.alloc(size * size * 4)
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      let bg = 0
      let fg = 0
      for (let sy = 0; sy < SS; sy++) {
        for (let sx = 0; sx < SS; sx++) {
          const u = ((x + (sx + 0.5) / SS) / size) * 100
          const v = ((y + (sy + 0.5) / SS) / size) * 100
          if (inRoundRect(u, v, [6, 6, 94, 94])) bg += 1
          for (const m of marks) {
            if (inRoundRect(u, v, m)) {
              fg += 1
              break
            }
          }
        }
      }
      const total = SS * SS
      const alpha = Math.min(1, bg / total)
      const f = fg / total
      const t = Math.min(1, Math.max(0, (x / size) * 0.5 + (y / size) * 0.5))
      const r = lerp(lerp(0x4f, 0x7b, t), 255, f)
      const g = lerp(lerp(0x6e, 0x5c, t), 255, f)
      const b = lerp(lerp(0xf7, 0xf0, t), 255, f)
      const i = (y * size + x) * 4
      px[i] = Math.round(r)
      px[i + 1] = Math.round(g)
      px[i + 2] = Math.round(b)
      px[i + 3] = Math.round(alpha * 255)
    }
  }
  return px
}

// ---- PNG 编码 ----
const CRC_TABLE = (() => {
  const table = new Int32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    table[n] = c
  }
  return table
})()

function crc32(buf) {
  let c = 0xffffffff
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

function chunk(type, data) {
  const len = Buffer.alloc(4)
  len.writeUInt32BE(data.length, 0)
  const typeBuf = Buffer.from(type, 'ascii')
  const crcBuf = Buffer.alloc(4)
  crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0)
  return Buffer.concat([len, typeBuf, data, crcBuf])
}

function encodePNG(size) {
  const rgba = renderRGBA(size)
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(size, 0)
  ihdr.writeUInt32BE(size, 4)
  ihdr[8] = 8 // bit depth
  ihdr[9] = 6 // RGBA
  const stride = size * 4
  const raw = Buffer.alloc((stride + 1) * size)
  for (let y = 0; y < size; y++) {
    raw[y * (stride + 1)] = 0 // filter: none
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride)
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

// ---- 多尺寸 ICO 封装 ----
const images = SIZES.map((size) => ({ size, data: encodePNG(size) }))
const header = Buffer.alloc(6 + images.length * 16)
header.writeUInt16LE(0, 0)
header.writeUInt16LE(1, 2) // type: icon
header.writeUInt16LE(images.length, 4)

let offset = header.length
const body = []
images.forEach((img, i) => {
  const e = 6 + i * 16
  header[e] = img.size === 256 ? 0 : img.size
  header[e + 1] = img.size === 256 ? 0 : img.size
  header[e + 2] = 0 // palette
  header[e + 3] = 0 // reserved
  header.writeUInt16LE(1, e + 4) // color planes
  header.writeUInt16LE(32, e + 6) // bpp
  header.writeUInt32LE(img.data.length, e + 8)
  header.writeUInt32LE(offset, e + 12)
  offset += img.data.length
  body.push(img.data)
})

const ico = Buffer.concat([header, ...body])
fs.mkdirSync(path.dirname(OUT), { recursive: true })
fs.writeFileSync(OUT, ico)
console.log(`${OUT}: ${ico.length} bytes, sizes=${SIZES.join('/')}`)
