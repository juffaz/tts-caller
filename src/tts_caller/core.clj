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
(def sip-port (or (System/getenv "SIP_PORT") "5062"))

(def baresip-home "/tmp/baresip_config")
(def accounts-path (str baresip-home "/accounts"))
(def config-path (str baresip-home "/config"))

(defn kill-existing-baresip []
  (println "🛑 Убиваем старые процессы baresip")
  (try
    (let [{:keys [exit out err]} (sh "pkill" "-f" "baresip")]
      (if (zero? exit)
        (println "✅ Процессы baresip убиты")
        (println "⚠ Процессы не найдены или ошибка:" err)))
    (Thread/sleep 1000)
    (catch Exception e
      (println "⚠ Ошибка при убийстве через pkill:" (.getMessage e))
      (try
        (let [{:keys [exit out err]} (sh "killall" "baresip")]
          (if (zero? exit)
            (println "✅ Убиты через killall")
            (println "⚠ Ошибка killall:" err)))
        (Thread/sleep 1000)
        (catch Exception e2
          (println "⚠ Ошибка killall:" (.getMessage e2)))))))

(defn ensure-baresip-config [final-wav]
  (println "📁 Создаем папку конфигурации baresip")
  (.mkdirs (File. baresip-home))
  (when (.exists (File. baresip-home))
    (println "✅ Папка существует:" baresip-home))

  (println "📝 Создаем файл accounts:" accounts-path)
  (let [acc-content (str "<sip:" sip-user "@" sip-domain ":" sip-port ">"
                         ";auth_user=" sip-user
                         ";auth_pass=" sip-pass
                         ";transport=udp\n")
        acc-file (File. accounts-path)]
    (.createNewFile acc-file)
    (spit acc-file acc-content)
    (with-open [raf (java.io.RandomAccessFile. acc-file "rw")]
      (let [fd (.getFD raf)]
        (.sync fd)))
    (println "✅ Файл accounts создан"))

  (println "📝 Пишем config:" config-path)
  (spit config-path
        (str "module_path /usr/lib64/baresip/modules\n"
             "module g711.so\n"
             "module aufile.so\n"
             "module cons.so\n\n"
             "sip_transp udp\n"
             "sip_listen 0.0.0.0:" sip-port "\n"
             "audio_player aufile\n"
             "audio_source aufile\n"
             "audio_path " final-wav "\n"))
  (println "✅ Config записан"))

(defn call-sip [final-wav phone]
  (kill-existing-baresip)
  (ensure-baresip-config final-wav)
  (println "📞 Начинаем SIP-вызов:" phone)

  (println "🔍 Проверяем доступность SIP-сервера:" sip-domain)
  (try
    (let [{:keys [exit out err]} (sh "nc" "-z" "-u" sip-domain "5060")]
      (if (zero? exit)
        (println "✅ Сервер доступен")
        (println "⚠ Сервер недоступен:" err)))
    (catch Exception e
      (println "⚠ Ошибка проверки сервера:" (.getMessage e))))

  (let [command ["baresip" "-f" baresip-home "-v" "-d"] ; -d для доп. логов
        pb (doto (ProcessBuilder. command)
             (.redirectErrorStream true))
        process (.start pb)
        writer (java.io.BufferedWriter.
                (java.io.OutputStreamWriter. (.getOutputStream process)))
        reader (clojure.java.io/reader (.getInputStream process))
        output (atom [])]

    (try
      (let [reader-thread
            (future
              (try
                (doseq [line (line-seq reader)]
                  (swap! output conj line)
                  (println "[BARESIP]:" line))
                (catch Exception e
                  (println "⚠ Ошибка чтения вывода baresip:" (.getMessage e)))))]

        (println "⏳ Ждем инициализации baresip...")
        (Thread/sleep 10000)

        (if-not (.isAlive process)
          (throw (Exception. "❌ Baresip завершился неожиданно")))

        (if (.exists (java.io.File. final-wav))
          (println "✅ WAV-файл существует:" final-wav)
          (throw (Exception. (str "❌ WAV не найден: " final-wav))))

        (println "⚙ Отправляем /ausrc")
        (.write writer (str "/ausrc aufile," final-wav "\n"))
        (.flush writer)
        (Thread/sleep 2000)

        (println "📞 Отправляем /dial sip:" phone "@" sip-domain)
        (.write writer (str "/dial sip:" phone "@" sip-domain "\n"))
        (.flush writer)
        (Thread/sleep 60000) ; 60с для звонка

        (println "👋 Отправляем /quit")
        (.write writer "/quit\n")
        (.flush writer)

        (println "⏳ Ждем завершения baresip...")
        (let [exit-code (.waitFor process 10000 TimeUnit/MILLISECONDS)]
          (println "ℹ Baresip завершился с кодом:" exit-code))

        (future-cancel reader-thread))

      (catch Exception e
        (println "❌ Ошибка SIP-вызова:" (.getMessage e))
        (throw e))
      (finally
        (try (.close writer) (catch Exception _))
        (try (.close reader) (catch Exception _))
        (try
          (when (.isAlive process)
            (println "🛑 Принудительно завершаем baresip")
            (.destroy process)
            (.waitFor process 2000 TimeUnit/MILLISECONDS))
          (catch Exception e
            (println "⚠ Ошибка завершения baresip:" (.getMessage e))))
        (println "📜 Лог baresip:" (clojure.string/join "\n" @output))))))

(defn split-phones [s]
  (->> (clojure.string/split s #"[,\s]+")
       (remove clojure.string/blank?)))

(defn handle-call [{:keys [query-params]}]
  (let [{:strs [text phone]} query-params
        final "/tmp/final.wav"]
    (if (and text phone)
      (let [phones (split-phones phone)]
        (println "🗣 Синтез текста:" text)
        (audio/generate-final-wav-auto text final)
        (println "📁 WAV создан:" final)
        (go
          (doseq [p phones]
            (try
              (call-sip final p)
              (println "📞 Вызов отправлен:" p)
              (catch Exception e
                (println "❌ Ошибка вызова" p ":" (.getMessage e))))))
        (resp/response (str "📞 Вызов в очереди: " (clojure.string/join ", " phones)
                            " от " sip-user "@" sip-domain)))
      (resp/bad-request "❌ Нет ?text=...&phone=..."))))

(defroutes app-routes
  (GET "/call" [] handle-call)
  (GET "/health" [] (resp/response "OK")))

(def app
  (wrap-params app-routes))

(defn -main []
  (println (str "✅ TTS SIP Caller на порту 8899: " sip-user "@" sip-domain ":" sip-port))
  (run-jetty app {:port 8899}))