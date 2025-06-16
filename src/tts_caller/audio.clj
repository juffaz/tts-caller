(ns tts-caller.audio
  (:import [javax.sound.sampled AudioFormat AudioInputStream AudioSystem AudioFileFormat$Type]
           [java.io ByteArrayInputStream File]
           [org.w3c.dom Document]
           [javax.xml.parsers DocumentBuilderFactory]))

;; ✅ Установи нужный голос здесь:


(def voice "dfki-ot-hsmm")

(comment


  (user/init!)
  
  
  
  (require '[tts-caller.audio :as audio] :reload)

  (def voice "cmu-slt-hsmm")
  (def voice "dfki-ot-hsmm")

  (generate-final-wav-auto
   "Sphera və Atlas  işləmir problem var !!!"
   "/tmp/alert.wav")
  
  (generate-final-wav-auto
   "Salam Mobil Şöbəyə işləmir -!!!"
   "/tmp/alert.wav")
  


  )



(defn create-mary []
  (let [cls (Class/forName "marytts.LocalMaryInterface")
        ctor (.getConstructor cls (into-array Class []))]
    (.newInstance ctor (object-array []))))

(defn list-voices []
  (let [mary (create-mary)
        voices (.getAvailableVoices mary)]
    (println "Available voices:" voices)))

(defn ssml->document [^String ssml]
  (let [factory (DocumentBuilderFactory/newInstance)
        builder (.newDocumentBuilder factory)
        is (ByteArrayInputStream. (.getBytes ssml "UTF-8"))]
    (.parse builder is)))

(defn generate-audio-bytes-plain [text voice]
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
  (let [bytes-per-ms (* (/ (.getSampleRate format) 1000)
                        (/ (.getSampleSizeInBits format) 8)
                        (.getChannels format))]
    (byte-array (* millis bytes-per-ms))))

(defn concat-audio-streams [streams format]
  (let [total-bytes (apply concat (map vec streams))]
    (AudioInputStream.
     (ByteArrayInputStream. (byte-array total-bytes))
     format
     (long (/ (count total-bytes)
              (* (.getSampleSizeInBits format) 0.125 (.getChannels format)))))))

(defn generate-final-wav-plain [text outfile]
  (let [format (AudioFormat. 16000 16 1 true false)
        audio (generate-audio-bytes-plain text voice)
        silence15 (silence-bytes 2000 format)
        silence10 (silence-bytes 500 format)
        full (concat-audio-streams [silence15 audio audio silence10] format)]
    (AudioSystem/write full AudioFileFormat$Type/WAVE (File. outfile))))

(defn generate-final-wav-ssml [ssml outfile]
  (let [format (AudioFormat. 16000 16 1 true false)
        audio (generate-audio-bytes-ssml ssml voice)
        silence15 (silence-bytes 2000 format)
        silence10 (silence-bytes 500 format)
        full (concat-audio-streams [silence15 audio audio silence10] format)]
    (AudioSystem/write full AudioFileFormat$Type/WAVE (File. outfile))))

(defn generate-final-wav-auto [text outfile]
  (if (.startsWith text "<speak>")
    (generate-final-wav-ssml text outfile)
    (generate-final-wav-plain text outfile)))

(comment
  ;; 🔁 Пример быстрой проверки

  ;; Проверка обычного текста:
  (generate-final-wav-auto "Salam, Sphere və Atlas işləmir!" "/tmp/plain.wav")

  ;; Проверка SSML с быстрой скоростью:
  (generate-final-wav-auto "<speak><prosody rate='x-fast'>Salam, Sphere və Atlas işləmir!</prosody></speak>" "/tmp/ssml.wav")
  
   (tts-caller.audio/generate-final-wav-auto
   "<speak><prosody rate='fast'>Sphere və ATLAS işləmir.</prosody></speak>"
   "/tmp/test.wav")

  
(tts-caller.audio/generate-final-wav-auto
 "<speak><prosody rate='fast'>Sphere və Atlas işləmir.</prosody></speak>"
 "/tmp/voice.wav")

  )
