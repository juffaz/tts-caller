(ns user
  (:require [clojure.java.io :as io]))

(defn add-lib-jars! []
  (doseq [f (file-seq (io/file "lib"))]
    (when (.endsWith (.getName f) ".jar")
      (clojure.lang.RT/addURL (.toURL f)))))

(defn init! []
  (add-lib-jars!)
  (require 'tts-caller.audio :reload))

(comment
  ;; Запускай это один раз после Jack-In:
  (init!)
  (tts-caller.audio/generate-final-wav-plain "Test" "/tmp/test.wav"))


(comment
  
  (user/init!)
  
  )