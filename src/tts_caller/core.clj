(ns tts-caller.core
  (:gen-class)
  (:require [compojure.core :refer [GET defroutes routes]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :as resp]
            [tts-caller.audio :as audio]
            [ring.middleware.params :refer [wrap-params]])
  (:import [java.lang ProcessBuilder]
           [java.lang ProcessBuilder ProcessBuilder$Redirect]))

(def sip-user (or (System/getenv "SIP_USER") "0201011221"))
(def sip-pass (or (System/getenv "SIP_PASS") "pass1234"))
(def sip-domain (or (System/getenv "SIP_DOMAIN") "10.22.6.249"))

(defn call-sip [final-wav phone]
  (let [cfg-dir "/tmp/baresip_config"
        cfg-path (str cfg-dir "/config")
        cfg (str "auth_user " sip-user "\n"
                 "auth_pass " sip-pass "\n"
                 "sip_transp udp\n"
                 "sip_listen 0.0.0.0\n"
                 "sip_contact sip:" sip-user "@" sip-domain)
        cmd (str "/ausrc aufile," final-wav "\n"
                 "/dial sip:" phone "@" sip-domain "\n")]
    (.mkdirs (java.io.File. cfg-dir))
    (spit cfg-path cfg)
    (println "🛠  baresip config written to:" cfg-path)
    (println "📨 baresip commands:\n" cmd)
    (let [pb (doto (ProcessBuilder. ["baresip" "-f" cfg-dir])
               (.redirectOutput ProcessBuilder$Redirect/INHERIT)
               (.redirectError ProcessBuilder$Redirect/INHERIT))
          process (.start pb)]
      (with-open [writer (java.io.OutputStreamWriter. (.getOutputStream process))]
        (.write writer cmd)
        (.flush writer))
      (Thread/sleep 8000)
      (.destroy process))))


(defn split-phones [s]
  (->> (clojure.string/split s #"[,\s]+")
       (remove clojure.string/blank?)))

(defn handle-call [{:keys [query-params]}]
  (let [{:strs [text phone]} query-params
        final "/tmp/final.wav"]
    (if (and text phone)
      (let [phones (split-phones phone)]
        (println "🗣  Synthesizing text:" text)
        (audio/generate-final-wav-auto text final)
        (println "📁 WAV file created at:" final)
        (doseq [p phones]
          (call-sip final p)
          (println "📞 Call requested to:" p))
        (resp/response (str "📞 Calling " (clojure.string/join ", " phones) " from " sip-user "@" sip-domain)))
      (resp/bad-request "❌ Missing ?text=...&phone=..."))))


(defroutes app-routes
  (GET "/call" [] handle-call)
  (GET "/health" [] (resp/response "OK")))

(def app
  (wrap-params app-routes))

(defn -main []
  (println (str "✅ TTS SIP Caller on port 8899 using " sip-user "@" sip-domain))
  (run-jetty app {:port 8899}))
