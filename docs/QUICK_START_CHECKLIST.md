# Quick Start: Интеграция Voices/Piper в tts-caller

## Чек-лист для быстрого старта (4-13 часов)

### ☑️ Предварительная проверка

```bash
# 1. Проверить Java версию (нужна ≥17)
java -version

# Если < 17, установить:
# macOS: brew install openjdk@17
# Linux: apt install openjdk-17-jdk или через SDKMAN
# Windows: скачать с https://adoptium.net/

# 2. Проверить наличие sox (для конвертации аудио)
which sox

# Если нет:
# macOS: brew install sox
# Linux: apt install sox
# Windows: chocolatey install sox
```

### ☑️ Шаг 1: Скачать турецкую модель (5 мин)

```bash
cd /path/to/tts-caller

# Создать папку для моделей
mkdir -p resources/piper-models
cd resources/piper-models

# Скачать dfki-medium (рекомендуется для начала)
wget https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/tr/tr_TR/dfki/medium/tr_TR-dfki-medium.onnx
wget https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/tr/tr_TR/dfki/medium/tr_TR-dfki-medium.onnx.json

# Проверить
ls -lh
# Должны быть:
# tr_TR-dfki-medium.onnx (~20 MB)
# tr_TR-dfki-medium.onnx.json (~5 KB)
```

### ☑️ Шаг 2: Добавить зависимости (5 мин)

**Редактировать:** [project.clj](../../project.clj)

```clojure
:dependencies [
  ;; Существующие зависимости
  [org.clojure/clojure "1.11.1"]
  [org.clojure/core.async "1.6.681"]
  [ring/ring-core "1.11.0"]
  [ring/ring-jetty-adapter "1.11.0"]
  [compojure "1.7.1"]
  [instaparse "1.4.8"]
  [medley "1.4.0"]
  [org.slf4j/slf4j-simple "1.7.36"]

  ;; ========== НОВЫЕ: Voices/Piper ==========
  [org.pitest.voices/chorus "0.0.9"]
  [org.pitest.voices/en_uk "0.0.9"]
  [org.pitest.voices/openvoice-phonemizer "0.0.9"]
  [com.microsoft.onnxruntime/onnxruntime "1.20.0"]
]
```

**Установить:**

```bash
cd /path/to/tts-caller
lein deps
# Должно скачать ~80-150 MB
```

### ☑️ Шаг 3A: Быстрое решение - Alba с OpenVoice (30 мин)

**Если нужно быстро протестировать без кастомного Model wrapper.**

**Редактировать:** [src/tts_caller/audio.clj](../../src/tts_caller/audio.clj)

**3.1 Добавить импорты (после строки 6):**

```clojure
(:import [javax.sound.sampled AudioFormat AudioInputStream AudioSystem AudioFileFormat$Type]
         [java.io ByteArrayInputStream File]
         [org.w3c.dom Document]
         [javax.xml.parsers DocumentBuilderFactory]
         ;; ========== НОВЫЕ ИМПОРТЫ ==========
         [org.pitest.voices Chorus ChorusConfig Audio Voice]
         [org.pitest.voices.alba Alba]
         [org.pitest.voices.dictionaries EnUkDictionary]
         [org.pitest.voices.openvoice OpenVoiceSupplier]))
```

**3.2 Добавить инициализацию (после строки 19, перед create-mary):**

```clojure
;; ========== PIPER/VOICES INITIALIZATION ==========

(defonce ^:private piper-chorus
  (delay
    (try
      (ts-println "🎵 Initializing Piper Chorus...")
      (let [config (-> (ChorusConfig/chorusConfig (EnUkDictionary/en_uk))
                       (.withModel (OpenVoiceSupplier.)))]
        (Chorus. config))
      (catch Exception e
        (ts-println "❌ Failed to initialize Chorus:" (.getMessage e))
        nil))))

(defonce ^:private piper-voice
  (delay
    (try
      (when-let [chorus @piper-chorus]
        (ts-println "🎵 Loading Alba voice with OpenVoice...")
        (.voice chorus (Alba/albaMedium)))
      (catch Exception e
        (ts-println "❌ Failed to load voice:" (.getMessage e))
        nil))))

(defn generate-audio-bytes-piper
  "Генерирует аудио через Piper/Voices"
  [text & {:keys [speed gain] :or {speed 1.0 gain 1.0}}]
  (if-let [voice @piper-voice]
    (try
      (locking piper-chorus
        (let [voice (-> voice
                        (.withSpeed (float speed))
                        (.withGain (float gain)))
              ^Audio audio (.say voice text)]
          (.asBytes audio)))
      (catch Exception e
        (ts-println "❌ Piper synthesis error:" (.getMessage e))
        (throw e)))
    (do
      (ts-println "❌ Piper voice not initialized")
      (throw (ex-info "Piper voice not available" {})))))
```

**3.3 Добавить case "piper" в generate-final-wav-auto (после строки 125, перед закрывающей скобкой):**

```clojure
      ;; ========== НОВЫЙ ДВИЖОК: PIPER ==========
      "piper"
      (let [speed (if (= rate "fast") 1.3 1.0)
            gain-val (if (zero? gain) 1.0 gain)
            audio-bytes (generate-audio-bytes-piper text
                                                     :speed speed
                                                     :gain gain-val)
            tmp1 (str outfile ".tmp.wav")]
        ;; Сохранить временный WAV
        (with-open [out (java.io.FileOutputStream. tmp1)]
          (.write out audio-bytes))
        (ts-println "🎵 Piper audio generated (22.05kHz)")

        ;; Конвертация sox: 22050Hz → 8000Hz mono
        (let [{:keys [exit err]} (sh "sox" tmp1 "-r" "8000" "-c" "1" "-b" "16" outfile)]
          (when-not (zero? exit)
            (ts-println "❌ sox conversion error:" err))
          (.delete (File. tmp1)))))
```

**3.4 Тест:**

```bash
lein repl

(require '[tts-caller.audio :as audio])

;; Турецкий тест
(audio/generate-final-wav-auto
  "Merhaba dünya"
  "/tmp/piper-test.wav"
  :tts-engine "piper")

;; Послушать (macOS)
(clojure.java.shell/sh "open" "/tmp/piper-test.wav")

;; Азербайджанский тест
(audio/generate-final-wav-auto
  "Salam, bu testdir!"
  "/tmp/piper-az.wav"
  :tts-engine "piper")
```

**⚠️ ВНИМАНИЕ:** Это решение использует английский голос Alba с OpenVoice фонемизатором. Качество будет хуже турецкой модели, но работает из коробки.

**Оценка качества:** ★★★☆☆ (5/10)

---

### ☑️ Шаг 3B: Полное решение - Турецкая модель (2-4 часа)

**Если нужно высокое качество с турецкой моделью.**

**3B.1 Создать файл:** `src/tts_caller/turkish_model.clj`

```clojure
(ns tts-caller.turkish-model
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [java.nio.file Files Paths]
           [org.pitest.voices Model ModelConfig Language]))

;; ВАРИАНТ 1: Попытка использовать FileModel через рефлексию
(defn load-turkish-model []
  (try
    (let [file-model-class (Class/forName "org.pitest.voices.download.FileModel")
          constructor (.getDeclaredConstructor file-model-class
                        (into-array Class [String String String Language Float]))
          _ (.setAccessible constructor true)]
      (.newInstance constructor
        (object-array ["tr-TR-dfki-medium"
                       "resources/piper-models/tr_TR-dfki-medium.onnx"
                       "resources/piper-models/tr_TR-dfki-medium.onnx.json"
                       Language/OTHER
                       (float 1.0)])))
    (catch Exception e
      (println "⚠️ FileModel not accessible, trying alternative...")
      (load-turkish-model-alt))))

;; ВАРИАНТ 2: Если FileModel не работает - полная реализация Model
(defrecord TurkishPiperModel [model-id onnx-path json-path]
  Model
  (id [_] model-id)
  (sid [_] -1)
  (language [_] Language/OTHER)
  (withLanguage [this lang] this)
  (asBytes [_ cache-base]
    (Files/readAllBytes (Paths/get onnx-path (into-array String []))))
  (resolveConfig [_ cache-base]
    ;; TODO: парсить JSON и создать ModelConfig
    ;; Сложная часть - требует изучения ModelConfig структуры
    (throw (UnsupportedOperationException. "resolveConfig not implemented")))
  (defaultGain [_] 1.0))

(defn load-turkish-model-alt []
  (->TurkishPiperModel
    "tr-TR-dfki-medium"
    "resources/piper-models/tr_TR-dfki-medium.onnx"
    "resources/piper-models/tr_TR-dfki-medium.onnx.json"))
```

**3B.2 Обновить audio.clj:**

```clojure
;; Изменить импорты
(:require [clojure.java.shell :refer [sh]]
          [tts-caller.turkish-model :as tr-model])  ; ДОБАВИТЬ

;; Изменить инициализацию
(defonce ^:private piper-chorus
  (delay
    (try
      (ts-println "🎵 Initializing Piper Chorus...")
      (let [config (-> (ChorusConfig/chorusConfig (Dictionaries/empty))
                       (.withModel (OpenVoiceSupplier.)))]
        (Chorus. config))
      (catch Exception e
        (ts-println "❌ Failed to initialize Chorus:" (.getMessage e))
        nil))))

(defonce ^:private piper-voice
  (delay
    (try
      (when-let [chorus @piper-chorus]
        (ts-println "🎵 Loading Turkish voice...")
        (let [model (tr-model/load-turkish-model)]
          (.voice chorus model)))
      (catch Exception e
        (ts-println "❌ Failed to load Turkish voice:" (.getMessage e))
        nil))))
```

**⚠️ ВАЖНО:** Вариант 2 требует доработки метода `resolveConfig`. Если застрянете - используйте Шаг 3A (Alba).

**Оценка качества:** ★★★★☆ (7-8/10)

---

### ☑️ Шаг 4: HTTP API тест (10 мин)

```bash
# Запустить сервер
lein run

# В другом терминале - тест с турецким
curl "http://localhost:8899/call?text=Merhaba&phone=1001&engine=piper&repeat=3"

# Тест с азербайджанским
curl "http://localhost:8899/call?text=Salam%20d%C3%BCnya&phone=1001&engine=piper&repeat=3"

# Проверить созданные файлы
ls -lh /tmp/final_batch_*.wav

# Послушать
# macOS: open /tmp/final_batch_*.wav
# Linux: aplay /tmp/final_batch_*.wav
```

### ☑️ Шаг 5: Сравнение качества (15 мин)

```bash
# Сгенерировать тот же текст всеми движками
TEXT="Salam, Sphere və Atlas işləmir"

curl "http://localhost:8899/call?text=$TEXT&phone=1001&engine=espeak" &
curl "http://localhost:8899/call?text=$TEXT&phone=1001&engine=marytts" &
curl "http://localhost:8899/call?text=$TEXT&phone=1001&engine=piper" &

# Сравнить качество звука
# espeak: ★★☆☆☆ (робот)
# marytts: ★★★☆☆ (синтетика, но приятнее)
# piper (Alba): ★★★☆☆ (английский акцент)
# piper (Turkish): ★★★★☆ (натурально!)
```

---

## Ожидаемые результаты

### ✅ После Шага 3A (Alba с OpenVoice)

```
✓ Новый движок "piper" работает
✓ Качество лучше espeak, но хуже турецкой модели
✓ Время разработки: 30-60 мин
✓ Готово к тестированию
```

### ✅ После Шага 3B (Турецкая модель)

```
✓ Турецкая модель загружена
✓ Высокое качество синтеза
✓ Приемлемо для азербайджанского текста
✓ Время разработки: 2-4 часа
```

---

## Troubleshooting

### Проблема: "Class not found: marytts.LocalMaryInterface"

**Причина:** Конфликт зависимостей или отсутствие MaryTTS в classpath

**Решение:** Временно удалить MaryTTS из lib/ или использовать другой движок

### Проблема: "java.lang.UnsupportedClassVersionError"

**Причина:** Java версия < 17

**Решение:**
```bash
java -version  # Проверить текущую версию

# Установить Java 17+
# macOS:
brew install openjdk@17
sudo ln -sfn $(brew --prefix openjdk@17)/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk

# Linux:
sudo apt update
sudo apt install openjdk-17-jdk

# Проверить
java -version  # Должна быть ≥17
```

### Проблема: "❌ Piper voice not initialized"

**Причина:** Ошибка при загрузке модели или Chorus

**Решение:**
```bash
# Проверить логи при запуске REPL
lein repl

# Должны увидеть:
# 🎵 Initializing Piper Chorus...
# 🎵 Loading Alba voice with OpenVoice...  (или Turkish voice)

# Если ошибки - проверить:
1. Наличие зависимостей: lein deps :tree | grep pitest
2. Пути к моделям: ls resources/piper-models/
3. Java версию: java -version
```

### Проблема: "❌ sox conversion error"

**Причина:** sox не установлен или неверные параметры

**Решение:**
```bash
# Проверить sox
which sox

# Установить если нет
# macOS: brew install sox
# Linux: apt install sox

# Проверить вручную
sox /tmp/test.wav -r 8000 -c 1 -b 16 /tmp/test-converted.wav
```

### Проблема: Качество турецкого не устраивает для азербайджанского

**Решение 1:** Попробовать другие турецкие голоса

```bash
cd resources/piper-models

# Скачать fahrettin
wget https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/tr/tr_TR/fahrettin/medium/tr_TR-fahrettin-medium.onnx
wget https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/tr/tr_TR/fahrettin/medium/tr_TR-fahrettin-medium.onnx.json

# Скачать fettah
wget https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/tr/tr_TR/fettah/medium/tr_TR-fettah-medium.onnx
wget https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/tr/tr_TR/fettah/medium/tr_TR-fettah-medium.onnx.json

# Изменить в turkish_model.clj имя модели
```

**Решение 2:** Использовать внешний API (Azure/Google)

```clojure
;; Добавить новый case "azure" в generate-final-wav-auto
;; Azure TTS поддерживает az-AZ (азербайджанский)
```

---

## Следующие шаги

1. ✅ Завершить интеграцию (Шаг 3A или 3B)
2. ✅ Протестировать HTTP API
3. ✅ Провести реальный SIP звонок
4. 📊 Собрать feedback от пользователей
5. 🎯 Решить: оставить турецкий или искать альтернативы
6. 🚀 Deploy в продакшн

---

## Полезные команды

```bash
# REPL с автоперезагрузкой
lein repl

# Запуск сервера
lein run

# Сборка uberjar
lein uberjar

# Проверка зависимостей
lein deps :tree | grep pitest

# Очистка кеша
lein clean

# Проверка синтаксиса
lein check
```

---

**Время выполнения чек-листа:**
- Шаг 3A (Alba): 30-60 мин
- Шаг 3B (Turkish): 2-4 часа
- Всего с тестированием: 1-5 часов

**Ожидаемое улучшение качества:**
- espeak (текущий): ★★☆☆☆
- piper (Alba): ★★★☆☆
- piper (Turkish): ★★★★☆

Удачи! 🚀
