(ns tts-caller.core
  (:gen-class)
  (:require [compojure.core :refer [GET defroutes routes]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :as resp]
            [tts-caller.audio :as audio]
            [ring.middleware.params :refer [wrap-params]]
            [clojure.java.shell :refer [sh]])
  (:import [java.io File]
           [java.lang ProcessBuilder]))

(def sip-user (or (System/getenv "SIP_USER") "python_client"))
(def sip-pass (or (System/getenv "SIP_PASS") "1234pass"))
(def sip-domain (or (System/getenv "SIP_HOST") "10.22.6.249"))
(def sip-port (or (System/getenv "SIP_PORT") "5062")) ; Уникальный порт для этого экземпляра

(def baresip-home "/tmp/baresip_config")
(def accounts-path (str baresip-home "/accounts"))
(def config-path (str baresip-home "/config"))

(defn kill-existing-baresip []
  (println "🛑 Killing existing baresip processes")
  (try
    (sh "pkill" "-f" "baresip")
    (Thread/sleep 1000) ; Даем время на завершение
    (println "✅ Existing baresip processes killed")
    (catch Exception e
      (println "⚠ No baresip processes found or error:" (.getMessage e)))))

(defn ensure-baresip-config [final-wav]
  (.mkdirs (File. baresip-home))
  (println "📁 Writing SIP config to" accounts-path)

  ;; Записываем accounts
  (let [acc-content (str "<sip:" sip-user "@" sip-domain ":" sip-port ">"
                         ";auth_user=" sip-user
                         ";auth_pass=" sip-pass
                         ";transport=udp\n")
        acc-file (File. accounts-path)]
    (spit acc-file acc-content)
    (with-open [raf (java.io.RandomAccessFile. acc-file "rw")]
      (let [fd (.getFD raf)]
        (.sync fd)))
    (println "✅ accounts записан и fsync выполнен"))

  ;; Записываем config
  (spit config-path
        (str "module_path /usr/lib64/baresip/modules\n"
             "module g711.so\n"
             "module aufile.so\n"
             "module cons.so\n\n"
             "sip_transp udp\n"
             "sip_listen 0.0.0.0:" sip-port "\n" ; Уникальный порт
             "audio_player aufile\n"
             "audio_source aufile\n"
             "audio_path " final-wav "\n"))
  (println "✅ config записан"))

(defn call-sip [final-wav phone]
  (kill-existing-baresip) ; Убиваем старые процессы
  (ensure-baresip-config final-wav)
  (println "📞 Calling via baresip:" phone)

  (Thread/sleep 1000) ; Ждем записи конфигурации

  (let [command ["baresip" "-f" baresip-home]
        pb (doto (ProcessBuilder. command)
             (.redirectErrorStream true))
        process (.start pb)
        writer (java.io.BufferedWriter.
                (java.io.OutputStreamWriter. (.getOutputStream process)))
        reader (clojure.java.io/reader (.getInputStream process))]

    (future
      (doseq [line (line-seq reader)]
        (println "[BARESIP]:" line)))

    (Thread/sleep 5000) ; Увеличиваем время для инициализации

    (if (.exists (java.io.File. final-wav))
      (println "✅ WAV exists at:" final-wav)
      (throw (Exception. (str "❌ WAV not found at: " final-wav))))

    (println "⚙ Sending /ausrc")
    (.write writer (str "/ausrc aufile," final-wav "\n"))
    (.flush writer)
    (Thread/sleep 2000)

    (println "📞 Sending /dial")
    (.write writer (str "/dial sip:" phone "@" sip-domain "\n"))
    (.flush writer)
    (Thread/sleep 20000) ; Увеличиваем время для вызова

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
        (println "🗣 Synthesizing text:" text)
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
  (println (str "✅ TTS SIP Caller on port 8899 using " sip-user "@" sip-domain ":" sip-port))
  (run-jetty app {:port 8899}))