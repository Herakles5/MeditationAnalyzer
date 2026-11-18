#!/usr/bin/env python3
"""Zeigt einen klaren QR-Code für direkten Handy-Download (WLAN-LAN & GitHub)."""
import os
import socket
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
import tkinter as tk
from PIL import Image, ImageTk

if "DISPLAY" not in os.environ:
    os.environ["DISPLAY"] = ":0"

GITHUB_URL = "https://raw.githubusercontent.com/Herakles5/MeditationAnalyzer/main/MeditationAnalyzer_v1.3.apk"
HERE = os.path.dirname(os.path.abspath(__file__))
APK_PATH = os.path.join(HERE, "MeditationAnalyzer_v1.3.apk")
GITHUB_QR_PATH = os.path.join(HERE, "MeditationAnalyzer_QR.png")


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
LAN_URL = f"http://{LAN_IP}:{PORT}/MeditationAnalyzer_v1.3.apk"

root = tk.Tk()
root.title("SCAN TO DOWNLOAD APK // Meditation Analyzer v1.3")
root.geometry("520x640")
root.configure(bg="#FFFFFF")
root.attributes("-topmost", True)

lbl_title = tk.Label(
    root,
    text="📱 SCANNEN MIT HANDY-KAMERA / CHROME",
    font=("Arial", 13, "bold"),
    fg="#000000",
    bg="#FFFFFF",
)
lbl_title.pack(pady=(14, 4))

lbl_sub = tk.Label(
    root,
    text="WLAN-Direktdownload mit echtem Android-APK-Header",
    font=("Arial", 10),
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
    text="Status: Warte auf Handy-Download...",
    font=("Arial", 9, "bold"),
    fg="#006633",
    bg="#FFFFFF",
)
lbl_status.pack(pady=(0, 8))

mode = {"lan": LAN_IP != "127.0.0.1"}
tmp_dir = tempfile.mkdtemp(prefix="med_qr_")
LAN_QR_PATH = os.path.join(tmp_dir, "lan_qr.png")
if os.path.exists(APK_PATH):
    subprocess.run(["qrencode", "-s", "12", "-m", "4", "-l", "M", "-o", LAN_QR_PATH, LAN_URL], check=False)


def render_qr():
    use_lan = mode["lan"] and os.path.exists(LAN_QR_PATH)
    path = LAN_QR_PATH if use_lan else GITHUB_QR_PATH
    url = LAN_URL if use_lan else GITHUB_URL
    sub = (
        "WLAN-Direktdownload (Handy im selben WLAN, schnellster Weg)"
        if use_lan
        else "GitHub Direktlink (funktioniert überall ohne PC)"
    )
    lbl_sub.config(text=sub)
    lbl_url.config(text=f"MeditationAnalyzer_v1.3.apk:\n{url}")
    if os.path.exists(path):
        img = Image.open(path).convert("RGBA").resize((340, 340), Image.Resampling.NEAREST)
        photo = ImageTk.PhotoImage(img)
        lbl_img.config(image=photo)
        lbl_img.image = photo


def toggle_mode():
    mode["lan"] = not mode["lan"]
    btn_switch.config(text="Zu GitHub-QR wechseln" if mode["lan"] else "Zu WLAN-Direkt-QR wechseln")
    render_qr()


f_btns = tk.Frame(root, bg="#FFFFFF")
f_btns.pack(pady=6)

btn_switch = tk.Button(
    f_btns,
    text="Zu GitHub-QR wechseln" if mode["lan"] else "Zu WLAN-Direkt-QR wechseln",
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


class ApkHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if not os.path.exists(APK_PATH):
            self.send_error(404)
            return
        size = os.path.getsize(APK_PATH)
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Disposition", 'attachment; filename="MeditationAnalyzer_v1.3.apk"')
        self.send_header("Content-Length", str(size))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        root.after(0, lambda: lbl_status.config(text=f"⏳ Sende APK an {self.client_address[0]}...", fg="#AA5500"))
        sent = 0
        with open(APK_PATH, "rb") as f:
            while True:
                chunk = f.read(65536)
                if not chunk:
                    break
                self.wfile.write(chunk)
                sent += len(chunk)
        root.after(0, lambda s=sent: lbl_status.config(
            text=f"✓ Komplett übertragen: {s / (1024*1024):.2f} MB — Jetzt auf Handy tippen!",
            fg="#007722",
        ))

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
