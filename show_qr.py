#!/usr/bin/env python3
"""Zeigt einen klaren QR-Code für Handy-Download (WLAN-Webseite + GitHub)."""
import os
import socket
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
import tkinter as tk
from PIL import Image, ImageTk

if "DISPLAY" not in os.environ:
    os.environ["DISPLAY"] = ":0"

APK_NAME = "MeditationAnalyzer_v1.4.apk"
GITHUB_URL = f"https://raw.githubusercontent.com/Herakles5/MeditationAnalyzer/main/{APK_NAME}"
HERE = os.path.dirname(os.path.abspath(__file__))
APK_PATH = os.path.join(HERE, APK_NAME)
GITHUB_QR_PATH = os.path.join(HERE, "MeditationAnalyzer_QR.png")
HTTP_LOG_PATH = "/tmp/med_qr_http.log"


def detect_lan_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


LAN_IP = detect_lan_ip()
PORT = 8888
LAN_PAGE_URL = f"http://{LAN_IP}:{PORT}/"
LAN_APK_URL = f"http://{LAN_IP}:{PORT}/{APK_NAME}"

root = tk.Tk()
root.title(f"SCAN TO DOWNLOAD APK // {APK_NAME}")
root.geometry("520x660")
root.configure(bg="#FFFFFF")
root.attributes("-topmost", True)

lbl_title = tk.Label(
    root,
    text="📱 SCANNEN MIT HANDY-KAMERA",
    font=("Arial", 13, "bold"),
    fg="#000000",
    bg="#FFFFFF",
)
lbl_title.pack(pady=(14, 4))

lbl_sub = tk.Label(
    root,
    text="Öffnet Download-Seite im Browser (verhindert QR-Mini-Browser-Fehler)",
    font=("Arial", 9),
    fg="#444444",
    bg="#FFFFFF",
)
lbl_sub.pack(pady=(0, 8))

lbl_img = tk.Label(root, bg="#FFFFFF", bd=0)
lbl_img.pack(pady=4)

lbl_url = tk.Label(
    root,
    text="",
    font=("Consolas", 8, "bold"),
    fg="#0044AA",
    bg="#FFFFFF",
    justify=tk.CENTER,
    wraplength=480,
)
lbl_url.pack(pady=8)

lbl_status = tk.Label(
    root,
    text="Status: Warte auf Handy-Verbindung...",
    font=("Arial", 9, "bold"),
    fg="#006633",
    bg="#FFFFFF",
    wraplength=480,
)
lbl_status.pack(pady=(0, 8))

mode = {"lan": LAN_IP != "127.0.0.1"}
tmp_dir = tempfile.mkdtemp(prefix="med_qr_")
LAN_QR_PATH = os.path.join(tmp_dir, "lan_qr.png")
subprocess.run(["qrencode", "-s", "12", "-m", "4", "-l", "M", "-o", LAN_QR_PATH, LAN_PAGE_URL], check=False)


def render_qr():
    use_lan = mode["lan"] and os.path.exists(LAN_QR_PATH)
    path = LAN_QR_PATH if use_lan else GITHUB_QR_PATH
    url = LAN_PAGE_URL if use_lan else GITHUB_URL
    sub = (
        "WLAN-Downloadseite (Handy im selben WLAN — öffnet sauber im Browser)"
        if use_lan
        else "GitHub Direktlink (im Handy-Browser öffnen, funktioniert ohne PC)"
    )
    lbl_sub.config(text=sub)
    lbl_url.config(text=f"{APK_NAME}:\n{url}")
    if os.path.exists(path):
        img = Image.open(path).convert("RGBA").resize((340, 340), Image.Resampling.NEAREST)
        photo = ImageTk.PhotoImage(img)
        lbl_img.config(image=photo)
        lbl_img.image = photo


def toggle_mode():
    mode["lan"] = not mode["lan"]
    btn_switch.config(text="Zu GitHub-QR wechseln" if mode["lan"] else "Zu WLAN-Browser-QR wechseln")
    render_qr()


f_btns = tk.Frame(root, bg="#FFFFFF")
f_btns.pack(pady=6)

btn_switch = tk.Button(
    f_btns,
    text="Zu GitHub-QR wechseln" if mode["lan"] else "Zu WLAN-Browser-QR wechseln",
    font=("Arial", 9, "bold"),
    bg="#E8F0FE",
    fg="#174EA6",
    relief=tk.FLAT,
    padx=12,
    pady=6,
    command=toggle_mode,
)
btn_switch.pack(side=tk.LEFT, padx=6)

tk.Button(
    f_btns,
    text="Schließen",
    font=("Arial", 9, "bold"),
    bg="#EEEEEE",
    fg="#222222",
    relief=tk.FLAT,
    padx=16,
    pady=6,
    command=root.destroy,
).pack(side=tk.LEFT, padx=6)


def append_log(msg: str):
    ts = time.strftime("%H:%M:%S")
    try:
        with open(HTTP_LOG_PATH, "a", encoding="utf-8") as lf:
            lf.write(f"[{ts}] {msg}\n")
    except Exception:
        pass


class ApkHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _serve_apk(self, filepath: str, dl_name: str, ua: str, cip: str):
        if not os.path.exists(filepath):
            self.send_error(404)
            return
        size = os.path.getsize(filepath)
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Disposition", f'attachment; filename="{dl_name}"')
        self.send_header("Content-Length", str(size))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        root.after(0, lambda: lbl_status.config(text=f"⏳ Sende {dl_name} an {cip}...", fg="#AA5500"))
        sent = 0
        try:
            with open(filepath, "rb") as f:
                while True:
                    chunk = f.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    sent += len(chunk)
        except Exception as exc:
            append_log(f"ERR {cip} {self.path} sent={sent}/{size} err={exc} UA={ua}")
            return
        append_log(f"OK {cip} {self.path} sent={sent}/{size} UA={ua}")
        root.after(0, lambda s=sent: lbl_status.config(
            text=f"✓ Komplett gesendet: {s / (1024*1024):.2f} MB an {cip}",
            fg="#007722",
        ))

    def do_HEAD(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Connection", "close")
        self.end_headers()

    def do_GET(self):
        ua = self.headers.get("User-Agent", "-")
        cip = self.client_address[0]
        if self.path.startswith("/" + APK_NAME):
            self._serve_apk(APK_PATH, APK_NAME, ua, cip)
            return
        if self.path.startswith("/HtcBinauralScope.apk"):
            htc_path = "/root/coding/HtcBinauralScope-APK/HtcBinauralScope.apk"
            self._serve_apk(htc_path, "HtcBinauralScope.apk", ua, cip)
            return

        # Landing page with clear download button + A/B test button
        size_mb = (os.path.getsize(APK_PATH) / (1024 * 1024)) if os.path.exists(APK_PATH) else 0.0
        html = f"""<!doctype html>
<html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Meditation Analyzer APK Download</title>
<style>
body{{background:#080f18;color:#f8f8f2;font-family:sans-serif;text-align:center;padding:32px 16px}}
a.btn{{display:inline-block;margin-top:20px;padding:18px 26px;background:#00ff88;color:#05080c;
font-weight:bold;font-size:17px;border-radius:12px;text-decoration:none}}
a.btn2{{display:inline-block;margin-top:14px;padding:12px 18px;background:#1d2b3a;color:#8be9fd;
font-size:13px;border-radius:8px;text-decoration:none}}
.box{{background:#0d151c;padding:20px;border-radius:12px;max-width:420px;margin:0 auto}}
</style></head><body><div class="box">
<h2>🧘 Meditation Analyzer v1.4</h2>
<p>Signierte APK ({size_mb:.2f} MB, Android 8–16)</p>
<a class="btn" href="/{APK_NAME}">⬇ {APK_NAME} herunterladen</a><br>
<a class="btn2" href="/HtcBinauralScope.apk">🧪 Gegentest: HtcBinauralScope.apk laden</a>
<p style="font-size:12px;color:#ffb86c;margin-top:20px"><b>Tipp bei Firefox-Parse-Fehler:</b> Nicht auf „Öffnen“ in Firefox tippen, sondern in der Android-App <b>Dateien / Files → Downloads</b> antippen (oder Link in Chrome öffnen)!</p>
</div></body></html>""".encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(html)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(html)
        append_log(f"PAGE {cip} {self.path} UA={ua}")
        root.after(0, lambda: lbl_status.config(text=f"📱 Handy ({cip}) hat Download-Seite geöffnet...", fg="#174EA6"))

    def log_message(self, format, *args):
        pass


def start_server():
    try:
        HTTPServer.allow_reuse_address = True
        srv = HTTPServer(("0.0.0.0", PORT), ApkHandler)
        srv.serve_forever()
    except Exception as exc:
        root.after(0, lambda e=exc: lbl_status.config(text=f"HTTP Hinweis: {e}", fg="#884400"))


threading.Thread(target=start_server, daemon=True).start()
render_qr()
root.mainloop()
