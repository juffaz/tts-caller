# TTS Caller - Technical Architecture Documentation

**Version:** 0.1.0-SNAPSHOT
**Last Updated:** 2025-12-28
**Technology Stack:** Clojure 1.11.1, Ring/Jetty, Compojure, MaryTTS 5.2, Baresip, espeak-ng

---

## Table of Contents

1. [System Overview](#system-overview)
2. [Architecture Diagram](#architecture-diagram)
3. [Component Architecture](#component-architecture)
4. [Data Flow](#data-flow)
5. [Core Modules](#core-modules)
6. [Function Reference](#function-reference)
7. [Process Lifecycle](#process-lifecycle)
8. [SIP Communication](#sip-communication)
9. [Audio Processing Pipeline](#audio-processing-pipeline)
10. [Concurrency Model](#concurrency-model)
11. [Configuration Management](#configuration-management)
12. [Error Handling Strategy](#error-handling-strategy)
13. [Deployment Architecture](#deployment-architecture)
14. [Integration Patterns](#integration-patterns)

---

## System Overview

### Purpose

TTS Caller is a microservice that provides voice notification capabilities by converting text to speech and delivering it via SIP phone calls. It serves as a bridge between monitoring/alerting systems and telephony infrastructure.

### Key Capabilities

- **Text-to-Speech Conversion:** Supports MaryTTS (high quality) and espeak-ng (lightweight)
- **SIP Call Management:** Automated outbound calls via Baresip SIP client
- **Multi-Phone Broadcasting:** Single request can trigger calls to multiple recipients
- **Async Processing:** Non-blocking queue-based call processing
- **Retry Logic:** Automatic retry with exponential backoff for failed calls
- **Multi-Language Support:** English, Russian, Turkish TTS voices

### Technology Rationale

| Component | Technology | Reason |
|-----------|-----------|---------|
| Application Language | Clojure | Functional paradigm, excellent concurrency primitives (core.async), JVM ecosystem |
| Web Server | Jetty via Ring | Lightweight, embedded, production-grade HTTP server |
| Routing | Compojure | Simple, declarative HTTP routing for Clojure |
| TTS Engine (Primary) | MaryTTS | Open-source, high-quality voices, multi-language, pure Java |
| TTS Engine (Secondary) | espeak-ng | Fast generation, low resource usage, good for simple alerts |
| SIP Client | Baresip | Lightweight, command-line driven, supports audio file playback |
| Audio Processing | Sox | Industry standard for audio format conversion and manipulation |
| Concurrency | core.async | CSP-style channels for async processing, backpressure handling |
| Container | Docker | Simplified deployment, dependency isolation |

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                        External Systems                          │
│  (Zabbix, Alertmanager, ElastAlert, Centreon, Custom Apps)     │
└────────────────────────────┬────────────────────────────────────┘
                             │ HTTP GET/POST
                             │ /call?text=...&phone=...
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                      TTS Caller Service                          │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │                    HTTP Layer (Jetty)                        │ │
│ │  ┌────────────┐  ┌──────────────┐  ┌───────────────┐       │ │
│ │  │ /call      │  │ /health      │  │ middleware    │       │ │
│ │  │ endpoint   │  │ endpoint     │  │ (params)      │       │ │
│ │  └─────┬──────┘  └──────────────┘  └───────────────┘       │ │
│ └────────┼────────────────────────────────────────────────────┘ │
│          │                                                       │
│          ▼                                                       │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │              Request Handler (handle-call)                   │ │
│ │  • Parse parameters (text, phone, engine, repeat)           │ │
│ │  • Validate inputs                                           │ │
│ │  • Create batch job                                          │ │
│ │  • Enqueue to batch-queue-channel                           │ │
│ └─────────────────────┬───────────────────────────────────────┘ │
│                       │                                          │
│                       ▼                                          │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │         Async Queue (core.async channel, capacity=10)        │ │
│ │     ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────┐   │ │
│ │     │ Job 1    │  │ Job 2    │  │ Job 3    │  │  ...   │   │ │
│ │     └──────────┘  └──────────┘  └──────────┘  └────────┘   │ │
│ └─────────────────────┬───────────────────────────────────────┘ │
│                       │                                          │
│                       ▼                                          │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │         Batch Worker (start-batch-worker!)                   │ │
│ │  • Dequeue job from channel                                 │ │
│ │  • Generate audio file                                       │ │
│ │  • Iterate through phone numbers                            │ │
│ │  • Call call-sip-single for each phone                      │ │
│ └────────┬────────────────────────────┬───────────────────────┘ │
│          │                            │                          │
│          ▼                            ▼                          │
│ ┌────────────────────┐      ┌────────────────────────────────┐ │
│ │  Audio Generation  │      │   SIP Call Management          │ │
│ │  (audio namespace) │      │   (call-sip-single)            │ │
│ │                    │      │                                │ │
│ │ ┌────────────────┐ │      │ ┌──────────────────────────┐  │ │
│ │ │ MaryTTS        │ │      │ │ 1. Kill existing Baresip │  │ │
│ │ │ - LocalMary    │ │      │ │ 2. Wait for port free    │  │ │
│ │ │ - Voice select │ │      │ │ 3. Setup config files    │  │ │
│ │ │ - SSML support │ │      │ │ 4. Start Baresip         │  │ │
│ │ └────────────────┘ │      │ │ 5. Monitor output        │  │ │
│ │        OR          │      │ │ 6. Retry on failure      │  │ │
│ │ ┌────────────────┐ │      │ └──────────────────────────┘  │ │
│ │ │ espeak-ng      │ │      │                                │ │
│ │ │ - Shell exec   │ │      └────────────────┬───────────────┘ │
│ │ │ - Sox resample │ │                       │                  │
│ │ └────────────────┘ │                       │                  │
│ │        ↓           │                       │                  │
│ │ ┌────────────────┐ │                       │                  │
│ │ │ WAV file       │ │◄──────────────────────┘                  │
│ │ │ /tmp/*.wav     │ │                                          │
│ │ │ 8kHz mono PCM  │ │                                          │
│ │ └────────────────┘ │                                          │
│ └────────────────────┘                                          │
└──────────────────────────────┬──────────────────────────────────┘
                               │ SIP/UDP
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                      External Process                            │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │  Baresip (SIP User Agent)                                    │ │
│ │  • SIP REGISTER to PBX                                       │ │
│ │  • INVITE to destination number                             │ │
│ │  • RTP audio stream (WAV file playback)                     │ │
│ │  • Call state monitoring (Connected/Busy/Failed)            │ │
│ └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────┬──────────────────────────────────┘
                               │ SIP/RTP
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                       SIP Infrastructure                         │
│  (PBX, SIP Proxy, PSTN Gateway)                                 │
│                              │                                   │
│                              ▼                                   │
│                    Destination Phone(s)                          │
└─────────────────────────────────────────────────────────────────┘
```

---

## Component Architecture

### Layer Breakdown

#### 1. Presentation Layer
- **Component:** Jetty HTTP Server + Compojure Routes
- **File:** [core.clj:224-236](src/tts_caller/core.clj#L224-L236)
- **Responsibilities:**
  - Receive HTTP requests
  - Parameter parsing and validation
  - Response formatting
  - Health check endpoint

#### 2. Application Layer
- **Component:** Request Handler + Batch Queue Manager
- **File:** [core.clj:175-223](src/tts_caller/core.clj#L175-L223)
- **Responsibilities:**
  - Business logic orchestration
  - Job creation and queuing
  - Phone number parsing
  - Engine selection

#### 3. Service Layer
- **Component:** SIP Call Manager + Audio Generator
- **Files:** [core.clj:95-172](src/tts_caller/core.clj#L95-L172), [audio.clj:91-127](src/tts_caller/audio.clj#L91-L127)
- **Responsibilities:**
  - SIP call lifecycle management
  - TTS audio generation
  - Retry logic
  - Process management

#### 4. Infrastructure Layer
- **Component:** External Process Managers
- **File:** [core.clj:35-92](src/tts_caller/core.clj#L35-L92)
- **Responsibilities:**
  - Baresip process control
  - Port management
  - Configuration file generation
  - Process cleanup

---

## Data Flow

### Request Flow Sequence

```
1. HTTP Request Arrives
   ├─> GET /call?text=Alert&phone=123456&engine=marytts&repeat=3
   └─> Jetty receives request

2. Parameter Extraction (handle-call)
   ├─> text: "Alert"
   ├─> phone: "123456"
   ├─> engine: "marytts"
   └─> repeat: 3

3. Phone Number Parsing (split-phones)
   ├─> Input: "123456,789012"
   └─> Output: ["123456" "789012"]

4. Batch Job Creation
   ├─> Generate unique WAV path: /tmp/final_batch_1234567890.wav
   └─> Create job map:
       {:text "Alert"
        :phones ["123456" "789012"]
        :wav-path "/tmp/final_batch_1234567890.wav"
        :engine "marytts"
        :repeat 3}

5. Queue Enqueue (async/offer!)
   ├─> Attempt to add job to batch-queue-channel
   ├─> If queue full (capacity=10): return 503 Service Busy
   └─> If success: return 200 with confirmation message

6. HTTP Response Sent
   └─> "📞 Calls queued for: 123456, 789012"

7. Async Worker Dequeue (start-batch-worker!)
   ├─> Worker constantly listening on channel
   └─> Receives batch job

8. Audio Generation (generate-final-wav-auto)
   ├─> Select TTS engine (marytts or espeak)
   ├─> Generate speech audio
   ├─> Add silence padding (1500ms start, 500ms end)
   ├─> Resample to 8kHz mono PCM
   └─> Save to /tmp/final_batch_1234567890.wav

9. Iterate Phone Numbers
   └─> For each phone in ["123456" "789012"]:

10. SIP Call Execution (call-sip-single)
    ├─> Attempt 1 (max 3 retries)
    │   ├─> Kill existing Baresip processes
    │   ├─> Wait for port 50060 to be free
    │   ├─> Create Baresip config files
    │   │   ├─> /tmp/baresip_config/accounts
    │   │   └─> /tmp/baresip_config/config
    │   ├─> Start Baresip process
    │   │   └─> baresip -f /tmp/baresip_config \
    │   │       -e /ausrc aufile,/tmp/file.wav \
    │   │       -e /dial sip:123456@pbx.example.com
    │   ├─> Monitor process output (45s timeout)
    │   ├─> Parse output for status codes
    │   └─> Exit code analysis:
    │       ├─> 0 (Success): Log success, continue to next phone
    │       ├─> 480 (Temporarily Unavailable): Wait 10s, retry
    │       └─> Other error: Wait 10s, retry
    └─> Cleanup: Kill Baresip, wait for port release

11. Batch Completion
    └─> Log "🏁 Batch finished: [123456, 789012]"

12. Loop Back to Step 7
    └─> Worker waits for next job
```

---

## Core Modules

### Module 1: tts-caller.core

**Purpose:** Main application entry point, HTTP server, SIP call orchestration

**Namespace Dependencies:**
```clojure
(:require [compojure.core :refer [GET defroutes routes]]
          [ring.adapter.jetty :refer [run-jetty]]
          [ring.util.response :as resp]
          [tts-caller.audio :as audio]
          [ring.middleware.params :refer [wrap-params]]
          [clojure.java.shell :refer [sh]]
          [clojure.core.async :as async :refer [go go-loop chan >!! <! close!]])
(:import [java.io File]
         [java.lang ProcessBuilder]
         [java.util.concurrent TimeUnit])
```

**Key Components:**

#### Configuration Constants
```clojure
(def sip-user (or (System/getenv "SIP_USER") "python_client"))
(def sip-pass (or (System/getenv "SIP_PASS") "1234pass"))
(def sip-domain (or (System/getenv "SIP_HOST") "10.22.6.249"))
(def sip-port "50060")
(def baresip-dir "/tmp/baresip_config")
(def batch-queue-channel (chan 10))  ;; Bounded channel with capacity 10
```

#### State Management
- **batch-queue-channel:** core.async channel holding pending call jobs
- **Capacity:** 10 jobs (provides backpressure)
- **Semantics:** FIFO queue, blocking writes when full

---

### Module 2: tts-caller.audio

**Purpose:** Text-to-speech audio generation, audio processing

**Namespace Dependencies:**
```clojure
(:require [clojure.java.shell :refer [sh]])
(:import [javax.sound.sampled AudioFormat AudioInputStream AudioSystem AudioFileFormat$Type]
         [java.io ByteArrayInputStream File]
         [org.w3c.dom Document]
         [javax.xml.parsers DocumentBuilderFactory])
```

**Audio Format Specifications:**
- **Input (MaryTTS):** 16kHz, 16-bit, mono, signed PCM
- **Output (Final WAV):** 8kHz, 16-bit, mono, signed PCM (telephony standard)
- **Conversion Tool:** Sox for resampling

---

## Function Reference

### Core Namespace Functions

#### `ts-println [& args]`
**Location:** [core.clj:15-18](src/tts_caller/core.clj#L15-L18)

**Purpose:** Timestamped logging function

**Signature:**
```clojure
(ts-println & args) → nil
```

**Parameters:**
- `args` (variadic): Any number of arguments to log

**Behavior:**
1. Gets current LocalDateTime
2. Formats timestamp as "yyyy-MM-dd HH:mm:ss"
3. Joins all arguments with spaces
4. Prints to stdout

**Example:**
```clojure
(ts-println "Starting call to" "123456")
;; Output: 2025-12-28 14:32:45 Starting call to 123456
```

**Technical Details:**
- Thread-safe (println is synchronized)
- Uses Java Time API (Java 8+)
- No log levels (all output is INFO equivalent)

---

#### `kill-baresip []`
**Location:** [core.clj:35-48](src/tts_caller/core.clj#L35-L48)

**Purpose:** Terminate all running Baresip processes

**Signature:**
```clojure
(kill-baresip) → nil
```

**Behavior:**
1. Executes `pkill -f baresip`
2. If pkill fails, fallback to `killall baresip`
3. Sleeps 1000ms to allow process cleanup
4. Catches and logs exceptions

**Technical Details:**
- **Signal:** SIGTERM (default for pkill/killall)
- **Pattern Matching:** `-f` flag matches full command line
- **Timeout:** 1s sleep after kill
- **Error Handling:** Non-zero exit codes logged but not thrown

**Process Flow:**
```
pkill -f baresip
  ├─> Success (exit 0): Wait 1s, return
  ├─> Failure (exit 1): Try killall
  │   ├─> Success: Wait 1s, return
  │   └─> Failure: Log error, return
  └─> Exception: Log error, try killall
```

**Known Issues:**
- Kills ALL Baresip processes (not just service-owned)
- No verification of actual process termination
- Race condition possible if process spawns between kill and check

---

#### `wait-for-port-release [port]`
**Location:** [core.clj:51-59](src/tts_caller/core.clj#L51-L59)

**Purpose:** Block until specified port is no longer in use

**Signature:**
```clojure
(wait-for-port-release port) → nil
```

**Parameters:**
- `port` (string): Port number to monitor

**Behavior:**
1. Runs `ss -tulpn` to list all listening ports
2. Searches output for port pattern
3. If port found: sleep 2s and retry
4. If port not found: return

**Technical Details:**
- **Polling Interval:** 2000ms
- **Command:** `ss -tulpn` (socket statistics)
- **Regex Pattern:** Matches `:port` anywhere in output
- **Blocking:** Infinite loop until port free

**Example:**
```clojure
(wait-for-port-release "50060")
;; Blocks until no process listens on port 50060
```

**Performance Considerations:**
- Spawns new process every 2s (overhead)
- No timeout (can block indefinitely)
- Alternative: Check /proc/net/tcp directly

---

#### `setup-baresip-config [wav repeat]`
**Location:** [core.clj:62-92](src/tts_caller/core.clj#L62-L92)

**Purpose:** Generate Baresip configuration files

**Signature:**
```clojure
(setup-baresip-config wav repeat) → nil
```

**Parameters:**
- `wav` (string): Absolute path to WAV audio file
- `repeat` (integer): Number of times to repeat audio

**Behavior:**
1. Creates `/tmp/baresip_config/` directory
2. Writes `/tmp/baresip_config/accounts` file with SIP credentials
3. Writes `/tmp/baresip_config/config` file with module configuration

**Generated Files:**

**accounts file format:**
```
sip:USERNAME@HOST:PORT;auth_user=USERNAME;auth_pass=PASSWORD;transport=udp;regint=300
```

**config file format:**
```
module_path /usr/lib64/baresip/modules
module account.so
module g711.so
module stun.so
module turn.so
module contact.so
module menu.so
module aufile.so
module sndfile.so
module cons.so
module auresamp.so

sip_transp udp
sip_listen 0.0.0.0:50060
audio_source aufile,play=/tmp/file.wav,repeat=3
audio_alert aufile,/dev/null
```

**Technical Details:**
- **Permissions:** Uses default umask (usually 0022)
- **File Encoding:** UTF-8 (via `spit`)
- **Overwrite Behavior:** Replaces existing files
- **No Validation:** Doesn't verify WAV file exists

**Modules Loaded:**
- `account.so`: Account management
- `g711.so`: G.711 codec (PCMU/PCMA)
- `aufile.so`: Audio file playback
- `sndfile.so`: Sound file format support
- `stun.so`: STUN NAT traversal
- `turn.so`: TURN relay support
- `cons.so`: Console interface
- `auresamp.so`: Audio resampling

**Configuration Parameters:**
- `regint=300`: SIP registration interval (5 minutes)
- `audio_source`: Specifies file to play during call
- `audio_alert`: Suppresses alerting sounds

---

#### `call-sip-single [wav phone repeat]`
**Location:** [core.clj:95-172](src/tts_caller/core.clj#L95-L172)

**Purpose:** Execute single SIP call with retry logic

**Signature:**
```clojure
(call-sip-single wav phone repeat) → nil (or throws exception)
```

**Parameters:**
- `wav` (string): Path to WAV file
- `phone` (string): Destination phone number
- `repeat` (integer): Audio repeat count

**Return Value:**
- `nil` on success
- Throws `ExceptionInfo` after max retries exceeded

**Algorithm:**
```
function call-sip-single(wav, phone, repeat):
    max_retries = 3
    retry_delay = 10000ms

    attempt_call(attempt_number):
        if attempt_number > max_retries:
            throw "Call failed after retries"

        if not file_exists(wav):
            throw "WAV not found"

        kill_baresip()
        wait_for_port_release("50060")
        setup_baresip_config(wav, repeat)

        process = start_baresip([
            "baresip", "-f", "/tmp/baresip_config",
            "-e", "/ausrc aufile," + wav,
            "-e", "/dial sip:" + phone + "@" + domain
        ])

        reader_thread = spawn_async_reader(process.stdout)

        try:
            exit_code = wait_for_process(process, timeout=45000ms)
            output = get_accumulated_output(reader_thread)

            kill_reader_thread()
            kill_baresip()
            wait_for_port_release("50060")

            if exit_code == 0:
                log("Success")
                return

            if output.contains("480 Temporarily unavailable"):
                log("480 error, retrying...")
                sleep(retry_delay)
                attempt_call(attempt_number + 1)

            else:
                log("Failed, retrying...")
                sleep(retry_delay)
                attempt_call(attempt_number + 1)

        catch Exception:
            kill_reader_thread()
            kill_baresip()
            sleep(retry_delay)
            attempt_call(attempt_number + 1)

    attempt_call(1)
```

**Process Execution:**
- **Method:** Java ProcessBuilder
- **Output Handling:** Merged stdout/stderr
- **Timeout:** 45 seconds
- **Thread Management:** Async reader in separate future

**Retry Conditions:**
1. Non-zero exit code
2. Exception during execution
3. 480 SIP response (Temporarily Unavailable)
4. Process timeout

**No Retry Conditions:**
- Exit code 0 (success)
- Max retries exceeded
- WAV file not found

**SIP Status Code Handling:**

| Code | Meaning | Action |
|------|---------|--------|
| 0 | Success | Return (call completed) |
| 480 | Temporarily Unavailable | Retry after delay |
| Other | Various errors | Retry after delay |

**Technical Details:**
- **Recursive Implementation:** `letfn` local function for recursion
- **Backoff Strategy:** Fixed 10s delay (not exponential)
- **Thread Cleanup:** `future-cancel` for reader thread
- **Output Buffering:** Accumulates all output in atom

**Monitoring:**
```clojure
;; Output monitoring thread
(future
  (doseq [line (line-seq reader)]
    (swap! output conj line)
    (ts-println "[BARESIP]:" line)))
```

---

#### `start-batch-worker! []`
**Location:** [core.clj:175-198](src/tts_caller/core.clj#L175-L198)

**Purpose:** Background worker processing call queue

**Signature:**
```clojure
(start-batch-worker!) → nil
```

**Behavior:**
1. Creates infinite `go-loop`
2. Blocks on channel read (`<!`)
3. Processes batch job:
   - Generate audio
   - Iterate phones
   - Call each phone
4. Loops back to wait for next job

**Concurrency Model:**
- **Type:** CSP (Communicating Sequential Processes)
- **Thread:** Runs on core.async thread pool
- **Blocking:** Blocks on empty channel (no CPU usage)
- **Lifecycle:** Runs until channel closed

**Job Structure:**
```clojure
{:text "Alert message"
 :phones ["123456" "789012"]
 :wav-path "/tmp/final_batch_1234567890.wav"
 :engine "marytts"
 :repeat 3}
```

**Processing Flow:**
```
1. (<! batch-queue-channel)  ;; Block until job available
2. Generate WAV file once (shared for all phones)
3. For each phone:
   a. call-sip-single (blocks until complete/failed)
   b. If exception: log error, sleep 15s
4. Delete WAV? NO (current implementation leaks files)
5. Loop back to step 1
```

**Error Handling:**
- Audio generation failure: Log error, skip batch
- Individual call failure: Log error, continue to next phone
- All errors caught (worker never dies)

**Performance:**
- **Sequential Processing:** One batch at a time
- **No Parallelism:** Phones called sequentially
- **Throughput:** ~60s per phone (45s timeout + retry delays)

---

#### `split-phones [s]`
**Location:** [core.clj:201-203](src/tts_caller/core.clj#L201-L203)

**Purpose:** Parse phone number list from string

**Signature:**
```clojure
(split-phones s) → vector of strings
```

**Parameters:**
- `s` (string): Phone numbers separated by commas or spaces

**Algorithm:**
```clojure
(->> (clojure.string/split s #"[,\s]+")
     (remove clojure.string/blank?))
```

**Examples:**
```clojure
(split-phones "123456")
;; => ["123456"]

(split-phones "123456,789012")
;; => ["123456" "789012"]

(split-phones "123456 789012 999999")
;; => ["123456" "789012" "999999"]

(split-phones "123456,  ,789012")
;; => ["123456" "789012"]  ; empty entries removed
```

**Regex Pattern:**
- `[,\s]+`: One or more commas or whitespace characters
- Supports tabs, newlines, multiple spaces

**Edge Cases:**
- Empty string → empty vector
- Only separators → empty vector
- Trailing/leading separators → ignored

---

#### `handle-call [{:keys [query-params]}]`
**Location:** [core.clj:206-222](src/tts_caller/core.clj#L206-L222)

**Purpose:** HTTP request handler for /call endpoint

**Signature:**
```clojure
(handle-call request) → Ring response map
```

**Parameters:**
- `request` (map): Ring request map with :query-params

**Query Parameters:**
- `text` (required): Message to speak
- `phone` (required): Phone number(s)
- `engine` (optional): "marytts" or "espeak" (default: "marytts")
- `repeat` (optional): Integer (default: 3)

**Response Codes:**

| Code | Condition | Body |
|------|-----------|------|
| 200 | Success (queued) | "📞 Calls queued for: 123456, 789012" |
| 400 | Missing text/phone | "❌ Missing ?text=...&phone=..." |
| 503 | Queue full | "Service busy" |

**Request Flow:**
```
1. Extract query parameters
2. Parse phone numbers (split-phones)
3. Validate text and phones not empty
4. Generate unique WAV path with timestamp
5. Create batch job map
6. Attempt to enqueue (async/offer!)
   ├─> Success: Return 200
   └─> Failure (queue full): Return 503
```

**Technical Details:**
- **WAV Path Generation:** `/tmp/final_batch_${timestamp}.wav`
- **Timestamp:** `System/currentTimeMillis`
- **Queue Behavior:** Non-blocking write (offer!)
- **Default Engine:** "marytts"
- **Default Repeat:** 3

**Example Requests:**
```bash
# Simple call
curl "http://localhost:8899/call?text=Hello&phone=123456"

# Multiple phones
curl "http://localhost:8899/call?text=Alert&phone=123,456,789"

# Custom engine and repeat
curl "http://localhost:8899/call?text=Test&phone=123&engine=espeak&repeat=5"
```

---

### Audio Namespace Functions

#### `create-mary []`
**Location:** [audio.clj:20-24](src/tts_caller/audio.clj#L20-L24)

**Purpose:** Instantiate MaryTTS engine via reflection

**Signature:**
```clojure
(create-mary) → LocalMaryInterface instance
```

**Implementation:**
```clojure
(let [cls (Class/forName "marytts.LocalMaryInterface")
      ctor (.getConstructor cls (into-array Class []))]
  (.newInstance ctor (object-array [])))
```

**Why Reflection?**
- MaryTTS JAR loaded at runtime (not compile-time dependency)
- Allows dynamic engine selection
- Avoids classpath issues during compilation

**Technical Details:**
- **Class:** `marytts.LocalMaryInterface`
- **Constructor:** No-args constructor
- **Initialization:** Loads voice files from `lib/` directory
- **Performance:** Slow first call (~2-3 seconds), fast thereafter

---

#### `generate-audio-bytes-plain [text voice]`
**Location:** [audio.clj:39-52](src/tts_caller/audio.clj#L39-L52)

**Purpose:** Generate speech audio from plain text

**Signature:**
```clojure
(generate-audio-bytes-plain text voice) → byte array
```

**Parameters:**
- `text` (string): Text to synthesize
- `voice` (string): Voice name (e.g., "cmu-slt-hsmm")

**Algorithm:**
```
1. Create MaryTTS instance
2. Set voice (e.g., "cmu-slt-hsmm")
3. Set audio effects: "Rate(durScale:1.5)" (1.5x speed)
4. Generate audio stream
5. Read stream into byte array
6. Return raw audio bytes
```

**Audio Effects:**
- **Rate(durScale:1.5):** Speed up speech by 50%
- Makes alerts more concise
- Still intelligible at 1.5x speed

**Output Format:**
- **Sample Rate:** 16kHz
- **Bit Depth:** 16-bit signed PCM
- **Channels:** Mono
- **Encoding:** Little-endian

---

#### `silence-bytes [millis format]`
**Location:** [audio.clj:75-80](src/tts_caller/audio.clj#L75-L80)

**Purpose:** Generate silence audio data

**Signature:**
```clojure
(silence-bytes millis format) → byte array
```

**Parameters:**
- `millis` (number): Duration in milliseconds
- `format` (AudioFormat): Audio format specification

**Calculation:**
```clojure
bytes-per-ms = (sample_rate / 1000) * (bit_depth / 8) * channels
total-bytes = millis * bytes-per-ms
```

**Example:**
```clojure
;; For 16kHz, 16-bit, mono:
;; bytes-per-ms = (16000 / 1000) * (16 / 8) * 1 = 32 bytes/ms
;; For 1500ms: 1500 * 32 = 48000 bytes

(def format (AudioFormat. 16000 16 1 true false))
(silence-bytes 1500 format)
;; => byte array of 48000 zeros
```

**Use Cases:**
- Add pause before message (prevents cut-off)
- Add gap after message (prevents abrupt end)
- Current: 1500ms start, 500ms end

---

#### `concat-audio-streams [streams format]`
**Location:** [audio.clj:82-89](src/tts_caller/audio.clj#L82-L89)

**Purpose:** Concatenate multiple audio byte arrays

**Signature:**
```clojure
(concat-audio-streams streams format) → AudioInputStream
```

**Parameters:**
- `streams` (seq of byte arrays): Audio data to concatenate
- `format` (AudioFormat): Format of audio data

**Implementation:**
```clojure
1. Flatten all byte arrays into single vector
2. Convert to byte array
3. Wrap in ByteArrayInputStream
4. Wrap in AudioInputStream with format metadata
5. Calculate frame length
```

**Frame Length Calculation:**
```clojure
frame-length = total-bytes / (bit-depth * 0.125 * channels)
```

**Example:**
```clojure
(let [silence1 (silence-bytes 1000 format)
      speech (generate-audio-bytes-plain "Hello" "cmu-slt-hsmm")
      silence2 (silence-bytes 500 format)]
  (concat-audio-streams [silence1 speech silence2] format))
;; => AudioInputStream with: [1s silence][speech][0.5s silence]
```

---

#### `generate-final-wav-auto [text outfile & options]`
**Location:** [audio.clj:91-127](src/tts_caller/audio.clj#L91-L127)

**Purpose:** Generate final 8kHz WAV file for telephony

**Signature:**
```clojure
(generate-final-wav-auto text outfile
  & {:keys [tts-engine repeat voice rate gain]
     :or {tts-engine "marytts"
          repeat 3
          voice "dfki-ot-hsmm"
          rate "default"
          gain 0.0}})
→ nil
```

**Parameters:**
- `text` (string): Text to synthesize
- `outfile` (string): Output WAV file path
- `tts-engine` (keyword opt): "marytts" or "espeak"
- `repeat` (keyword opt): Repeat count (ignored in current implementation)
- `voice` (keyword opt): Voice name
- `rate` (keyword opt): Speech rate (currently unused)
- `gain` (keyword opt): Audio gain in dB

**Processing Pipeline:**

**MaryTTS Path:**
```
1. Check if text starts with "<speak>" (SSML)
2. Generate 16kHz audio (plain or SSML)
3. Add 1500ms silence at start
4. Add 500ms silence at end
5. Write temporary 16kHz WAV
6. Resample to 8kHz mono with Sox:
   sox input.wav -r 8000 -c 1 output.wav
```

**espeak-ng Path:**
```
1. Execute shell command:
   espeak-ng -v tr -s 140 "text" --stdout | \
   sox -t wav - -r 8000 -c 1 -b 16 temp.wav gain <gain>
2. Additional Sox conversion to final format:
   sox temp.wav -r 8000 -c 1 output.wav
```

**Technical Details:**

**MaryTTS Options:**
- Voice: "dfki-ot-hsmm" (German female)
- Effects: "Rate(durScale:1.5)"
- Format: 16kHz → 8kHz conversion

**espeak-ng Options:**
- Voice: Turkish (`-v tr`)
- Speed: 140 words/minute (`-s 140`)
- Gain: Configurable (default 0.0 dB)

**Why 8kHz?**
- Telephony standard (G.711 codec)
- Sufficient for speech intelligibility
- Reduces bandwidth and file size
- Compatible with all SIP systems

**File Output:**
- Format: WAV (RIFF header)
- Sample Rate: 8000 Hz
- Bit Depth: 16-bit
- Channels: 1 (mono)
- Encoding: Signed PCM, little-endian

---

## Process Lifecycle

### Application Startup

```
1. JVM Initialization
   ├─> Load Clojure runtime
   ├─> Load application namespaces
   └─> Execute -main function

2. Configuration Loading (core.clj:21-24)
   ├─> Read SIP_USER from environment
   ├─> Read SIP_PASS from environment
   ├─> Read SIP_HOST from environment
   └─> Use defaults if not set

3. Channel Creation (core.clj:32)
   └─> Create batch-queue-channel with capacity 10

4. Worker Thread Startup (core.clj:234)
   ├─> Call (start-batch-worker!)
   ├─> Spawn go-loop on core.async thread pool
   └─> Block on channel read

5. HTTP Server Startup (core.clj:235)
   ├─> Call (run-jetty app {:port 8899 :join? false})
   ├─> Bind to port 8899
   ├─> Start Jetty in background thread
   └─> Return control to REPL (if :join? false)

6. Ready State
   └─> Application ready to accept requests
```

**Startup Time:**
- Cold start: ~5-10 seconds
- MaryTTS initialization: ~2-3 seconds (lazy, on first use)
- Baresip: Not started until first call

---

### Request Processing Lifecycle

```
┌─────────────────────────────────────────┐
│  1. HTTP Request Received (Jetty)       │
└────────────────┬────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────┐
│  2. Middleware Processing               │
│     - wrap-params (parse query string)  │
└────────────────┬────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────┐
│  3. Route Matching (Compojure)          │
│     GET /call → handle-call             │
└────────────────┬────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────┐
│  4. Request Handler (handle-call)       │
│     - Extract parameters                │
│     - Validate inputs                   │
│     - Parse phone numbers               │
│     - Create job map                    │
└────────────────┬────────────────────────┘
                 │
                 ▼
         ┌───────┴────────┐
         │                │
    Valid Input?      Invalid?
         │                │
         ▼                ▼
   ┌──────────┐    ┌──────────────┐
   │ Continue │    │ Return 400   │
   └────┬─────┘    └──────────────┘
        │
        ▼
┌──────────────────────────────────────────┐
│  5. Queue Enqueue (async/offer!)         │
│     - Non-blocking write attempt         │
└────────────┬─────────────────────────────┘
             │
      ┌──────┴───────┐
      │              │
 Queue Free?    Queue Full?
      │              │
      ▼              ▼
┌───────────┐  ┌──────────┐
│ Return    │  │ Return   │
│ 200 OK    │  │ 503 Busy │
└───────────┘  └──────────┘
      │
      ▼
┌─────────────────────────────────────────┐
│  6. HTTP Response Sent                   │
│     Client receives confirmation         │
└─────────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────────┐
│  7. Async Processing (Batch Worker)     │
│     - Dequeue job from channel          │
│     - Generate audio file               │
│     - Iterate phone numbers             │
│     - Make SIP calls (sequential)       │
└─────────────────────────────────────────┘
```

**Timing Breakdown:**

| Phase | Typical Duration |
|-------|------------------|
| HTTP parsing | <1ms |
| Route matching | <1ms |
| Request handling | 1-5ms |
| Queue enqueue | <1ms |
| **Total response time** | **<10ms** |
| Audio generation (MaryTTS) | 1000-2000ms |
| Audio generation (espeak) | 100-300ms |
| Single SIP call (success) | 20000-30000ms |
| Single SIP call (retry 3x) | 60000-90000ms |

---

### SIP Call Lifecycle

```
┌─────────────────────────────────────────────────────────────┐
│  Phase 1: Preparation                                        │
├─────────────────────────────────────────────────────────────┤
│  1. Kill existing Baresip processes                         │
│     pkill -f baresip                                         │
│     ├─> Wait 1000ms                                         │
│     └─> Verify processes killed                             │
│                                                              │
│  2. Wait for port 50060 to be free                          │
│     Loop until: ss -tulpn | grep :50060 → no match         │
│     └─> Poll every 2000ms                                   │
│                                                              │
│  3. Create configuration files                              │
│     /tmp/baresip_config/accounts                            │
│     /tmp/baresip_config/config                              │
└─────────────────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│  Phase 2: Baresip Startup                                   │
├─────────────────────────────────────────────────────────────┤
│  4. Launch Baresip process                                  │
│     Command: baresip -f /tmp/baresip_config \               │
│              -e /ausrc aufile,/tmp/file.wav \               │
│              -e /dial sip:PHONE@HOST                        │
│                                                              │
│  5. Start output reader thread                              │
│     Future thread reads stdout line-by-line                 │
│     Each line logged with [BARESIP] prefix                  │
└─────────────────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│  Phase 3: SIP Registration & Call Setup                     │
├─────────────────────────────────────────────────────────────┤
│  [Baresip Internal]                                         │
│  6. SIP REGISTER                                            │
│     → SIP server (with credentials)                         │
│     ← 200 OK (registration successful)                      │
│                                                              │
│  7. SIP INVITE                                              │
│     → To: sip:PHONE@HOST                                    │
│     ← 100 Trying                                            │
│     ← 180 Ringing                                           │
│     ← 200 OK (call accepted)                                │
│         OR                                                   │
│     ← 486 Busy Here                                         │
│     ← 480 Temporarily Unavailable                           │
│     ← 404 Not Found                                         │
└─────────────────────────────────────────────────────────────┘
                         │
                ┌────────┴────────┐
                │                 │
           Call Accepted    Call Rejected
                │                 │
                ▼                 ▼
┌──────────────────────┐   ┌──────────────────┐
│  Phase 4: Audio      │   │  Phase 4: Retry  │
│  Streaming           │   │  Logic           │
├──────────────────────┤   ├──────────────────┤
│  8. SIP ACK sent     │   │  8. Parse error  │
│                      │   │     code         │
│  9. RTP session      │   │                  │
│     established      │   │  9. Log failure  │
│                      │   │                  │
│  10. Audio stream    │   │  10. Sleep 10s   │
│      WAV file sent   │   │                  │
│      via RTP/G.711   │   │  11. Retry call  │
│      Repeat N times  │   │      (max 3x)    │
│                      │   │                  │
│  11. SIP BYE sent    │   └──────────────────┘
│      (call end)      │            │
│                      │            │
│  12. RTP teardown    │            │
└──────────┬───────────┘            │
           │                        │
           └────────────┬───────────┘
                        ▼
┌─────────────────────────────────────────────────────────────┐
│  Phase 5: Cleanup                                           │
├─────────────────────────────────────────────────────────────┤
│  13. Wait for Baresip exit (45s timeout)                    │
│      ├─> Exit code 0: Success                               │
│      ├─> Exit code >0: Failure                              │
│      └─> Timeout: Force kill                                │
│                                                              │
│  14. Stop output reader thread                              │
│      future-cancel                                          │
│                                                              │
│  15. Kill Baresip processes                                 │
│      pkill -f baresip                                        │
│                                                              │
│  16. Wait for port release                                  │
│      Loop until port 50060 free                             │
└─────────────────────────────────────────────────────────────┘
```

**Baresip Command Breakdown:**

```bash
baresip \
  -f /tmp/baresip_config \              # Config directory
  -e "/ausrc aufile,/tmp/file.wav" \    # Audio source: file playback
  -e "/dial sip:123456@pbx.host"        # Dial command
```

**Command-line Options:**
- `-f DIR`: Use DIR for configuration files
- `-e CMD`: Execute command on startup

**Baresip Commands:**
- `/ausrc aufile,FILE`: Set audio source to file
- `/dial URI`: Initiate call to SIP URI

---

## SIP Communication

### SIP Protocol Flow

```
Client (Baresip)                    SIP Server/PBX              Destination Phone
     │                                    │                            │
     │──── REGISTER ──────────────────────>│                            │
     │     From: sip:user@pbx              │                            │
     │     To: sip:user@pbx                │                            │
     │     Contact: sip:user@client-ip     │                            │
     │     Authorization: Digest ...       │                            │
     │                                     │                            │
     │<───── 200 OK ──────────────────────│                            │
     │     Expires: 300                    │                            │
     │                                     │                            │
     │──── INVITE ────────────────────────>│                            │
     │     From: sip:user@pbx              │                            │
     │     To: sip:destination@pbx         │                            │
     │     Content-Type: application/sdp   │                            │
     │     SDP: (media capabilities)       │                            │
     │                                     │                            │
     │                                     │──── INVITE ──────────────>│
     │                                     │                            │
     │<───── 100 Trying ──────────────────│                            │
     │                                     │                            │
     │                                     │<──── 180 Ringing ─────────│
     │<───── 180 Ringing ─────────────────│                            │
     │                                     │                            │
     │                                     │<──── 200 OK ──────────────│
     │<───── 200 OK ──────────────────────│     SDP: (media accepted)  │
     │     Content-Type: application/sdp   │                            │
     │     SDP: (media accepted)           │                            │
     │                                     │                            │
     │──── ACK ────────────────────────────>│──── ACK ──────────────────>│
     │                                     │                            │
     │<═══════════════════ RTP Audio Stream ═══════════════════════════>│
     │                    (G.711 PCMU/PCMA, 8kHz)                       │
     │                    (WAV file playback, repeated N times)         │
     │<═════════════════════════════════════════════════════════════════>│
     │                                     │                            │
     │──── BYE ────────────────────────────>│──── BYE ──────────────────>│
     │                                     │                            │
     │<───── 200 OK ──────────────────────│<──── 200 OK ──────────────│
     │                                     │                            │
```

### SDP (Session Description Protocol) Example

**INVITE SDP Body:**
```
v=0
o=- 1234567890 1234567890 IN IP4 192.168.1.100
s=Baresip
c=IN IP4 192.168.1.100
t=0 0
m=audio 49152 RTP/AVP 0 8
a=rtpmap:0 PCMU/8000
a=rtpmap:8 PCMA/8000
a=sendrecv
```

**SDP Field Meanings:**
- `v=0`: SDP version
- `o=`: Originator (username, session ID, version, IP)
- `s=`: Session name
- `c=`: Connection data (IP address)
- `t=`: Timing (start=0, end=0 means permanent)
- `m=audio`: Media description
  - Port: 49152 (dynamic RTP port)
  - Protocol: RTP/AVP
  - Payload types: 0 (PCMU), 8 (PCMA)
- `a=rtpmap`: Codec mappings
  - 0: PCMU (G.711 μ-law) at 8kHz
  - 8: PCMA (G.711 A-law) at 8kHz
- `a=sendrecv`: Bidirectional audio

---

### RTP (Real-Time Protocol) Audio Streaming

**RTP Packet Structure:**
```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|V=2|P|X|  CC   |M|     PT      |       sequence number         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                           timestamp                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|           synchronization source (SSRC) identifier            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         payload (audio)                       |
|                            ...                                |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

**G.711 Codec Parameters:**
- **Sample Rate:** 8000 Hz
- **Bit Rate:** 64 kbps
- **Frame Size:** 20ms (160 samples)
- **Payload Size:** 160 bytes per packet
- **Packet Rate:** 50 packets/second
- **Codec Delay:** <1ms (very low latency)

**Audio Streaming Process:**
1. Baresip reads WAV file (8kHz, 16-bit PCM)
2. Converts to G.711 (8-bit compressed)
3. Packetizes into RTP packets (160 bytes each)
4. Sends 50 packets/second via UDP
5. Destination phone decodes and plays audio

---

## Audio Processing Pipeline

### MaryTTS Pipeline

```
┌──────────────────────────────────────────────────────────────┐
│  Input: Text String                                           │
│  "Server disk is full!"                                       │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────┐
│  Step 1: Text Analysis                                        │
│  • Tokenization (split into words)                           │
│  • Part-of-speech tagging                                    │
│  • Phonetic transcription                                    │
│  • Prosody prediction (intonation, stress)                   │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────┐
│  Step 2: Waveform Synthesis (HMM-based)                      │
│  • Hidden Markov Model voice (cmu-slt-hsmm)                  │
│  • Generate speech parameters (F0, spectrum, duration)       │
│  • Synthesize waveform                                       │
│  • Apply audio effects: Rate(durScale:1.5)                   │
│  Output: AudioInputStream (16kHz, 16-bit, mono)              │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────┐
│  Step 3: Silence Padding                                     │
│  • Generate 1500ms silence (48000 bytes)                     │
│  • Prepend to audio stream                                   │
│  • Generate 500ms silence (16000 bytes)                      │
│  • Append to audio stream                                    │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────┐
│  Step 4: Concatenation                                       │
│  • Merge byte arrays:                                        │
│    [silence-1500ms][speech][silence-500ms]                   │
│  • Wrap in AudioInputStream                                  │
│  • Format: 16kHz, 16-bit, mono, signed PCM                   │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────┐
│  Step 5: Write Temporary WAV                                 │
│  • AudioSystem.write()                                       │
│  • File: /tmp/OUTPUT.wav.tmp.wav                             │
│  • Format: WAV/RIFF container                                │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────┐
│  Step 6: Resample to 8kHz (Sox)                              │
│  • Command: sox input.wav -r 8000 -c 1 output.wav           │
│  • Downsample: 16kHz → 8kHz                                  │
│  • Maintain: mono, 16-bit PCM                                │
│  • Delete temporary file                                     │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────┐
│  Output: Final WAV File                                       │
│  • Path: /tmp/final_batch_1234567890.wav                     │
│  • Format: 8kHz, 16-bit, mono, PCM                           │
│  • Size: ~16KB per second of speech                          │
│  • Ready for SIP call                                        │
└──────────────────────────────────────────────────────────────┘
```

### espeak-ng Pipeline

```
┌──────────────────────────────────────────────────────────────┐
│  Input: Text String                                           │
│  "Sunucu diski dolu!" (Turkish)                              │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────┐
│  Step 1: espeak-ng Generation + Sox Processing (piped)       │
│  • Command:                                                   │
│    espeak-ng -v tr -s 140 "TEXT" --stdout | \                │
│    sox -t wav - -r 8000 -c 1 -b 16 temp.wav gain 0.0        │
│                                                               │
│  espeak-ng options:                                          │
│    -v tr: Turkish voice                                      │
│    -s 140: Speed 140 words/minute                           │
│    --stdout: Output to stdout (not file)                    │
│                                                               │
│  sox options (piped from espeak):                            │
│    -t wav -: Input from stdin, WAV format                   │
│    -r 8000: Resample to 8kHz                                │
│    -c 1: Mono                                               │
│    -b 16: 16-bit depth                                      │
│    gain 0.0: Apply 0dB gain (no change)                    │
│                                                               │
│  Output: /tmp/generated.wav (8kHz)                          │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────┐
│  Step 2: Final Sox Conversion (redundant in current code)    │
│  • Command: sox temp.wav -r 8000 -c 1 final.wav             │
│  • Already 8kHz from step 1 (unnecessary)                    │
│  • Output: Final WAV file                                    │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────┐
│  Output: Final WAV File                                       │
│  • Path: /tmp/final_batch_1234567890.wav                     │
│  • Format: 8kHz, 16-bit, mono, PCM                           │
│  • Size: ~16KB per second of speech                          │
│  • Faster generation than MaryTTS (~5-10x)                   │
└──────────────────────────────────────────────────────────────┘
```

**Comparison:**

| Aspect | MaryTTS | espeak-ng |
|--------|---------|-----------|
| Quality | High (natural) | Lower (robotic) |
| Speed | Slow (1-2s) | Fast (100-300ms) |
| Languages | 4 (en, de, ru, tr) | 100+ |
| Voices | 2 included | Many built-in |
| File Size | Large (~100MB JARs) | Small (~5MB) |
| Memory | High (~200MB) | Low (~10MB) |
| CPU | High (HMM synthesis) | Low (formant synthesis) |
| Customization | SSML, effects | Limited |
| Use Case | High-quality alerts | Fast, simple alerts |

---

## Concurrency Model

### Thread Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         JVM Process                             │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  Main Thread                                                │ │
│  │  • Application startup                                     │ │
│  │  • Configuration loading                                   │ │
│  │  • Channel creation                                        │ │
│  │  • Worker startup                                          │ │
│  │  • HTTP server startup                                     │ │
│  │  • Then: blocked/waiting                                   │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  Jetty Thread Pool (default: 200 threads)                  │ │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐                 │ │
│  │  │ Request  │  │ Request  │  │ Request  │  ...             │ │
│  │  │ Handler  │  │ Handler  │  │ Handler  │                 │ │
│  │  │ Thread 1 │  │ Thread 2 │  │ Thread 3 │                 │ │
│  │  └────┬─────┘  └────┬─────┘  └────┬─────┘                 │ │
│  │       │             │             │                        │ │
│  │       └─────────────┼─────────────┘                        │ │
│  │                     │                                       │ │
│  │                     ▼                                       │ │
│  │           ┌──────────────────┐                             │ │
│  │           │ handle-call      │                             │ │
│  │           │ (async/offer!)   │                             │ │
│  │           └────────┬─────────┘                             │ │
│  │                    │ (non-blocking write)                  │ │
│  └────────────────────┼───────────────────────────────────────┘ │
│                       │                                          │
│                       ▼                                          │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  core.async Thread Pool (default: CPU cores + 2)           │ │
│  │  ┌──────────────────────────────────────────────────────┐  │ │
│  │  │  Batch Worker (go-loop)                               │  │ │
│  │  │  • One worker thread                                  │  │ │
│  │  │  • Blocks on channel read (<!)                        │  │ │
│  │  │  • Processes jobs sequentially                        │  │ │
│  │  │  • Spawns child threads for:                          │  │ │
│  │  │    - Baresip output reading                           │  │ │
│  │  │    - Process monitoring                               │  │ │
│  │  └────────────────┬─────────────────────────────────────┘  │ │
│  │                   │                                         │ │
│  │                   ▼                                         │ │
│  │        ┌────────────────────┐                              │ │
│  │        │ call-sip-single    │                              │ │
│  │        │ (blocks on call)   │                              │ │
│  │        └────────┬───────────┘                              │ │
│  │                 │                                           │ │
│  │                 ▼                                           │ │
│  │  ┌──────────────────────────────────────────────────────┐  │ │
│  │  │  Future Thread (output reader)                        │  │ │
│  │  │  • Created per call                                   │  │ │
│  │  │  • Reads Baresip stdout                               │  │ │
│  │  │  • Logs each line                                     │  │ │
│  │  │  • Cancelled on call end                              │  │ │
│  │  └──────────────────────────────────────────────────────┘  │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

External Processes (not in JVM):
┌─────────────────────────────────────────┐
│  Baresip Process (one per active call)  │
│  • Started by ProcessBuilder            │
│  • Monitored by Future thread           │
│  • Killed after call completion         │
└─────────────────────────────────────────┘
```

### Concurrency Primitives

#### core.async Channels

**Channel Definition:**
```clojure
(def batch-queue-channel (chan 10))
```

**Channel Semantics:**
- **Type:** Bounded buffer channel
- **Capacity:** 10 items
- **Blocking Behavior:**
  - Put (>!!, offer!): Blocks when full
  - Take (<!, <!!, poll!): Blocks when empty
- **Thread Safety:** Lock-free implementation
- **Backpressure:** Automatic via bounded buffer

**Operations:**

| Operation | Blocking? | Use Case |
|-----------|-----------|----------|
| `>!!` | Yes | Blocking put (not used in code) |
| `offer!` | No | Try put, return false if full |
| `<! | Yes | Blocking take (in go block) |
| `poll!` | No | Try take, return nil if empty |
| `close!` | No | Close channel |

**Current Usage:**
```clojure
;; Non-blocking write (returns true/false)
(async/offer! batch-queue-channel batch-job)

;; Blocking read (in go-loop)
(<! batch-queue-channel)
```

**Why Bounded Channel?**
- Prevents memory exhaustion from request flood
- Provides backpressure to clients (503 when full)
- Limits concurrent resource usage
- Bounded capacity = bounded WAV files on disk

---

### Process Synchronization

**Critical Section: Port 50060 Access**

```clojure
;; Problem: Multiple calls would conflict on same port
;; Solution: Sequential processing via single worker

Worker Thread (sequential):
  ├─> Dequeue job 1
  ├─> Kill Baresip (if running)
  ├─> Wait for port free
  ├─> Start Baresip on port 50060
  ├─> Wait for call completion (up to 45s)
  ├─> Kill Baresip
  ├─> Wait for port free
  ├─> Dequeue job 2
  └─> ...
```

**No Locking Needed:**
- Only one worker thread
- Sequential processing ensures mutual exclusion
- Port 50060 never accessed concurrently

**Alternative (not implemented):**
- Multiple workers with dynamic port allocation
- Each worker uses different port (50061, 50062, ...)
- Requires port pool management
- Allows parallel calls

---

### Thread Safety Analysis

**Thread-Safe Components:**
- ✅ Atom operations (swap!, reset!)
- ✅ core.async channels (lock-free)
- ✅ Immutable data structures (all Clojure collections)
- ✅ println (synchronized in Java)

**Not Thread-Safe (but OK):**
- ❌ File system operations (no concurrent access in current design)
- ❌ Baresip process (single instance at a time)
- ❌ Global vars (only read, never written after startup)

**Potential Race Conditions (if scaling):**
- ⚠️ Port allocation (if multiple workers)
- ⚠️ WAV file deletion (if parallel processing)
- ⚠️ Baresip process management (if concurrent calls)

---

## Configuration Management

### Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `SIP_USER` | No | "python_client" | SIP account username |
| `SIP_PASS` | No | "1234pass" | SIP account password |
| `SIP_HOST` | No | "10.22.6.249" | SIP server IP/hostname |

**Loading Mechanism:**
```clojure
(def sip-user (or (System/getenv "SIP_USER") "python_client"))
```

**Evaluation Time:** Namespace load (once at startup)

**Reconfiguration:** Requires service restart

---

### Hard-Coded Configuration

**Network Configuration:**
```clojure
(def sip-port "50060")              ;; SIP listen port
;; HTTP server port: 8899 (in -main)
```

**Retry Configuration:**
```clojure
(def max-retries 3)                 ;; Max call attempts
(def retry-delay-ms 10000)          ;; Delay between retries
```

**Timeout Configuration:**
```clojure
;; Process timeout: 45000ms (in call-sip-single)
;; Port wait polling: 2000ms (in wait-for-port-release)
;; Process kill sleep: 1000ms (in kill-baresip)
;; Call failure sleep: 15000ms (in batch worker)
```

**Queue Configuration:**
```clojure
(def batch-queue-channel (chan 10)) ;; Queue capacity
```

**Audio Configuration:**
```clojure
;; Silence padding: 1500ms start, 500ms end
;; Speech rate: 1.5x (durScale:1.5)
;; Sample rate: 8kHz output
;; Default engine: "marytts"
;; Default repeat: 3
```

**File Paths:**
```clojure
(def baresip-dir "/tmp/baresip_config")
(def accounts-path (str baresip-dir "/accounts"))
(def config-path (str baresip-dir "/config"))
;; WAV files: /tmp/final_batch_${timestamp}.wav
```

---

## Error Handling Strategy

### Exception Hierarchy

```
java.lang.Exception
 ├─ java.lang.RuntimeException
 │   └─ clojure.lang.ExceptionInfo (used for structured errors)
 ├─ java.io.IOException (file operations)
 ├─ java.lang.InterruptedException (thread operations)
 └─ java.util.concurrent.TimeoutException (process timeout)
```

### Error Handling Patterns

**Pattern 1: Log and Continue (Batch Worker)**
```clojure
(catch Exception e
  (ts-println "💥 Batch error:" (.getMessage e)))
;; Loop continues to next job
```

**Pattern 2: Retry with Delay (Call Execution)**
```clojure
(catch Exception e
  (future-cancel reader-thread)
  (kill-baresip)
  (ts-println "💥 Error:" (.getMessage e) "- Retrying...")
  (Thread/sleep retry-delay-ms)
  (attempt-call (inc n)))
```

**Pattern 3: Throw After Max Retries**
```clojure
(when (> n max-retries)
  (ts-println "❌ Call failed after" max-retries "attempts:" phone)
  (throw (ex-info "Call failed after retries"
                  {:phone phone :max-retries max-retries})))
```

**Pattern 4: Return Error Response (HTTP Handler)**
```clojure
(if (and text (seq phones-list))
  ;; Success path
  (if (async/offer! batch-queue-channel batch-job)
    (resp/response "Calls queued")
    (resp/status (resp/response "Service busy") 503))
  ;; Error path
  (resp/bad-request "Missing parameters"))
```

---

### Error Scenarios & Handling

| Scenario | Detection | Handling | User Impact |
|----------|-----------|----------|-------------|
| Missing text/phone parameter | Query param check | Return 400 | Immediate error |
| Queue full | offer! returns false | Return 503 | Immediate error |
| WAV file not found | File.exists check | Throw exception, retry | Logged, call fails |
| Baresip process timeout | ProcessBuilder.waitFor | Retry call | Delay, eventual success/failure |
| SIP 480 (Unavailable) | Output string match | Retry after delay | Delay, eventual success/failure |
| SIP other error | Non-zero exit code | Retry after delay | Delay, eventual success/failure |
| pkill failure | sh exit code | Try killall | Process may leak |
| Port still in use | ss command check | Wait and retry | Delay in next call |
| Audio generation failure | Exception in generate-final-wav-auto | Log error, skip batch | Batch fails |
| Individual call failure | Exception in call-sip-single | Log error, continue to next phone | One phone fails, others succeed |

---

## Deployment Architecture

### Docker Container Structure

```
Container: tts-caller
├─ Base Image: quay.io/centos/centos:stream9
├─ Installed Packages:
│  ├─ baresip (SIP client)
│  ├─ baresip-alsa (audio backend)
│  ├─ baresip-sndfile (WAV file support)
│  ├─ sox (audio processing)
│  ├─ espeak-ng (TTS engine)
│  ├─ java-17-openjdk (Java runtime)
│  ├─ leiningen (Clojure build tool)
│  └─ procps, psmisc, nmap-ncat (utilities)
├─ Application:
│  ├─ /app/src/ (Clojure source code)
│  ├─ /app/lib/ (MaryTTS JAR files)
│  ├─ /app/target/tts-caller-standalone.jar (compiled uberjar)
│  └─ /tmp/baresip_config/ (runtime config directory)
├─ Exposed Ports:
│  └─ 8899 (HTTP API)
└─ Entrypoint:
   └─ java -Dmary.base=/app/lib \
      -cp target/tts-caller-standalone.jar:lib/* \
      clojure.main -m tts-caller.core
```

### Runtime File System

```
/tmp/
├─ baresip_config/
│  ├─ accounts             (SIP credentials)
│  ├─ config               (Baresip modules & settings)
│  └─ *.so                 (Baresip modules - optional copy)
├─ final_batch_1234567890.wav
├─ final_batch_1234567891.wav
├─ final_batch_1234567892.wav  ⚠️ Never deleted!
└─ ...                     (Files accumulate)

/app/
├─ lib/
│  ├─ marytts-client-5.2-jar-with-dependencies.jar
│  ├─ marytts-runtime-5.2-jar-with-dependencies.jar
│  ├─ marytts-lang-en-5.2.jar
│  ├─ marytts-lang-ru-5.2.jar
│  ├─ marytts-lang-tr-5.2.jar
│  ├─ voice-cmu-slt-hsmm-5.2.jar
│  └─ voice-dfki-ot-hsmm-5.2.jar
├─ src/
│  └─ tts_caller/
│     ├─ core.clj
│     └─ audio.clj
├─ target/
│  └─ tts-caller-standalone.jar
└─ project.clj

/usr/lib64/baresip/modules/
├─ account.so
├─ g711.so
├─ aufile.so
└─ ... (other Baresip modules)
```

---

### Network Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  External Client (Monitoring System)                         │
│  - Zabbix, Alertmanager, etc.                               │
└────────────────────┬────────────────────────────────────────┘
                     │ HTTP (port 8899)
                     │ GET /call?text=...&phone=...
                     ▼
┌─────────────────────────────────────────────────────────────┐
│  Docker Host Network (--network=host mode)                   │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  TTS Caller Container                                   │ │
│  │  ├─ Jetty HTTP Server                                  │ │
│  │  │  └─ Listens: 0.0.0.0:8899                           │ │
│  │  └─ Baresip SIP Client                                 │ │
│  │     └─ Listens: 0.0.0.0:50060 (UDP)                    │ │
│  └────────────────────────────────────────────────────────┘ │
└────────────────────┬────────────────────────────────────────┘
                     │ SIP/UDP (port 50060)
                     │ RTP/UDP (dynamic ports 49152-65535)
                     ▼
┌─────────────────────────────────────────────────────────────┐
│  SIP Infrastructure (PBX/Proxy)                              │
│  - IP: 10.22.6.249 (or configured SIP_HOST)                 │
│  - Protocols: SIP (UDP 5060), RTP (UDP)                     │
└────────────────────┬────────────────────────────────────────┘
                     │ SIP/RTP
                     ▼
┌─────────────────────────────────────────────────────────────┐
│  Destination Phones (PSTN/SIP)                               │
└─────────────────────────────────────────────────────────────┘
```

**Network Requirements:**
- **Inbound:** Port 8899 (HTTP API)
- **Outbound:** Port 50060 (SIP signaling to PBX)
- **Outbound:** Ports 49152-65535 (RTP media, dynamic allocation)
- **Protocol:** UDP for SIP and RTP (no TCP/TLS support)

**Why --network=host?**
- Simplifies RTP port range mapping (dynamic ports)
- Avoids NAT traversal issues
- Baresip can bind to any port without Docker port mapping
- Trade-off: Less isolation, but easier SIP communication

---

## Integration Patterns

### Monitoring System Integration

#### Zabbix Integration

**Alerting Action Script:**
```bash
#!/bin/bash
# /usr/lib/zabbix/alertscripts/tts-caller.sh

TEXT="$1"      # {ALERT.MESSAGE}
PHONE="$2"     # Recipient phone number

curl --get "http://tts-caller:8899/call" \
  --data-urlencode "text=${TEXT}" \
  --data-urlencode "phone=${PHONE}" \
  --data-urlencode "engine=marytts" \
  --max-time 10

# Exit code: 0 if queued, non-zero if failed
```

**Zabbix Trigger Configuration:**
```
Trigger: Server {HOST.NAME} disk full
Severity: High
Actions:
  - Send voice alert to: 0722111111
  - Execute: tts-caller.sh "Disk full on {HOST.NAME}" "0722111111"
```

---

#### Prometheus Alertmanager Integration

**alertmanager.yml:**
```yaml
route:
  receiver: 'tts-caller'
  routes:
    - match:
        severity: critical
      receiver: 'tts-caller-urgent'

receivers:
  - name: 'tts-caller'
    webhook_configs:
      - url: 'http://tts-caller:8899/call?phone=0722111111'
        send_resolved: false
        http_config:
          params:
            text: ['{{ .GroupLabels.alertname }}: {{ .CommonAnnotations.summary }}']
            engine: ['espeak']
            repeat: ['3']

  - name: 'tts-caller-urgent'
    webhook_configs:
      - url: 'http://tts-caller:8899/call?phone=0722111111,0722222222'
        http_config:
          params:
            text: ['URGENT: {{ .GroupLabels.alertname }}']
            engine: ['marytts']
            repeat: ['5']
```

---

#### ElastAlert Integration

**elastalert_rule.yml:**
```yaml
name: Critical Error Alert
type: frequency
index: logs-*
num_events: 10
timeframe:
  minutes: 5

filter:
  - term:
      level: "ERROR"

alert:
  - "command"

command: >
  curl --get "http://tts-caller:8899/call"
  --data-urlencode "text=Critical errors detected in production"
  --data-urlencode "phone=0722111111"
  --data-urlencode "engine=espeak"
```

---

### API Integration Examples

#### Python Integration

```python
import requests
import urllib.parse

def send_voice_alert(message, phones, engine="marytts", repeat=3):
    """
    Send voice alert via TTS Caller service

    Args:
        message (str): Text to speak
        phones (list): Phone numbers to call
        engine (str): "marytts" or "espeak"
        repeat (int): Number of times to repeat message

    Returns:
        dict: Response status
    """
    url = "http://tts-caller:8899/call"

    params = {
        "text": message,
        "phone": ",".join(phones),
        "engine": engine,
        "repeat": repeat
    }

    try:
        response = requests.get(url, params=params, timeout=10)

        if response.status_code == 200:
            return {"success": True, "message": response.text}
        elif response.status_code == 503:
            return {"success": False, "error": "Service busy, retry later"}
        else:
            return {"success": False, "error": f"HTTP {response.status_code}"}

    except requests.exceptions.Timeout:
        return {"success": False, "error": "Request timeout"}
    except requests.exceptions.RequestException as e:
        return {"success": False, "error": str(e)}

# Usage
result = send_voice_alert(
    message="Database connection lost!",
    phones=["0722111111", "0722222222"],
    engine="espeak",
    repeat=3
)

print(result)
# Output: {'success': True, 'message': '📞 Calls queued for: 0722111111, 0722222222'}
```

---

#### Bash/cURL Integration

```bash
#!/bin/bash
# send-alert.sh - Send voice alert from bash script

MESSAGE="$1"
PHONE="$2"
ENGINE="${3:-marytts}"  # Default to marytts
REPEAT="${4:-3}"        # Default repeat 3 times

TTS_CALLER_URL="http://localhost:8899/call"

# URL encode parameters (curl --data-urlencode does this automatically)
response=$(curl --silent --show-error --get "${TTS_CALLER_URL}" \
  --data-urlencode "text=${MESSAGE}" \
  --data-urlencode "phone=${PHONE}" \
  --data-urlencode "engine=${ENGINE}" \
  --data-urlencode "repeat=${REPEAT}" \
  --max-time 10 \
  --write-out "\n%{http_code}")

# Extract HTTP status code (last line)
http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | head -n-1)

if [ "$http_code" -eq 200 ]; then
    echo "✅ Alert queued: $body"
    exit 0
elif [ "$http_code" -eq 503 ]; then
    echo "⚠️  Service busy, try again later"
    exit 1
else
    echo "❌ Failed with HTTP $http_code: $body"
    exit 1
fi

# Usage:
# ./send-alert.sh "Server crashed!" "0722111111" "espeak" 5
```

---

#### Node.js Integration

```javascript
const axios = require('axios');

class TTSCallerClient {
    constructor(baseURL = 'http://localhost:8899') {
        this.baseURL = baseURL;
    }

    async sendAlert(options) {
        const {
            text,
            phones,  // Array or string
            engine = 'marytts',
            repeat = 3
        } = options;

        if (!text || !phones) {
            throw new Error('text and phones are required');
        }

        const phoneStr = Array.isArray(phones) ? phones.join(',') : phones;

        try {
            const response = await axios.get(`${this.baseURL}/call`, {
                params: { text, phone: phoneStr, engine, repeat },
                timeout: 10000
            });

            return {
                success: true,
                message: response.data,
                status: response.status
            };

        } catch (error) {
            if (error.response) {
                return {
                    success: false,
                    error: error.response.data,
                    status: error.response.status
                };
            } else {
                return {
                    success: false,
                    error: error.message
                };
            }
        }
    }

    async checkHealth() {
        try {
            const response = await axios.get(`${this.baseURL}/health`, {
                timeout: 5000
            });
            return response.status === 200;
        } catch {
            return false;
        }
    }
}

// Usage
const client = new TTSCallerClient('http://tts-caller:8899');

client.sendAlert({
    text: 'Payment system failure!',
    phones: ['0722111111', '0722222222'],
    engine: 'espeak',
    repeat: 5
}).then(result => {
    console.log(result);
    // { success: true, message: '📞 Calls queued for: ...', status: 200 }
});
```

---

## Conclusion

This technical architecture document provides comprehensive coverage of the TTS Caller service implementation, including:

- **Component architecture** with clear layer separation
- **Detailed function reference** with signatures, algorithms, and technical details
- **Process lifecycle** documentation for startup, request handling, and SIP calls
- **SIP/RTP protocol** implementation details
- **Audio processing pipelines** for both MaryTTS and espeak-ng
- **Concurrency model** with thread architecture and synchronization
- **Configuration management** patterns and current limitations
- **Error handling strategy** with retry logic and failure modes
- **Deployment architecture** including Docker container and network setup
- **Integration patterns** for monitoring systems and custom applications

**Key Technical Highlights:**

1. **Async Processing:** core.async channels provide CSP-style concurrency with backpressure
2. **Process Management:** Robust Baresip lifecycle with cleanup and retry logic
3. **Dual TTS Engines:** Flexible audio generation (quality vs. speed trade-off)
4. **Sequential Call Processing:** Simple, reliable, single-worker architecture
5. **SIP Integration:** Complete SIP/RTP implementation via Baresip
6. **Container Deployment:** Docker-based with host networking for SIP compatibility

**Implementation Quality:**
- Functional and reliable for single-instance deployment
- Good retry logic and error recovery
- Clear separation of concerns (HTTP → Queue → Worker → SIP)
- Room for improvement in configuration, resource management, and scalability

This architecture serves its intended use case well: lightweight voice notifications for monitoring systems in controlled environments.
