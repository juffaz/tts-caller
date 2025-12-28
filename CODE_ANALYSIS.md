# TTS Caller Service - Code Analysis

**Analysis Date:** 2025-12-28
**Service Version:** 0.1.0-SNAPSHOT
**Technology Stack:** Clojure 1.11.1, Ring/Compojure, MaryTTS, Baresip, espeak-ng

---

## Executive Summary

TTS Caller is a lightweight voice alert service that converts text to speech and makes automated SIP phone calls. The service is designed for integration with monitoring systems (Zabbix, ElastAlert, Alertmanager, etc.) to deliver critical voice notifications.

**Overall Assessment:** The service is functional and solves a specific use case well, but has room for improvement in error handling, code maintainability, configurability, and production readiness.

---

## Strengths

### 1. Clear Purpose and Use Case
- **Focused functionality:** The service does one thing well - converting text to speech and making SIP calls
- **Practical integration:** Well-suited for monitoring and alerting systems
- **Real-world problem solving:** Addresses the need for voice notifications in critical scenarios

### 2. Good Process Management
- **Dynamic port allocation:** Avoids port conflicts by checking port availability ([core.clj:51-59](src/tts_caller/core.clj#L51-L59))
- **Process cleanup:** Proper cleanup of Baresip processes to prevent resource leaks ([core.clj:35-48](src/tts_caller/core.clj#L35-L48))
- **Timeout handling:** Uses ProcessBuilder with timeout to prevent hanging processes ([core.clj:135](src/tts_caller/core.clj#L135))

### 3. Retry Logic
- **Configurable retries:** Handles temporary failures with retry mechanism ([core.clj:96-172](src/tts_caller/core.clj#L96-L172))
- **Smart retry conditions:** Different handling for different SIP error codes (e.g., 480 Temporarily unavailable)
- **Prevents infinite loops:** Max retry limit prevents endless retry attempts ([core.clj:96](src/tts_caller/core.clj#L96))

### 4. Async Queue System
- **Non-blocking API:** Uses core.async channel for batch processing ([core.clj:32](src/tts_caller/core.clj#L32))
- **Queue management:** Prevents overload with bounded queue (capacity: 10) ([core.clj:32](src/tts_caller/core.clj#L32))
- **Sequential processing:** Ensures calls are made in order without overwhelming the system

### 5. Multi-Phone Support
- **Batch calling:** Supports calling multiple phone numbers in a single request ([core.clj:201-203](src/tts_caller/core.clj#L201-L203))
- **Flexible input:** Accepts comma or space-separated phone numbers ([core.clj:202](src/tts_caller/core.clj#L202))

### 6. Dual TTS Engine Support
- **MaryTTS:** High-quality TTS with multiple voices and language support
- **espeak-ng:** Lightweight alternative for faster generation
- **Runtime selection:** User can choose engine per request ([core.clj:209](src/tts_caller/core.clj#L209))

### 7. Detailed Logging
- **Timestamped logs:** All operations include timestamps for troubleshooting ([core.clj:15-18](src/tts_caller/core.clj#L15-L18))
- **Process output:** Captures and logs Baresip output for debugging ([core.clj:130-132](src/tts_caller/core.clj#L130-L132))
- **Status tracking:** Clear indication of call progress (queued, calling, success, failure)

### 8. Containerization
- **Docker support:** Complete Dockerfile for easy deployment
- **Dependency management:** All required packages installed in container
- **Network mode:** Supports host networking for SIP communication

---

## Weaknesses

### 1. Hard-Coded Configuration
**Severity: High**

- **Magic numbers throughout code:**
  - Timeout: 45000ms ([core.clj:135](src/tts_caller/core.clj#L135))
  - Retry delay: 10000ms ([core.clj:97](src/tts_caller/core.clj#L97))
  - Max retries: 3 ([core.clj:96](src/tts_caller/core.clj#L96))
  - Port: 8899 ([core.clj:235](src/tts_caller/core.clj#L235))
  - Sleep delays: 15000ms ([core.clj:193](src/tts_caller/core.clj#L193)), 1000ms, 2000ms
- **No external configuration:** Values cannot be changed without code modification
- **Environment variables only for SIP:** Other parameters should also be configurable ([core.clj:21-24](src/tts_caller/core.clj#L21-L24))

**Impact:** Difficult to tune for different environments or use cases

### 2. Error Handling Gaps
**Severity: High**

- **Silent failures possible:**
  - File deletion not verified after use
  - Process cleanup errors caught but may leave orphan processes
  - No validation of WAV file content, only existence check ([core.clj:107-110](src/tts_caller/core.clj#L107-L110))
- **Incomplete exception handling:**
  - Generic `Exception` catch blocks lose error context ([core.clj:162-168](src/tts_caller/core.clj#L162-L168))
  - Batch worker errors logged but not reported to caller ([core.clj:195-196](src/tts_caller/core.clj#L195-L196))
- **No circuit breaker:** Repeated failures to same number waste resources
- **Missing validation:**
  - Phone number format not validated
  - Text length not checked (could generate huge WAV files)
  - No SIP credentials validation at startup

**Impact:** Production issues may go unnoticed until manual inspection

### 3. Resource Management Issues
**Severity: Medium-High**

- **Temporary file accumulation:**
  - WAV files created in `/tmp` but never cleaned up ([core.clj:208](src/tts_caller/core.clj#L208))
  - Each call creates a new file with timestamp
  - Long-running service will fill disk space
- **Memory concerns:**
  - Audio bytes loaded entirely into memory ([audio.clj:47-52](src/tts_caller/audio.clj#L47-L52))
  - No limits on queue size for temp files
- **Process leaks:**
  - Future threads may not be properly cancelled ([core.clj:138](src/tts_caller/core.clj#L138))
  - Reader thread cleanup only via `future-cancel`

**Impact:** Service may crash or degrade over time

### 4. Limited Observability
**Severity: Medium**

- **No metrics:** No Prometheus/StatsD integration for monitoring
- **No health checks:** `/health` endpoint returns "OK" without checking dependencies ([core.clj:227](src/tts_caller/core.clj#L227))
- **No call history:** Cannot query past calls or their status
- **No alerting:** No notification when queue is full or calls fail repeatedly
- **Logs only to stdout:** No structured logging (JSON) or log levels

**Impact:** Difficult to monitor service health in production

### 5. Security Concerns
**Severity: Medium**

- **SIP credentials in environment:** No encryption or secrets management
- **No authentication:** HTTP API is completely open ([core.clj:225-227](src/tts_caller/core.clj#L225-L227))
- **No rate limiting:** Service can be abused to make unlimited calls
- **Command injection risk:** Text parameter passed to shell commands
  - espeak-ng command uses format string with text ([audio.clj:104](src/tts_caller/audio.clj#L104))
  - No sanitization of user input
- **No HTTPS:** All communication in plaintext

**Impact:** Service could be exploited for unauthorized calls or DoS attacks

### 6. Code Maintainability Issues
**Severity: Medium**

- **Mixed languages:** Comments in Russian/Turkish/English ([core.clj:14](src/tts_caller/core.clj#L14), [core.clj:26](src/tts_caller/core.clj#L26), [audio.clj:8](src/tts_caller/audio.clj#L8))
- **Large functions:** `call-sip-single` is 78 lines with nested logic ([core.clj:95-172](src/tts_caller/core.clj#L95-L172))
- **No tests:** No unit tests or integration tests
- **Reflection warnings likely:** Java interop without type hints may be slow
- **Duplicate logging function:** Defined in both core.clj and audio.clj ([core.clj:15-18](src/tts_caller/core.clj#L15-L18), [audio.clj:9-12](src/tts_caller/audio.clj#L9-L12))

**Impact:** Harder to maintain and extend codebase

### 7. SIP Configuration Limitations
**Severity: Low-Medium**

- **Fixed SIP port:** Port 50060 hardcoded ([core.clj:24](src/tts_caller/core.clj#L24))
- **UDP only:** No TCP/TLS transport options ([core.clj:69](src/tts_caller/core.clj#L69))
- **Single account:** Cannot use multiple SIP accounts
- **No NAT traversal:** STUN/TURN modules loaded but not configured ([core.clj:80-81](src/tts_caller/core.clj#L80-L81))
- **Limited codec support:** Only G.711 codec ([core.clj:79](src/tts_caller/core.clj#L79))

**Impact:** Limited deployment scenarios

### 8. Audio Generation Issues
**Severity: Low**

- **Fixed audio parameters:**
  - Sample rate: 8kHz (standard for telephony but low quality)
  - Mono only
  - Speech rate hardcoded: 1.5x ([audio.clj:43](src/tts_caller/audio.clj#L43))
- **Unused parameters:** `rate` and `gain` parameters accepted but ignored in MaryTTS path ([audio.clj:93-98](src/tts_caller/audio.clj#L93-L98))
- **Silence timing hardcoded:** 1500ms start, 500ms end ([audio.clj:118-119](src/tts_caller/audio.clj#L118-L119))
- **No audio file validation:** Generated WAV may be corrupted

**Impact:** Less flexibility for different use cases

---

## Improvement Recommendations

### High Priority

#### 1. Add Configuration Management
**Effort: Medium | Impact: High**

```clojure
;; Suggested approach:
;; - Use environ library for environment variables
;; - Add config.edn file for non-sensitive settings
;; - Allow runtime override via HTTP headers or query params

(def config
  {:sip {:user (env :sip-user)
         :pass (env :sip-pass)
         :host (env :sip-host)
         :port (env :sip-port "50060")}
   :call {:max-retries (env :max-retries 3)
          :retry-delay-ms (env :retry-delay-ms 10000)
          :timeout-ms (env :timeout-ms 45000)}
   :server {:port (env :server-port 8899)
            :queue-size (env :queue-size 10)}})
```

#### 2. Implement Proper Resource Cleanup
**Effort: Medium | Impact: High**

- Add cleanup worker to delete old WAV files periodically
- Implement file age-based cleanup (e.g., delete files older than 1 hour)
- Add disk space monitoring
- Use try-with-resources pattern for audio streams
- Add JVM shutdown hook to cleanup on exit

```clojure
;; Suggested cleanup worker
(defn cleanup-old-files! [dir max-age-ms]
  (go-loop []
    (<! (async/timeout 300000)) ;; Check every 5 minutes
    (let [cutoff (- (System/currentTimeMillis) max-age-ms)
          old-files (filter #(< (.lastModified %) cutoff)
                           (.listFiles (io/file dir)))]
      (doseq [f old-files]
        (io/delete-file f true)))
    (recur)))
```

#### 3. Enhance Security
**Effort: High | Impact: High**

- Add API key authentication (middleware)
- Implement rate limiting per IP/API key
- Sanitize text input to prevent command injection
- Add HTTPS support with TLS certificates
- Use secrets manager for SIP credentials (e.g., HashiCorp Vault)
- Add input validation (phone number format, text length limits)

```clojure
;; Suggested rate limiting
(defn rate-limit-middleware [handler]
  (let [limiter (atom {})]
    (fn [request]
      (let [ip (get-in request [:headers "x-forwarded-for"]
                       (:remote-addr request))
            now (System/currentTimeMillis)
            calls (get-in @limiter [ip] [])]
        (if (>= (count (filter #(> % (- now 60000)) calls)) 10)
          (resp/status (resp/response "Rate limit exceeded") 429)
          (do
            (swap! limiter update ip conj now)
            (handler request)))))))
```

#### 4. Improve Error Handling
**Effort: Medium | Impact: High**

- Add specific exception types for different failure modes
- Implement circuit breaker pattern for failing numbers
- Return error details in API response (not just 503)
- Add structured error logging with error codes
- Validate SIP credentials on startup
- Add retry budget to prevent excessive retries

```clojure
;; Suggested error response structure
(defn handle-call-with-errors [{:keys [query-params]}]
  (try
    ;; ... existing logic
    (catch ExceptionInfo e
      (let [data (ex-data e)]
        (resp/status
          (resp/response {:error (.getMessage e)
                         :code (:code data)
                         :details data})
          (or (:status data) 500))))
    (catch Exception e
      (resp/status
        (resp/response {:error "Internal server error"
                       :message (.getMessage e)})
        500))))
```

### Medium Priority

#### 5. Add Observability
**Effort: Medium | Impact: Medium-High**

- Add Prometheus metrics endpoint (`/metrics`)
- Track: calls queued, in progress, succeeded, failed, queue depth
- Add structured JSON logging with log levels
- Implement proper health check (verify Baresip, check disk space, test TTS)
- Add call history endpoint (last N calls with status)
- Add tracing with correlation IDs

```clojure
;; Suggested metrics structure
(defonce metrics
  {:calls-queued (atom 0)
   :calls-in-progress (atom 0)
   :calls-succeeded (atom 0)
   :calls-failed (atom 0)
   :queue-depth (atom 0)
   :audio-generation-time-ms (atom [])})

(defn health-check []
  {:status "UP"
   :checks {:baresip (check-baresip-available)
            :disk-space (check-disk-space "/tmp" 1000000000)
            :tts (check-tts-engines)
            :sip-account (check-sip-registration)}
   :metrics @metrics})
```

#### 6. Refactor for Maintainability
**Effort: Medium | Impact: Medium**

- Extract configuration to separate namespace
- Split large functions into smaller units
- Add type hints to avoid reflection
- Standardize on English for all comments/logs
- Create shared utilities namespace
- Add comprehensive docstrings
- Write unit tests (test helpers, audio generation, phone parsing)
- Add integration tests (full call flow with mock SIP server)

#### 7. Enhance SIP Capabilities
**Effort: Medium | Impact: Medium**

- Support TCP and TLS transports
- Make SIP port configurable
- Add STUN/TURN server configuration
- Support multiple SIP accounts (round-robin or priority)
- Add codec negotiation (G.722, Opus)
- Implement call recording/archiving option
- Add DTMF support for interactive voice response

### Low Priority

#### 8. Audio Improvements
**Effort: Low-Medium | Impact: Low-Medium**

- Make speech rate, gain, and silence configurable
- Support custom audio file upload (play pre-recorded message)
- Add audio format validation
- Support more TTS engines (Google TTS, AWS Polly)
- Add voice gender/accent selection
- Implement audio caching (same text = reuse WAV)

#### 9. API Enhancements
**Effort: Low | Impact: Low**

- Add webhook callback on call completion
- Support scheduled calls (call at specific time)
- Add call cancellation endpoint
- Return job ID for tracking
- Add batch status query endpoint
- Support JSON POST body (in addition to query params)
- Add OpenAPI/Swagger documentation

#### 10. Deployment & Operations
**Effort: Low-Medium | Impact: Medium**

- Add Kubernetes manifests (Deployment, Service, ConfigMap, Secret)
- Create docker-compose.yml for local development
- Add CI/CD pipeline configuration
- Create systemd service file for non-Docker deployment
- Add backup/restore scripts for call history
- Document disaster recovery procedures

---

## Architecture Suggestions

### 1. Persistence Layer
Add database to store:
- Call history (phone, text, timestamp, status, duration, error)
- Failed call queue (for later retry)
- Configuration (dynamic SIP accounts, blacklisted numbers)

**Suggested:** SQLite for simplicity, or PostgreSQL for production

### 2. Message Queue Integration
Replace in-memory channel with external queue:
- **RabbitMQ** or **Redis** for durability
- Enables horizontal scaling (multiple workers)
- Prevents lost calls on service restart

### 3. Multi-Instance Support
Current design doesn't support clustering:
- Shared port (50060) conflicts between instances
- Queue is in-memory only
- Configuration is per-instance

**Solution:** Use external queue + dynamic port allocation per instance

### 4. Admin Interface
Add simple web UI for:
- Viewing call history
- Testing calls manually
- Monitoring queue status
- Managing SIP accounts

---

## Performance Considerations

### Current Bottlenecks

1. **Sequential call processing:** Only one call at a time per queue worker
2. **TTS generation:** MaryTTS is slow (1-2 seconds per message)
3. **No caching:** Same text generates new WAV every time
4. **Synchronous cleanup:** Process killing blocks call flow

### Optimization Suggestions

1. **Add TTS caching:**
   - Hash text + engine + voice → cache filename
   - Reuse WAV files for identical requests
   - Implement LRU cache with size limit

2. **Parallel call processing:**
   - Multiple queue workers (configurable pool size)
   - Each worker handles independent phone number
   - Requires multiple SIP accounts or dynamic port allocation

3. **Async cleanup:**
   - Move process killing to separate thread pool
   - Don't block on port availability check
   - Use exponential backoff for retries

4. **Optimize audio generation:**
   - Pre-generate silence buffers (don't recalculate)
   - Stream audio instead of loading to memory
   - Use native espeak-ng for faster generation

---

## Code Quality Score

| Category | Score | Notes |
|----------|-------|-------|
| Functionality | 8/10 | Works well for intended use case |
| Reliability | 6/10 | Retry logic good, but error handling gaps |
| Performance | 6/10 | Sequential processing limits throughput |
| Security | 4/10 | No auth, potential injection issues |
| Maintainability | 5/10 | Large functions, mixed languages, no tests |
| Observability | 5/10 | Good logging, but no metrics or tracing |
| Scalability | 4/10 | Single worker, no persistence, no clustering |
| Configuration | 4/10 | Hardcoded values throughout |
| **Overall** | **5.25/10** | **Functional but needs hardening for production** |

---

## Production Readiness Checklist

- [ ] Add authentication to API endpoints
- [ ] Implement rate limiting
- [ ] Add input validation and sanitization
- [ ] Set up structured logging with log levels
- [ ] Add Prometheus metrics endpoint
- [ ] Implement proper health checks
- [ ] Add resource cleanup worker
- [ ] Externalize all configuration
- [ ] Add comprehensive error handling
- [ ] Write unit and integration tests
- [ ] Add circuit breaker for failing numbers
- [ ] Set up secrets management for SIP credentials
- [ ] Add HTTPS/TLS support
- [ ] Implement call history persistence
- [ ] Create monitoring dashboards
- [ ] Document API with OpenAPI spec
- [ ] Set up CI/CD pipeline
- [ ] Add load testing
- [ ] Create runbooks for common issues
- [ ] Implement graceful shutdown

---

## Conclusion

TTS Caller is a well-conceived service that solves a specific problem effectively. The core functionality is solid, with good retry logic, process management, and async queue handling. However, the service requires significant hardening before production use.

**Key Takeaways:**
- **Strengths:** Clear purpose, good retry logic, async processing, dual TTS engine support
- **Critical gaps:** Security (no auth), resource management (file cleanup), error handling
- **Quick wins:** Add config file, implement file cleanup, add input validation
- **Long-term improvements:** Add persistence, metrics, tests, security layer

**Recommended next steps:**
1. Add configuration management (week 1)
2. Implement security basics: auth + rate limiting (week 1-2)
3. Add resource cleanup worker (week 1)
4. Improve error handling and add metrics (week 2)
5. Write tests and add monitoring (week 3)

With these improvements, the service would be production-ready for internal use. For external-facing deployment, additional security hardening and scalability features would be needed.
