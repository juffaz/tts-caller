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
(def sip-port (str (+ 5061 (rand-int 39)))) ; 5061–5099

(def baresip-dir "/tmp/baresip_config")
(def accounts-path (str baresip-dir "/accounts"))
(def config-path (str baresip-dir "/config"))

(defn kill-baresip []
  (println "🛑 Kill baresip")
  (try
    (let [{:keys [exit err]} (sh "pkill" "-f" "baresip")]
      (if (zero? exit)
        (println "✅ Processes killed")
        (println "⚠ Error or no processes found:" err)))
    (Thread/sleep 1000)
    (catch Exception e
      (println "⚠ pkill error:" (.getMessage e))
      (try
        (let [{:keys [exit err]} (sh "killall" "baresip")]
          (if (zero? exit)
            (println "✅ Killed via killall")
            (println "⚠ killall error:" err)))
        (Thread/sleep 1000)
        (catch Exception e2
          (println "⚠ killall error:" (.getMessage e2)))))))

(defn setup-baresip-config [wav]
  (println "📁 Create baresip config")
  (.mkdirs (File. baresip-dir))
  (println "✅ Folder:" baresip-dir)

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
    (println "✅ Accounts file created")
    (println "📄 Contents of accounts:\n" acc))

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
         "audio_source aufile,play=" wav "\n"
         "audio_alert aufile,/dev/null\n"))
  (println "✅ Config file created"))

(defn call-sip [wav phone]
  (kill-baresip)
  (setup-baresip-config wav)
  (println "📞 Call:" phone)
  (println "🔍 Checking SIP server:" sip-domain)
  (try
    (let [{:keys [err]} (sh "nc" "-z" "-u" sip-domain "5060")]
      (println "ℹ Check completed (UDP may not always respond)"))
    (catch Exception e
      (println "⚠ Check error:" (.getMessage e))))
  
  (if-not (.exists (File. wav))
    (throw (Exception. (str "❌ WAV not found: " wav)))
    (println "✅ WAV found:" wav))

  ;; 🟢 Starting baresip with configuration
  (let [cmd ["baresip"
             "-f" baresip-dir
             "-e" (str "/ausrc aufile," wav)
             "-e" (str "/dial sip:" phone "@" sip-domain)]
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

        (println "⏳ Waiting for baresip initialization...")
        (Thread/sleep 2000)

        ;; Ensure the process is alive
        (when-not (.isAlive proc)
          (throw (Exception. "❌ baresip exited unexpectedly")))

        (println "⚙ /ausrc aufile," wav)
        (.write writer (str "/ausrc auffile," wav "\n"))
        (.flush writer)
        (Thread/sleep 1000)

        ;; Dial the phone number
        (let [target (str "sip:" phone "@" sip-domain)]
          (println "📞 /dial" target)
          (.write writer (str "/dial " target "\n"))
          (.flush writer))

        ;; ⏱ Wait for baresip to finish (max 20 seconds)
        (println "⏳ Waiting for baresip to finish...")
        (let [code (.waitFor proc 20000 TimeUnit/MILLISECONDS)]
          (println "ℹ baresip exited with code:" code))

        (future-cancel reader-thread))

      (catch Exception e
        (println "❌ Call error:" (.getMessage e))
        (throw e))

      (finally
        (doseq [s [writer reader]]
          (try (.close s) (catch Exception _)))
        (println "📜 Full baresip log:")
        (println (clojure.string/join "\n" @output))))))

(defn split-phones [s]
  (->> (clojure.string/split s #"[,\s]+")
       (remove clojure.string/blank?)))

(defn handle-call [{:keys [query-params]}]
  (let [{:strs [text phone engine repeat]} query-params
        wav "/tmp/final.wav"
        engine (or engine "marytts")
        repeat (try
                 (let [r (Integer/parseInt (or repeat "3"))]
                   (min (max r 1) 5)) ;; Ограничиваем: 1–5 повторов
                 (catch Exception _ 3))]
    (if (and text phone)
      (let [phones (split-phones phone)]
        (println "🗣 Text:" text)
        (println "⚙ Engine TTS:" engine " Repeat:" repeat)
        (audio/generate-final-wav-auto text wav
                                       :tts-engine engine
                                       :repeat repeat)
        (println "📁 WAV:" wav)
        (go
          (loop [numbers phones]
            (when-let [p (first numbers)]
              (try
                (println "📞 Call:" p)
                (call-sip wav p)
                (catch Exception e
                  (println "❌ Error:" p (.getMessage e)))
              (recur (rest numbers)))))
        (resp/response (str "📞 Call queued: " (clojure.string/join ", " phones)
                            " from " sip-user "@" sip-domain
                            " via " engine)))
      (resp/bad-request "❌ No ?text=...&phone=..."))))

(defroutes app-routes
  (GET "/call" [] handle-call)
  (GET "/health" [] (resp/response "OK")))

(def app
  (wrap-params app-routes))

(defn -main []
  (println "✅ TTS SIP Caller on 8899: " sip-user "@" sip-domain ":" sip-port)
  (run-jetty app {:port 8899}))
