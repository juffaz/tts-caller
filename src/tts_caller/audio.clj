(ns tts-caller.audio
  (:require
   [clojure.java.shell :refer [sh]])
  (:import [javax.sound.sampled AudioFormat AudioInputStream AudioSystem AudioFileFormat$Type]
           [java.io ByteArrayInputStream File]
           [org.w3c.dom Document]
           [javax.xml.parsers DocumentBuilderFactory]))
  

;; ✅ Set the desired voice here:

(def voice "cmu-slt-hsmm")
;;; (def voice "dfki-ot-hsmm")
;;;  (def voice "ac-irina-hsmm")

(comment

(def voice "ac-irina-hsmm")
(def voice "ac-elena-hsmm")

 
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
        full (concat-audio-streams [silence15 audio audio silence10] format)
        tmp (str outfile ".tmp.wav")]
    (AudioSystem/write full AudioFileFormat$Type/WAVE (File. tmp))
    (let [{:keys [exit err]} (sh "sox" tmp "-r" "8000" outfile)]
      (when-not (zero? exit)
        (println "❌ sox error:" err)))))

(defn generate-final-wav-ssml [ssml outfile]
  (let [format (AudioFormat. 16000 16 1 true false)
        audio (generate-audio-bytes-ssml ssml voice)
        silence15 (silence-bytes 2000 format)
        silence10 (silence-bytes 500 format)
        full (concat-audio-streams [silence15 audio audio silence10] format)
        tmp (str outfile ".tmp.wav")]
    (AudioSystem/write full AudioFileFormat$Type/WAVE (File. tmp))
    (let [{:keys [exit err]} (sh "sox" tmp "-r" "8000" outfile)]
      (when-not (zero? exit)
        (println "❌ sox error:" err)))))


(defn generate-final-wav-auto
  [text outfile & {:keys [tts-engine repeat voice rate gain]
                   :or {tts-engine "marytts"
                        repeat 30
                        voice "dfki-ot-hsmm"
                        rate "default"
                        gain 0.0}}]
  (let [tmp "/tmp/generated.wav"]
    (case tts-engine
      "espeak"
      (do
        ;; Generate WAV using espeak-ng
        (let [{:keys [exit err]} (sh "bash" "-c"
                                     (format "espeak-ng -v tr -s 140 \"%s\" --stdout | sox -t wav - -r 8000 -c 1 -b 16 %s gain %s"
                                             text tmp gain))]
          (when-not (zero? exit)
            (println "❌ espeak error:" err)))
        ;; Repeat + save final output
        (let [{:keys [exit err]} (sh "sox" tmp outfile "repeat" (str repeat))]
          (when-not (zero? exit)
            (println "❌ sox repeat error:" err))))

      "marytts"
      (let [ssml? (.startsWith text "<speak>")
            format (AudioFormat. 16000 16 1 true false)
            audio-bytes (if ssml?
                          (generate-audio-bytes-ssml text voice)
                          (generate-audio-bytes-plain text voice))
            silence15 (silence-bytes 2000 format)
            silence10 (silence-bytes 500 format)
            full (concat-audio-streams [silence15 audio-bytes audio-bytes silence10] format)
            tmp1 (str outfile ".tmp.wav")]
        (AudioSystem/write full AudioFileFormat$Type/WAVE (File. tmp1))
        (let [{:keys [exit err]} (sh "sox" tmp1 "-r" "8000" outfile "repeat" (str repeat))]
          (when-not (zero? exit)
            (println "❌ sox format/convert error:" err)))))

    (println "✅ File is ready:" outfile)))



(comment
  ;; 🔁 Example of a quick check

  ;; Check of regular text:
  (generate-final-wav-auto "Salam, Sphere və Atlas işləmir!" "/tmp/plain.wav")

  ;; Check SSML with fast speed:
  (generate-final-wav-auto "<speak><prosody rate='x-fast'>Salam, Sphere və Atlas işləmir!</prosody></speak>" "/tmp/ssml.wav")
  
   (tts-caller.audio/generate-final-wav-auto
   "<speak><prosody rate='fast'>Sphere və ATLAS işləmir.</prosody></speak>"
   "/tmp/test.wav")

  
(tts-caller.audio/generate-final-wav-auto
 "<speak><prosody rate='fast'>Sphere və Atlas işləmir.</prosody></speak>"
 "/tmp/voice.wav")

  )
