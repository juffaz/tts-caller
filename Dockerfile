FROM clojure:temurin-17-lein

WORKDIR /app

# Установим baresip и нужные библиотеки
RUN apt-get update && apt-get install -y \
    baresip \
    libbaresip-dev \
    librem-dev \
    libre-dev \
    libasound2 \
    alsa-utils \
    curl \
 && apt-get clean && rm -rf /var/lib/apt/lists/*

# Копируем проект
COPY . /app

# Собираем uberjar
RUN lein uberjar

# Порт API
EXPOSE 8899

# Запуск приложения
CMD ["java", "-cp", "target/tts-caller-standalone.jar:lib/*", "clojure.main", "-m", "tts-caller.core"]
