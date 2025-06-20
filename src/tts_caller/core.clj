(ns tts-caller.core
  (:gen-class)
  (:require [compojure.core :refer [GET defroutes routes]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :as resp]
            [tts-caller.audio :as audio]
            [ring.middleware.params :refer [wrap-params]]
            [clojure.java.shell :refer [sh]]
            [clojure.core.async :refer [go]])
  (:import [java.io File]
           [java.lang ProcessBuilder]
           [java.util.concurrent TimeUnit]))

(def sip-user (or (System/getenv "SIP_USER") "python_client"))
(def sip-pass (or (System/getenv "SIP_PASS") "1234pass"))
(def sip-domain (or (System/getenv "SIP_HOST") "10.22.6.249"))
(def sip-port (or (System/getenv "SIP_PORT") "5060")) ; Порт 5060 для SIP-сервера

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

  (println "📝 Файл accounts:" accounts-path)

  (let [acc (str "<sip:" sip-user "@" sip-domain ":" sip-port ">"
                 ";auth_user=" sip-user
                 ";auth_pass=" sip-pass
                 ";transport=udp\n")
        file (File. accounts-path)]
    (.createNewFile file)
    (spit file acc)
    (with-open [raf (java.io.RandomAccessFile. file "rw")]
      (.sync (.getFD raf)))
    (println "✅ Accounts создан")
    (println "📄 Содержимое accounts:\n" acc))

  (println "📝 Config:" config-path)
  (spit config-path
        (str
         "module_path /usr/lib64/baresip/modules\n"
         "module g711.so\n"
         "module stun.so\n"
         "module turn.so\n"
         "module contact.so\n"
         "module menu.so\n"
         "module aufile.so\n"
         "module sndfile.so\n"
         "module cons.so\n\n"
         "sip_transp udp\n"
         "sip_listen 0.0.0.0:" sip-port "\n"
         "audio_player aufile\n"
         "audio_source aufile\n"
         "audio_path " wav "\n"))
  (println "✅ Config создан"))


(defn call-sip [wav phone]
  (kill-baresip)
  (setup-baresip-config wav)
  (println "📞 Вызов:" phone)

  (println "🔍 Проверка SIP-сервера:" sip-domain)
  (try
    (let [{:keys [exit err]} (sh "nc" "-z" "-u" sip-domain "5060")]
      (if (zero? exit)
        (println "✅ Сервер доступен")
        (println "⚠ Сервер недоступен:" err)))
    (catch Exception e
      (println "⚠ Ошибка проверки:" (.getMessage e))))

  (let [cmd ["baresip" "-f" baresip-dir "-v" "-d"]
        pb (doto (ProcessBuilder. cmd)
             (.redirectErrorStream true))
        proc (.start pb)
        writer (java.io.BufferedWriter.
                (java.io.OutputStreamWriter. (.getOutputStream proc)))
        reader (clojure.java.io/reader (.getInputStream proc))
        output (atom [])]

    (try
      (let [reader-thread
            (future
              (try
                (doseq [line (line-seq reader)]
                  (swap! output conj line)
                  (println "[BARESIP]:" line))
                (catch Exception e
                  (println "⚠ Ошибка вывода baresip:" (.getMessage e)))))]

        (println "⏳ Ждем baresip...")
        (Thread/sleep 10000)

        (if-not (.isAlive proc)
          (throw (Exception. "❌ Baresip завершился")))

        (if (.exists (File. wav))
          (println "✅ WAV:" wav)
          (throw (Exception. (str "❌ WAV не найден: " wav))))

        (println "⚙ /ausrc")
        (.write writer (str "/ausrc aufile," wav "\n"))
        (.flush writer)
        (Thread/sleep 2000)

        (println "📞 /dial sip:" phone "@" sip-domain)
        (.write writer (str "/dial sip:" phone "@" sip-domain "\n"))
        (.flush writer)
        (Thread/sleep 60000)

        (println "👋 /quit")
        (.write writer "/quit\n")
        (.flush writer)

        (println "⏳ Ждем завершения...")
        (let [code (.waitFor proc 10000 TimeUnit/MILLISECONDS)]
          (println "ℹ Код выхода:" code))

        (future-cancel reader-thread))

      (catch Exception e
        (println "❌ Ошибка вызова:" (.getMessage e))
        (throw e))
      (finally
        (try (.close writer) (catch Exception _))
        (try (.close reader) (catch Exception _))
        (try
          (when (.isAlive proc)
            (println "🛑 Завершаем baresip")
            (.destroy proc)
            (.waitFor proc 2000 TimeUnit/MILLISECONDS))
          (catch Exception e
            (println "⚠ Ошибка завершения:" (.getMessage e))))
        (println "📜 Лог baresip:" (clojure.string/join "\n" @output))))))

(defn split-phones [s]
  (->> (clojure.string/split s #"[,\s]+")
       (remove clojure.string/blank?)))

(defn handle-call [{:keys [query-params]}]
  (let [{:strs [text phone]} query-params
        wav "/tmp/final.wav"]
    (if (and text phone)
      (let [phones (split-phones phone)]
        (println "🗣 Текст:" text)
        (audio/generate-final-wav-auto text wav)
        (println "📁 WAV:" wav)
        (go
          (doseq [p phones]
            (try
              (call-sip wav p)
              (println "📞 Вызов:" p)
              (catch Exception e
                (println "❌ Ошибка:" p (.getMessage e))))))
        (resp/response (str "📞 Вызов в очереди: " (clojure.string/join ", " phones)
                            " от " sip-user "@" sip-domain)))
      (resp/bad-request "❌ Нет ?text=...&phone=..."))))

(defroutes app-routes
  (GET "/call" [] handle-call)
  (GET "/health" [] (resp/response "OK")))

(def app
  (wrap-params app-routes))

(defn -main []
  (println "✅ TTS SIP Caller на 8899: " sip-user "@" sip-domain ":" sip-port)
  (run-jetty app {:port 8899}))