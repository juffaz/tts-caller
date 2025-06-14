(defproject tts-caller "0.1.0-SNAPSHOT"
  :description "TTS Caller using MaryTTS and Baresip"
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [mary-tts "0.1.0-SNAPSHOT"]
                 [ring/ring-core "1.11.0"]
                 [ring/ring-jetty-adapter "1.11.0"]
                 [compojure "1.7.1"]
                 [clout "2.2.1"]
                 [instaparse "1.4.8"]
                 [medley "1.4.0"]]
  :repositories [["sonatype-snapshots" "https://oss.sonatype.org/content/repositories/snapshots/"]])
