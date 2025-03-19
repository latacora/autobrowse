(ns com.latacora.autobrowse
  (:require
   [babashka.process :as p]
   [clojure.java.browse :as browse]
   [clojure.string :as str]
   [lambdaisland.uri :as uri]))

(defn ^:private os-type!
  []
  (if (-> "os.name" System/getProperty (.startsWith "Windows"))
    :windows
    :unix))

(def ^:private stdout-lines (comp str/split-lines :out))

(defn ^:private get-windows-child-pids!
  "Gets child PIDs for a given PID on Windows (using Powershell CIM cmdlets)."
  [pid]
  (->>
   (p/sh
    "powershell" "-Command"
    (str
     "Get-CimInstance -ClassName Win32_Process -Filter \"ParentProcessId=" pid "\" | Select-Object -ExpandProperty ProcessId"))
   (stdout-lines)
   (map str/trim)
   (remove str/blank?)))

(defn ^:private get-unix-child-pids!
  "Gets child PIDs for a given PID on Unix/Linux/Mac systems (using pgrep)."
  [pid]
  (->>
   (p/sh "pgrep" "-P" pid)
   (stdout-lines)
   (map str/trim)
   (remove str/blank?)))

(defn ^:private get-descendent-pids!
  "Gets the given PID and all its descendent (transitive child) PIDs."
  [pid]
  (let [get-child-pids! (case (os-type!)
                          :windows get-windows-child-pids!
                          :unix get-unix-child-pids!)]
    (loop [pids #{pid} to-check [pid]]
      (if (empty? to-check)
        pids
        (let [new-pids (set (mapcat get-child-pids! to-check))]
          (recur (into pids new-pids) new-pids))))))

(comment
  (get-descendent-pids! "91456"))

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
   (drop 3) ;; Skip property names, spacer, horizontal line
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

(defn ^:private any->localhost
  [host]
  (case host
    ("*" "0.0.0.0" "::" "[::]") "127.0.0.1"
    host))

(defn get-listening-urls!
  "Gets (presumed-http) URLs that a process is listening for.

  Options:
  - :extra-url-parts (map): Additional URL parts to merge with the base URL.
  - :include-descendents? (boolean): Whether to include child processes' URLs. Defaults to true."
  ([pid]
   (get-listening-urls! pid {}))
  ([pid
    {:keys [extra-url-parts include-descendents?]
     :or {extra-url-parts nil include-descendents? true}}]
   (let [get-listening-sockets!
         (case (os-type!)
           :windows get-windows-listening-sockets!
           :unix get-lsof-listening-sockets!)
         all-pids (if include-descendents?
                    (get-descendent-pids! pid)
                    #{pid})]
     (->>
      all-pids
      (mapcat get-listening-sockets!)
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
