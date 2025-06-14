FROM clojure:temurin-17-lein

WORKDIR /app

# Устанавливаем зависимости
RUN apt-get update && apt-get install -y baresip sox curl alsa-utils

# Копируем проект
COPY . /app

# Скачиваем зависимости и собираем uberjar
RUN lein deps && lein uberjar

# Healthcheck
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD curl -sf http://localhost:8899/health || exit 1

# Запуск приложения
CMD ["java", "-cp", "lib/voice-dfki-ot-hsmm-5.2.jar:target/uberjar/tts-caller-0.1.0-SNAPSHOT-standalone.jar", "tts-caller.core"]
