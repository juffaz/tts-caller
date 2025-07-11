(defproject tts-caller "0.1.0-SNAPSHOT"
  :description "TTS Caller using MaryTTS and Baresip"
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/core.async "1.6.681"]
                 [ring/ring-core "1.11.0"]
                 [ring/ring-jetty-adapter "1.11.0"]
                 [compojure "1.7.1"]
                 [clout "2.2.1"]
                 [instaparse "1.4.8"]
                 [medley "1.4.0"]
                 [org.slf4j/slf4j-simple "1.7.36"]
                 ]
  :main tts-caller.core
  :uberjar-name "tts-caller-standalone.jar"
  :aot [tts-caller.core]
  :resource-paths ["resources"]
  :jvm-opts ["-Dmary.base=lib"] ;; Apply mary.base globally
  :profiles {:dev {:jvm-opts ["-Dmary.base=lib"]}}
  :repositories [["central" "https://repo1.maven.org/maven2/"]])
