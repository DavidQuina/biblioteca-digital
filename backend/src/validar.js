'use strict';
const { HttpError } = require('./framework');

const TIPOS_MATERIAL = ['pdf', 'video', 'imagen', 'examen', 'formulario', 'taller'];
const TIPOS_JUEGO = ['memoria', 'unir', 'reloj'];
const MAX_DESCARGAS_ACTIVAS = 10;

function falla(mensaje, detalle) { throw new HttpError(400, mensaje, detalle); }

function texto(valor, campo, { max = 200, opcional = false } = {}) {
  if (valor === undefined || valor === null || String(valor).trim() === '') {
    if (opcional) return '';
    falla(`Falta el campo "${campo}"`);
  }
  const t = String(valor).trim();
  if (t.length > max) falla(`"${campo}" es muy largo (máximo ${max} caracteres)`);
  return t;
}

function entero(valor, campo, { min = 1, max = Number.MAX_SAFE_INTEGER, opcional = false } = {}) {
  if ((valor === undefined || valor === null || valor === '') && opcional) return null;
  const n = Number(valor);
  if (!Number.isInteger(n) || n < min || n > max) falla(`"${campo}" debe ser un número entre ${min} y ${max}`);
  return n;
}

function grado(valor) { return entero(valor, 'grado', { min: 1, max: 11 }); }

function grupo(valor) {
  const g = texto(valor, 'grupo', { max: 2 }).toUpperCase();
  if (!/^[A-Z]$/.test(g)) falla('"grupo" debe ser una letra (A, B, C...)');
  return g;
}

function correo(valor) {
  const c = texto(valor, 'correo', { max: 120 }).toLowerCase();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(c)) falla('El correo no es válido');
  return c;
}

function fecha(valor) {
  const f = texto(valor, 'fecha', { max: 10 });
  if (!/^\d{4}-\d{2}-\d{2}$/.test(f) || Number.isNaN(Date.parse(f))) falla('"fecha" debe tener el formato AAAA-MM-DD');
  return f;
}

function booleano(valor, campo) {
  if (valor === true || valor === 'true' || valor === 1 || valor === '1') return true;
  if (valor === false || valor === 'false' || valor === 0 || valor === '0') return false;
  falla(`"${campo}" debe ser true o false`);
}

module.exports = {
  TIPOS_MATERIAL, TIPOS_JUEGO, MAX_DESCARGAS_ACTIVAS,
  falla, texto, entero, grado, grupo, correo, fecha, booleano,
};
