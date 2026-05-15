(ns samuraibff.ui.langs
  "ISO-639-1 language dataset + small helpers used by the UI.

  Motivation:
  - The backend expects `lang` as an ISO-639-1 language code (e.g. \"en\", \"cs\")
    or an empty string for auto-detection.
  - The UI should show human-friendly language names and a small flag icon.

  This namespace is intentionally dependency-free so it can be shared between
  Clojure (tests) and ClojureScript (SPA).

  Notes on dataset:
  - We start from Java's `Locale/getISOLanguages` list (188 entries), but we
    filter out a handful of deprecated alias codes (e.g. \"iw\" for Hebrew).
    Those aliases are not desirable as user-facing values.
  - The resulting `iso-639-1-codes` are *lowercase* 2-letter strings."
  (:require
    [clojure.string :as str]))

(def ^:private deprecated-alias-codes
  "Deprecated ISO-639-1 alias codes that should not be emitted by the UI.

  References (common historical aliases):
  - he (Hebrew)  was iw
  - id (Indonesian) was in
  - yi (Yiddish) was ji
  - ro (Romanian/Moldavian) was mo
  - jv (Javanese) was jw
  - sr (Serbian/Croatian) had historical sh

  Returns: set of strings." 
  #{"in" "iw" "ji" "jw" "mo" "sh"})

(def raw-iso-639-1-codes
  "Raw list of ISO language codes from `java.util.Locale/getISOLanguages`.

  Contains a few deprecated alias codes; use `iso-639-1-codes` for the final
  list.

  Returns: vector of lowercase strings." 
  ["aa" "ab" "ae" "af" "ak" "am" "an" "ar" "as" "av" "ay" "az"
   "ba" "be" "bg" "bh" "bi" "bm" "bn" "bo" "br" "bs"
   "ca" "ce" "ch" "co" "cr" "cs" "cu" "cv" "cy"
   "da" "de" "dv" "dz"
   "ee" "el" "en" "eo" "es" "et" "eu"
   "fa" "ff" "fi" "fj" "fo" "fr" "fy"
   "ga" "gd" "gl" "gn" "gu" "gv"
   "ha" "he" "hi" "ho" "hr" "ht" "hu" "hy" "hz"
   "ia" "id" "ie" "ig" "ii" "ik" "in" "io" "is" "it" "iu" "iw"
   "ja" "ji" "jv"
   "ka" "kg" "ki" "kj" "kk" "kl" "km" "kn" "ko" "kr" "ks" "ku" "kv" "kw" "ky"
   "la" "lb" "lg" "li" "ln" "lo" "lt" "lu" "lv"
   "mg" "mh" "mi" "mk" "ml" "mn" "mo" "mr" "ms" "mt" "my"
   "na" "nb" "nd" "ne" "ng" "nl" "nn" "no" "nr" "nv" "ny"
   "oc" "oj" "om" "or" "os"
   "pa" "pi" "pl" "ps" "pt"
   "qu"
   "rm" "rn" "ro" "ru" "rw"
   "sa" "sc" "sd" "se" "sg" "si" "sk" "sl" "sm" "sn" "so" "sq" "sr" "ss" "st" "su" "sv" "sw"
   "ta" "te" "tg" "th" "ti" "tk" "tl" "tn" "to" "tr" "ts" "tt" "tw" "ty"
   "ug" "uk" "ur" "uz"
   "ve" "vi" "vo"
   "wa" "wo"
   "xh"
   "yi" "yo"
   "za" "zh" "zu"])

(def whisper-supported-codes
  "Language codes supported by Whisper / faster-whisper / WhisperX.

  Notes:
  - Whisper supports many languages; our UI intentionally constrains the dropdown
    to those officially supported by the Whisper model family.
  - This list is kept in-code so the SPA stays dependency-free (no runtime fetch).

  Returns: set of lowercase strings." 
  #{"af" "am" "ar" "as" "az" "ba" "be" "bg" "bn" "bo" "br" "bs" "ca" "cs" "cy"
    "da" "de" "el" "en" "es" "et" "eu" "fa" "fi" "fo" "fr" "ga" "gl" "gu" "ha"
    "haw" "he" "hi" "hr" "ht" "hu" "hy" "id" "is" "it" "ja" "jw" "ka" "kk" "km"
    "kn" "ko" "la" "lb" "ln" "lo" "lt" "lv" "mg" "mi" "mk" "ml" "mn" "mr" "ms"
    "mt" "my" "ne" "nl" "nn" "no" "oc" "pa" "pl" "ps" "pt" "ro" "ru" "sa" "sd"
    "si" "sk" "sl" "sn" "so" "sq" "sr" "su" "sv" "sw" "ta" "te" "tg" "th" "tk"
    "tl" "tr" "tt" "uk" "ur" "uz" "vi" "yi" "yo" "zh"})

(def iso-639-1-codes
  "Canonical list of ISO-639-1 language codes for the UI.

  Returns: vector of lowercase 2-letter strings." 
  (->> raw-iso-639-1-codes
       (remove deprecated-alias-codes)
       (filter whisper-supported-codes)
       distinct
       sort
       vec))

(defn valid-lang-code?
  "Return true when `lang` is a valid UI language value.

  Inputs:
  - lang: any (typically string)

  Semantics:
  - empty / blank string is valid (means auto-detection)
  - otherwise must be present in `iso-639-1-codes`

  Returns: boolean." 
  [lang]
  (let [lang (str (or lang ""))
        lang (str/trim lang)]
    (or (str/blank? lang)
        (contains? (set iso-639-1-codes) lang))))

#?(:cljs
   (do
     (def ^:private display-names-en
       "Best-effort `Intl.DisplayNames` for English language names.

       May be nil in older browsers." 
       (when (and (exists? js/Intl)
                  (exists? (.-DisplayNames js/Intl)))
         (try
           (js/Intl.DisplayNames. #js ["en"] #js {:type "language"})
           (catch :default _
             nil))))

     (defn lang->display-name
       "Return a human-friendly language name for ISO-639-1 code.

       Inputs:
       - code: string (e.g. \"cs\")

       Returns: string (fallbacks to the code itself)." 
       [code]
       (let [code (str (or code ""))]
         (cond
           (str/blank? code) "Auto"

           (nil? display-names-en) code

           :else
           (or (.of display-names-en code) code))))

     (defn- region->flag-emoji
       "Convert an ISO-3166-1 alpha-2 region code (e.g. \"CZ\") into a Unicode flag.

       Returns a string. Fallback is a white flag." 
       [region]
       (let [region (some-> region str str/trim str/upper-case)]
         (if (and (string? region)
                  (= 2 (count region))
                  (re-matches #"[A-Z]{2}" region))
           (let [a (.codePointAt region 0)
                 b (.codePointAt region 1)
                 base 127397]
             (str (js/String.fromCodePoint (+ base a))
                  (js/String.fromCodePoint (+ base b))))
           "🏳")))

      (defn region->flag-icons-src
        "Return a relative URL to a `flag-icons` SVG for a region.

        We vendor a subset of `flag-icons` into:
        - resources/public/img/flags/4x3/<region>.svg

        Inputs:
        - region: string ISO-3166-1 alpha-2, e.g. \"CZ\"

        Returns:
        - string URL, e.g. \"/img/flags/4x3/cz.svg\"
        - nil when the input is invalid.

        Notes:
        - Existence of the file is not checked here (no IO in CLJS); the UI
          handles errors/fallback." 
        [region]
        (let [region (some-> region str str/trim str/lower-case)
              prefix (if (= "file:"
                            (some-> js/window .-location .-protocol))
                       "img/flags/4x3/"
                       "/img/flags/4x3/")]
          (when (and (string? region)
                     (= 2 (count region))
                     (re-matches #"[a-z]{2}" region))
            (str prefix region ".svg"))))

     (defn lang->flag
       "Best-effort flag emoji for ISO-639-1 language code.

       Implementation detail:
       - We use `Intl.Locale(...).maximize().region` (likely subtags) to derive a
         reasonable region for the language.
       - If the browser doesn't support `Intl.Locale` / maximize or no region is
         derived, we fall back to a white flag.

       Inputs:
       - code: string (e.g. \"cs\")

       Returns: string (emoji)." 
       [code]
       (let [code (str (or code ""))]
         (cond
           (str/blank? code) "🌐"

           (and (exists? js/Intl)
                (exists? (.-Locale js/Intl)))
           (try
             (let [loc (js/Intl.Locale. code)
                   max-loc (.maximize loc)
                   region (.-region max-loc)]
               (region->flag-emoji region))
             (catch :default _
               "🏳"))

           :else
           "🏳")))

      (defn lang->flag-icon
        "Return a best-effort flag icon descriptor for a language code.

        This is intended for Electron, where Unicode flag ligatures may not
        render correctly.

        Inputs:
        - code: string ISO-639-1 language code (e.g. \"cs\") or blank

        Returns:
        - {:type :emoji :value <string>} for Auto / fallback
        - {:type :svg :src <string> :alt <string>} when a region can be derived

        Notes:
        - Region is derived via Intl.Locale(...).maximize().region (best-effort).
        - SVG assets are provided by vendored `flag-icons` under /img/flags/4x3/." 
        [code]
        (let [code (str (or code ""))]
          (cond
            (str/blank? code)
            {:type :emoji :value "🌐"}

            (and (exists? js/Intl)
                 (exists? (.-Locale js/Intl)))
            (try
              (let [loc (js/Intl.Locale. code)
                    max-loc (.maximize loc)
                    region (.-region max-loc)
                    src (region->flag-icons-src region)]
                (if (seq src)
                  {:type :svg
                   :src src
                   :alt (.toUpperCase (str (or region "")))}
                  {:type :emoji :value "🌐"}))
              (catch :default _
                {:type :emoji :value "🌐"}))

            :else
            {:type :emoji :value "🌐"})))

     (defn language-options
       "Return all language dropdown options for the Live Recording page.

       Returns:
       - vector of maps:
         {:value <string>, :label <string>, :flag <string>}
       
       Notes:
       - Includes the special auto option as the first element.
       - English (\"en\") is placed first among explicit languages." 
       []
       (let [codes (->> iso-639-1-codes
                        (remove #{"en"})
                        (cons "en"))]
         (into
           [{:value ""
             :label "Auto"
             :flag "🌐"}]
           (map (fn [code]
                  {:value code
                   :label (lang->display-name code)
                   :flag (lang->flag code)
                   :flagIcon (lang->flag-icon code)}))
           codes)))))
