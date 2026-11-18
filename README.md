# Meditation Analyzer (Android APK)

Eigenständige, komplett offline laufende Android-App für ruhige Meditation ohne PC und ohne Übersetzungs-/Cloud-Module.  
Optimiert für leise Raumumgebungen direkt auf dem Smartphone oder mit Bluetooth-/Kabel-Kopfhörern.

---

## Funktionen

- **3-in-1 Live-Oszilloskop (`OscilloscopeView`)**
  - **CH2 (Oben) — Mic-Ruhe & Band-Telemetrie (`dBFS`)**: Verfolgt die akustische Umgebungslautstärke (`-72` bis `0 dBFS`) mit Orientierungsmarken (`-10 dB LAUT`, `-30 dB SPRACHE`, `-50 dB LEISE`, `-68 dB RUHE`) sowie den Modulationspegel im `θ/α (4–12 Hz)`- bzw. Tinnitus-Band.
  - **CH1 (Mitte) — Echtzeit-Audio-Wellenform** mit einstellbarem Gain (`x1.0` bis `x10.0`) und Pause/Freeze.
  - **CH3 (Unten) — Dual-Spektrum (`SignalAnalyzer`)**:
    - **Brain `1–30 Hz` Hüllkurven-FFT** (`100 Hz` RMS-Hüllkurve, `2048`-Punkt-Hanning-FFT): markiert **Delta (`1–4 Hz`)**, **Theta (`4–8 Hz`)**, **Schumann-Resonanz (`7.83 Hz`)** und **Alpha (`8–12 Hz`)** inkl. gelber Peak-Hold-Kurve.
    - **Tinnitus `2–16 kHz` & Full `0–16 kHz` Audio-FFT** (`4096`-Punkt-Hanning-FFT bei `44.1 kHz`) mit automatischem Tonal-/Tinnitus-Detektor.
- **Lokaler Stereo Binaural-Beat-Trainer (`BinauralBeatGenerator`)**
  - Erzeugt klickfreie Stereo-Sinustöne direkt auf dem Handy (`44.1 kHz`, linker Ohrhörer Trägerfrequenz `200 Hz` oder `136.1 Hz OM`, rechter Ohrhörer `Träger + Beat-Frequenz`).
  - Presets: **Delta `2.5 Hz`**, **Theta `6.0 Hz`**, **Schumann `7.83 Hz`**, **Alpha `10.0 Hz`** mit Lautstärkeregler.
- **Kompakter Recorder (`CosmosAudioStorage`)**
  - Speichert und lädt Aufnahmen platzsparend als **`.cfs` (CFS1 + zlib + 4-bit IMA-ADPCM)** oder **`.tba` (TBA4 + LZ77 + 4-bit IMA-ADPCM)** sowie **`.wav`** – binärkompatibel zum Desktop-Tool.
- **Audioquellen ohne PC**
  - `🎙️ Handy-Mic (44.1 kHz)` für ruhige Raum- und Atemmessung.
  - `🎧 BT Headset Mic` (Bluetooth SCO).
  - `🧪 Beat-Monitor (L+R Mix)` zur direkten visuellen Kontrolle des internen Binaural-Beats im Oszilloskop.

---

## Technischer Hinweis zur Messung

Das Mikrofon misst **akustische Modulationen** (Raumruhe, gleichmäßiges Atmen/Summen, Klangquellen bzw. den internen Beat-Monitor) — keine direkten elektrischen EEG-Hirnströme von der Kopfhaut. Die gewünschte Zielfrequenz (`Alpha 8–12 Hz`, `Schumann 7.83 Hz`, `Theta 4–8 Hz`) wird über den Binaural-Beat-Generator per Stereo-Kopfhörer vorgegeben.

---

## Installation & Build

- **Direkt auf Android installieren (QR-Code oder Link):**
  - Direkter APK-Download (`v1.2`): [`MeditationAnalyzer_v1.2.apk`](https://raw.githubusercontent.com/Herakles5/MeditationAnalyzer/main/MeditationAnalyzer_v1.2.apk)
  - Oder QR-Code mit der Smartphone-Kamera scannen:

  ![Meditation Analyzer APK Download QR](./MeditationAnalyzer_QR.png)

- **Selbst bauen (Gradle + Android SDK 34):**
  ```bash
  gradle assembleRelease
  ```
