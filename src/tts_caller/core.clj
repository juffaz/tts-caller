(ns tts-caller.core
  (:require [compojure.core :refer [GET defroutes routes]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :as resp]
            [tts-caller.audio :refer [generate-final-wav]])
  (:import [java.lang ProcessBuilder]))

(def sip-user (or (System/getenv "SIP_USER") "0201011221"))
(def sip-pass (or (System/getenv "SIP_PASS") "pass1234"))
(def sip-domain (or (System/getenv "SIP_DOMAIN") "10.22.6.249"))

(defn call-sip [final-wav phone]
  (let [cfg (str "auth_user " sip-user "\n"
                 "auth_pass " sip-pass "\n"
                 "sip_transp udp\n"
                 "sip_listen 0.0.0.0\n"
                 "sip_contact sip:" sip-user "@" sip-domain)
        cfg-path "/tmp/baresip_config"]
    (spit cfg-path cfg)
    (let [pb (ProcessBuilder.
               ["baresip"
                "-f" cfg-path
                "-e" (str "/ausrc aufile," final-wav)
                "-e" (str "/dial sip:" phone "@" sip-domain)
                "-t" "45"])]
      (.inheritIO pb)
      (.start pb))))

(defn handle-call [{:keys [query-params]}]
  (let [{:strs [text phone]} query-params
        final "/tmp/final.wav"]
    (if (and text phone)
      (do
        (generate-final-wav text final)
        (call-sip final phone)
        (resp/response (str "📞 Calling " phone " from " sip-user "@" sip-domain)))
      (resp/bad-request "❌ Missing ?text=...&phone=..."))))

(defroutes app
  (GET "/call" [] handle-call)
  (GET "/health" [] (resp/response "OK")))

(defn -main []
  (println (str "✅ TTS SIP Caller on port 8899 using " sip-user "@" sip-domain))
  (run-jetty app {:port 8899}))
