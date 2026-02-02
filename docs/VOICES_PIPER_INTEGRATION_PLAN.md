# План интеграции Voices/Piper в tts-caller

## Текущее состояние проекта

### Архитектура
- **Платформа:** Clojure на Leiningen
- **HTTP сервер:** Ring/Jetty на порту 8899
- **SIP вызовы:** baresip (внешний процесс)
- **Основные файлы:**
  - `project.clj` - зависимости и конфигурация
  - `src/tts_caller/audio.clj` - TTS движки
  - `src/tts_caller/core.clj` - HTTP API и SIP логика

### Текущие TTS движки

**1. MaryTTS** (строки 112-125 в audio.clj)
- Загрузка через reflection (`Class/forName "marytts.LocalMaryInterface"`)
- Поддержка SSML
- Sample rate: 16kHz → sox конвертирует в 8kHz mono
- Голоса: английский/немецкий/русский (нет азербайджанского)
- Зависимости: в папке `lib/` (не в project.clj)

**2. espeak-ng** (строки 101-110 в audio.clj)
- Внешний процесс через shell
- Турецкий голос: `espeak-ng -v tr -s 140`
- Sample rate: напрямую 8kHz через sox
- Качество: синтетическое, но быстрое

### API для вызова
```clojure
(audio/generate-final-wav-auto
  text
  outfile
  :tts-engine "marytts"  ; или "espeak"
  :repeat 3
  :voice "dfki-ot-hsmm"
  :gain 0.0)
```

HTTP endpoint:
```
GET /call?text=Salam&phone=1001&engine=marytts&repeat=3
```

---

## Почему Voices/Piper?

### Преимущества

1. **Нейросетевое качество**
   - Piper: быстрые модели (CPU ~50-200ms на фразу)
   - Kokoro: высокое качество (медленнее в 4x)
   - Значительно лучше espeak-ng

2. **Многоязычность**
   - 40+ языков через Piper models
   - Турецкие модели (близки к азербайджанскому)
   - Возможность кастомных моделей

3. **Чистая JVM**
   - Только зависимость: ONNX Runtime Java
   - Нет JNI кроме ONNX (стабильно)
   - Maven Central (org.pitest.voices:0.0.9)

4. **Легковесность**
   - Модели: 5-32 MB
   - Нет внешних сервисов
   - Локальный синтез

### Недостатки

1. **Нет готовых азербайджанских моделей**
   - Решение: использовать турецкую модель (фонетика похожа)
   - Альтернатива: обучить свою модель Piper

2. **Java 17+**
   - Проверить версию JDK в проекте

3. **Не потокобезопасно**
   - Нужен единый экземпляр Chorus + синхронизация
   - Или один Chorus на запрос (медленнее)

---

## План имплементации

### Шаг 1: Добавить зависимости в project.clj

**Минимальный набор:**
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

  ;; НОВЫЕ: Voices/Piper
  [org.pitest.voices/chorus "0.0.9"]
  [org.pitest.voices/en_uk "0.0.9"]              ; словарь (нужен даже для неанглийских)
  [org.pitest.voices/model-downloader "0.0.9"]  ; для загрузки Piper моделей
  [org.pitest.voices/openvoice-phonemizer "0.0.9"]  ; многоязычный фонемизатор (50MB)
  [com.microsoft.onnxruntime/onnxruntime "1.20.0"]
]
```

**Опциональные (если нужно качество Kokoro):**
```clojure
[org.pitest.voices/kokoro-runtime "0.0.9"]  ; 11 английских голосов, лучше качество
```

**Для GPU (если есть CUDA):**
```clojure
;; Заменить onnxruntime на:
[com.microsoft.onnxruntime/onnxruntime_gpu "1.20.0"]
;; И использовать ChorusConfig/gpuChorusConfig
```

---

### Шаг 2: Скачать модель Piper для турецкого/азербайджанского

#### Вариант A: Турецкая модель (рекомендуется для начала)

Источник: https://huggingface.co/rhasspy/piper-voices/tree/main/tr/tr_TR

**Доступные модели:**
- `tr_TR-dfki-medium.onnx` (22.05kHz, ~20MB)
- `tr_TR-dfki-high.onnx` (22.05kHz, ~32MB, лучше качество)

**Скачать:**
```bash
mkdir -p resources/piper-models
cd resources/piper-models

# Скачать модель
wget https://huggingface.co/rhasspy/piper-voices/resolve/main/tr/tr_TR/dfki/medium/tr_TR-dfki-medium.onnx
wget https://huggingface.co/rhasspy/piper-voices/resolve/main/tr/tr_TR/dfki/medium/tr_TR-dfki-medium.onnx.json

# Или high качество
wget https://huggingface.co/rhasspy/piper-voices/resolve/main/tr/tr_TR/dfki/high/tr_TR-dfki-high.onnx
wget https://huggingface.co/rhasspy/piper-voices/resolve/main/tr/tr_TR/dfki/high/tr_TR-dfki-high.onnx.json
```

#### Вариант B: Поиск азербайджанской модели

```bash
# Поиск на HuggingFace
# Возможные репозитории:
# - rhasspy/piper-voices (официальный, нет az)
# - пользовательские загрузки (искать "azerbaijani piper" или "azərbaycan tts")
```

Если найдется - использовать те же шаги.

#### Вариант C: model-downloader (программно)

```clojure
;; В audio.clj можно добавить:
(import '[org.pitest.voices.downloader NonEnglishModels])

;; Проверить доступные модели:
;; (методы в NonEnglishModels для разных языков)
```

---

### Шаг 3: Создать wrapper для кастомной Piper модели

Нужно реализовать интерфейс `org.pitest.voices.Model` для загрузки скачанной турецкой модели.

**Создать файл:** `src/tts_caller/piper_model.clj`

```clojure
(ns tts-caller.piper-model
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json])
  (:import [org.pitest.voices Model ModelConfig Language VoiceSession]
           [java.nio.file Path Paths Files]
           [java.nio.charset StandardCharsets]
           [ai.onnxruntime OrtEnvironment OrtSession]))

(defrecord CustomPiperModel [model-id onnx-path json-path language default-gain]
  Model

  (id [this] model-id)

  (sid [this] -1)  ; Single speaker

  (language [this] language)

  (withLanguage [this lang]
    (assoc this :language lang))

  (asBytes [this cache-base]
    ;; Читаем .onnx файл как byte[]
    (Files/readAllBytes (Paths/get onnx-path (into-array String []))))

  (resolveConfig [this cache-base]
    ;; Читаем .onnx.json и парсим в ModelConfig
    (let [json-str (slurp json-path)
          config-map (json/read-str json-str :key-fn keyword)]
      ;; Создать ModelConfig из JSON
      ;; TODO: реализовать маппинг JSON → ModelConfig
      ;; (это самая сложная часть - нужно изучить структуру ModelConfig класса)
      ))

  (defaultGain [this] default-gain)

  ;; Остальные методы делегируем дефолтной реализации или Piper-based классу
  ;; (createVoice, createSession - обычно не переопределяют напрямую)
  )

(defn make-turkish-model
  "Создает модель для турецкого голоса"
  []
  (->CustomPiperModel
    "tr-TR-dfki-medium"
    "resources/piper-models/tr_TR-dfki-medium.onnx"
    "resources/piper-models/tr_TR-dfki-medium.onnx.json"
    Language/TURKISH  ; если есть в enum, иначе Language/OTHER
    1.0))
```

**ПРОБЛЕМА:** Интерфейс `Model` сложный, нужны детали класса `ModelConfig`.

**АЛЬТЕРНАТИВА (проще):** Использовать `model-downloader` и `NonEnglishModels`, если там есть турецкий. Проверим в коде Voices.

---

### Шаг 4: Инициализировать Chorus в audio.clj

**Добавить в начало файла:**

```clojure
(ns tts-caller.audio
  (:require [clojure.java.shell :refer [sh]])
  (:import [javax.sound.sampled AudioFormat AudioInputStream AudioSystem AudioFileFormat$Type]
           [java.io ByteArrayInputStream File]
           [org.w3c.dom Document]
           [javax.xml.parsers DocumentBuilderFactory]
           ;; НОВЫЕ импорты для Voices
           [org.pitest.voices Chorus ChorusConfig Audio Voice]
           [org.pitest.voices.dictionaries EnUkDictionary Dictionaries]
           [org.pitest.voices.openvoice OpenVoiceSupplier]
           [java.nio.file Paths]))

;; ...существующий код...

;; Глобальный Chorus (инициализируется один раз)
;; ВАЖНО: Chorus НЕ потокобезопасен! Нужна синхронизация
(defonce ^:private piper-chorus
  (delay
    (let [;; Используем пустой словарь + OpenVoice для многоязычности
          config (-> (ChorusConfig/chorusConfig (Dictionaries/empty))
                     (.withModel (OpenVoiceSupplier.)))]
      (Chorus. config))))

;; Для турецкой модели нужно создать Voice
;; Используем model-downloader или кастомную реализацию
(defonce ^:private turkish-voice
  (delay
    (let [chorus @piper-chorus
          ;; TODO: загрузить турецкую модель
          ;; model (NonEnglishModels/trTR...)  ; если есть в NonEnglishModels
          ;; ИЛИ использовать кастомную реализацию
          ;; model (piper-model/make-turkish-model)
          ]
      ;; (.voice chorus model)
      nil  ; временно, пока не реализуем загрузку модели
      )))
```

---

### Шаг 5: Добавить функцию generate-audio-bytes-piper

**Добавить после существующих generate-audio-bytes-* функций:**

```clojure
(defn generate-audio-bytes-piper
  "Генерирует аудио через Piper/Voices"
  [text & {:keys [speed gain] :or {speed 1.0 gain 1.0}}]
  (try
    (let [voice @turkish-voice
          ;; Настройка параметров
          voice (-> voice
                    (.withSpeed (float speed))
                    (.withGain (float gain)))
          ;; Синтез
          ^Audio audio (.say voice text)
          ;; Конвертация в byte[]
          audio-bytes (.asBytes audio)]
      ;; Audio.asBytes() возвращает WAV с заголовком (22.05kHz, 16-bit mono)
      audio-bytes)
    (catch Exception e
      (ts-println "❌ Piper synthesis error:" (.getMessage e))
      (throw e))))

;; Альтернатива: получить float[] samples и самим создать AudioInputStream
(defn generate-audio-bytes-piper-raw
  "Генерирует аудио через Piper, возвращает raw PCM"
  [text & {:keys [speed gain] :or {speed 1.0 gain 1.0}}]
  (let [voice @turkish-voice
        voice (-> voice
                  (.withSpeed (float speed))
                  (.withGain (float gain)))
        ^Audio audio (.say voice text)
        ;; Получаем raw samples (float[])
        samples (.getSamples audio)
        sample-rate (.getSampleRate audio)  ; обычно 22050
        ;; Конвертируем float[] в byte[] (16-bit PCM)
        byte-buffer (java.nio.ByteBuffer/allocate (* (alength samples) 2))
        _ (.order byte-buffer java.nio.ByteOrder/LITTLE_ENDIAN)]
    (doseq [sample samples]
      ;; Конвертация float [-1.0, 1.0] → short [-32768, 32767]
      (let [short-val (short (Math/max -32768 (Math/min 32767 (* sample 32767.0))))]
        (.putShort byte-buffer short-val)))
    {:bytes (.array byte-buffer)
     :sample-rate sample-rate
     :format (AudioFormat. sample-rate 16 1 true false)}))
```

---

### Шаг 6: Добавить case "piper" в generate-final-wav-auto

**Изменить функцию generate-final-wav-auto (после строки 100):**

```clojure
(defn generate-final-wav-auto
  "Генерирует финальный WAV файл (8kHz, mono) для вызова"
  [text outfile & {:keys [tts-engine repeat voice rate gain]
                   :or {tts-engine "marytts"
                        repeat 3
                        voice "dfki-ot-hsmm"
                        rate "default"
                        gain 0.0}}]
  (let [tmp "/tmp/generated.wav"]
    (case tts-engine
      "espeak"
      (do
        (let [{:keys [exit err]} (sh "bash" "-c"
                                     (format "espeak-ng -v tr -s 140 \"%s\" --stdout | sox -t wav - -r 8000 -c 1 -b 16 %s gain %s"
                                             text tmp gain))]
          (when-not (zero? exit)
            (ts-println "❌ espeak error:" err)))
        (let [{:keys [exit err]} (sh "sox" tmp "-r" "8000" "-c" "1" outfile)]
          (when-not (zero? exit)
            (ts-println "❌ sox error:" err))))

      "marytts"
      (let [ssml? (.startsWith text "<speak>")
            format (AudioFormat. 16000 16 1 true false)
            audio-bytes (if ssml?
                          (generate-audio-bytes-ssml text voice)
                          (generate-audio-bytes-plain text voice))
            silence-start (silence-bytes 1500 format)
            silence-end (silence-bytes 500 format)
            full (concat-audio-streams [silence-start audio-bytes silence-end] format)
            tmp1 (str outfile ".tmp.wav")]
        (AudioSystem/write full AudioFileFormat$Type/WAVE (File. tmp1))
        (let [{:keys [exit err]} (sh "sox" tmp1 "-r" "8000" "-c" "1" outfile)]
          (when-not (zero? exit)
            (ts-println "❌ sox error:" err))))

      ;; НОВЫЙ CASE для Piper/Voices
      "piper"
      (let [;; Синтез через Piper (возвращает WAV @ 22.05kHz)
            audio-bytes (generate-audio-bytes-piper text
                                                     :speed (if (= rate "fast") 1.3 1.0)
                                                     :gain (if (zero? gain) 1.0 gain))
            ;; Сохраняем временный файл
            tmp1 (str outfile ".tmp.wav")]
        ;; Сохранить WAV
        (with-open [out (java.io.FileOutputStream. tmp1)]
          (.write out audio-bytes))

        ;; Конвертация sox: 22050Hz → 8000Hz mono
        (let [{:keys [exit err]} (sh "sox" tmp1 "-r" "8000" "-c" "1" "-b" "16" outfile)]
          (when-not (zero? exit)
            (ts-println "❌ sox error after piper:" err))
          ;; Удалить временный файл
          (.delete (File. tmp1))))

      ;; Дефолт
      (ts-println "⚠ Unknown TTS engine:" tts-engine))

    (ts-println "✅ Аудио файл создан:" outfile)))
```

---

### Шаг 7: Тестирование

#### 7.1 Локальный тест в REPL

```clojure
;; Запустить REPL: lein repl

(require '[tts-caller.audio :as audio])

;; Тест с турецким текстом (близко к азербайджанскому)
(audio/generate-final-wav-auto
  "Merhaba, bu bir test mesajıdır."
  "/tmp/piper-test.wav"
  :tts-engine "piper")

;; Послушать: aplay /tmp/piper-test.wav (Linux) или open /tmp/piper-test.wav (macOS)

;; Азербайджанский текст
(audio/generate-final-wav-auto
  "Salam, Sphere və Atlas işləmir!"
  "/tmp/piper-az-test.wav"
  :tts-engine "piper")
```

#### 7.2 Тест через HTTP API

```bash
# Запустить сервер
lein run

# В другом терминале:
curl "http://localhost:8899/call?text=Salam%20d%C3%BCnya&phone=1001&engine=piper&repeat=3"

# Проверить логи сервера и файл /tmp/final_batch_*.wav
```

#### 7.3 Тест с реальным SIP звонком

```bash
# Полный end-to-end тест
curl "http://localhost:8899/call?text=Sphere%20v%C9%99%20Atlas%20i%C5%9Fl%C9%99mir&phone=1001&engine=piper&repeat=3"

# Должен:
# 1. Сгенерировать WAV через Piper (турецкий голос)
# 2. Конвертировать в 8kHz mono
# 3. Позвонить через baresip на 1001
```

---

## Возможные проблемы и решения

### Проблема 1: Не найден турецкий голос в model-downloader

**Решение:** Скачать вручную с HuggingFace и реализовать кастомный `Model` wrapper.

**Альтернатива:** Использовать английский голос с OpenVoice фонемизатором (он умеет обрабатывать разные языки, но качество будет хуже).

```clojure
;; В turkish-voice использовать Alba вместо турецкой модели
(defonce ^:private turkish-voice
  (delay
    (let [chorus @piper-chorus]
      (.voice chorus (Alba/albaMedium)))))  ; английский, но с OpenVoice может работать на турецком
```

### Проблема 2: ModelConfig не создается из JSON

**Решение:** Изучить исходники Voices, посмотреть как NonEnglishModels создает модели.

Пример из исходников (если найдем):
```java
// Из NonEnglishModels.java
public static Model nlNLRonnie() {
    return PiperModel.builder()
        .id("nl_NL-rdh-medium")
        .language(Language.DUTCH)
        .onnxPath("...")
        .configPath("...")
        .build();
}
```

Адаптировать для турецкого.

### Проблема 3: Chorus не потокобезопасен

**Симптомы:** Concurrent modification exceptions при параллельных запросах.

**Решение 1:** Синхронизация через `locking`
```clojure
(defn generate-audio-bytes-piper [text & opts]
  (locking piper-chorus
    (let [voice @turkish-voice
          ;; ... rest of code
          ])))
```

**Решение 2:** Создавать новый Chorus для каждого запроса (медленнее)
```clojure
(defn generate-audio-bytes-piper [text & opts]
  (let [config (ChorusConfig/chorusConfig (Dictionaries/empty))]
    (with-open [chorus (Chorus. config)]
      ;; ... использовать chorus
      )))
```

**Решение 3:** Пул из N экземпляров Chorus (сложнее, но оптимально)

### Проблема 4: Java версия < 17

```bash
# Проверить:
java -version

# Если < 17, обновить JDK или использовать старую версию Voices (если есть)
```

### Проблема 5: Качество турецкого голоса не подходит для азербайджанского

**Решение:**
1. Попробовать разные турецкие модели (high quality)
2. Обучить свою Piper модель на азербайджанском датасете
3. Использовать другой TTS (Azure TTS, Google Cloud TTS есть азербайджанский, но не локальные)

---

## Оценка изменений

### Затронутые файлы

1. **project.clj** - добавить 5-7 новых зависимостей
2. **src/tts_caller/audio.clj** - добавить ~100 строк (imports, chorus init, generate-audio-bytes-piper, новый case)
3. **src/tts_caller/piper_model.clj** (опционально) - новый файл ~100 строк, если нужен кастомный Model
4. **resources/piper-models/** - папка с моделями (~20-32 MB на модель)

### Размер изменений
- Новые зависимости: ~80-150 MB (ONNX Runtime + Voices + модели)
- Новый код: ~100-200 строк Clojure
- Время разработки: 4-8 часов (с тестированием)

### Обратная совместимость
✅ Полная - старые движки ("marytts", "espeak") работают как прежде.

---

## Следующие шаги

1. **Проверить Java версию** - должна быть 17+
2. **Добавить зависимости** в project.clj
3. **Скачать турецкую модель** (или найти азербайджанскую)
4. **Изучить NonEnglishModels** в Voices - возможно, турецкий уже есть
5. **Реализовать инициализацию Chorus** в audio.clj
6. **Добавить case "piper"** в generate-final-wav-auto
7. **Протестировать** на турецком/азербайджанском тексте
8. **Сравнить качество** с espeak-ng

---

## Полезные ссылки

- GitHub Voices: https://github.com/hcoles/voices
- Piper Voices на HuggingFace: https://huggingface.co/rhasspy/piper-voices
- Piper Samples (прослушать голоса): https://rhasspy.github.io/piper-samples/
- ONNX Runtime Java: https://onnxruntime.ai/docs/get-started/with-java.html
- InfoQ статья о Voices: https://www.infoq.com/news/2025/11/voices-text-to-speech/

---

## Резюме

**Выгода:**
- Значительно лучше качество чем espeak-ng
- Локальный синтез без внешних сервисов
- Поддержка турецкого (близок к азербайджанскому)
- Чистая JVM интеграция

**Риски:**
- Нет готового азербайджанского голоса (используем турецкий)
- Нужно разобраться с загрузкой кастомных моделей
- Потокобезопасность требует синхронизации

**Рекомендация:** Начать с интеграции турецкой модели через model-downloader (если есть) или скачанной вручную. Протестировать качество на азербайджанском тексте. Если устроит - внедрить в продакшн.
