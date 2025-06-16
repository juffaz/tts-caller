# TTS Caller (Clojure + MaryTTS + Baresip)

**TTS Caller** is a lightweight voice alert service that converts text to speech (TTS) and makes SIP phone calls.  
Built with **Clojure**, using **MaryTTS** for speech synthesis and **Baresip** for SIP call handling.

This service is ideal for integration with monitoring systems like Zabbix, ElastAlert, Alertmanager, Centreon, and others.

---

## How to Run

### 1. Build the Docker image

```bash
docker build -t tts-caller .

### 2. Start the service (default)

docker run --rm -p 8899:8899 --name tts-caller tts-caller

### 3. Start with SIP credentials

```bash
docker run --rm -p 8899:8899 \
  -e SIP_USER="0201011221" \
  -e SIP_PASS="yourpassword" \
  -e SIP_HOST="10.22.6.249" \
  --name tts-caller tts-caller

### Trigger a call via HTTP API

```bash
curl --get "http://localhost:8899/call" \
  --data-urlencode "text=Mobile işləmir!" \
  --data-urlencode "phone=0722111111"

### Environment variables
Variable	Description
SIP_USER	SIP username/login
SIP_PASS	SIP password
SIP_HOST	SIP server IP or FQDN

### Example use cases

    Critical incident voice alerts

    SIP integration with monitoring systems

    Automated emergency call notifications

### Technologies used

    Clojure

    MaryTTS

    Baresip

    Docker