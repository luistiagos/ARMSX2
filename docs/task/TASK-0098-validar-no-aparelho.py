# -*- coding: utf-8 -*-
"""
Validação no aparelho da TASK-0098 (Bug da varredura com pasta ilegível e revarredura automática).

Valida dois comportamentos fundamentais no dispositivo real conectado:
1. Preservação do cache: Quando uma pasta configurada não pode ser lida (ex: pasta não existe ou SD desmontado),
   a varredura NÃO sobrescreve o cache existente com lista vazia `[]`.
2. Revarredura automática de cache vazio: Quando o cache está vazio `[]`, mas há pastas configuradas e válidas,
   abrir o app aciona a revarredura automática (pendingInitialScan) e repovoa os jogos no cache sem necessidade de ↻ manual.
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
        raise RuntimeError("Nenhum aparelho conectado.")
    return devs[0]

def stop_app():
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)

def start_app():
    adb("shell", "am", "start", "-n", COMPONENT)
    time.sleep(5)

def le_prefs_xml():
    return adb("shell", "run-as", PKG, "cat", PREFS_REL)

def grava_prefs_xml(xml_content):
    # Grava via arquivo temporario no sdcard e copia via run-as
    stop_app()
    tmp_path = "/data/local/tmp/prefs_tmp.xml"
    # Escreve o arquivo no host e faz push
    with io.open("temp_prefs.xml", "w", encoding="utf-8") as f:
        f.write(xml_content)
    adb("push", "temp_prefs.xml", tmp_path)
    adb("shell", "run-as", PKG, "cp", tmp_path, PREFS_REL)
    adb("shell", "rm", tmp_path)
    if os.path.exists("temp_prefs.xml"):
        os.remove("temp_prefs.xml")

def parse_prefs(xml_str):
    root = ET.fromstring(xml_str)
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

def update_pref_xml(xml_str, updates):
    root = ET.fromstring(xml_str)
    for name, val in updates.items():
        # Remove existing if present
        for child in list(root):
            if child.get("name") == name:
                root.remove(child)
        # Add new
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

    original_xml = le_prefs_xml()
    prefs = parse_prefs(original_xml)
    games_cache_str = prefs.get("gamesCache", "[]")
    games_cache = json.loads(games_cache_str)
    print("Estado inicial do cache: %d jogos encontrados" % len(games_cache))
    if len(games_cache) == 0:
        print("ALERTA: Cache inicial vazio. Precisamos de pelo menos 1 jogo no cache para o teste de preservacao.")

    # ------------------------------------------------------------------------------------------------
    # TESTE 1: Preservacao do cache bom quando ha pasta inacessivel/inexistente
    # ------------------------------------------------------------------------------------------------
    print("\n--- TESTE 1: Pasta inacessivel nao pode apagar o cache bom ---")
    bad_folder = "/storage/emulated/0/PastaInexistente_12345"
    test1_xml = update_pref_xml(original_xml, {
        "romsDirs": json.dumps([bad_folder])
    })
    grava_prefs_xml(test1_xml)

    print("Iniciando app com pasta inacessivel configurada...")
    start_app()

    current_xml = le_prefs_xml()
    current_prefs = parse_prefs(current_xml)
    current_cache = json.loads(current_prefs.get("gamesCache", "[]"))
    print("Apos varredura com pasta inacessivel, jogos no cache: %d" % len(current_cache))

    if len(current_cache) != len(games_cache):
        print("FALHA: O cache bom anterior foi modificado ou destruido! Esperado %d, obtido %d" % (len(games_cache), len(current_cache)))
        sys.exit(1)
    print("SUCESSO: Cache anterior de %d jogos foi preservado intacto!" % len(current_cache))
    stop_app()

    # ------------------------------------------------------------------------------------------------
    # TESTE 2: Cache vazio revarre automaticamente se as pastas forem validas
    # ------------------------------------------------------------------------------------------------
    print("\n--- TESTE 2: Cache vazio revarre automaticamente no boot do app ---")
    valid_folder = "/storage/emulated/0/Android/data/come.nanodata.armsx2/files/roms"
    # Zera propositalmente o cache nas preferencias mantendo a chave
    test2_xml = update_pref_xml(original_xml, {
        "romsDirs": json.dumps([valid_folder]),
        "gamesCache": "[]",
        "gamesCacheKey": valid_folder
    })
    grava_prefs_xml(test2_xml)

    # Verifica que foi zerado
    check_xml = le_prefs_xml()
    check_cache = json.loads(parse_prefs(check_xml).get("gamesCache", "[]"))
    assert len(check_cache) == 0, "Deveria ter iniciado com 0 jogos para o teste 2"
    print("Cache foi zerado nas preferencias para simular abertura com cache vazio...")

    print("Iniciando app...")
    start_app()

    # Aguarda brevemente para a varredura assincrona em background rodar
    time.sleep(3)

    final_xml = le_prefs_xml()
    final_prefs = parse_prefs(final_xml)
    final_cache = json.loads(final_prefs.get("gamesCache", "[]"))
    print("Apos boot do app, jogos encontrados automaticamente: %d" % len(final_cache))

    if len(final_cache) == 0:
        print("FALHA: O app continuou com cache vazio e nao revarreu automaticamente!")
        sys.exit(1)
    print("SUCESSO: O app revarreu automaticamente e encontrou %d jogos!" % len(final_cache))

    # Restaura prefs originais
    grava_prefs_xml(original_xml)
    print("\nPreferencias originais restauradas.")
    print("TODOS OS TESTES NO APARELHO PASSARAM COM SUCESSO!")

if __name__ == "__main__":
    main()
