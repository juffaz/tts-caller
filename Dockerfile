FROM quay.io/centos/centos:stream9

# Установка зависимостей
RUN dnf install -y epel-release && \
    dnf install -y \
    baresip baresip-alsa baresip-pulse baresip-sndfile \
    alsa-utils java-17-openjdk curl && \
    dnf clean all

# Установка Leiningen
RUN curl -O https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein && \
    mv lein /usr/local/bin/lein && \
    chmod +x /usr/local/bin/lein && \
    lein


WORKDIR /app

# Копируем проект
COPY . /app

# Сборка
RUN lein uberjar

# Копируем baresip-модули (если вдруг baresip будет искать их локально)
RUN mkdir -p /root/.baresip && \
    cp /usr/lib64/baresip/modules/*.so /root/.baresip || true

    EXPOSE 8899


CMD ["java", "-cp", "target/tts-caller-standalone.jar:lib/*", "clojure.main", "-m", "tts-caller.core"]
