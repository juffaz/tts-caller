FROM ubuntu:22.04

# Установим нужные зависимости
RUN apt-get update && apt-get install -y \
    openjdk-17-jdk \
    curl \
    baresip \
    libasound2 \
    alsa-utils \
    leiningen \
 && apt-get clean && rm -rf /var/lib/apt/lists/*

# Создадим рабочую директорию
WORKDIR /app

# Копируем проект (с учётом .dockerignore)
COPY . /app

# Собираем uberjar
RUN lein uberjar

# API-порт
EXPOSE 8899

# Запуск
CMD ["java", "-cp", "target/tts-caller-standalone.jar:lib/*", "clojure.main", "-m", "tts-caller.core"]
