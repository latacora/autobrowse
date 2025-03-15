# autobrowse

Automatically opens a web page being served by a local process.

Lots of local tools serve web pages. Sometimes, that tool will start a web
server, but won't open a browser for you (e.g. [hugo], [fava]): they'll just
print the URL to the terminal, often with a million other messages so you have
to hunt for it. It'd be nice if my browser just opened that web page
automatically, because that's almost certainly what I'm about to do manually.

This library figures out what the listening port is and opens a browser for you.

[hugo]: https://gohugo.io/
[fava]: https://beancount.github.io/fava/

## Usage

Here's a simplified example of how you might use this in a `bb.edn`:

```clojure
{:tasks
 {:requires
  ([babashka.process :as p]
   [clojure.string :as str]
   [com.latacora.autobrowse :refer [browse-once-listening!]])

  dev
  {:doc "Run a development hugo server"
   :task
   (let [p (p/process {:inherit true} "hugo" "server" "yada" "yada")]
     (-> p :proc .pid browse-once-listening! future)
     (p/check p))}}}
```

We create the process serving the web page (here `hugo server`) with
`p/process`, which doesn't block. That's necessary so we can grab the process id
(pid), which we pass to `browse-once-listening!`. `browse-once-listening!`
blocks until it succeeds, hence why it is ran in a separate thread (with
`future`).

## Development

Run the project's CI pipeline and build a JAR:

    $ clojure -T:build ci

This will produce an updated `pom.xml` file with synchronized dependencies inside the `META-INF`
directory inside `target/classes` and the JAR in `target`. You can update the version (and SCM tag)
information in generated `pom.xml` by updating `build.clj`.

Install it locally (requires the `ci` task be run first):

    $ clojure -T:build install

Deploy it to Clojars -- needs `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` environment
variables (requires the `ci` task be run first):

    $ clojure -T:build deploy

Your library will be deployed to com.latacora/autobrowse on Clojars.

## License

Copyright © Latacora, LLC

Distributed under the Eclipse Public License version 2.0 (see LICENSE).
