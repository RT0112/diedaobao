// 跌倒宝后端服务器 - SQLite 数据库初始化 (Node.js内置sqlite版)
// Node.js 22+ 内置 node:sqlite 模块，无需额外依赖

const { DatabaseSync } = require('node:sqlite')
const path = require('path')

const DB_PATH = path.join(__dirname, 'diedaobao.db')

let db

function getDb() {
  if (!db) {
    db = new DatabaseSync(DB_PATH)
    db.exec('PRAGMA journal_mode = WAL')
    db.exec('PRAGMA foreign_keys = ON')
    db.exec('PRAGMA busy_timeout = 5000')
    initTables()
  }
  return db
}

function initTables() {
  db.exec(`
    CREATE TABLE IF NOT EXISTS users (
      id TEXT PRIMARY KEY,
      deviceId TEXT NOT NULL,
      name TEXT NOT NULL DEFAULT '老人',
      phone TEXT NOT NULL DEFAULT '',
      role TEXT NOT NULL DEFAULT 'elder',
      status TEXT NOT NULL DEFAULT 'active',
      lastLocationLat REAL,
      lastLocationLng REAL,
      lastLocationAccuracy REAL DEFAULT 0,
      lastLocationTime INTEGER,
      pullLocationRequest INTEGER,
      pullLocationStatus TEXT DEFAULT 'idle',
      lastFallEvent TEXT,
      remoteAssist TEXT,
      createdAt INTEGER NOT NULL DEFAULT (strftime('%s','now')*1000),
      updatedAt INTEGER NOT NULL DEFAULT (strftime('%s','now')*1000)
    );
    CREATE TABLE IF NOT EXISTS fall_events (
      id TEXT PRIMARY KEY,
      userId TEXT NOT NULL,
      timestamp INTEGER NOT NULL,
      latitude REAL,
      longitude REAL,
      impactG REAL DEFAULT 0,
      ffDuration INTEGER DEFAULT 0,
      mlScore REAL DEFAULT 0,
      physicalScore REAL DEFAULT 0,
      status TEXT NOT NULL DEFAULT 'pending',
      confirmedBy TEXT,
      confirmedAt INTEGER,
      createdAt INTEGER NOT NULL DEFAULT (strftime('%s','now')*1000)
    );
    CREATE TABLE IF NOT EXISTS locations (
      id TEXT PRIMARY KEY,
      userId TEXT NOT NULL,
      latitude REAL NOT NULL,
      longitude REAL NOT NULL,
      accuracy REAL DEFAULT 0,
      timestamp INTEGER NOT NULL,
      createdAt INTEGER NOT NULL DEFAULT (strftime('%s','now')*1000)
    );
    CREATE TABLE IF NOT EXISTS bind_codes (
      id TEXT PRIMARY KEY,
      code TEXT NOT NULL,
      elderId TEXT NOT NULL,
      used INTEGER NOT NULL DEFAULT 0,
      expiresAt INTEGER NOT NULL,
      createdAt INTEGER NOT NULL DEFAULT (strftime('%s','now')*1000)
    );
    CREATE TABLE IF NOT EXISTS family_bindings (
      id TEXT PRIMARY KEY,
      elderId TEXT NOT NULL,
      familyId TEXT NOT NULL,
      relation TEXT NOT NULL DEFAULT '家属',
      status TEXT NOT NULL DEFAULT 'active',
      createdAt INTEGER NOT NULL DEFAULT (strftime('%s','now')*1000),
      updatedAt INTEGER NOT NULL DEFAULT (strftime('%s','now')*1000)
    );
    CREATE TABLE IF NOT EXISTS geofences (
      id TEXT PRIMARY KEY,
      elderId TEXT NOT NULL,
      creatorId TEXT NOT NULL,
      name TEXT NOT NULL,
      latitude REAL NOT NULL,
      longitude REAL NOT NULL,
      radius INTEGER NOT NULL,
      isActive INTEGER NOT NULL DEFAULT 1,
      isBreached INTEGER NOT NULL DEFAULT 0,
      createdAt INTEGER NOT NULL DEFAULT (strftime('%s','now')*1000),
      updatedAt INTEGER NOT NULL DEFAULT (strftime('%s','now')*1000)
    );
    CREATE TABLE IF NOT EXISTS screen_frames (
      id TEXT PRIMARY KEY,
      elderId TEXT NOT NULL,
      frameData TEXT,
      frameWidth INTEGER DEFAULT 0,
      frameHeight INTEGER DEFAULT 0,
      frameNum INTEGER DEFAULT 0,
      frameTs INTEGER DEFAULT 0,
      updatedAt INTEGER NOT NULL DEFAULT (strftime('%s','now')*1000)
    );
    CREATE TABLE IF NOT EXISTS logs (
      id TEXT PRIMARY KEY,
      userId TEXT NOT NULL,
      level TEXT NOT NULL DEFAULT 'ERROR',
      tag TEXT NOT NULL DEFAULT 'App',
      message TEXT NOT NULL,
      stackTrace TEXT,
      metadata TEXT,
      timestamp INTEGER NOT NULL,
      date TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_users_deviceId ON users(deviceId);
    CREATE INDEX IF NOT EXISTS idx_fall_events_userId ON fall_events(userId);
    CREATE INDEX IF NOT EXISTS idx_fall_events_timestamp ON fall_events(timestamp);
    CREATE INDEX IF NOT EXISTS idx_locations_userId ON locations(userId);
    CREATE INDEX IF NOT EXISTS idx_locations_timestamp ON locations(timestamp);
    CREATE INDEX IF NOT EXISTS idx_bind_codes_code ON bind_codes(code);
    CREATE INDEX IF NOT EXISTS idx_family_bindings_elderId ON family_bindings(elderId);
    CREATE INDEX IF NOT EXISTS idx_family_bindings_familyId ON family_bindings(familyId);
    CREATE INDEX IF NOT EXISTS idx_geofences_elderId ON geofences(elderId);
    CREATE INDEX IF NOT EXISTS idx_screen_frames_elderId ON screen_frames(elderId);
    CREATE INDEX IF NOT EXISTS idx_logs_userId ON logs(userId);
    CREATE INDEX IF NOT EXISTS idx_logs_timestamp ON logs(timestamp);
  `)
}

// 生成简短 ID
function genId() {
  return Date.now().toString(36) + Math.random().toString(36).substr(2, 9)
}

module.exports = { getDb, genId }
