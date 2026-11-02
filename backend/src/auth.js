// Contraseñas con scrypt y tokens JWT (HS256) firmados con node:crypto.
'use strict';
const crypto = require('node:crypto');
const { HttpError } = require('./framework');

const DURACION_TOKEN_S = 60 * 60 * 24 * 30; // 30 días: el estudiante puede pasar semanas sin internet

function hashClave(clave) {
  const sal = crypto.randomBytes(16).toString('hex');
  const hash = crypto.scryptSync(clave, sal, 64).toString('hex');
  return `${sal}:${hash}`;
}

function verificarClave(clave, guardado) {
  const [sal, hash] = guardado.split(':');
  const calculado = crypto.scryptSync(clave, sal, 64);
  return crypto.timingSafeEqual(calculado, Buffer.from(hash, 'hex'));
}

const b64 = (obj) => Buffer.from(JSON.stringify(obj)).toString('base64url');

function firmarToken(payload, secreto) {
  const ahora = Math.floor(Date.now() / 1000);
  const cuerpo = `${b64({ alg: 'HS256', typ: 'JWT' })}.${b64({ ...payload, iat: ahora, exp: ahora + DURACION_TOKEN_S })}`;
  const firma = crypto.createHmac('sha256', secreto).update(cuerpo).digest('base64url');
  return `${cuerpo}.${firma}`;
}

function verificarToken(token, secreto) {
  const partes = token.split('.');
  if (partes.length !== 3) return null;
  const esperada = crypto.createHmac('sha256', secreto).update(`${partes[0]}.${partes[1]}`).digest();
  const recibida = Buffer.from(partes[2], 'base64url');
  if (esperada.length !== recibida.length || !crypto.timingSafeEqual(esperada, recibida)) return null;
  try {
    const payload = JSON.parse(Buffer.from(partes[1], 'base64url').toString());
    if (payload.exp && payload.exp < Math.floor(Date.now() / 1000)) return null;
    return payload;
  } catch {
    return null;
  }
}

// Middlewares
function crearMiddlewares(db, secreto) {
  const autenticado = (req) => {
    const h = req.headers.authorization || '';
    const token = h.startsWith('Bearer ') ? h.slice(7) : null;
    if (!token) throw new HttpError(401, 'Falta el token. Inicia sesión.');
    const payload = verificarToken(token, secreto);
    if (!payload) throw new HttpError(401, 'Token inválido o vencido. Inicia sesión de nuevo.');
    const u = db.prepare('SELECT id, nombre, correo, rol, grado, grupo FROM usuarios WHERE id = ?').get(payload.sub);
    if (!u) throw new HttpError(401, 'El usuario ya no existe');
    req.usuario = { ...u };
  };
  const soloProfesor = (req) => {
    autenticado(req);
    if (req.usuario.rol !== 'profesor') throw new HttpError(403, 'Solo el profesor puede hacer esto');
  };
  const soloEstudiante = (req) => {
    autenticado(req);
    if (req.usuario.rol !== 'estudiante') throw new HttpError(403, 'Solo el estudiante puede hacer esto');
  };
  return { autenticado, soloProfesor, soloEstudiante };
}

module.exports = { hashClave, verificarClave, firmarToken, verificarToken, crearMiddlewares };
