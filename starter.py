#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Starter — Inicia o servidor C2
Uso: python starter.py
"""

import os
import sys
import subprocess

def install_dependencies():
    """Instala Flask e Flask-SocketIO se necessário."""
    deps = ['flask', 'flask-socketio']
    missing = []
    
    for dep in deps:
        module = dep.replace('-', '_')
        try:
            __import__(module)
        except ImportError:
            missing.append(dep)
    
    if missing:
        print(f"[+] A instalar dependências: {', '.join(missing)}")
        try:
            subprocess.check_call([sys.executable, '-m', 'pip', 'install', *missing])
            print("[+] Dependências instaladas.")
        except subprocess.CalledProcessError:
            print("[-] Falha na instalação. Instala manualmente:")
            print("    pip install flask flask-socketio")
            sys.exit(1)

def main():
    install_dependencies()
    
    # Adiciona a pasta server ao path
    server_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'server')
    sys.path.insert(0, server_dir)
    
    print("[+] A iniciar o servidor C2...")
    
    try:
        from server import app, socketio
        print("[+] Servidor pronto em http://0.0.0.0:5000")
        print("[+] Painel: http://localhost:5000 (admin / admin123)")
        socketio.run(app, host='0.0.0.0', port=5000, debug=False)
    except ImportError as e:
        print(f"[-] Erro ao importar server.py: {e}")
        print("    Verifica se server/server.py existe.")
        sys.exit(1)

if __name__ == '__main__':
    main()
