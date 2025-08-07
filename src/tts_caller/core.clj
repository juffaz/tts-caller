(ns tts-caller.core
  (:gen-class)
  (:require [compojure.core :refer [GET defroutes routes]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :as resp]
            [tts-caller.audio :as audio]
            [ring.middleware.params :refer [wrap-params]]
            [clojure.java.shell :refer [sh]]
            ;; Import core.async
            [clojure.core.async :as async :refer [go go-loop chan >!! <!! close!]])
  (:import [java.io File]
           [java.lang ProcessBuilder]
           [java.util.concurrent TimeUnit]))

;; --- SIP Configuration ---
(def sip-user (or (System/getenv "SIP_USER") "python_client"))
(def sip-pass (or (System/getenv "SIP_PASS") "1234pass"))
(def sip-domain (or (System/getenv "SIP_HOST") "10.22.6.249"))
(def sip-port "5060") ; Use a fixed port

(def baresip-dir "/tmp/baresip_config")
(def accounts-path (str baresip-dir "/accounts"))
(def config-path (str baresip-dir "/config"))

;; --- Core.async for Batch Queue ---
;; Channel for batch jobs. Buffered.
;; Buffer size 10 - can be increased if needed.
(def batch-queue-channel (chan 10))

;; --- Functions for working with baresip ---
(defn kill-baresip []
  (println "🛑 Kill baresip")
  (try
    (let [{:keys [exit err]} (sh "pkill" "-f" "baresip")]
      (if (zero? exit)
        (println "✅ Processes killed")
        ;;(println "⚠ Error or no processes found:" err) ; This can be normal sometimes
        ))
    (Thread/sleep 1000)
    (catch Exception e
      (println "⚠ pkill error:" (.getMessage e))
      (try
        (let [{:keys [exit err]} (sh "killall" "baresip")]
          (if (zero? exit)
            (println "✅ Killed via killall")
            ;;(println "⚠ killall error:" err)
            ))
        (Thread/sleep 1000)
        (catch Exception e2
          (println "⚠ killall error:" (.getMessage e2)))))))

(defn setup-baresip-config [wav]
  (println "📁 Create baresip config")
  (.mkdirs (File. baresip-dir))
  ;;(println "✅ Folder:" baresip-dir) ; Removed for log clarity

  (let [acc (str "sip:" sip-user "@" sip-domain ":5060"
                 ";auth_user=" sip-user
                 ";auth_pass=" sip-pass
                 ";transport=udp"
                 ";regint=0\n")
        file (File. accounts-path)]
    (.createNewFile file)
    (spit file acc)
    ;;(println "✅ Accounts file created")
    ;;(println "📄 Contents of accounts:\n" acc)
    )

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

(defn call-sip-single [wav phone]
  ;; Check WAV file
  (if-not (.exists (File. wav))
    (println "❌ WAV not found: " wav)
    (println "✅ WAV found:" wav))

  ;; Kill old baresip if hanging
  (let [{:keys [out]} (sh "pgrep" "-f" "baresip")]
    (when-not (clojure.string/blank? out)
      (println "⚠ Active baresip processes found, killing...")
      (kill-baresip)))

  ;; Setup config
  (setup-baresip-config wav)

  (println "📞 Calling:" phone)
  (println "🔍 SIP server:" sip-domain)

  ;; Launch baresip
  (let [cmd ["baresip"
             "-f" baresip-dir
             "-e" (str "/ausrc aufile," wav)
             "-e" (str "/dial sip:" phone "@" sip-domain)]
        pb (doto (ProcessBuilder. cmd)
             (.redirectErrorStream true))
        proc (.start pb)
        reader (clojure.java.io/reader (.getInputStream proc))
        output (atom [])]

    (try
      ;; Read baresip output in a separate thread
      (let [reader-thread
            (future
              (doseq [line (line-seq reader)]
                (swap! output conj line)
                (println "[BARESIP]:" line)))]

        (println "⏳ Waiting for baresip to finish call...")
        ;; Wait for call to finish (max 45 seconds)
        (let [code (.waitFor proc 45000 TimeUnit/MILLISECONDS)]
          (println "ℹ baresip exited with code:" code))

        ;; Stop reading log
        (future-cancel reader-thread))

      (catch Exception e
        (println "❌ Call error for" phone ":" (.getMessage e))
        ;; Kill process on error
        (kill-baresip)
        (throw e))

      (finally
        ;; Close resources
        (try (.close reader) (catch Exception _))
        ;;(println "📜 Full baresip log for" phone ":")
        ;;(println (clojure.string/join "\n" @output)) ; Removed for clarity

        ;; Kill process after finishing
        (kill-baresip)))))

;; --- Initialize worker for processing batches ---
(defn start-batch-worker! []
  (go-loop []
    (when-let [batch-job (<!! batch-queue-channel)] ; Take a batch job from the queue
      (let [{:keys [text phones wav-path engine repeat]} batch-job]
        (println "🚀 Starting to process batch job for phones:" phones)
        (try
          ;; 1. Generate WAV file for this batch
          ;; WAV generation is now deferred until the worker is ready to process this specific batch.
          ;; This ensures that a new WAV is not generated while previous calls are still running.
          (println "🗣 Generating WAV for text:" text)
          (audio/generate-final-wav-auto text wav-path
                                         :tts-engine engine
                                         :repeat repeat)
          (println "📁 WAV generated:" wav-path)

          ;; 2. Sequentially call each number in the batch
          (doseq [phone phones]
            (try
              (println "📞 Attempting call to" phone)
              (call-sip-single wav-path phone) ; Use the WAV generated for this batch
              (println "✅ Call completed for" phone)
              ;; Pause between calls
              (println "⏳ Waiting before next call...")
              (Thread/sleep 15000) ; 15 seconds
              (catch Exception e
                ;; Catch error for a specific call, but continue with the next one
                (println "💥 Error during call to" phone ":" (.getMessage e))
                ;; Pause after error as well
                (println "⏳ Waiting before next call (after error)...")
                (Thread/sleep 15000)
                )))

          (println "🏁 Finished processing batch job for phones:" phones)
          ;; The next batch will only start processing after this one finishes completely.
          (catch Exception e
            ;; Catch error at the batch level
            (println "💥 Fatal error processing batch job for phones" phones ":" (.getMessage e))
            ;; Retry logic or notification could be added here
            ))))
    (recur)) ; Continue listening to the channel
  (println "👷 Batch worker started"))

;; --- Handle HTTP request ---
(defn split-phones [s]
  (->> (clojure.string/split s #"[,\s]+")
       (remove clojure.string/blank?)))

(defn handle-call [{:keys [query-params]}]
  (let [{:strs [text phone engine repeat]} query-params
        ;; Generate a unique path for the WAV file for this batch
        wav-path (str "/tmp/final_batch_" (System/currentTimeMillis) ".wav")
        engine (or engine "marytts")
        repeat-int (try (Integer/parseInt (or repeat "30"))
                        (catch Exception _ 30))
        phones-list (if phone (split-phones phone) [])]
    (if (and text (seq phones-list)) ; Check that text exists and phone list is not empty
      (let [batch-job {:text text       ; Text for TTS
                       :phones phones-list ; List of phone numbers to call
                       :wav-path wav-path  ; Path for the WAV file dedicated to this batch
                       :engine engine      ; TTS engine to use
                       :repeat repeat-int  ; Number of repeats for the audio
                       }]
        ;; Send the batch job to the queue
        ;; Using `offer!` with a timeout prevents the HTTP request from blocking indefinitely
        ;; if the queue is full.
        (if (async/offer! batch-queue-channel batch-job 5000) ; 5 second timeout
          (do
            (println "📥 Batch job queued for phones:" phones-list)
            ;; Return response immediately
            (resp/response (str "📞 Batch job queued for: " (clojure.string/join ", " phones-list)
                                " from " sip-user "@" sip-domain
                                " via " engine)))
          (do
            ;; If the queue is full or times out, reject the request
            (println "꽉 Queue is full or timeout, rejecting batch job for phones:" phones-list)
            (resp/status (resp/response "❌ Service busy, try again later") 503))))
      ;; If parameters are missing, return a bad request response
      (resp/bad-request "❌ No valid ?text=...&phone=..."))))

;; --- Routes ---
(defroutes app-routes
  (GET "/call" [] handle-call)
  (GET "/health" [] (resp/response "OK")))

(def app
  (wrap-params app-routes))

;; --- Application Startup ---
(defn -main []
  (println "✅ TTS SIP Caller starting on port 8899: " sip-user "@" sip-domain ":" sip-port)
  ;; Start the worker for processing batches (only one!)
  ;; This ensures that only one call (from one batch) is processed at any given time.
  (start-batch-worker!)
  ;; Start the web server
  (run-jetty app {:port 8899 :join? false}) ; join? false so the main thread is not blocked
  (println "🚀 Server started"))
