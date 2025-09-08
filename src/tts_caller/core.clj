(ns tts-caller.core
  (:gen-class)
  (:require [compojure.core :refer [GET defroutes routes]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :as resp]
            [tts-caller.audio :as audio]
            [ring.middleware.params :refer [wrap-params]]
            [clojure.java.shell :refer [sh]]
            [clojure.core.async :as async :refer [go go-loop chan >!! <! close!]])
  (:import [java.io File]
           [java.lang ProcessBuilder]
           [java.util.concurrent TimeUnit]))

;; --- Функция логирования с временем ---
(defn ts-println [& args]
  (let [ts (java.time.LocalDateTime/now)
        formatted-ts (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss") ts)]
    (println formatted-ts (clojure.string/join " " args))))

;; --- SIP Configuration ---
(def sip-user (or (System/getenv "SIP_USER") "python_client"))
(def sip-pass (or (System/getenv "SIP_PASS") "1234pass"))
(def sip-domain (or (System/getenv "SIP_HOST") "10.22.6.249"))
(def sip-port "50060")

;; --- Пути к конфигурации baresip ---
(def baresip-dir "/tmp/baresip_config")
(def accounts-path (str baresip-dir "/accounts"))
(def config-path (str baresip-dir "/config"))

;; --- Асинхронная очередь ---
(def batch-queue-channel (chan 10))

;; --- Убить baresip ---
(defn kill-baresip []
  (ts-println "🛑 Killing baresip processes")
  (try
    (let [{:keys [exit]} (sh "pkill" "-f" "baresip")]
      (when (zero? exit) (ts-println "✅ baresip killed")))
    (Thread/sleep 1000)
    (catch Exception e
      (ts-println "⚠ pkill failed:" (.getMessage e))
      (try
        (let [{:keys [exit]} (sh "killall" "baresip")]
          (when (zero? exit) (ts-println "✅ Killed via killall")))
        (Thread/sleep 1000)
        (catch Exception e2
          (ts-println "⚠ killall failed:" (.getMessage e2)))))))

;; --- Ждать освобождения порта ---
(defn wait-for-port-release [port]
  (loop []
    (let [{:keys [out]} (sh "ss" "-tulpn")]
      (if (not (re-find (re-pattern (str ":" port)) out))
        (ts-println (str "✅ Port " port " is free"))
        (do
          (ts-println (str "⌛ Port " port " is still in use, waiting..."))
          (Thread/sleep 2000)
          (recur))))))

;; --- Настроить конфиг baresip ---
(defn setup-baresip-config [wav repeat]
  (ts-println "📁 Creating baresip config")
  (.mkdirs (File. baresip-dir))

  (let [acc (str "sip:" sip-user "@" sip-domain ":" sip-port
                 ";auth_user=" sip-user
                 ";auth_pass=" sip-pass
                 ";transport=udp"
                 ";regint=300\n")
        file (File. accounts-path)]
    (.createNewFile file)
    (spit file acc))

  (spit config-path
        (str
         "module_path /usr/lib64/baresip/modules\n"
         "module account.so\n"
         "module g711.so\n"
         "module stun.so\n"
         "module turn.so\n"
         "module contact.so\n"
         "module menu.so\n"
         "module aufile.so\n"
         "module sndfile.so\n"
         "module cons.so\n"
         "module auresamp.so\n\n"
         "sip_transp udp\n"
         "sip_listen 0.0.0.0:" sip-port "\n"
         "audio_source aufile,play=" wav ",repeat=" repeat "\n"
         "audio_alert aufile,/dev/null\n"))
  (ts-println "✅ Config file created"))

;; --- Основная функция вызова с retry (исправленная) ---
(defn call-sip-single [wav phone repeat]
  (let [max-retries 3
        retry-delay-ms 10000]
    (letfn [(attempt-call [n]
              ;; Проверка лимита попыток
              (when (> n max-retries)
                (ts-println "❌ Call failed after" max-retries "attempts:" phone)
                (throw (ex-info "Call failed after retries" {:phone phone :max-retries max-retries})))

              (ts-println "📞 Calling:" phone "(attempt" n "/" max-retries ")")

              ;; Проверка WAV
              (if-not (.exists (File. wav))
                (do
                  (ts-println "❌ WAV not found:" wav)
                  (throw (ex-info "WAV not found" {:phone phone}))))

              ;; Очистка
              (kill-baresip)
              (wait-for-port-release sip-port)

              ;; Настройка
              (setup-baresip-config wav repeat)

              ;; Запуск baresip
              (let [cmd ["baresip" "-f" baresip-dir
                         "-e" (str "/ausrc aufile," wav)
                         "-e" (str "/dial sip:" phone "@" sip-domain)]
                    pb (doto (ProcessBuilder. cmd) (.redirectErrorStream true))
                    proc (.start pb)
                    reader (clojure.java.io/reader (.getInputStream proc))
                    output (atom [])]

                (let [reader-thread
                      (future
                        (doseq [line (line-seq reader)]
                          (swap! output conj line)
                          (ts-println "[BARESIP]:" line)))]

                  (try
                    (let [exit-code (.waitFor proc 45000 TimeUnit/MILLISECONDS)
                          output-str (clojure.string/join "\n" @output)]

                      (future-cancel reader-thread)
                      (kill-baresip)
                      (wait-for-port-release sip-port)

                      (cond
                        ;; Успех
                        (zero? exit-code)
                        (ts-println "✅ Call succeeded:" phone)

                        ;; 480 — временно недоступен → retry
                        (clojure.string/includes? output-str "480 Temporarily unavailable")
                        (do
                          (ts-println "⚠️ 480 received. Retrying in" (int (/ retry-delay-ms 1000)) "sec...")
                          (Thread/sleep retry-delay-ms)
                          (attempt-call (inc n)))

                        ;; Любая другая ошибка
                        :else
                        (do
                          (ts-println "❌ Failed (code:" exit-code "). Retrying...")
                          (Thread/sleep retry-delay-ms)
                          (attempt-call (inc n))))
                      ) ; close inner let for exit-code

                      (catch Exception e
                      (future-cancel reader-thread)
                      (kill-baresip)
                      (do
                        (ts-println "💥 Error:" (.getMessage e) "- Retrying...")
                        (Thread/sleep retry-delay-ms)
                        (attempt-call (inc n))))

                    (finally
                      (try (.close reader) (catch Exception _)))))))]
      (attempt-call 1)))) ; Начинаем с попытки 1

;; --- Обработчик очереди ---
(defn start-batch-worker! []
  (go-loop []
    (when-let [batch-job (<! batch-queue-channel)]
      (let [{:keys [text phones wav-path engine repeat]} batch-job]
        (ts-println "🚀 Starting batch:" phones)
        (try
          (ts-println "🗣 Generating audio...")
          (audio/generate-final-wav-auto text wav-path
                                         :tts-engine engine
                                         :repeat 1)
          (ts-println "📁 Audio ready:" wav-path)

          (doseq [phone phones]
            (try
              (call-sip-single wav-path phone repeat)
              (ts-println "✅ Completed:" phone)
              (catch Exception e
                (ts-println "💥 Call failed:" phone (.getMessage e))
                (Thread/sleep 15000))))
          (ts-println "🏁 Batch finished:" phones)
          (catch Exception e
            (ts-println "💥 Batch error:" (.getMessage e))))))
    (recur))
  (ts-println "👷 Batch worker started"))

;; --- Парсинг номеров ---
(defn split-phones [s]
  (->> (clojure.string/split s #"[,\s]+")
       (remove clojure.string/blank?)))

;; --- HTTP обработчик ---
(defn handle-call [{:keys [query-params]}]
  (let [{:strs [text phone engine repeat]} query-params
        wav-path (str "/tmp/final_batch_" (System/currentTimeMillis) ".wav")
        engine (or engine "marytts")
        repeat-int (try (Integer/parseInt (or repeat "3")) (catch Exception _ 3))
        phones-list (split-phones phone)]
    (if (and text (seq phones-list))
      (let [batch-job {:text text :phones phones-list :wav-path wav-path
                       :engine engine :repeat repeat-int}]
        (if (async/offer! batch-queue-channel batch-job)
          (do
            (ts-println "📥 Batch queued:" phones-list)
            (resp/response (str "📞 Calls queued for: " (clojure.string/join ", " phones-list))))
          (do
            (ts-println "❌ Queue full")
            (resp/status (resp/response "Service busy") 503))))
      (resp/bad-request "❌ Missing ?text=...&phone=..."))))

;; --- Маршруты ---
(defroutes app-routes
  (GET "/call" [] handle-call)
  (GET "/health" [] (resp/response "OK")))

(def app (wrap-params app-routes))

;; --- Запуск ---
(defn -main []
  (ts-println "✅ Starting TTS Caller:" sip-user "@" sip-domain ":" sip-port)
  (start-batch-worker!)
  (run-jetty app {:port 8899 :join? false})
  (ts-println "🚀 Server started on port 8899"))
