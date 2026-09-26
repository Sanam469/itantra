# 🛰️ iTantra Neural Transceiver

> **Next-Generation Offline Neural Speech-to-Speech Transceiver for Low-Power Devices & Constrained Bandwidth Links**  
> *Developed for ISRO Problem Statement 26173 | Smart India Hackathon (SIH)*

---

## 🌟 Overview

In disaster response, remote exploration, and mission-critical scenarios, raw vocal audio transmission is bandwidth-prohibitive over low data rate links (HF/VHF, low-bandwidth mesh, or congested Wi-Fi/Bluetooth). Furthermore, in multilingual rescue operations, transmitting audio in a single language leaves non-native speakers and illiterate individuals at risk.

**iTantra** solves this with an edge-native, zero-cloud neural transceiver pipeline:
1. **Speech-to-Text (STT):** Captures voice via Push-to-Talk (PTT) or Hands-Free Silero VAD, converting speech to text locally in **10 Indian languages**.
2. **Compact Wire Transmission:** Transmits lightweight semantic text/CBOR packets (~50-100 bytes) instead of heavy raw audio (~32,000 bytes/sec) over dual-channel hybrid Wi-Fi/Bluetooth/Nearby links (saving **>99.8% bandwidth**).
3. **On-Device Translation:** Cross-translates received messages locally into the listener's chosen language.
4. **Formant Text-to-Speech (TTS):** Synthesizes translated speech 100% offline using an ultra-low-power rule-based formant acoustic synthesizer (eSpeak NG), speaking it aloud immediately to the recipient.
5. **Emergency Distress Broadcast:** High-priority distress override with non-interruptible alarm and max-volume audio readout.

---

## 🏗️ Architecture

```
[Phone 1: Speaker]
  🎙️ Microphone (16 kHz 16-bit PCM)
        │
        ▼
  🧠 Sherpa-ONNX + AI4Bharat IndicConformer (INT8)
        │
        ▼ (Text: "बाढ़ का पानी बढ़ रहा है")
  📦 CBOR Binary Wire Packet (~60 bytes)
        │
   ═════╪═════════════════════════════════════════════════════
        │  📡 Hybrid Dual-Channel Link:
        │     • Hotspot / Wi-Fi Direct (TCP + UDP Broadcast)
        │     • Bluetooth RFCOMM Sockets
        │     • Google Nearby Connections
   ═════╪═════════════════════════════════════════════════════
        │
[Phone 2: Listener]
        ▼
  🌐 Google ML Kit On-Device Translation (Hindi ➔ Tamil)
        │
        ▼ (Text: "வெள்ள நீர் உயர்ந்து வருகிறது")
  🔊 eSpeak NG Rule-Based Formant Synthesizer (~5 MB engine)
        │
        ▼
  📢 Loudspeaker / Earpiece Playback
```

---

## 🇮🇳 Supported Indian Languages

| Language | Code | Script | STT Engine | TTS Engine |
|---|---|---|---|---|
| **Hindi** | `hi` | Devanagari | IndicConformer CTC (INT8) | eSpeak NG (`hi`) |
| **English** | `en` | Latin | NeMo FastConformer CTC (INT8) | eSpeak NG (`en-us`) |
| **Bengali** | `bn` | Bengali | IndicConformer CTC (INT8) | eSpeak NG (`bn`) |
| **Tamil** | `ta` | Tamil | IndicConformer CTC (INT8) | eSpeak NG (`ta`) |
| **Telugu** | `te` | Telugu | IndicConformer CTC (INT8) | eSpeak NG (`te`) |
| **Marathi** | `mr` | Devanagari | IndicConformer CTC (INT8) | eSpeak NG (`mr`) |
| **Gujarati** | `gu` | Gujarati | IndicConformer CTC (INT8) | eSpeak NG (`gu`) |
| **Kannada** | `kn` | Kannada | IndicConformer CTC (INT8) | eSpeak NG (`kn`) |
| **Malayalam** | `ml` | Malayalam | IndicConformer CTC (INT8) | eSpeak NG (`ml`) |
| **Odia** | `or` | Odia | IndicConformer CTC (INT8) | eSpeak NG (`or`) |

---

## ⚡ Key Technical Innovations

* **INT8 Model Quantization:** AI4Bharat IndicConformer models compressed from ~750MB float32 down to **~188MB INT8**, enabling low-RAM Android phones to run state-of-the-art ASR with <1% WER degradation.
* **Formant Synthesis Over Heavy Neural TTS:** Using rule-based formant acoustic modeling (eSpeak NG) requires **zero GPU/NPU**, runs on <1% CPU, occupies <5MB storage, and operates with zero battery strain.
* **Semantic Compression (99.8% Bandwidth Reduction):** Instead of streaming 32 kB/s raw voice, iTantra transmits structured CBOR packets containing tokenized text, timestamps, and sequence IDs (~60 bytes total).
* **Zero-Configuration Hybrid Transport:**
  * Auto-discovering TCP/UDP peer discovery over Wi-Fi Hotspot.
  * Direct Bluetooth RFCOMM SPP fallback when Wi-Fi is unavailable.
  * Google Nearby Connections for mesh/peer topology.
* **Turn-Taking Echo Prevention:** Automatic acoustic ducking and microphone mute during TTS playback to prevent self-trigger feedback loops.

---

## 📱 Hardware & OS Requirements

* **OS:** Android 7.0 (API Level 24) or higher
* **Architecture:** `arm64-v8a`, `armeabi-v7a`, `x86_64`
* **RAM:** Minimum 2 GB (Recommended: 3 GB+)
* **Microphone:** 16,000 Hz, 16-bit Mono PCM
* **Connectivity:** Wi-Fi (Hotspot/LAN) or Bluetooth 4.2+

---

## 🛠️ Project Setup & Build

### 1. Prerequisites
* [Android Studio Hedgehog](https://developer.android.com/studio) or newer
* Android SDK 34
* JDK 17

### 2. Clone and Open
```bash
git clone https://github.com/Sanam469/itantra.git
cd itantra
```

### 3. Build via Gradle
```bash
./gradlew assembleDebug
```
The APK will be generated at: `app/build/outputs/apk/debug/app-debug.apk`

---

## 📄 Open Source Licenses & Attribution

* **Sherpa-ONNX:** Apache 2.0 (Next-gen Kaldi project)
* **AI4Bharat IndicConformer:** MIT License
* **Silero VAD:** MIT License
* **eSpeak NG:** GNU General Public License v3.0
* **Google ML Kit:** Google APIs Terms of Service (On-device NMT)

---

## 👥 Authors & Team

Developed with ❤️ for **ISRO Problem Statement 26173**  
Smart India Hackathon 2026.
