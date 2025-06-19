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

(def sip-user (or (System/getenv "SIP_USER") "client"))
(def sip-pass (or (System/getenv "SIP_PASS") "passss"))
(def sip-domain (or (System/getenv "SIP_HOST") "10.22.6.249"))
(def sip-port
  (or (System/getenv "SIP_PORT")
      (str (+ 5062 (rand-int 1000))))) ; Random port between 5062 and 6062

(def baresip-home "/tmp/baresip_config")
(def accounts-path (str baresip-home "/accounts"))
(def config-path (str baresip-home "/config"))

(defn kill-existing-baresip []
  (println "🛑 Killing existing baresip processes")
  (try
    (let [{:keys [exit out err]} (sh "pkill" "-f" "baresip")]
      (if (zero? exit)
        (println "✅ Existing baresip processes killed")
        (println "⚠ No baresip processes found or error:" err)))
    (Thread/sleep 1000) ; Wait for processes to terminate
    (catch Exception e
      (println "⚠ Error killing baresip with pkill:" (.getMessage e))
      (try
        (let [{:keys [exit out err]} (sh "killall" "baresip")]
          (if (zero? exit)
            (println "✅ Killed baresip processes with killall")
            (println "⚠ No baresip processes found or error with killall:" err)))
        (Thread/sleep 1000)
        (catch Exception e2
          (println "⚠ Error killing baresip with killall:" (.getMessage e2)))))))

(defn ensure-baresip-config [final-wav]
  (println "📁 Ensuring baresip config directory exists")
  (.mkdirs (File. baresip-home))
  (when (.exists (File. baresip-home))
    (println "✅ Config directory exists at" baresip-home))

  ;; Ensure accounts file is created
  (println "📝 Creating SIP accounts file at" accounts-path)
  (let [acc-content (str "<sip:" sip-user "@" sip-domain ":" sip-port ">"
                         ";auth_user=" sip-user
                         ";auth_pass=" sip-pass
                         ";transport=udp\n")
        acc-file (File. accounts-path)]
    (.createNewFile acc-file) ; Explicitly create the file
    (spit acc-file acc-content)
    (with-open [raf (java.io.RandomAccessFile. acc-file "rw")]
      (let [fd (.getFD raf)]
        (.sync fd)))
    (println "✅ Accounts file created and synced"))

  ;; Write config file
  (println "📝 Writing config file at" config-path)
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
  (println "✅ Config file written"))

(defn call-sip [final-wav phone]
  (kill-existing-baresip) ; Ensure no old processes
  (ensure-baresip-config final-wav)
  (println "📞 Initiating SIP call to:" phone)

  ;; Test SIP server reachability
  (println "🔍 Checking SIP server reachability: " sip-domain)
  (try
    (let [{:keys [exit out err]} (sh "nc" "-z" "-u" sip-domain "5060")]
      (if (zero? exit)
        (println "✅ SIP server is reachable")
        (println "⚠ SIP server may not be reachable:" err)))
    (catch Exception e
      (println "⚠ Error checking SIP server:" (.getMessage e))))

  (let [command ["baresip" "-f" baresip-home]
        pb (doto (ProcessBuilder. command)
             (.redirectErrorStream true))
        process (.start pb)
        writer (java.io.BufferedWriter.
                (java.io.OutputStreamWriter. (.getOutputStream process)))
        reader (clojure.java.io/reader (.getInputStream process))
        output (atom [])]

    (try
      ;; Read baresip output in a separate thread
      (let [reader-thread
            (future
              (try
                (doseq [line (line-seq reader)]
                  (swap! output conj line)
                  (println "[BARESIP]:" line))
                (catch Exception e
                  (println "⚠ Error reading baresip output:" (.getMessage e)))))]

        ;; Wait for baresip to initialize
        (println "⏳ Waiting for baresip to initialize...")
        (Thread/sleep 10000) ; Increased to 10s for stability

        ;; Check if process is still alive
        (if-not (.isAlive process)
          (throw (Exception. "❌ Baresip process terminated unexpectedly")))

        ;; Verify WAV file exists
        (if (.exists (java.io.File. final-wav))
          (println "✅ WAV exists at:" final-wav)
          (throw (Exception. (str "❌ WAV not found at: " final-wav))))

        ;; Send commands
        (println "⚙ Sending /ausrc")
        (.write writer (str "/ausrc aufile," final-wav "\n"))
        (.flush writer)
        (Thread/sleep 2000)

        (println "📞 Sending /dial sip:" phone "@" sip-domain)
        (.write writer (str "/dial sip:" phone "@" sip-domain "\n"))
        (.flush writer)
        (Thread/sleep 30000) ; Increased to 30s for call completion

        (println "👋 Sending /quit")
        (.write writer "/quit\n")
        (.flush writer)

        ;; Wait for process to terminate
        (println "⏳ Waiting for baresip to terminate...")
        (let [exit-code (.waitFor process 10000 TimeUnit/MILLISECONDS)]
          (println "ℹ Baresip process exited with code:" exit-code))

        ;; Wait for reader thread to finish
        (future-cancel reader-thread))

      (catch Exception e
        (println "❌ Error during SIP call:" (.getMessage e))
        (throw e))
      (finally
        ;; Ensure streams and process are closed
        (try
          (.close writer)
          (catch Exception _))
        (try
          (.close reader)
          (catch Exception _))
        (try
          (when (.isAlive process)
            (println "🛑 Forcing baresip process to terminate")
            (.destroy process)
            (.waitFor process 2000 TimeUnit/MILLISECONDS))
          (catch Exception e
            (println "⚠ Error terminating baresip process:" (.getMessage e))))
        (println "📜 Baresip output log:" (clojure.string/join "\n" @output))))))

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
        ;; Run calls asynchronously
        (go
          (doseq [p phones]
            (try
              (call-sip final p)
              (println "📞 Call requested to:" p)
              (catch Exception e
                (println "❌ Failed to call" p ":" (.getMessage e))))))
        (resp/response (str "📞 Queued call to " (clojure.string/join ", " phones)
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