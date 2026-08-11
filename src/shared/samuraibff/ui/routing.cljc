(ns samuraibff.ui.routing
  "Pure URL helpers shared by browser and packaged Electron routing."
  (:require
   [clojure.string :as str]))

(defn navigation-href
  "Return the browser href for a logical application route.

  Inputs:
  - protocol: location protocol string, such as `http:` or `file:`
  - route-href: absolute logical route, such as `/live`

  Returns:
  - hash route such as `#/live` for packaged `file:` pages
  - the unchanged logical route for HTTP(S) pages."
  [protocol route-href]
  (if (= "file:" protocol)
    (str "#" route-href)
    route-href))

(defn location-route-path
  "Return the logical application path represented by a browser location.

  Inputs:
  - protocol: location protocol string
  - pathname: location pathname string
  - hash: location hash string

  Returns:
  - the route stored after `#` for packaged `file:` pages
  - `/recordings` when a packaged page has no route hash
  - the pathname for HTTP(S) pages."
  [protocol pathname hash]
  (if (= "file:" protocol)
    (if (and (string? hash)
             (str/starts-with? hash "#/"))
      (subs hash 1)
      "/recordings")
    pathname))
