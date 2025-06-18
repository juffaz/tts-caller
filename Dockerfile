FROM quay.io/centos/centos:stream9

# Устанавливаем системные зависимости и baresip
RUN dnf install -y epel-release && \
    dnf install -y baresip baresip-alsa baresip-pulse baresip-sndfile alsa-utils java-17-openjdk curl \
    && dnf clean all

RUN curl -O https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein && \
    mv lein /usr/local/bin/lein && \
    chmod +x /usr/local/bin/lein && \
    lein

# Рабочая директория
WORKDIR /app
COPY . /app

# Сборка проекта
RUN lein uberjar

# Копируем baresip модули (если надо)
RUN mkdir -p /tmp/baresip_config && \
    cp /usr/lib64/baresip/modules/*.so /tmp/baresip_config || true && \
    echo "\
module_path /tmp/baresip_config\n\
module aufile.so\n\
module g711.so\n\
module stdio.so\n\
sip_transp udp\n\
sip_listen 0.0.0.0" > /tmp/baresip_config/config && \
    mkdir -p /root/.baresip && \
    touch /root/.baresip/accounts

# Порт API
EXPOSE 8899

# Запуск сервиса
CMD ["java", "-cp", "target/tts-caller-standalone.jar:lib/*", "clojure.main", "-m", "tts-caller.core"]
