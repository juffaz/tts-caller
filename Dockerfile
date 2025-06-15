FROM clojure:temurin-17-lein

WORKDIR /app

# Копируем deps и lib сначала — это кэшируется
COPY project.clj .
COPY lib ./lib/
RUN lein deps

# Копируем остальной код
COPY . .

# Собираем uberjar внутри Docker
RUN lein uberjar

# Healthcheck
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD curl -sf http://localhost:8899/health || exit 1

# Запускаем
CMD ["java", "-cp", "lib/*:target/uberjar/tts-caller-0.1.0-SNAPSHOT-standalone.jar", "tts-caller.core"]
