FROM quay.io/centos/centos:stream9

# Install dependencies
RUN dnf install -y epel-release && \
    dnf install -y --allowerasing \
    baresip baresip-alsa baresip-pulse baresip-sndfile \
    alsa-utils java-17-openjdk curl procps-ng psmisc nmap-ncat sox espeak-ng && \
    dnf clean all

# Install Leiningen
RUN curl -O https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein && \
    mv lein /usr/local/bin/lein && \
    chmod +x /usr/local/bin/lein && \
    lein

WORKDIR /app

# Copy project files
COPY . /app

# Build the project
RUN lein uberjar

# Copy baresip modules (in case baresip looks for them locally)
RUN mkdir -p /root/.baresip && \
    cp /usr/lib64/baresip/modules/*.so /root/.baresip || true

# Copy baresip modules (custom config path)
RUN mkdir -p /tmp/baresip_config && \
    cp /usr/lib64/baresip/modules/*.so /tmp/baresip_config || true

EXPOSE 8899

CMD ["java", "-Dmary.base=/app/lib", "-cp", "target/tts-caller-standalone.jar:lib/*", "clojure.main", "-m", "tts-caller.core"]
