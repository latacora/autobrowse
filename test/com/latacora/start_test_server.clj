(ns com.latacora.start-test-server
  (:require
   [org.httpkit.server :as http-kit]))

(defonce the-server
  (atom nil))

(defn test-handler
  [_]
  {:status 200 :body "hello from test handler"})

(defn start-test-server!
  []
  (swap!
   the-server
   (fn [server]
     (or
      server
      (let [new-server (http-kit/run-server test-handler {:port 0 :host "127.0.0.1"})]
        (println "Created a new test server on port " (-> new-server meta :local-port))
        new-server)))))

(defn stop-test-server!
  []
  (@the-server)
  (reset! the-server nil))

(defn -main
  []
  (start-test-server!)
  (println "Stopping server in 10s...")
  (Thread/sleep 10000)
  (stop-test-server!))
