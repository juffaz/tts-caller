# TTS Caller (Clojure + MaryTTS + Baresip)

**TTS Caller** is a lightweight voice alert service that converts text to speech (TTS) and makes SIP phone calls.  
Built with **Clojure**, using **MaryTTS** or **espeak-ng** for speech synthesis and **Baresip** for SIP call handling.

This service is ideal for integration with monitoring systems like Zabbix, ElastAlert, Alertmanager, Centreon, and others.

---

## How to Run

### 1. Build the Docker image
```bash
docker build -t tts-caller .
```

### 2. Start the service in background
```bash
docker run -d --network=host \
  -e SIP_USER="0201011221" \
  -e SIP_PASS="yourpassword" \
  -e SIP_HOST="10.22.6.249" \
  --name tts-caller tts-caller
```

### 3. Trigger a call via HTTP API
```bash
curl --get "http://localhost:8899/call" \
  --data-urlencode "text=Mobile işləmir!" \
  --data-urlencode "phone=0722111111"
```

#### Additional API parameters
Parameter	Description
text	Text to convert to speech
phone	One or more phone numbers (comma or space)
engine	marytts (default) or espeak
repeat	Number of times to repeat the message
Multiple phones example
```bash
curl --get "http://localhost:8899/call" \
  --data-urlencode "text=Alert from system!" \
  --data-urlencode "phone=0722111111,0722111112" \
  --data-urlencode "engine=espeak" \
  --data-urlencode "repeat=3"
```
#### Environment variables
Variable	Description

SIP_USER	SIP username/login

SIP_PASS	SIP password

SIP_HOST	SIP server IP or FQDN

#### Example use cases

    Critical incident voice alerts
    SIP integration with monitoring systems
    Automated emergency call notifications

#### Technologies used

    Clojure
    MaryTTS
    espeak-ng
    Baresip
    Docker

#### Required JAR files in lib/ folder

Ensure the following JARs are present in the lib directory:

    marytts-client-5.2-jar-with-dependencies.jar
    marytts-runtime-5.2-jar-with-dependencies.jar
    marytts-lang-en-5.2.jar
    marytts-lang-ru-5.2.jar
    marytts-lang-tr-5.2.jar
    voice-cmu-slt-hsmm-5.2.jar
    voice-dfki-ot-hsmm-5.2.jar

#### Bugfixes and improvements

    Fixed early termination of baresip via ProcessBuilder
    Switched from stdin commands to -e arguments for reliable execution
    Added support for multiple phones
    Added repeat and engine parameters
    Full logs available at /tmp/baresip.log inside container
```bash
docker exec -it tts-caller cat /tmp/baresip.log
```

