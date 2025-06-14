(ns tts-caller.audio
  (:import [marytts LocalMaryInterface]
           [javax.sound.sampled AudioFormat AudioInputStream AudioSystem AudioFileFormat$Type]
           [java.io ByteArrayInputStream File]))

(defn generate-audio-bytes [text voice]
  (let [mary (LocalMaryInterface.)]
    (.setVoice mary voice)
    (with-open [ais (.generateAudio mary text)]
      (let [buffer (byte-array 1024)
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

