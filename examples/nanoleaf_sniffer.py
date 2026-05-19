#!/usr/bin/env python3
"""
Laboratorio Numark Mixstream Pro Go → Nanoleaf virtual (FASE 0)
Corregido: Soporte para endpoint /api/v1/new (Protocolo Numark/Engine OS)
"""

from __future__ import annotations

import json
import logging
import socket
import sys
import threading
import time
from collections import Counter
from typing import Any

from flask import Flask, jsonify, request
from zeroconf import InterfaceChoice, IPVersion, ServiceInfo, Zeroconf

# --- Config ---
HTTP_HOST = "0.0.0.0"
HTTP_PORT = 8081
UDP_PORTS = (60222, 7022)
LOG_FILE = "nanoleaf_sniffer.log"
MDNS_SERVICE_TYPE = "_nanoleafapi._tcp.local."
MDNS_SERVICE_NAME = "NanoleafLab_FINAL._nanoleafapi._tcp.local."
MDNS_SERVER_HOSTNAME = "nanoleaflab_final.local."
TEST_AUTH_TOKEN = "lab-token-nanoleaf-001"
SIDE_LENGTH = 150

def _detect_lan_ip() -> str:
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.settimeout(0.5)
        probe.connect(("8.8.8.8", 80))
        ip = probe.getsockname()[0]
        probe.close()
        return ip
    except OSError:
        return "127.0.0.1"

def _build_panel_layout_16() -> dict[str, Any]:
    position_data = []
    cols = 4
    step = 86
    for i in range(16):
        row, col = divmod(i, cols)
        x = col * step
        y = row * step
        position_data.append({"panelId": i + 1, "x": x, "y": y, "o": 0})
    return {"numPanels": 16, "sideLength": SIDE_LENGTH, "positionData": position_data}

# --- Logging ---
class _NanoTimestampFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.nano_ts = time.time_ns()
        return True

def setup_logging() -> logging.Logger:
    log = logging.getLogger("nanoleaf_sniffer")
    log.setLevel(logging.DEBUG)
    log.handlers.clear()
    log.propagate = False
    fmt = logging.Formatter(fmt="%(created).6f nano=%(nano_ts)d %(levelname)s %(message)s")
    nano_f = _NanoTimestampFilter()
    fh = logging.FileHandler(LOG_FILE, mode="a", encoding="utf-8")
    fh.setLevel(logging.DEBUG)
    fh.addFilter(nano_f)
    fh.setFormatter(fmt)
    sh = logging.StreamHandler(sys.stdout)
    sh.setLevel(logging.DEBUG)
    sh.addFilter(nano_f)
    sh.setFormatter(fmt)
    log.addHandler(fh)
    log.addHandler(sh)
    return log

LOG = setup_logging()

def hexdump(data: bytes, width: int = 16) -> str:
    lines: list[str] = []
    for offset in range(0, len(data), width):
        chunk = data[offset : offset + width]
        hex_part = " ".join(f"{b:02x}" for b in chunk)
        pad = width * 3 - 1
        ascii_part = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
        lines.append(f"{offset:08x}  {hex_part:<{pad}}  |{ascii_part}|")
    return "\n".join(lines)

def rgb_packet_hypothesis(data: bytes) -> str:
    n = len(data)
    if n == 0: return "Hipótesis: paquete vacío."
    bits = []
    if n == 48:
        bits.append("Hipótesis: 48 bytes = 16 paneles × 3 (RGB). ¡Match directo!")
    elif n > 48:
        bits.append(f"Hipótesis: {n} bytes. Posible Header de {n-48} bytes + 48 bytes RGB.")
    if n % 3 == 0:
        bits.append(f"Hipótesis: Múltiplo de 3 ({n//3} colores).")
    return " ".join(bits)

# --- Flask ---
app = Flask(__name__)

@app.before_request
def _log_request() -> None:
    body = request.get_data(as_text=True) if request.method == "POST" else ""
    LOG.debug("HTTP ← %s %s from=%s body=%r", request.method, request.path, request.remote_addr, body)

@app.after_request
def _log_response(response):
    LOG.debug("HTTP → %s %s status=%s", request.method, request.path, response.status_code)
    return response

# RUTA CORREGIDA: La Numark usa /api/v1/new
@app.route("/api/v1/new", methods=["POST"])
@app.route("/api/v1/auth", methods=["POST"])
def auth():
    print("\n[!!!] PAREO DETECTADO: Enviando Auth Token a la Numark...", flush=True)
    return jsonify({"auth_token": TEST_AUTH_TOKEN}), 200

@app.get("/api/v1/<token>")
def device_state(token: str):
    return jsonify({
        "name": "NanoleafLab",
        "model": "NL42",
        "serialNo": "LAB-SERIAL-0001",
        "manufacturer": "Nanoleaf",
        "on": {"value": True},
        "brightness": {"value": 50},
    })

@app.get("/api/v1/<token>/panelLayout/layout")
def panel_layout(token: str):
    print("[!!!] LAYOUT SOLICITADO: Enviando mapa de 16 paneles...", flush=True)
    return jsonify(_build_panel_layout_16())

def _run_flask(stop: threading.Event) -> None:
    from werkzeug.serving import make_server
    logging.getLogger("werkzeug").setLevel(logging.ERROR)
    server = make_server(HTTP_HOST, HTTP_PORT, app, threaded=True)
    serve_t = threading.Thread(target=server.serve_forever, daemon=True)
    serve_t.start()
    stop.wait()
    server.shutdown()

# --- UDP ---
def udp_listener(port: int, stop: threading.Event) -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((HTTP_HOST, port))
    sock.settimeout(1.0)
    LOG.info("UDP escuchando en puerto %s", port)
    while not stop.is_set():
        try:
            data, addr = sock.recvfrom(65535)
            print(f"\n[UDP:{port}] ráfaga de {len(data)} bytes de {addr[0]}")
            print(hexdump(data))
            print(rgb_packet_hypothesis(data), flush=True)
        except socket.timeout:
            continue
    sock.close()

def register_mdns(local_ip: str) -> tuple[Zeroconf, ServiceInfo]:
    zc = Zeroconf(interfaces=InterfaceChoice.Default, ip_version=IPVersion.V4Only)
    info = ServiceInfo(
        type_=MDNS_SERVICE_TYPE,
        name=MDNS_SERVICE_NAME,
        addresses=[socket.inet_aton(local_ip)],
        port=HTTP_PORT,
        properties={b"id": b"deadbeef0001", b"nm": b"NanoleafLab", b"md": b"NL42"},
        server=MDNS_SERVER_HOSTNAME,
    )
    zc.register_service(info, allow_name_change=True)
    return zc, info

def main() -> None:
    local_ip = _detect_lan_ip()
    stop = threading.Event()
    try:
        zc, info = register_mdns(local_ip)
        threading.Thread(target=_run_flask, args=(stop,), daemon=True).start()
        for p in UDP_PORTS:
            threading.Thread(target=udp_listener, args=(p, stop), daemon=True).start()
        
        print(f"\n--- Laboratorio Fase 0 Activo ---")
        print(f"IP: {local_ip} | Puerto: {HTTP_PORT}")
        print(f"Esperando conexión de la Numark...\n")
        
        while True: time.sleep(1)
    except KeyboardInterrupt:
        stop.set()
        print("\nCerrando laboratorio...")
    finally:
        if 'zc' in locals(): zc.close()

if __name__ == "__main__":
    main()