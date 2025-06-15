(ns user
  (:require [clojure.java.io :as io]))

(defn add-lib-jars []
  (doseq [name ["marytts-runtime-5.2.jar"
                "marytts-lang-en-5.2.jar"
                "voice-cmu-slt-hsmm-5.2.jar" ;; <== ОБЯЗАТЕЛЬНО ПОСЛЕ EN!
                "marytts-lang-tr-5.2.jar"
                "marytts-lang-ru-5.2.jar"
                "marytts-lang-fr-5.2.jar"
                "voice-dfki-ot-hsmm-5.2.jar"]]
    (let [f (io/file "lib" name)]
      (when (.exists f)
        (.addURL (.getContextClassLoader (Thread/currentThread))
                 (.toURL f))))))


(defn init! []
  (add-lib-jars)
  (require 'tts-caller.audio :reload))

(user/init!)

(require '[tts-caller.audio :as audio] :reload)

(audio/list-voices)


(comment


(user/init!) ;; добавляет JAR'ы

  (require '[tts-caller.audio :as audio] :reload)

  (audio/list-voices)


  )

