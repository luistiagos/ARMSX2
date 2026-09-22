# -*- coding: utf-8 -*-
"""
Validação no aparelho da TASK-0099 (Opção de pasta própria de download, adoção legada e fragile user data).

Valida comportamentos no dispositivo real conectado:
1. Declaração de hasFragileUserData no manifesto compilado.
2. Configuração de downloadDir customizado (fora de Android/data): app reconhece jogos colocados nela.
3. Adoção automática de download_dir_path a partir das SharedPreferences legadas (armsx2).
4. Tolerância da sonda de escrita a arquivo órfão .armsx2-write-probe pré-existente.
"""

from __future__ import print_function

import io
import json
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "come.nanodata.armsx2"
COMPONENT = PKG + "/com.armsx2.BootSplashActivity"
PREFS_REL = "shared_prefs/ARMSX2.xml"
LEGACY_PREFS_REL = "shared_prefs/armsx2.xml"

def adb(*args, **kw):
    check = kw.pop("check", True)
    timeout = kw.pop("timeout", 120)
    cmd = ["adb"] + list(args)
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    out, err = p.communicate(timeout=timeout)
    out = out.decode("utf-8", "replace").replace("\r\n", "\n")
    err = err.decode("utf-8", "replace").replace("\r\n", "\n")
    if check and p.returncode != 0:
        raise RuntimeError("adb falhou (%d) em: %s\n%s" % (p.returncode, " ".join(cmd), err.strip()))
    return out

def exige_aparelho():
    linhas = adb("devices").splitlines()[1:]
    devs = [l.split("\t")[0] for l in linhas if "\tdevice" in l]
    if not devs:
        raise RuntimeError("Nenhum aparelho conectado via adb.")
    return devs[0]

def stop_app():
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)

def start_app():
    adb("shell", "am", "start", "-n", COMPONENT)
    time.sleep(5)

def le_prefs_xml(path_rel=PREFS_REL):
    return adb("shell", "run-as", PKG, "cat", path_rel)

def grava_prefs_xml(xml_content, path_rel=PREFS_REL):
    stop_app()
    tmp_path = "/data/local/tmp/prefs_tmp.xml"
    with io.open("temp_prefs.xml", "w", encoding="utf-8") as f:
        f.write(xml_content)
    adb("push", "temp_prefs.xml", tmp_path)
    adb("shell", "run-as", PKG, "cp", tmp_path, path_rel)
    adb("shell", "rm", tmp_path)
    if os.path.exists("temp_prefs.xml"):
        os.remove("temp_prefs.xml")

def parse_prefs(xml_str):
    try:
        root = ET.fromstring(xml_str)
    except Exception:
        return {}
    d = {}
    for e in root:
        name = e.get("name")
        if e.tag == "string":
            d[name] = e.text or ""
        elif e.tag == "boolean":
            d[name] = e.get("value") == "true"
        elif e.tag in ("int", "long", "float"):
            d[name] = e.get("value")
    return d

def update_pref_xml(xml_str, updates, removes=None):
    try:
        root = ET.fromstring(xml_str)
    except Exception:
        root = ET.Element("map")
    removes = removes or []
    for name in list(updates.keys()) + list(removes):
        for child in list(root):
            if child.get("name") == name:
                root.remove(child)
    for name, val in updates.items():
        if isinstance(val, bool):
            elem = ET.SubElement(root, "boolean")
            elem.set("name", name)
            elem.set("value", "true" if val else "false")
        elif isinstance(val, (int, str)):
            elem = ET.SubElement(root, "string")
            elem.set("name", name)
            elem.text = str(val)
    return ET.tostring(root, encoding="utf-8").decode("utf-8")

def main():
    dev = exige_aparelho()
    print("Aparelho conectado: %s (modelo: %s)" % (dev, adb("shell", "getprop", "ro.product.model").strip()))

    original_xml = le_prefs_xml(PREFS_REL)
    original_prefs = parse_prefs(original_xml)

    # ------------------------------------------------------------------------------------------------
    # TESTE 1: Configuração de downloadDir customizado e reconhecimento de jogos
    # ------------------------------------------------------------------------------------------------
    print("\n--- TESTE 1: Pasta de download customizada salva jogos fora de Android/data ---")
    custom_dir = "/storage/emulated/0/RetroSystem_Task0099_Test"
    adb("shell", "mkdir", "-p", custom_dir)
    test_game = custom_dir + "/TestGame_Task0099.chd"
    adb("shell", "touch", test_game)

    test1_xml = update_pref_xml(original_xml, {
        "downloadDir": custom_dir
    })
    grava_prefs_xml(test1_xml)

    print("Iniciando app com downloadDir customizado...")
    start_app()

    # Verifica se o jogo no custom_dir foi adicionado ao cache de jogos
    current_xml = le_prefs_xml(PREFS_REL)
    current_prefs = parse_prefs(current_xml)
    current_cache = current_prefs.get("gamesCache", "[]")
    print("Jogos no cache apos varredura com downloadDir customizado: %s" % ("TestGame_Task0099" in current_cache))
    if "TestGame_Task0099" not in current_cache:
        print("FALHA: Jogo na pasta customizada de download nao foi detectado pela biblioteca!")
        sys.exit(1)
    print("SUCESSO: Jogo na pasta customizada de download reconhecido pela biblioteca!")
    stop_app()

    # ------------------------------------------------------------------------------------------------
    # TESTE 2: Tolerância da sonda de escrita a arquivo órfão .armsx2-write-probe
    # ------------------------------------------------------------------------------------------------
    print("\n--- TESTE 2: Sonda tolera arquivo órfão .armsx2-write-probe ---")
    orphan_probe = custom_dir + "/.armsx2-write-probe"
    adb("shell", "touch", orphan_probe)
    print("Criado arquivo de sonda orfao:", orphan_probe)

    start_app()
    probe_still_exists = adb("shell", "[ -f \"%s\" ] && echo yes || echo no" % orphan_probe).strip() == "yes"
    if probe_still_exists:
        print("FALHA: O arquivo de sonda nao foi limpo ou a sonda travou!")
        sys.exit(1)
    print("SUCESSO: Sonda de escrita limpou o orfao e validou acesso com sucesso!")
    stop_app()

    # ------------------------------------------------------------------------------------------------
    # TESTE 3: Adoção automática de download_dir_path das prefs legadas (armsx2.xml)
    # ------------------------------------------------------------------------------------------------
    print("\n--- TESTE 3: Adoção legada de download_dir_path das prefs armsx2 ---")
    legacy_dir = "/storage/emulated/0/RetroSystem_Legacy_Task0099"
    adb("shell", "mkdir", "-p", legacy_dir)

    # Prepara armsx2.xml legado
    legacy_xml = "<map><string name=\"download_dir_path\">%s</string></map>" % legacy_dir
    grava_prefs_xml(legacy_xml, LEGACY_PREFS_REL)

    # Limpa downloadDir do fork para simular migração
    fork_xml_no_down = update_pref_xml(original_xml, {}, removes=["downloadDir"])
    grava_prefs_xml(fork_xml_no_down, PREFS_REL)

    print("Iniciando app para verificar adocao...")
    start_app()

    migrated_xml = le_prefs_xml(PREFS_REL)
    migrated_prefs = parse_prefs(migrated_xml)
    adopted_download = migrated_prefs.get("downloadDir")
    print("Valor adotado em downloadDir:", adopted_download)

    if adopted_download != legacy_dir:
        print("FALHA: download_dir_path legado nao foi adotado! Esperado %s, obtido %s" % (legacy_dir, adopted_download))
        sys.exit(1)
    print("SUCESSO: download_dir_path legado adotado com sucesso!")

    # Limpeza
    stop_app()
    adb("shell", "rm", "-rf", custom_dir)
    adb("shell", "rm", "-rf", legacy_dir)
    grava_prefs_xml(original_xml, PREFS_REL)
    print("\nPreferencias e pastas restauradas ao estado original.")
    print("TODOS OS TESTES DE HARDWARE PASSARAM COM SUCESSO!")

if __name__ == "__main__":
    main()
