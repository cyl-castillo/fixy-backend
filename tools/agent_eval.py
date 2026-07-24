#!/usr/bin/env python3
"""Banco de pruebas del agente de Fixy (2026-07-24).

Reproduce contra el API real (leads [smoke]) los casos que fallaron en prod
con el 8B, más los comportamientos que NO deben romperse. Sirve para comparar
modelos (CF_MODEL) de forma objetiva: correr → cambiar modelo → volver a correr.

Uso:
    python3 tools/agent_eval.py                     # contra prod
    BASE_URL=http://127.0.0.1:8080 python3 ...      # contra local

Cada escenario crea un chat nuevo, manda turnos del cliente, espera la
respuesta async del agente y evalúa: regex sobre la respuesta, zonas no
invitadas (alucinación), y campos del lead (categoría/zona). Sin deps
externas (urllib + json). Exit code = cantidad de escenarios fallados.
"""

import json
import os
import re
import sys
import time
import unicodedata
import urllib.request

BASE_URL = os.environ.get("BASE_URL", "https://api.fixy.com.uy")
# 90s de default: el 70B en Workers AI responde en ~40-60s (medido 2026-07-24);
# con 50s el banco cortaba antes de poder evaluar el contenido.
REPLY_TIMEOUT_S = int(os.environ.get("REPLY_TIMEOUT_S", "90"))
POLL_EVERY_S = 2.5

KNOWN_ZONES = [
    "solymar", "lagomar", "el pinar", "shangrila", "barra de carrasco",
    "parque miramar", "san jose de carrasco", "lomas de solymar",
    "colinas de solymar", "aeroparque", "ciudad de la costa",
]


def norm(text):
    """minúsculas y sin tildes, para comparar sin pelearse con el modelo."""
    text = unicodedata.normalize("NFD", text or "")
    return "".join(c for c in text if unicodedata.category(c) != "Mn").lower()


def http(method, path, body=None):
    req = urllib.request.Request(
        BASE_URL + path,
        data=json.dumps(body).encode() if body is not None else None,
        # UA propio: Cloudflare bloquea el default de urllib (Python-urllib/3.x)
        # con 403 — curl pasa, python pelado no (verificado 2026-07-24).
        headers={"Content-Type": "application/json", "User-Agent": "fixy-agent-eval/1.0"},
        method=method,
    )
    # El anti-abuso de prod permite 5 requests de escritura por IP cada 10
    # min (fixy.abuse.*): ante 429 esperamos y reintentamos — el banco
    # prioriza medir bien sobre terminar rápido.
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=20) as res:
                return json.loads(res.read().decode())
        except urllib.error.HTTPError as err:
            if err.code == 429 and attempt < 5:
                print("       (429 del anti-abuso: esperando 130s...)", flush=True)
                time.sleep(130)
                continue
            raise


def create_chat():
    data = http("POST", "/api/public/chats", {"channel": "web-chat"})
    return data["id"], data["accessToken"]


def send_and_wait_reply(lead_id, token, text, known_ids):
    """Manda un mensaje del cliente y espera la PRÓXIMA respuesta de fixy."""
    http("POST", f"/api/public/leads/{lead_id}/messages?token={token}", {"text": text})
    started = time.time()
    deadline = started + REPLY_TIMEOUT_S
    while time.time() < deadline:
        time.sleep(POLL_EVERY_S)
        msgs = http("GET", f"/api/public/leads/{lead_id}/messages?token={token}")
        fresh = [m for m in msgs if m["sender"] == "fixy" and m["id"] not in known_ids]
        for m in msgs:
            known_ids.add(m["id"])
        if fresh:
            print(f"       (respuesta en {time.time() - started:.0f}s)", flush=True)
            return fresh[-1]["text"]
    return None


def get_lead(lead_id, token):
    return http("GET", f"/api/public/leads/{lead_id}?token={token}")


# ---------------------------------------------------------------------------
# Escenarios. Cada uno: turnos [(mensaje, checks_de_respuesta)] + checks_de_lead.
# checks_de_respuesta: must (regexes sobre texto normalizado), must_not,
#   allowed_zones (las demás zonas mencionadas = alucinación).
# lead: category / location esperados ("" = debe quedar vacío/sin definir).
# ---------------------------------------------------------------------------
SCENARIOS = [
    {
        "name": "caso_138_aire_sin_zona",
        "why": "lead #138 real: repreguntó el tipo de servicio ya dicho y alucinó 'en Lomas'",
        "turns": [(
            "[smoke] Necesito aire acondicionado: necesito limpieza y mantenimiento del aire",
            {"must": [r"zona|barrio|donde|ubicac"],
             "must_not": [r"tipo de servicio", r"instalacion, service|instalacion, servicio"],
             "allowed_zones": []},
        )],
        "lead": {"category": "aires_acondicionados", "location": ""},
    },
    {
        "name": "pedido_completo_no_repregunta",
        "why": "con categoría+detalle+zona en el primer mensaje no hay nada que repreguntar",
        "turns": [(
            "[smoke] Necesito plomería: tengo una pérdida de agua en la cocina, en Solymar",
            {"must": [],
             "must_not": [r"en que zona|que zona|de que zona", r"que necesitas arreglar"],
             "allowed_zones": ["solymar", "ciudad de la costa"]},
        )],
        "lead": {"category": "plomeria", "location": "Solymar"},
    },
    {
        "name": "saludo_vago_sin_presumir",
        "why": "leads #116/#119 reales: a un 'hola' le presumió pastelería",
        "turns": [(
            "[smoke] hola",
            {"must": [r"que (te pasa|necesitas|precisas)|contame"],
             "must_not": [r"torta|pasteleria|plomeria|jardin"],
             "allowed_zones": []},
        )],
        "lead": {"category": "", "location": ""},
    },
    {
        "name": "precio_honesto",
        "why": "pregunta de precio: rango orientativo o honestidad, siempre 'el proveedor confirma'",
        "turns": [(
            "[smoke] hola, ¿cuánto sale cortar el pasto de un jardín en Lagomar?",
            {"must": [r"proveedor"],
             "must_not": [],
             "allowed_zones": ["lagomar", "ciudad de la costa"]},
        )],
        "lead": {"category": "jardineria", "location": "Lagomar"},
    },
    {
        "name": "decoracion_categoria_nueva",
        "why": "categoría 2026-07-23: globos/ambientación debe clasificar decoracion_fiestas",
        "turns": [(
            "[smoke] Quiero decoración con globos para un evento, en Lagomar",
            {"must": [],
             "must_not": [r"torta"],
             "allowed_zones": ["lagomar", "ciudad de la costa"]},
        )],
        "lead": {"category": "decoracion_fiestas", "location": "Lagomar"},
    },
    {
        "name": "mixto_cumple_decoracion",
        "why": "DIFERENCIADOR de modelo: 'decoración para el cumpleaños' confunde al heurístico "
               "(cumpleaños=keyword de pastelería); un modelo que entiende clasifica decoración",
        "turns": [(
            "[smoke] Necesito decoración y ambientación para el cumpleaños de mi hija",
            {"must": [], "must_not": [], "allowed_zones": []},
        )],
        "lead": {"category": "decoracion_fiestas", "location": ""},
    },
    {
        "name": "zona_en_seguimiento",
        "why": "la respuesta corta con la zona debe capturarse (extracción del turno)",
        "turns": [
            ("[smoke] Necesito aire acondicionado: mi aire no enfría",
             {"must": [], "must_not": [], "allowed_zones": []}),
            ("En El Pinar",
             {"must": [], "must_not": [], "allowed_zones": ["el pinar", "ciudad de la costa"]}),
        ],
        "lead": {"category": "aires_acondicionados", "location": "El Pinar"},
    },
    {
        "name": "no_se_repite",
        "why": "lead #123 real: el 8B repetía textual su mensaje anterior",
        "turns": [
            ("[smoke] necesito ayuda con el jardín de casa",
             {"must": [], "must_not": [], "allowed_zones": []}),
            ("es un jardín chico, de unos 30 metros, en Lagomar",
             {"must": [], "must_not": [], "allowed_zones": ["lagomar", "ciudad de la costa"]}),
        ],
        "lead": {"category": "jardineria", "location": "Lagomar"},
        "distinct_replies": True,
    },
]


def uninvited_zones(reply, allowed):
    found = []
    n = norm(reply)
    for zone in KNOWN_ZONES:
        if zone in n and zone not in allowed:
            # "lomas/colinas de solymar" contienen "solymar": no doble-contar el padre
            if zone == "solymar" and ("lomas de solymar" in n or "colinas de solymar" in n):
                continue
            found.append(zone)
    return found


def run_scenario(sc):
    failures = []
    lead_id, token = create_chat()
    known_ids = set()
    # el saludo estático no viene del backend, pero por las dudas registramos todo
    for m in http("GET", f"/api/public/leads/{lead_id}/messages?token={token}"):
        known_ids.add(m["id"])

    replies = []
    for text, checks in sc["turns"]:
        reply = send_and_wait_reply(lead_id, token, text, known_ids)
        if reply is None:
            failures.append(f"sin respuesta del agente en {REPLY_TIMEOUT_S}s (turno: {text[:40]}...)")
            break
        replies.append(reply)
        n = norm(reply)
        for rx in checks["must"]:
            if not re.search(rx, n):
                failures.append(f"falta en la respuesta /{rx}/ — dijo: \"{reply[:110]}\"")
        for rx in checks["must_not"]:
            if re.search(rx, n):
                failures.append(f"NO debía decir /{rx}/ — dijo: \"{reply[:110]}\"")
        bad_zones = uninvited_zones(reply, checks["allowed_zones"])
        if bad_zones:
            failures.append(f"zona(s) alucinada(s) {bad_zones} — dijo: \"{reply[:110]}\"")

    if sc.get("distinct_replies") and len(replies) >= 2 and norm(replies[-1]) == norm(replies[-2]):
        failures.append("se repitió textual entre turnos")

    # el lead termina de actualizarse poco después de la respuesta
    time.sleep(4)
    lead = get_lead(lead_id, token)
    expect = sc["lead"]
    got_cat = (lead.get("detectedCategory") or "").strip()
    got_loc = (lead.get("location") or "").strip()
    if norm(got_loc) == "sin definir":
        got_loc = ""
    if expect["category"] != got_cat:
        failures.append(f"categoría: esperaba '{expect['category']}', quedó '{got_cat}'")
    if norm(expect["location"]) != norm(got_loc):
        failures.append(f"zona: esperaba '{expect['location']}', quedó '{got_loc}'")

    return lead_id, failures


def main():
    # argv opcional: nombres de escenarios a correr (default: todos).
    wanted = set(sys.argv[1:])
    scenarios = [s for s in SCENARIOS if not wanted or s["name"] in wanted]
    print(f"Banco de pruebas del agente — {BASE_URL} ({len(scenarios)} escenarios)")
    print("=" * 74)
    failed = 0
    for sc in scenarios:
        lead_id, failures = run_scenario(sc)
        status = "PASS" if not failures else "FAIL"
        if failures:
            failed += 1
        print(f"[{status}] {sc['name']}  (lead #{lead_id})")
        print(f"       {sc['why']}")
        for f in failures:
            print(f"       ✗ {f}")
    print("=" * 74)
    print(f"{len(scenarios) - failed}/{len(scenarios)} escenarios OK")
    sys.exit(failed)


if __name__ == "__main__":
    main()
