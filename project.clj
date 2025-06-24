(defproject tts-caller "0.1.0-SNAPSHOT"
  :description "TTS Caller using MaryTTS and Baresip"
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/core.async "1.6.681"]
                 [ring/ring-core "1.11.0"]
                 [ring/ring-jetty-adapter "1.11.0"]
                 [compojure "1.7.1"]
                 [clout "2.2.1"]
                 [instaparse "1.4.8"]
                 [medley "1.4.0"]]
  :main tts-caller.core
  :uberjar-name "tts-caller-standalone.jar"
  :aot [tts-caller.core]
  :resource-paths ["resources"]
  :extra-classpath-dirs ["lib"]
  :jvm-opts ["-Dmary.base=lib"])
