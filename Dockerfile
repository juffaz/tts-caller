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

# Копируем проект
COPY . /app

# Копируем baresip-модули в /tmp/baresip_config (туда, где будет config)
RUN mkdir -p /tmp/baresip_config && \
    cp /usr/lib/baresip/modules/*.so /tmp/baresip_config/

# Собираем uberjar
RUN lein uberjar

# API-порт
EXPOSE 8899

# Запуск
CMD ["java", "-cp", "target/tts-caller-standalone.jar:lib/*", "clojure.main", "-m", "tts-caller.core"]
