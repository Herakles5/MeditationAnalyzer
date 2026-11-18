#!/usr/bin/env python3
"""Zeigt einen klaren QR-Code zum direkten Handy-Download von MeditationAnalyzer.apk."""
import os
import tkinter as tk
from PIL import Image, ImageTk

if "DISPLAY" not in os.environ:
    os.environ["DISPLAY"] = ":0"

APK_URL = "https://raw.githubusercontent.com/Herakles5/MeditationAnalyzer/main/MeditationAnalyzer_v1.2.apk"
HERE = os.path.dirname(os.path.abspath(__file__))
QR_PATH = os.path.join(HERE, "MeditationAnalyzer_QR.png")

root = tk.Tk()
root.title("SCAN TO DOWNLOAD APK // Meditation Analyzer")
root.geometry("500x580")
root.configure(bg="#FFFFFF")
root.attributes("-topmost", True)

tk.Label(
    root,
    text="📱 SCANNEN MIT HANDY-KAMERA",
    font=("Arial", 14, "bold"),
    fg="#000000",
    bg="#FFFFFF",
).pack(pady=(16, 6))

tk.Label(
    root,
    text="Direkter APK-Download (ohne PC nutzbar)",
    font=("Arial", 10),
    fg="#444444",
    bg="#FFFFFF",
).pack(pady=(0, 10))

if os.path.exists(QR_PATH):
    img = Image.open(QR_PATH).convert("RGB").resize((360, 360), Image.Resampling.NEAREST)
    photo = ImageTk.PhotoImage(img)
    lbl_img = tk.Label(root, image=photo, bg="#FFFFFF", bd=0)
    lbl_img.image = photo
    lbl_img.pack(pady=4)

tk.Label(
    root,
    text=f"MeditationAnalyzer_v1.2.apk (v2-Signatur, ab Android 7):\n{APK_URL}",
    font=("Consolas", 8, "bold"),
    fg="#0044AA",
    bg="#FFFFFF",
    justify=tk.CENTER,
    wraplength=460,
).pack(pady=12)

tk.Button(
    root,
    text="Schließen",
    font=("Arial", 10, "bold"),
    bg="#EEEEEE",
    fg="#222222",
    relief=tk.FLAT,
    padx=18,
    pady=6,
    command=root.destroy,
).pack(pady=(4, 14))

root.mainloop()
