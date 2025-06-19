(ns tts-caller.core
  (:gen-class)
  (:require [compojure.core :refer [GET defroutes routes]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :as resp]
            [tts-caller.audio :as audio]
            [ring.middleware.params :refer [wrap-params]])
  (:import [java.io File]
           [java.lang ProcessBuilder]))

(def sip-user (or (System/getenv "SIP_USER") "python_client"))
(def sip-pass (or (System/getenv "SIP_PASS") "1234pass"))
(def sip-domain (or (System/getenv "SIP_HOST") "10.22.6.249"))

(def baresip-home "/root/.baresip")
(def accounts-path (str baresip-home "/accounts"))
(def config-path (str baresip-home "/config"))

(defn ensure-baresip-config [final-wav]
  (.mkdirs (File. baresip-home))
  (spit accounts-path
        (str "sip:" sip-user "@" sip-domain ":5060"
             ";auth_user=" sip-user
             ";auth_pass=" sip-pass
             ";transport=udp\n"))
  (spit config-path
        (str "module_path /usr/lib64/baresip/modules\n"
             "module g711.so\n"
             "module aufile.so\n"
             "module cons.so\n\n"
             "sip_transp udp\n"
             "sip_listen 0.0.0.0\n"
             "audio_player aufile\n"
             "audio_source aufile\n"
             "audio_path " final-wav "\n")))

(defn call-sip [final-wav phone]
  (ensure-baresip-config final-wav)
  (println "📞 Calling via baresip:" phone)
  (let [command ["baresip" "-f" baresip-home]
        pb (doto (ProcessBuilder. command)
             (.redirectErrorStream true))
        process (.start pb)
        writer (java.io.BufferedWriter.
                (java.io.OutputStreamWriter. (.getOutputStream process)))
        reader (clojure.java.io/reader (.getInputStream process))]

    ;; читаем baresip stdout
    (future
      (doseq [line (line-seq reader)]
        (println "[BARESIP]:" line)))

    (Thread/sleep 3000)

    (if (.exists (java.io.File. final-wav))
      (println "✅ WAV exists at:" final-wav)
      (println "❌ WAV not found at:" final-wav))

    ;; отправка команд
    (println "⚙ Sending /ausrc")
    (.write writer (str "/ausrc aufile," final-wav "\n"))
    (.flush writer)
    (Thread/sleep 1500)

    (println "📞 Sending /dial")
    (.write writer (str "/dial sip:" phone "@" sip-domain "\n"))
    (.flush writer)
    (Thread/sleep 15000)

    (println "👋 Sending /quit")
    (.write writer "/quit\n")
    (.flush writer)
    (.close writer)

    (.waitFor process)))

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
        (resp/response (str "📞 Calling " (clojure.string/join ", " phones)
                            " from " sip-user "@" sip-domain)))
      (resp/bad-request "❌ Missing ?text=...&phone=..."))))

(defroutes app-routes
  (GET "/call" [] handle-call)
  (GET "/health" [] (resp/response "OK")))

(def app
  (wrap-params app-routes))

(defn -main []
  (println (str "✅ TTS SIP Caller on port 8899 using " sip-user "@" sip-domain))
  (run-jetty app {:port 8899}))



