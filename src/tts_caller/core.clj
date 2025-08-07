(ns tts-caller.core
  (:gen-class)
  (:require [compojure.core :refer [GET defroutes routes]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :as resp]
            [tts-caller.audio :as audio]
            [ring.middleware.params :refer [wrap-params]]
            [clojure.java.shell :refer [sh]]
            ;; Import core.async for managing asynchronous operations and queues
            [clojure.core.async :as async :refer [go go-loop chan >!! <!! close! alts!! timeout]])
  (:import [java.io File]
           [java.lang ProcessBuilder]
           [java.util.concurrent TimeUnit]))

;; --- SIP Configuration ---
;; Define SIP account details, obtained from environment variables or defaults.
(def sip-user (or (System/getenv "SIP_USER") "python_client"))
(def sip-pass (or (System/getenv "SIP_PASS") "1234pass"))
(def sip-domain (or (System/getenv "SIP_HOST") "10.22.6.249"))
;; Use a fixed SIP port for consistency.
(def sip-port "50060") ; Note: This was 5060 in previous snippets, corrected here if needed.

;; Define paths for the temporary baresip configuration directory and files.
(def baresip-dir "/tmp/baresip_config")
(def accounts-path (str baresip-dir "/accounts"))
(def config-path (str baresip-dir "/config"))

;; --- Core.async for Batch Queue ---
;; Create a buffered channel to hold batch jobs. This acts as the queue.
;; Buffer size 10 - can be increased if needed to handle bursts.
(def batch-queue-channel (chan 10))

;; --- Functions for working with baresip ---
;; Function to forcefully kill any existing baresip processes to ensure a clean state.
(defn kill-baresip []
  (println "🛑 Kill baresip")
  (try
    ;; Try using pkill first
    (let [{:keys [exit err]} (sh "pkill" "-f" "baresip")]
      (if (zero? exit)
        (println "✅ Processes killed")
        ;;(println "⚠ Error or no processes found:" err) ; This can be normal sometimes
        ))
    (Thread/sleep 1000)
    (catch Exception e
      (println "⚠ pkill error:" (.getMessage e))
      ;; Fallback to killall if pkill fails
      (try
        (let [{:keys [exit err]} (sh "killall" "baresip")]
          (if (zero? exit)
            (println "✅ Killed via killall")
            ;;(println "⚠ killall error:" err)
            ))
        (Thread/sleep 1000)
        (catch Exception e2
          (println "⚠ killall error:" (.getMessage e2)))))))

;; Function to create the necessary baresip configuration files for a call.
(defn setup-baresip-config [wav]
  (println "📁 Create baresip config")
  ;; Ensure the config directory exists.
  (.mkdirs (File. baresip-dir))
  ;;(println "✅ Folder:" baresip-dir) ; Removed for log clarity

  ;; Create the accounts file with SIP credentials.
  (let [acc (str "sip:" sip-user "@" sip-domain ":5060" ; Note: Port 5060 for registration
                 ";auth_user=" sip-user
                 ";auth_pass=" sip-pass
                 ";transport=udp"
                 ";regint=0\n") ; regint=0 means no automatic re-registration
        file (File. accounts-path)]
    (.createNewFile file)
    (spit file acc)
    ;;(println "✅ Accounts file created")
    ;;(println "📄 Contents of accounts:\n" acc)
    )

  ;; Create the main config file, specifying modules, SIP transport, and audio source.
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
         "sip_listen 0.0.0.0:" sip-port "\n" ; Listen on the specified port
         "audio_source aufile,play=" wav "\n" ; Play the specified WAV file
         "audio_alert aufile,/dev/null\n")) ; No alert sound
  (println "✅ Config file created"))

;; Function to make a single SIP call using baresip.
(defn call-sip-single [wav phone]
  ;; 1. Check if the WAV file exists.
  (if-not (.exists (File. wav))
    (println "❌ WAV not found: " wav)
    (println "✅ WAV found:" wav))

  ;; 2. Kill any potentially hanging baresip processes before starting a new one.
  (let [{:keys [out]} (sh "pgrep" "-f" "baresip")]
    (when-not (clojure.string/blank? out)
      (println "⚠ Active baresip processes found, killing...")
      (kill-baresip)))

  ;; 3. Setup the baresip configuration for this specific call/WAV file.
  (setup-baresip-config wav)

  ;; 4. Log the call attempt.
  (println "📞 Calling:" phone)
  (println "🔍 SIP server:" sip-domain)

  ;; 5. Launch the baresip process with the configuration and dial command.
  (let [cmd ["baresip"
             "-f" baresip-dir ; Use the generated config directory
             "-e" (str "/ausrc aufile," wav) ; Set audio source command
             "-e" (str "/dial sip:" phone "@" sip-domain)] ; Dial command
        pb (doto (ProcessBuilder. cmd)
             (.redirectErrorStream true)) ; Merge stderr into stdout for easier handling
        proc (.start pb) ; Start the process
        reader (clojure.java.io/reader (.getInputStream proc)) ; Read its output
        output (atom [])] ; Store output lines for potential debugging

    (try
      ;; Read baresip output in a separate thread/future to avoid blocking.
      (let [reader-thread
            (future
              (doseq [line (line-seq reader)]
                (swap! output conj line)
                (println "[BARESIP]:" line)))]

        (println "⏳ Waiting for baresip to finish call...")
        ;; Wait for the baresip process to finish (with a maximum timeout of 45 seconds).
        (let [code (.waitFor proc 45000 TimeUnit/MILLISECONDS)]
          (println "ℹ baresip exited with code:" code))

        ;; Stop reading the log from the future.
        (future-cancel reader-thread))

      ;; Handle any exceptions that occur during the call process.
      (catch Exception e
        (println "❌ Call error for" phone ":" (.getMessage e))
        ;; Ensure baresip is killed on error.
        (kill-baresip)
        (throw e))

      ;; Ensure resources are closed and baresip is killed in the 'finally' block.
      (finally
        ;; Close the reader resource.
        (try (.close reader) (catch Exception _))
        ;;(println "📜 Full baresip log for" phone ":")
        ;;(println (clojure.string/join "\n" @output)) ; Removed for clarity

        ;; Kill the baresip process after finishing, as a final cleanup step.
        (kill-baresip)))))

;; --- Initialize worker for processing batches ---
;; Function to start the background worker that processes batch jobs sequentially.
(defn start-batch-worker! []
  ;; Use a go-loop to create a lightweight thread that continuously listens to the channel.
  (go-loop []
    ;; Take a batch job from the queue. This will block if the queue is empty.
    (when-let [batch-job (<!! batch-queue-channel)]
      ;; Extract details from the batch job map.
      (let [{:keys [text phones wav-path engine repeat]} batch-job]
        (println "🚀 Starting to process batch job for phones:" phones)
        (try
          ;; 1. Generate the WAV file for this specific batch.
          ;; WAV generation is deferred until the worker is ready to process this batch.
          ;; This ensures that a new WAV is not generated while previous calls are still running.
          (println "🗣 Generating WAV for text:" text)
          (audio/generate-final-wav-auto text wav-path
                                         :tts-engine engine
                                         :repeat repeat)
          (println "📁 WAV generated:" wav-path)

          ;; 2. Sequentially call each number in the batch.
          ;; This loop ensures only one call is made at a time.
          (doseq [phone phones]
            (try
              (println "📞 Attempting call to" phone)
              ;; Make the call using the WAV file generated for this batch.
              (call-sip-single wav-path phone)
              (println "✅ Call attempt finished for" phone)
              ;; Pause between calls to avoid overwhelming the SIP server/recipient.
              (println "⏳ Waiting before next call...")
              (Thread/sleep 40000) ; 15 seconds pause
              (catch Exception e
                ;; Catch errors for a specific call, but continue with the next one in the batch.
                (println "💥 Error during call to" phone ":" (.getMessage e))
                ;; Pause after an error as well, before trying the next number.
                (println "⏳ Waiting before next call (after error)...")
                (Thread/sleep 15000)
                )))

          (println "🏁 Finished processing batch job for phones:" phones)
          ;; The next batch will only start processing after this one finishes completely.
          (catch Exception e
            ;; Catch fatal errors that affect the entire batch processing.
            (println "💥 Fatal error processing batch job for phones" phones ":" (.getMessage e))
            ;; Retry logic or notification could be added here.
            ))))
    ;; Continue the loop to process the next item in the queue.
    (recur))
  (println "👷 Batch worker started"))

;; --- Handle HTTP request ---
;; Helper function to parse and clean the phone number string.
(defn split-phones [s]
  (->> (clojure.string/split s #"[,\s]+")
       (remove clojure.string/blank?)))

;; The main function that handles incoming HTTP GET requests to /call.
(defn handle-call [{:keys [query-params]}]
  (let [{:strs [text phone engine repeat]} query-params
        wav-path (str "/tmp/final_batch_" (System/currentTimeMillis) ".wav")
        engine (or engine "marytts")
        repeat-int (try (Integer/parseInt (or repeat "30")) (catch Exception _ 30))
        phones-list (if phone (split-phones phone) [])]

    (if (and text (seq phones-list))
      (let [batch-job {:text text
                       :phones phones-list
                       :wav-path wav-path
                       :engine engine
                       :repeat repeat-int}]

        ;; ✅ Теперь и alts!! и timeout доступны
        (let [[result chan] (alts!! [[batch-queue-channel batch-job] (timeout 5000)])]
          (if (= chan batch-queue-channel)
            (do
              (println "📥 Batch job queued for phones:" phones-list)
              (resp/response (str "📞 Batch job queued for: " (clojure.string/join ", " phones-list)
                                  " from " sip-user "@" sip-domain
                                  " via " engine)))
            (do
              (println "⏰ Queue timeout, rejecting batch job for phones:" phones-list)
              (resp/status (resp/response "❌ Service busy, try again later") 503)))))
      (resp/bad-request "❌ No valid ?text=...&phone=..."))))

;; --- Routes ---
;; Define the web application routes.
(defroutes app-routes
  (GET "/call" [] handle-call) ; Route for making calls
  (GET "/health" [] (resp/response "OK"))) ; Simple health check endpoint

;; Wrap the routes with middleware to parse query parameters.
(def app
  (wrap-params app-routes))

;; --- Application Startup ---
;; The main entry point for the application.
(defn -main []
  ;; Print startup information.
  (println "✅ TTS SIP Caller starting on port 8899: " sip-user "@" sip-domain ":" sip-port)
  ;; Start the single batch worker.
  ;; This ensures that only one call (from one batch) is processed at any given time,
  ;; respecting the constraint of a single GSM line.
  (start-batch-worker!)
  ;; Start the embedded Jetty web server on port 8899.
  (run-jetty app {:port 8899 :join? false}) ; join? false so the main thread is not blocked
  (println "🚀 Server started"))
