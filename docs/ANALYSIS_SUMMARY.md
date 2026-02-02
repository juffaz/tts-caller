# Анализ и рекомендации по интеграции Voices/Piper для азербайджанского TTS

**Дата анализа:** 2026-02-02
**Проект:** tts-caller
**Цель:** Внедрить качественный TTS для азербайджанского языка

---

## Исполнительное резюме

### ✅ Что выяснили

1. **Библиотека Voices** существует и доступна в Maven Central (org.pitest.voices:0.0.9)
2. **Турецкие модели Piper** доступны на HuggingFace (3 голоса: dfki, fahrettin, fettah)
3. **NonEnglishModels** в model-downloader НЕ содержит турецких моделей (только французский и нидерландский)
4. **Азербайджанских моделей Piper** нет в официальном репозитории

### ⚠️ Ключевые выводы

- Турецкую модель придется скачивать и загружать вручную
- Нужно создать кастомный wrapper для Model интерфейса
- Турецкий язык фонетически близок к азербайджанскому (обе тюркские языки)
- Качество будет лучше espeak-ng, но хуже нативного азербайджанского TTS

### 🎯 Рекомендация

**ВНЕДРЯТЬ**, но с учетом ограничений:
- Использовать турецкую модель как fallback для азербайджанского
- Начать с `tr_TR-dfki-medium` (качество/скорость баланс)
- В будущем рассмотреть обучение кастомной азербайджанской модели Piper

---

## Детальный анализ

### 1. Доступные турецкие модели Piper

Источник: https://github.com/rhasspy/piper/blob/master/VOICES.md

| Голос | Качество | Размер | Sample Rate | HuggingFace путь |
|-------|----------|--------|-------------|------------------|
| **dfki** | medium | ~20 MB | 22050 Hz | tr/tr_TR/dfki/medium |
| **fahrettin** | medium | ~20 MB | 22050 Hz | tr/tr_TR/fahrettin/medium |
| **fettah** | medium | ~20 MB | 22050 Hz | tr/tr_TR/fettah/medium |

**Ссылки для скачивания (dfki пример):**
```
https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/tr/tr_TR/dfki/medium/tr_TR-dfki-medium.onnx
https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/tr/tr_TR/dfki/medium/tr_TR-dfki-medium.onnx.json
```

### 2. Поддержка в NonEnglishModels

Источник: https://github.com/hcoles/voices/blob/main/model_downloader/src/main/java/org/pitest/voices/download/NonEnglishModels.java

**Доступные модели:**
- `frFRSiwis()` - французский (vits-piper-fr_FR-siwis-medium)
- `nlNLRonnie()` - нидерландский (vits-piper-nl_NL-ronnie-medium)

**Вывод:** ❌ Турецкого нет, придется создавать кастомный Model

### 3. Альтернативы для азербайджанского

#### Вариант A: Турецкая модель Piper (РЕКОМЕНДУЕТСЯ)
- **Плюсы:**
  - Фонетически близок к азербайджанскому
  - Нейросетевое качество
  - Локальный синтез (без внешних API)
  - 3 голоса на выбор
- **Минусы:**
  - Не идеальная фонетика (отличия в произношении)
  - Нужен кастомный loader для модели
  - Нет готовой интеграции в Voices

**Оценка качества:** 7/10 для азербайджанского текста

#### Вариант B: OpenVoice + английская модель Alba
- **Плюсы:**
  - Готовая интеграция в Voices
  - Многоязычный фонемизатор
  - Простая реализация
- **Минусы:**
  - Качество хуже (английский голос на турецком тексте)
  - Акцент английского
  - Менее естественное звучание

**Оценка качества:** 5/10 для азербайджанского текста

#### Вариант C: Обучить свою азербайджанскую модель Piper
- **Плюсы:**
  - Идеальная фонетика
  - Полный контроль над качеством
- **Минусы:**
  - Требует датасет азербайджанской речи (10+ часов)
  - Время обучения: несколько дней на GPU
  - Сложность: высокая

**Оценка усилий:** Долгосрочный проект (2-4 недели)

#### Вариант D: Внешние API (Azure/Google Cloud TTS)
- **Плюсы:**
  - Есть нативный азербайджанский (az-AZ)
  - Отличное качество
  - Простая интеграция
- **Минусы:**
  - Не локальное решение (зависимость от сети)
  - Стоимость (~$4-16 за 1М символов)
  - Латентность (50-200ms + сеть)

**Оценка качества:** 9/10, но не локальное

---

## Рекомендованное решение: Вариант A (Турецкая модель)

### Этапы реализации

#### Этап 1: Подготовка (30 мин)

```bash
# 1. Проверить Java версию
java -version  # Должна быть ≥17

# 2. Создать папку для моделей
mkdir -p resources/piper-models

# 3. Скачать турецкую модель dfki-medium
cd resources/piper-models
wget https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/tr/tr_TR/dfki/medium/tr_TR-dfki-medium.onnx
wget https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/tr/tr_TR/dfki/medium/tr_TR-dfki-medium.onnx.json
```

#### Этап 2: Добавить зависимости (5 мин)

**Изменить project.clj:**

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
  [org.pitest.voices/en_uk "0.0.9"]                    ; словарь (обязателен)
  [org.pitest.voices/openvoice-phonemizer "0.0.9"]    ; многоязычный фонемизатор
  [com.microsoft.onnxruntime/onnxruntime "1.20.0"]    ; ONNX Runtime
]
```

**Установить зависимости:**

```bash
lein deps
```

#### Этап 3: Создать Model wrapper (1-2 часа)

**Создать файл:** `src/tts_caller/turkish_model.clj`

```clojure
(ns tts-caller.turkish-model
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [org.pitest.voices Model ModelConfig Language VoiceSession]
           [java.nio.file Path Paths Files]
           [ai.onnxruntime OrtEnvironment OrtSession OrtSession$SessionOptions]))

;; Простейший способ: использовать внутренний FileModel из Voices
;; (если доступен как public или через рефлексию)
;;
;; Альтернатива: реализовать полностью Model интерфейс

(defn make-turkish-model
  "Создает турецкую модель для Piper"
  [model-name]
  (let [base-path "resources/piper-models"
        onnx-file (str base-path "/" model-name ".onnx")
        json-file (str base-path "/" model-name ".onnx.json")]
    ;; TODO: реализовать Model интерфейс
    ;; Варианты:
    ;; 1. Использовать FileModel из org.pitest.voices.download (если public)
    ;; 2. Реализовать кастомный Model
    ;; 3. Изучить исходники NonEnglishModels и скопировать паттерн
    ))

;; ВРЕМЕННОЕ РЕШЕНИЕ: использовать рефлексию для доступа к FileModel
(defn load-turkish-model []
  (try
    ;; Попытка использовать FileModel (если доступен)
    (let [file-model-class (Class/forName "org.pitest.voices.download.FileModel")
          constructor (.getDeclaredConstructor file-model-class
                                                (into-array Class [String String String Language Float]))
          _ (.setAccessible constructor true)]
      (.newInstance constructor
                    (object-array ["tr-TR-dfki-medium"
                                   "resources/piper-models/tr_TR-dfki-medium.onnx"
                                   "resources/piper-models/tr_TR-dfki-medium.onnx.json"
                                   Language/OTHER  ; или создать TURKISH если есть
                                   (float 1.0)])))
    (catch Exception e
      (println "❌ Failed to load FileModel, need custom implementation:" (.getMessage e))
      nil)))
```

**ВАЖНО:** Это упрощенная версия. В реальности нужно изучить исходники FileModel и ModelConfig.

#### Этап 4: Интегрировать в audio.clj (1 час)

**Добавить в начало audio.clj:**

```clojure
(ns tts-caller.audio
  (:require [clojure.java.shell :refer [sh]]
            [tts-caller.turkish-model :as tr-model])  ; НОВОЕ
  (:import [javax.sound.sampled AudioFormat AudioInputStream AudioSystem AudioFileFormat$Type]
           [java.io ByteArrayInputStream File]
           [org.w3c.dom Document]
           [javax.xml.parsers DocumentBuilderFactory]
           ;; НОВЫЕ импорты
           [org.pitest.voices Chorus ChorusConfig Audio Voice]
           [org.pitest.voices.dictionaries EnUkDictionary Dictionaries]
           [org.pitest.voices.openvoice OpenVoiceSupplier]
           [java.nio.file Paths]))

;; ... существующий код ts-println, voice и т.д. ...

;; Глобальная инициализация Chorus
(defonce ^:private piper-chorus
  (delay
    (try
      (let [config (-> (ChorusConfig/chorusConfig (Dictionaries/empty))
                       (.withModel (OpenVoiceSupplier.)))]
        (ts-println "🎵 Initializing Piper Chorus...")
        (Chorus. config))
      (catch Exception e
        (ts-println "❌ Failed to initialize Chorus:" (.getMessage e))
        nil))))

;; Глобальный турецкий голос
(defonce ^:private turkish-voice
  (delay
    (try
      (when-let [chorus @piper-chorus]
        (let [model (tr-model/load-turkish-model)]
          (when model
            (ts-println "🎵 Loading Turkish voice...")
            (-> (.voice chorus model)
                (.withSpeed 1.0)
                (.withGain 1.0)))))
      (catch Exception e
        (ts-println "❌ Failed to load Turkish voice:" (.getMessage e))
        nil))))

;; Функция генерации аудио через Piper
(defn generate-audio-bytes-piper
  "Генерирует аудио через Piper/Voices (турецкий голос)"
  [text & {:keys [speed gain] :or {speed 1.0 gain 1.0}}]
  (if-let [voice @turkish-voice]
    (try
      ;; Синхронизация для потокобезопасности
      (locking piper-chorus
        (let [voice (-> voice
                        (.withSpeed (float speed))
                        (.withGain (float gain)))
              ^Audio audio (.say voice text)]
          ;; Получить byte[] (WAV с заголовком)
          (.asBytes audio)))
      (catch Exception e
        (ts-println "❌ Piper synthesis error:" (.getMessage e))
        (throw e)))
    (do
      (ts-println "❌ Turkish voice not initialized")
      (throw (ex-info "Turkish voice not available" {})))))
```

**Добавить case "piper" в generate-final-wav-auto:**

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
      ;; ... существующие case "espeak" и "marytts" ...

      ;; НОВЫЙ CASE
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
          ;; Удалить временный файл
          (.delete (File. tmp1))))

      ;; Дефолт
      (ts-println "⚠️ Unknown TTS engine:" tts-engine))

    (ts-println "✅ Аудио файл создан:" outfile)))
```

#### Этап 5: Тестирование (30 мин - 1 час)

**5.1 REPL тест**

```clojure
lein repl

(require '[tts-caller.audio :as audio])

;; Тест с турецким текстом
(audio/generate-final-wav-auto
  "Merhaba, bu bir test mesajıdır."
  "/tmp/piper-turkish.wav"
  :tts-engine "piper")

;; Прослушать (macOS)
;; sh "open" "/tmp/piper-turkish.wav"

;; Тест с азербайджанским текстом (латиница)
(audio/generate-final-wav-auto
  "Salam, Sphere və Atlas işləmir!"
  "/tmp/piper-azerbaijani.wav"
  :tts-engine "piper")
```

**5.2 HTTP API тест**

```bash
# Запустить сервер
lein run

# В другом терминале
curl "http://localhost:8899/call?text=Salam%20d%C3%BCnya&phone=1001&engine=piper&repeat=3"

# Проверить создание файла
ls -lh /tmp/final_batch_*.wav
```

**5.3 Сравнение качества**

```bash
# Сгенерировать тот же текст разными движками
curl "http://localhost:8899/call?text=Salam&phone=1001&engine=espeak" &
curl "http://localhost:8899/call?text=Salam&phone=1001&engine=piper" &

# Сравнить качество звука
```

---

## Сложности и решения

### Проблема 1: FileModel не public или отсутствует

**Симптом:** ClassNotFoundException или IllegalAccessException

**Решение 1:** Полностью реализовать Model интерфейс

```clojure
(defrecord TurkishModel [id onnx-bytes config-map]
  Model
  (id [_] id)
  (sid [_] -1)
  (language [_] Language/OTHER)
  (withLanguage [this lang] (assoc this :language lang))
  (asBytes [_ cache-base] onnx-bytes)
  (resolveConfig [_ cache-base]
    ;; Создать ModelConfig из config-map
    ;; TODO: изучить структуру ModelConfig
    )
  ;; ... остальные методы
  )
```

**Решение 2:** Скопировать паттерн из NonEnglishModels

- Изучить исходники `frFRSiwis()` и адаптировать для турецкого
- Использовать те же sherpa-onnx паттерны

### Проблема 2: ModelConfig сложная структура

**Решение:** Парсить из .onnx.json

```clojure
(defn parse-model-config [json-path]
  (let [config (json/read-str (slurp json-path) :key-fn keyword)]
    ;; Маппинг JSON → ModelConfig объект
    ;; Пример структуры JSON:
    ;; {
    ;;   "audio": {"sample_rate": 22050, ...},
    ;;   "espeak": {"voice": "tr"},
    ;;   "inference": {...},
    ;;   "num_speakers": 1,
    ;;   ...
    ;; }
    config))
```

### Проблема 3: Потокобезопасность Chorus

**Решение:** Использовать `locking` (уже добавлено в коде выше)

```clojure
(locking piper-chorus
  ;; Все операции с chorus/voice здесь
  )
```

### Проблема 4: Java версия < 17

**Проверка:**
```bash
java -version
```

**Решение:** Обновить JDK или использовать SDKMAN

```bash
# Установить SDKMAN
curl -s "https://get.sdkman.io" | bash

# Установить Java 17+
sdk install java 17.0.9-tem

# Использовать
sdk use java 17.0.9-tem
```

---

## Оценка усилий и рисков

### Временные затраты

| Этап | Минимум | Максимум | Комментарий |
|------|---------|----------|-------------|
| Подготовка | 30 мин | 1 час | Скачивание моделей, зависимостей |
| Зависимости | 5 мин | 15 мин | project.clj, lein deps |
| Model wrapper | 1 час | 4 часа | Зависит от сложности FileModel |
| Интеграция audio.clj | 1 час | 2 часа | Добавление кода |
| Тестирование | 30 мин | 2 часа | REPL, HTTP, SIP тесты |
| Отладка | 1 час | 4 часа | Непредвиденные проблемы |
| **ИТОГО** | **4 часа** | **13 часов** | **1-2 рабочих дня** |

### Риски

| Риск | Вероятность | Влияние | Митигация |
|------|-------------|---------|-----------|
| FileModel недоступен | Высокая | Среднее | Полная реализация Model интерфейса |
| ModelConfig сложная | Средняя | Высокое | Изучить исходники NonEnglishModels |
| Качество турецкого не устраивает | Низкая | Высокое | Fallback на espeak или внешние API |
| Java версия < 17 | Низкая | Среднее | Обновить JDK |
| Потокобезопасность | Средняя | Среднее | Использовать locking |

---

## Альтернативный быстрый старт (если Model wrapper сложный)

Если реализация кастомного Model займет слишком много времени, можно использовать **OpenVoice + Alba**:

```clojure
;; В audio.clj - упрощенная версия
(defonce ^:private piper-chorus
  (delay
    (let [config (-> (ChorusConfig/chorusConfig (EnUkDictionary/en_uk))
                     (.withModel (OpenVoiceSupplier.)))]
      (Chorus. config))))

(defonce ^:private alba-voice
  (delay
    (let [chorus @piper-chorus]
      (.voice chorus (Alba/albaMedium)))))

(defn generate-audio-bytes-piper [text & opts]
  (locking piper-chorus
    (let [voice @alba-voice
          ^Audio audio (.say voice text)]
      (.asBytes audio))))
```

**Плюсы:**
- Работает из коробки (5 минут)
- Не нужен кастомный Model

**Минусы:**
- Качество хуже (английский акцент на турецком/азербайджанском)
- Оценка качества: 5/10 вместо 7/10

---

## Долгосрочная стратегия

### Фаза 1 (немедленно): Турецкая модель Piper
- Реализовать интеграцию как описано выше
- Использовать `tr_TR-dfki-medium`
- Оценить качество на реальных пользователях

### Фаза 2 (1-2 месяца): Оптимизация
- Протестировать все 3 турецких голоса (dfki, fahrettin, fettah)
- Выбрать лучший по качеству/скорости
- Настроить параметры speed/gain для оптимального звучания

### Фаза 3 (3-6 месяцев): Кастомная азербайджанская модель
- Собрать датасет азербайджанской речи (10-20 часов)
- Обучить модель Piper на азербайджанском
- Интегрировать как `az_AZ-custom-medium`

### Фаза 4 (опционально): Гибридный подход
- Турецкий Piper для общих фраз
- Azure/Google TTS для критичных сообщений (если требуется идеальное качество)
- Кеширование частых фраз

---

## Заключение

### ✅ Рекомендуется внедрить Voices/Piper

**Причины:**
1. Значительное улучшение качества (espeak: ★★☆☆☆ → piper: ★★★★☆)
2. Локальное решение (нет зависимости от внешних API)
3. Турецкий язык достаточно близок к азербайджанскому для приемлемого качества
4. Умеренные затраты на разработку (1-2 дня)
5. Путь к долгосрочному решению (кастомная модель)

### ⚠️ С учетом ограничений

1. Нужен кастомный Model wrapper (сложность: средняя)
2. Качество не идеально (турецкий ≠ азербайджанский)
3. Требуется Java 17+
4. Потокобезопасность требует синхронизации

### 🎯 Следующие шаги

1. **Проверить Java версию** на продакшн сервере
2. **Скачать турецкую модель** (начать с dfki-medium)
3. **Реализовать Model wrapper** (или использовать Alba временно)
4. **Протестировать на азербайджанском тексте**
5. **Собрать feedback от пользователей**
6. **Принять решение:** внедрять или искать альтернативы

---

## Полезные ссылки

- [Voices GitHub](https://github.com/hcoles/voices)
- [Piper Voices (модели)](https://github.com/rhasspy/piper/blob/master/VOICES.md)
- [HuggingFace Turkish models](https://huggingface.co/rhasspy/piper-voices/tree/main/tr/tr_TR)
- [Piper Samples (послушать)](https://rhasspy.github.io/piper-samples/)
- [ONNX Runtime Java](https://onnxruntime.ai/docs/get-started/with-java.html)
- [Turkish TTS resources](https://github.com/Rumeysakeskin/free-turkish-tts-models)

---

**Контакт для вопросов:** [Ваш контакт]
**Версия документа:** 1.0
**Последнее обновление:** 2026-02-02
