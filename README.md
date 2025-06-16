# TTS Caller (Clojure + MaryTTS + Baresip)

**TTS Caller** is a lightweight voice alert service that converts text to speech (TTS) and makes SIP phone calls.  
Built with **Clojure**, using **MaryTTS** for speech synthesis and **Baresip** for SIP call handling.

This service is ideal for integration with monitoring systems like Zabbix, ElastAlert, Alertmanager, Centreon, and others.

---

## How to Run

### 1. Build the Docker image

```bash
docker build -t tts-caller .
