FROM clojure:temurin-17-lein

WORKDIR /app

# Установим зависимости
RUN apt-get update && apt-get install -y \
    baresip \
    baresip-modules \
    alsa-utils \
    curl \
 && apt-get clean && rm -rf /var/lib/apt/lists/*

# Копируем проект (с учетом .dockerignore)
COPY . /app

# Собираем uberjar
RUN lein uberjar

# Указываем порт API
EXPOSE 8899

# Запуск приложения
CMD ["java", "-cp", "target/tts-caller-standalone.jar:lib/*", "clojure.main", "-m", "tts-caller.core"]
