(ns tts-caller.audio
  (:require [clojure.java.shell :refer [sh]])
  (:import [javax.sound.sampled AudioFormat AudioInputStream AudioSystem AudioFileFormat$Type]
           [java.io ByteArrayInputStream File]
           [org.w3c.dom Document]
           [javax.xml.parsers DocumentBuilderFactory]))

;; Функция для логирования с временной меткой
(defn ts-println [& args]
  (let [ts (java.time.LocalDateTime/now)
        formatted-ts (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss") ts)]
    (println formatted-ts (clojure.string/join " " args))))

;; Выбор голоса по умолчанию
(def voice "cmu-slt-hsmm")
;; Доступные альтернативы:
;; (def voice "dfki-ot-hsmm")
;; (def voice "ac-irina-hsmm")

(defn create-mary []
  "Создаёт экземпляр локального движка MaryTTS"
  (let [cls (Class/forName "marytts.LocalMaryInterface")
        ctor (.getConstructor cls (into-array Class []))]
    (.newInstance ctor (object-array []))))

(defn list-voices []
  "Выводит список доступных голосов"
  (let [mary (create-mary)
        voices (.getAvailableVoices mary)]
    (ts-println "Доступные голоса:" (clojure.string/join ", " voices))))

(defn ssml->document [^String ssml]
  "Преобразует SSML-строку в DOM-документ"
  (let [factory (DocumentBuilderFactory/newInstance)
        builder (.newDocumentBuilder factory)
        is (ByteArrayInputStream. (.getBytes ssml "UTF-8"))]
    (.parse builder is)))

(defn generate-audio-bytes-plain [text voice]
  "Генерирует аудио из обычного текста"
  (let [mary (create-mary)
        _ (.setVoice mary voice)
        _ (.setAudioEffects mary "Rate(durScale:1.5)")
        audio (.generateAudio mary text)]
    (with-open [ais audio]
      (let [buffer (byte-array 1024)
            out (java.io.ByteArrayOutputStream.)]
        (loop [n (.read ais buffer)]
          (when (pos? n)
            (.write out buffer 0 n)
            (recur (.read ais buffer))))
        (.toByteArray out)))))

(defn generate-audio-bytes-ssml [ssml voice]
  "Генерирует аудио из SSML"
  (let [mary (create-mary)
        set-voice (.getMethod (class mary) "setVoice" (into-array Class [String]))
        set-input-type (.getMethod (class mary) "setInputType" (into-array Class [String]))
        set-output-type (.getMethod (class mary) "setOutputType" (into-array Class [String]))
        generate-audio (.getMethod (class mary) "generateAudio" (into-array Class [Document]))
        doc (ssml->document ssml)]
    (.invoke set-voice mary (object-array [voice]))
    (.invoke set-input-type mary (object-array ["RAWMARYXML"]))
    (.invoke set-output-type mary (object-array ["AUDIO"]))
    (with-open [ais (.invoke generate-audio mary (object-array [doc]))]
      (let [ais ^AudioInputStream ais
            buffer (byte-array 1024)
            out (java.io.ByteArrayOutputStream.)]
        (loop [n (.read ais buffer)]
          (when (pos? n)
            (.write out buffer 0 n)
            (recur (.read ais buffer))))
        (.toByteArray out)))))

(defn silence-bytes [millis format]
  "Генерирует байты тишины"
  (let [bytes-per-ms (* (/ (.getSampleRate format) 1000)
                        (/ (.getSampleSizeInBits format) 8)
                        (.getChannels format))]
    (byte-array (* millis bytes-per-ms))))

(defn concat-audio-streams [streams format]
  "Объединяет несколько аудиопотоков"
  (let [total-bytes (apply concat (map vec streams))]
    (AudioInputStream.
     (ByteArrayInputStream. (byte-array total-bytes))
     format
     (long (/ (count total-bytes)
              (* (.getSampleSizeInBits format) 0.125 (.getChannels format)))))))

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
            (ts-println "❌ sox error:" err)))))

    (ts-println "✅ Аудио файл создан:" outfile)))

;; Примеры использования (раскомментируйте при необходимости)
(comment
  (generate-final-wav-auto "Salam, Sphere və Atlas işləmir!" "/tmp/plain.wav")
  (generate-final-wav-auto "<speak><prosody rate='fast'>Sphere və Atlas işləmir.</prosody></speak>" "/tmp/voice.wav")
  )
