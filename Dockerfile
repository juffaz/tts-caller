FROM clojure:openjdk-17-slim

WORKDIR /app

RUN apt-get update && apt-get install -y baresip sox curl alsa-utils

COPY . /app

RUN lein deps && lein uberjar

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD curl -sf http://localhost:8899/health || exit 1

CMD ["java", "-cp", "lib/voice-hmm-tr-istfemale-5.2.jar:target/tts-caller-0.1.0-SNAPSHOT-standalone.jar", "tts-caller.core"]
