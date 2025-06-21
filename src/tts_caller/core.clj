(ns tts-caller.core
  (:gen-class)
  (:require [compojure.core :refer [GET defroutes routes]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :as resp]
            [tts-caller.audio :as audio]
            [ring.middleware.params :refer [wrap-params]]
            [clojure.java.shell :refer [sh]]
            [clojure.core.async :refer [go]])
  (:import [java.io File BufferedWriter OutputStreamWriter]
           [java.lang ProcessBuilder]
           [java.util.concurrent TimeUnit]))


(def sip-user (or (System/getenv "SIP_USER") "python_client"))
(def sip-pass (or (System/getenv "SIP_PASS") "1234pass"))
(def sip-domain (or (System/getenv "SIP_HOST") "10.22.6.249"))
;; (def sip-port (or (System/getenv "SIP_PORT") "5060")) ; Порт 5060 для SIP-сервера
(def sip-port (str (+ 5061 (rand-int 39)))) ; 5061–5099


(def baresip-dir "/tmp/baresip_config")
(def accounts-path (str baresip-dir "/accounts"))
(def config-path (str baresip-dir "/config"))

(defn kill-baresip []
  (println "🛑 Убиваем baresip")
  (try
    (let [{:keys [exit err]} (sh "pkill" "-f" "baresip")]
      (if (zero? exit)
        (println "✅ Процессы убиты")
        (println "⚠ Ошибка или процессы не найдены:" err)))
    (Thread/sleep 1000)
    (catch Exception e
      (println "⚠ Ошибка pkill:" (.getMessage e))
      (try
        (let [{:keys [exit err]} (sh "killall" "baresip")]
          (if (zero? exit)
            (println "✅ Убиты через killall")
            (println "⚠ Ошибка killall:" err)))
        (Thread/sleep 1000)
        (catch Exception e2
          (println "⚠ Ошибка killall:" (.getMessage e2)))))))

(defn setup-baresip-config [wav]
  (println "📁 Создаем конфиг baresip")
  (.mkdirs (File. baresip-dir))
  (println "✅ Папка:" baresip-dir)

  ;; Правильная строка accounts (без < > и с портом 5060)
  (let [acc (str "sip:" sip-user "@" sip-domain ":5060"
                 ";auth_user=" sip-user
                 ";auth_pass=" sip-pass
                 ";transport=udp"
                 ";regint=0\n")
        file (File. accounts-path)]
    (.createNewFile file)
    (spit file acc)
    (with-open [raf (java.io.RandomAccessFile. file "rw")]
      (.sync (.getFD raf)))
    (println "✅ Accounts создан")
    (println "📄 Содержимое accounts:\n" acc))

  ;; config с audio_source и audio_player как в /root/.baresip/config
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
         "audio_player aufile,play=" wav "\n"
         "audio_source aufile,/dev/zero\n"
         "audio_alert aufile,/dev/null\n"))
  (println "✅ Config создан"))



(comment
  
(def sip-user (or (System/getenv "SIP_USER") "python_client"))
(def sip-pass (or (System/getenv "SIP_PASS") "1234pass"))
(def sip-domain (or (System/getenv "SIP_HOST") "10.22.6.249"))
(def baresip-dir "/tmp/baresip_config")

  
  )

(defn call-sip [wav phone]
  (kill-baresip)
  (setup-baresip-config wav)
  (println "📞 Вызов:" phone)

  (println "🔍 Проверка SIP-сервера:" sip-domain)
  (try
    (let [{:keys [err]} (sh "nc" "-z" "-u" sip-domain "5060")]
      (println "ℹ Проверка завершена (UDP не всегда отвечает)"))
    (catch Exception e
      (println "⚠ Ошибка проверки:" (.getMessage e))))

  (if-not (.exists (File. wav))
    (throw (Exception. (str "❌ WAV не найден: " wav)))
    (println "✅ WAV найден:" wav))

  ;; УБРАНО -t 60
  (let [cmd ["baresip" "-f" baresip-dir]
        pb (doto (ProcessBuilder. cmd)
             (.redirectErrorStream true))
        proc (.start pb)
        writer (BufferedWriter. (OutputStreamWriter. (.getOutputStream proc)))
        reader (clojure.java.io/reader (.getInputStream proc))
        output (atom [])]

    (try
      (let [reader-thread
            (future
              (doseq [line (line-seq reader)]
                (swap! output conj line)
                (println "[BARESIP]:" line)))]

        (println "⏳ Ожидание инициализации baresip...")
        (Thread/sleep 2000)

        (when-not (.isAlive proc)
          (throw (Exception. "❌ baresip неожиданно завершился")))

        ;; Установка источника
        (println "⚙ /ausrc aufile," wav)
        (.write writer (str "/ausrc aufile," wav "\n"))
        (.flush writer)
        (Thread/sleep 1000)

        ;; Вызов
        (let [target (str "sip:" phone "@" sip-domain)]
          (println "📞 /dial" target)
          (.write writer (str "/dial " target "\n"))
          (.flush writer))

        ;; Ждём 7 секунд — затем убиваем
        (Thread/sleep 7000)
        (when (.isAlive proc)
          (println "🛑 Завершаем baresip через 7 сек")
          (.destroy proc))

        (future-cancel reader-thread))

      (catch Exception e
        (println "❌ Ошибка вызова:" (.getMessage e))
        (throw e))
      (finally
        (doseq [s [writer reader]]
          (try (.close s) (catch Exception _)))
        (println "📜 Полный лог baresip:")
        (println (clojure.string/join "\n" @output))))))


(defn split-phones [s]
  (->> (clojure.string/split s #"[,\s]+")
       (remove clojure.string/blank?)))

(defn handle-call [{:keys [query-params]}]
  (let [{:strs [text phone engine repeat]} query-params
        wav "/tmp/final.wav"
        engine (or engine "marytts")
        repeat (try (Integer/parseInt (or repeat "30"))
                    (catch Exception _ 30))]
    (if (and text phone)
      (let [phones (split-phones phone)]
        (println "🗣 Текст:" text)
        (println "⚙ Движок TTS:" engine " Повтор:" repeat)
        (audio/generate-final-wav-auto text wav
                                       :tts-engine engine
                                       :repeat repeat)
        (println "📁 WAV:" wav)
        (go
          (doseq [p phones]
            (try
              (call-sip wav p)
              (println "📞 Вызов:" p)
              (catch Exception e
                (println "❌ Ошибка:" p (.getMessage e))))))
        (resp/response (str "📞 Вызов в очереди: " (clojure.string/join ", " phones)
                            " от " sip-user "@" sip-domain
                            " через " engine)))
      (resp/bad-request "❌ Нет ?text=...&phone=..."))))

(defroutes app-routes
  (GET "/call" [] handle-call)
  (GET "/health" [] (resp/response "OK")))

(def app
  (wrap-params app-routes))

(defn -main []
  (println "✅ TTS SIP Caller на 8899: " sip-user "@" sip-domain ":" sip-port)
  (run-jetty app {:port 8899}))