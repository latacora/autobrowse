(ns com.latacora.autobrowse-test
  (:require
   [clojure.test :as t]
   [com.latacora.autobrowse :as autobrowse]
   [org.httpkit.server :as http-kit]
   [clojure.string :as str]
   [clojure.java.browse :as browse]
   [babashka.process :as p])
  (:import
   (java.lang.management ManagementFactory)))

(defonce the-server
  (atom nil))

(defn test-handler
  [_]
  {:status 200 :body "hello from test handler"})

(defn start-test-server!
  []
  (swap! the-server
         (fn [server]
           (or server
               (let [new-server (http-kit/run-server test-handler {:port 0 :host "127.0.0.1"})]
                 (Thread/sleep 1000) ;; Give the server a moment to start
                 (println "Created a new test server on port " (-> new-server meta :local-port))
                 new-server)))))

;; Unused: see below
(defn stop-test-server!
  []
  (@the-server)
  (reset! the-server nil))

(defmacro with-test-server
  [& body]
  `(try
     (start-test-server!)
     ~@body
     (finally
       ;; Not stopping the server is a bit messy but it's the only way
       ;; I've found to reliably make the tests pass quickly
       #_(stop-test-server!))))

(def pid
  (->
   (ManagementFactory/getRuntimeMXBean)
   (.getName)
   (str/split #"@")
   (first)
   (Long/parseLong)))

(t/deftest test-get-listening-urls!
  (t/testing "get-listening-urls! returns an empty list for processes not listening on a TCP port"
    ;; We assume 1 == init and it's probably not listening on any TCP ports
    (t/is (empty? (autobrowse/get-listening-urls! 1))))

  (t/testing "get-listening-urls! returns URLs for a process listening on a TCP port"
    (with-test-server
      (t/is
       (=
        (autobrowse/get-listening-urls! pid)
        [(str "http://127.0.0.1:" (-> @the-server meta :local-port))])))))

(def sample-pid "12345")

(def sample-lsof-outputs
  (->>
   (let [prefix [(str "p" sample-pid) "f4"]]
     [(conj prefix "n*:8000")
      (conj prefix "n0.0.0.0:8000")
      (conj prefix "n127.0.0.1:8000")
      (conj prefix "n[::]:8000")])
   (flatten)
   (str/join "\n")))

(defmacro with-fake-lsof
  [& body]
  `(with-redefs
    [p/sh
     (fn [& args#]
       (t/is (= args# ["lsof" "-nPa" "-p" sample-pid "-iTCP" "-sTCP:LISTEN" "-Fn"]))
       {:out sample-lsof-outputs})
     autobrowse/os-type! (constantly :unix)]
     ~@body))

(t/deftest test-get-listening-urls!-with-fake-lsof
  ;; Tests with fake lsof output. This is an additional test to ensure that we
  ;; parse the output of lsof correctly. It also lets us test more edge cases
  ;; easily.
  (with-fake-lsof
    (t/is
     (=
      ["http://127.0.0.1:8000"]
      (#'autobrowse/get-listening-urls! sample-pid)))))

(t/deftest test-browse-once-listening!
  (t/testing "browse-once-listening! opens a browser window once the process starts listening"
    (with-test-server
      (let [port (-> @the-server meta :local-port)
            browsed-urls (atom [])]
        (with-redefs
         [browse/browse-url (partial swap! browsed-urls conj)]
          (autobrowse/browse-once-listening! pid)
          (t/is (= [(str "http://127.0.0.1:" port)] @browsed-urls)))))))

(t/deftest test-sleep
  (t/testing "sleep function pauses execution for the given time"
    (let [start-time (System/currentTimeMillis)]
      (#'autobrowse/sleep 100)
      (let [end-time (System/currentTimeMillis)]
        (t/is (>= (- end-time start-time) 100))))))
