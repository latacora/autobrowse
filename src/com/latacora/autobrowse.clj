(ns com.latacora.autobrowse
  (:require
   [babashka.process :as p]
   [clojure.java.browse :as browse]
   [clojure.string :as str]
   [lambdaisland.uri :as uri]))

(def ^:private stdout-lines (comp str/split-lines :out))

(defn ^:private get-windows-listening-sockets!
  "Gets hosts and ports that a process is listening for on Windows (using Powershell)."
  [pid]
  (->>
   (p/sh
    "powershell" "-Command"
    (str
     "Get-NetTCPConnection -OwningProcess " pid
     " | Select-Object -Property LocalAddress, LocalPort"))
   ;; Alternatively we could | ConvertTo-JSON here
   (stdout-lines)
   (drop 2) ;; Skip property names, horizontal line
   (map (fn [line] (str/split line #"\s+" 2)))))

(defn ^:private get-lsof-listening-sockets!
  "Gets hosts and ports that a process is listening for on *nix/Linux/Mac systems (using lsof)."
  [pid]
  (->>
   (p/sh "lsof" "-nPa" "-p" (str pid) "-iTCP" "-sTCP:LISTEN" "-Fn")
   (stdout-lines)
   (filter (fn [line] (str/starts-with? line "n")))
   (map
    (fn [line]
     ;; Host might be IPv6, so we need to match on the last colon to ensure we
     ;; don't grab any address separators
      (let [[_ host port] (re-matches #"(.*):([^:]+)$" (subs line 1))]
        [host port])))))

(def ^:private bare-http-url
  (uri/uri "http://"))

(defn ^:private os-type!
  []
  (if (-> "os.name" System/getProperty (.startsWith "Windows"))
    :windows
    :unix))

(defn ^:private any->localhost
  [host]
  (case host
    ("*" "0.0.0.0" "::" "[::]") "127.0.0.1"
    host))

(defn get-listening-urls!
  "Gets (presumed-http) URLs that a process is listening for."
  ([pid]
   (get-listening-urls! pid nil))
  ([pid extra-url-parts]
   (let [get-listening-sockets!
         (case (os-type!)
           :windows get-windows-listening-sockets!
           :unix get-lsof-listening-sockets!)]
     (->>
      pid
      (get-listening-sockets!)
      (map (partial zipmap [:host :port]))
      (map (fn [url-parts] (update url-parts :host any->localhost)))
      (map (fn [url-parts] (merge bare-http-url url-parts extra-url-parts)))
      (map uri/uri-str)
      (distinct)))))

(defn ^:private sleep
  "A wrapper over Thread/sleep to facilitate testing."
  [msec]
  (Thread/sleep msec))

(defn browse-once-listening!
  "Opens a browser window once given pid starts listening on a TCP port."
  [pid]
  (let [pid (str pid)]
    (loop [urls (get-listening-urls! pid)]
      (if (empty? urls)
        (do
          (println (str "Waiting for process " pid " to start listening..."))
          (sleep 1000)
          (recur (get-listening-urls! pid)))
        (browse/browse-url (first urls))))))
