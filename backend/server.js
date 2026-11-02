// Punto de entrada: node server.js
'use strict';
process.removeAllListeners('warning'); // oculta el aviso "SQLite is experimental" de Node
const path = require('node:path');
const os = require('node:os');
const fs = require('node:fs');
const crypto = require('node:crypto');
const { abrir } = require('./src/db');
const { construirApp } = require('./src/app');

const [mayor, menor] = process.versions.node.split('.').map(Number);
if (mayor < 22 || (mayor === 22 && menor < 13)) {
  console.error(`Necesitas Node.js 22.13 o superior (tienes ${process.versions.node}). Descárgalo en https://nodejs.org`);
  process.exit(1);
}

const PUERTO = Number(process.env.PUERTO || process.env.PORT || 3000);
const CARPETA_DATOS = path.resolve(process.env.CARPETA_DATOS || path.join(__dirname, 'data'));
fs.mkdirSync(CARPETA_DATOS, { recursive: true });

// Secreto para firmar tokens: se crea una vez y se guarda en data/secreto.txt
const rutaSecreto = path.join(CARPETA_DATOS, 'secreto.txt');
if (!process.env.JWT_SECRETO && !fs.existsSync(rutaSecreto)) fs.writeFileSync(rutaSecreto, crypto.randomBytes(32).toString('hex'));
const secreto = process.env.JWT_SECRETO || fs.readFileSync(rutaSecreto, 'utf8').trim();

const db = abrir(path.join(CARPETA_DATOS, 'biblioteca.db'));
const app = construirApp({ db, secreto, carpetaArchivos: path.join(CARPETA_DATOS, 'archivos') });

app.escuchar(PUERTO, '0.0.0.0', () => {
  console.log(`\n📚 Biblioteca Digital API escuchando en el puerto ${PUERTO}`);
  console.log(`   En este computador:     http://localhost:${PUERTO}/api/salud`);
  console.log(`   Emulador de Android:    http://10.0.2.2:${PUERTO}`);
  for (const lista of Object.values(os.networkInterfaces())) {
    for (const ip of lista || []) {
      if (ip.family === 'IPv4' && !ip.internal) console.log(`   Celular en la misma red: http://${ip.address}:${PUERTO}`);
    }
  }
  console.log('');
});
