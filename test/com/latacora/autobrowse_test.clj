(ns com.latacora.autobrowse-test
  (:require
   [clojure.test :as t]
   [com.latacora.autobrowse :as autobrowse]
   [clojure.string :as str]
   [clojure.java.browse :as browse]
   [babashka.process :as p]))

(defn make-lsof-output
  [pid listening-ports]
  (->>
   listening-ports
   (map (fn [port] (conj [(str "p" pid) "f4"] (str "n" port))))
   (flatten)
   (str/join "\n")))

(def sample-parent-pid "12345")
(def sample-listening-pid "67890")

(def sample-listening-lsof-output
  (make-lsof-output
   sample-listening-pid
   ["*:8000" "0.0.0.0:8000" "127.0.0.1:8000" "[::]:8000"]))

(defn fake-sh
  [cmd & args]
  (case cmd
    "lsof"
    (let [[_ _ pid] args]
      (t/is (= args ["-nPa" "-p" pid "-iTCP" "-sTCP:LISTEN" "-Fn"]))

      {:out
       (if (= pid sample-listening-pid)
         sample-listening-lsof-output
         "")})

    "pgrep"
    (let [[_ pid] args]
      (t/is (= args ["-P" pid]))
      {:out
       (if (= pid sample-parent-pid)
         (str sample-listening-pid "\n")
         "")})

    (throw (ex-info "Unexpected command" {:cmd cmd :args args}))))

(defmacro with-fake-shell
  [& body]
  `(with-redefs
    [p/sh fake-sh
     autobrowse/os-type! (constantly :unix)]
     ~@body))

(t/deftest test-get-listening-urls!
  (t/testing "get-listening-urls! returns an empty list for processes not listening on a TCP port with include-descendents? off"
    ;; We assume 1 == init and it's probably not listening on any TCP ports
    (t/is (empty? (autobrowse/get-listening-urls! 1 {:include-descendents? false})))))

(defn wait-for-listener!
  [pid timeout-ms]
  (let [interval-ms 100
        end-time (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (>= (System/currentTimeMillis) end-time)
        (throw (ex-info "Timed out waiting for listener" {}))

        (empty? (autobrowse/get-listening-urls! pid))
        (do
          (Thread/sleep interval-ms)
          (recur))

        :else true))))

(defn run-nc-inside-shell!
  []
  ;; The echo 1 here is load bearing. Some shells, when they see they only have one command to run,
  ;; will try to be very clever and `exec` into the process instead of being a shell. This is a
  ;; problem because we need the shell as a process that doesn't listen on anything. We add ; echo 1
  ;; to force it to be a shell.

  ;;  You can check this with:
  ;; (def p (p/process ["sh" "-c" "nc -vl 0; echo 1"]))
  ;; (-> p :proc .info .command .get)
  (p/process {:inherit true} "/bin/sh" "-c" "nc -vl 0; echo 1"))

(t/deftest test-descendent-listening-urls
  (t/testing "get-listening-urls! returns URLs for a child process listening on a TCP port"
    (let [subprocess (run-nc-inside-shell!)
          pid (-> subprocess :proc .pid)]
      (try
        (wait-for-listener! pid 10000) ;; Retry for up to 10 seconds
        (t/is
         (empty?
          (autobrowse/get-listening-urls!
           pid {:include-descendents? false})))
        (t/is
         (some
          #(str/starts-with? % "http://127.0.0.1:")
          (autobrowse/get-listening-urls! pid)))
        (finally
          (p/destroy subprocess))))))

(t/deftest test-get-listening-urls!-with-fake-shell
  ;; Tests with fake shell output. This is an additional test to ensure that we
  ;; parse the output of shell commands correctly. It also lets us test more edge cases
  ;; easily.
  (with-fake-shell
    (t/is
     (=
      ["http://127.0.0.1:8000"]
      (#'autobrowse/get-listening-urls! sample-listening-pid))
     "find listening URLs recursively when handed listening pid directly")

    (t/is
     (=
      ["http://127.0.0.1:8000"]
      (#'autobrowse/get-listening-urls! sample-parent-pid))
     "find listening URLs recursively when handed non-listening parent pid")

    (t/is
     (empty?
      (#'autobrowse/get-listening-urls!
       sample-parent-pid
       {:include-descendents? false}))
     "non-listening parent pid + no descendents = empty list")))

(t/deftest test-browse-once-listening!
  (t/testing "browse-once-listening! opens a browser window once the process starts listening"
    (with-fake-shell
      (let [browsed-urls (atom [])]
        (with-redefs
         [browse/browse-url (partial swap! browsed-urls conj)]
          (autobrowse/browse-once-listening! sample-listening-pid)
          (t/is (= ["http://127.0.0.1:8000"] @browsed-urls)))))))

(t/deftest test-sleep
  (t/testing "sleep function pauses execution for the given time"
    (let [start-time (System/currentTimeMillis)]
      (#'autobrowse/sleep 100)
      (let [end-time (System/currentTimeMillis)]
        (t/is (>= (- end-time start-time) 100))))))
