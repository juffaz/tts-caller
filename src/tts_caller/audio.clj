(ns tts-caller.audio
  (:import [javax.sound.sampled AudioFormat AudioInputStream AudioSystem AudioFileFormat$Type]
           [java.io ByteArrayInputStream File]))

(defn create-mary []
  (let [cls (Class/forName "marytts.LocalMaryInterface")
        ctor (.getConstructor cls (into-array Class []))]
    (.newInstance ctor (object-array []))))


(comment

  (require 'tts-caller.audio :reload)
  (tts-caller.audio/generate-final-wav "Salam Sphere ATLAS" "/tmp/final.wav")


  
  (Class/forName "marytts.LocalMaryInterface")
  
  (tts-caller.audio/generate-final-wav
   "<speak><prosody rate='fast'>Salam, Sphere və ATLAS işləmir.</prosody></speak>"
   "/tmp/test.wav")
  
  
  (require 'tts-caller.audio :reload)

  
(tts-caller.audio/generate-final-wav "Salam, Sphere və ATLAS işləmir." "/tmp/test.wav")

(require 'tts-caller.audio :reload)

(tts-caller.audio/generate-final-wav
 "Salam, Sphere, Atlas və GNI hazırda işləmir."
 "/tmp/final.wav")

  
  (generate-final-wav
   "Salam, Sphere, Atlas və GNI hazırda işləmir."
   "/tmp/final.wav")

  (tts-caller.audio/generate-final-wav "Salam, bu səsli zəng testidir." "/tmp/test.wav")

  
  (generate-final-wav "Salam, bu səsli zəng testidir." "/tmp/test.wav")


  (.setVoice mary voice)

  
  )



(defn generate-audio-bytes [text voice]
  (let [mary (create-mary)
        set-voice (.getMethod (class mary) "setVoice" (into-array Class [String]))
        set-input-type (.getMethod (class mary) "setInputType" (into-array Class [String]))
        generate-audio (.getMethod (class mary) "generateAudio" (into-array Class [String]))
        ssml (str "<speak><prosody rate='fast' volume='+6dB'>" text "</prosody></speak>")]
    (.invoke set-voice mary (object-array [voice]))
    (.invoke set-input-type mary (object-array ["RAWMARYXML"]))
    (with-open [ais (.invoke generate-audio mary (object-array [ssml]))]
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

(defn generate-final-wav [text outfile]
  (let [voice "dfki-ot-hsmm"
        dummy-format (AudioFormat. 8000 16 1 true false)
        audio (generate-audio-bytes text voice)
        silence15 (silence-bytes 15000 dummy-format)
        silence10 (silence-bytes 10000 dummy-format)
        full (concat-audio-streams [silence15 audio audio silence10] dummy-format)]
    (AudioSystem/write full AudioFileFormat$Type/WAVE (File. outfile))))




