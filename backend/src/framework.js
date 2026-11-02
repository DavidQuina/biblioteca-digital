// Mini framework HTTP sin dependencias (rutas estilo Express: app.get('/ruta/:id', ...)).
'use strict';
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

const LIMITE_JSON = 2 * 1024 * 1024;        // 2 MB
const LIMITE_ARCHIVO = 60 * 1024 * 1024;    // 60 MB por petición multipart

class HttpError extends Error {
  constructor(status, mensaje, detalle) {
    super(mensaje);
    this.status = status;
    this.detalle = detalle;
  }
}

function compilarRuta(patron) {
  const nombres = [];
  const regex = patron
    .split('/')
    .map((seg) => {
      if (seg.startsWith(':')) {
        nombres.push(seg.slice(1));
        return '([^/]+)';
      }
      return seg.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    })
    .join('/');
  return { regex: new RegExp('^' + regex + '/?$'), nombres };
}

function leerCuerpo(req, limite) {
  return new Promise((resolve, reject) => {
    const partes = [];
    let total = 0;
    req.on('data', (c) => {
      total += c.length;
      if (total > limite) {
        reject(new HttpError(413, 'El archivo o la petición es demasiado grande'));
        req.destroy();
        return;
      }
      partes.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(partes)));
    req.on('error', reject);
  });
}

// Parser multipart/form-data: devuelve { campos, archivos: { nombreCampo: {nombre, mime, datos} } }
function parsearMultipart(buffer, contentType) {
  const m = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType);
  if (!m) throw new HttpError(400, 'multipart sin boundary');
  const limite = Buffer.from('--' + (m[1] || m[2]));
  const campos = {};
  const archivos = {};
  let inicio = buffer.indexOf(limite);
  while (inicio !== -1) {
    inicio += limite.length;
    if (buffer.slice(inicio, inicio + 2).toString() === '--') break;
    inicio += 2; // \r\n
    const finCabecera = buffer.indexOf('\r\n\r\n', inicio);
    if (finCabecera === -1) break;
    const cabecera = buffer.slice(inicio, finCabecera).toString('utf8');
    const siguiente = buffer.indexOf(limite, finCabecera);
    if (siguiente === -1) break;
    const datos = buffer.slice(finCabecera + 4, siguiente - 2); // quita \r\n final
    const nombre = /name="([^"]*)"/i.exec(cabecera)?.[1];
    const archivo = /filename="([^"]*)"/i.exec(cabecera)?.[1];
    const mime = /content-type:\s*([^\r\n]+)/i.exec(cabecera)?.[1]?.trim();
    if (nombre) {
      if (archivo !== undefined && archivo !== '') {
        archivos[nombre] = { nombre: archivo, mime: mime || 'application/octet-stream', datos };
      } else if (archivo === undefined) {
        campos[nombre] = datos.toString('utf8');
      }
    }
    inicio = siguiente;
  }
  return { campos, archivos };
}

function crearApp() {
  const rutas = [];
  const estaticos = [];

  const app = {
    agregar(metodo, patron, ...manejadores) {
      rutas.push({ metodo, ...compilarRuta(patron), patron, manejadores });
    },
    get(p, ...h) { this.agregar('GET', p, ...h); },
    post(p, ...h) { this.agregar('POST', p, ...h); },
    put(p, ...h) { this.agregar('PUT', p, ...h); },
    patch(p, ...h) { this.agregar('PATCH', p, ...h); },
    delete(p, ...h) { this.agregar('DELETE', p, ...h); },
    estatico(prefijo, carpeta) { estaticos.push({ prefijo, carpeta: path.resolve(carpeta) }); },

    async manejar(req, res) {
      const url = new URL(req.url, 'http://localhost');
      res.setHeader('Access-Control-Allow-Origin', '*');
      res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
      res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, PATCH, DELETE, OPTIONS');
      res.setHeader('Access-Control-Expose-Headers', 'X-Version, Content-Disposition');
      if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }

      const r = extenderRespuesta(res);
      try {
        // Archivos estáticos (imágenes de juegos)
        for (const e of estaticos) {
          if (req.method === 'GET' && url.pathname.startsWith(e.prefijo)) {
            const nombre = path.basename(decodeURIComponent(url.pathname.slice(e.prefijo.length)));
            const ruta = path.join(e.carpeta, nombre);
            if (nombre && fs.existsSync(ruta)) return r.enviarArchivo(ruta, tipoMime(nombre));
            throw new HttpError(404, 'Imagen no encontrada');
          }
        }

        let rutaEncontrada = null;
        let metodoNoPermitido = false;
        for (const ruta of rutas) {
          const m = ruta.regex.exec(url.pathname);
          if (!m) continue;
          if (ruta.metodo !== req.method) { metodoNoPermitido = true; continue; }
          req.params = {};
          ruta.nombres.forEach((n, i) => { req.params[n] = decodeURIComponent(m[i + 1]); });
          rutaEncontrada = ruta;
          break;
        }
        if (!rutaEncontrada) {
          throw new HttpError(metodoNoPermitido ? 405 : 404,
            metodoNoPermitido ? 'Método no permitido' : 'Ruta no encontrada');
        }

        req.query = Object.fromEntries(url.searchParams.entries());
        req.body = {};
        req.archivos = {};
        if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(req.method)) {
          const tipo = req.headers['content-type'] || '';
          if (tipo.startsWith('multipart/form-data')) {
            const buf = await leerCuerpo(req, LIMITE_ARCHIVO);
            const { campos, archivos } = parsearMultipart(buf, tipo);
            req.body = campos;
            req.archivos = archivos;
          } else {
            const buf = await leerCuerpo(req, LIMITE_JSON);
            if (buf.length) {
              try { req.body = JSON.parse(buf.toString('utf8')); }
              catch { throw new HttpError(400, 'El cuerpo no es un JSON válido'); }
            }
          }
        }

        for (const h of rutaEncontrada.manejadores) {
          await h(req, r);
          if (res.writableEnded || res.headersSent) return;
        }
        if (!res.writableEnded) throw new HttpError(500, 'La ruta no respondió');
      } catch (err) {
        const status = err.status || 500;
        if (status === 500) console.error(err);
        if (!res.headersSent) {
          r.json(status, { error: err.status ? err.message : 'Error interno del servidor', ...(err.detalle ? { detalle: err.detalle } : {}) });
        } else {
          res.end();
        }
      }
    },

    escuchar(puerto, host, cb) {
      const servidor = http.createServer((req, res) => this.manejar(req, res));
      return servidor.listen(puerto, host, cb);
    },
  };
  return app;
}

function extenderRespuesta(res) {
  return {
    raw: res,
    json(status, datos) {
      const cuerpo = JSON.stringify(datos);
      res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(cuerpo) });
      res.end(cuerpo);
    },
    ok(datos) { this.json(200, datos); },
    creado(datos) { this.json(201, datos); },
    enviarArchivo(ruta, mime, extras = {}) {
      const stat = fs.statSync(ruta);
      res.writeHead(200, { 'Content-Type': mime, 'Content-Length': stat.size, ...extras });
      fs.createReadStream(ruta).pipe(res);
    },
  };
}

const MIMES = {
  '.pdf': 'application/pdf', '.mp4': 'video/mp4', '.3gp': 'video/3gpp', '.webm': 'video/webm', '.mkv': 'video/x-matroska',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.webp': 'image/webp',
  '.doc': 'application/msword', '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  '.txt': 'text/plain; charset=utf-8',
};
function tipoMime(nombre) {
  return MIMES[path.extname(nombre).toLowerCase()] || 'application/octet-stream';
}

module.exports = { crearApp, HttpError, tipoMime };
