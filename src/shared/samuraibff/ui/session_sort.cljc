(ns samuraibff.ui.session-sort
  "Sort state and duration text of the Sessions list.

  The BFF sorts the list (`GET /api/recordings?sort=&order=`); this namespace
  holds the column order, the header state and the labels that the table and
  the mobile cards share. Sort values match the `sort` query values of the
  API."
  (:require
   [clojure.string :as str]))

(def default-sort
  "Sort of the Sessions list before the user picks one: newest first."
  {:sort "created_at" :order "desc"})

(def columns
  "Sortable Sessions columns in display order.

  Each entry: {:key API sort value, :label column header, :first-order order
  of the first click, :asc / :desc label of that order in the mobile select}.
  Actions is not a sortable column."
  [{:key "title" :label "Session" :first-order "asc"
    :asc "Session, A to Z" :desc "Session, Z to A"}
   {:key "created_at" :label "Created" :first-order "desc"
    :asc "Created, oldest first" :desc "Created, newest first"}
   {:key "started_at" :label "Started" :first-order "desc"
    :asc "Started, oldest first" :desc "Started, newest first"}
   {:key "duration" :label "Duration" :first-order "desc"
    :asc "Duration, shortest first" :desc "Duration, longest first"}
   {:key "status" :label "Status" :first-order "asc"
    :asc "Status, drafts first" :desc "Status, failed first"}])

(def ^:private column-by-key
  (into {} (map (juxt :key identity)) columns))

(defn next-sort
  "Return the sort after a click on a column header.

  Inputs: current sort {:sort string :order \"asc\"|\"desc\"} and the column
  key. A click on the active column reverses the order; a click on another
  column sorts by it in its :first-order. Returns a sort map."
  [{:keys [sort order]} column-key]
  (if (= sort column-key)
    {:sort column-key :order (if (= "asc" order) "desc" "asc")}
    {:sort column-key :order (get-in column-by-key [column-key :first-order] "asc")}))

(defn aria-sort
  "Return the `aria-sort` value of a column header, or nil.

  Inputs: current sort map and the column key. Returns \"ascending\" or
  \"descending\" for the active column and nil for the others, so only one
  header carries the attribute."
  [{:keys [sort order]} column-key]
  (when (= sort column-key)
    (if (= "asc" order) "ascending" "descending")))

(defn indicator-icon
  "Return the icon name that shows the sort state of a column header.

  Returns :chevron-up (ascending) or :chevron-down (descending) for the
  active column and :chevrons-up-down for the other columns."
  [current column-key]
  (case (aria-sort current column-key)
    "ascending" :chevron-up
    "descending" :chevron-down
    :chevrons-up-down))

(defn header-title
  "Return the tooltip of a column header button: what a click will do.

  Inputs: current sort map and a column map from `columns`."
  [current {:keys [key] :as column}]
  (let [{:keys [order]} (next-sort current key)]
    (str "Sort by " (get column (keyword order)))))

(defn option-value
  "Encode a sort map as one select option value, for example \"title:asc\"."
  [{:keys [sort order]}]
  (str sort ":" order))

(def select-options
  "Options of the mobile sort select: [value label] pairs for every column in
  both orders, in display order."
  (vec (for [{:keys [key] :as column} columns
             order ["asc" "desc"]]
         [(option-value {:sort key :order order}) (get column (keyword order))])))

(defn parse-option-value
  "Decode a select option value made by `option-value`.

  Returns a sort map, or `default-sort` for a value that is not one of
  `select-options`."
  [value]
  (if (some #(= value (first %)) select-options)
    (let [[sort order] (str/split value #":")]
      {:sort sort :order order})
    default-sort))

(defn- two-digits
  [n]
  (if (< n 10) (str "0" n) (str n)))

(defn format-duration
  "Format a duration in seconds as m:ss, or h:mm:ss from one hour.

  Inputs: number of seconds or nil. Rounds to whole seconds. Returns the
  text, or \"—\" for nil or a negative value."
  [seconds]
  (if (or (nil? seconds) (neg? seconds))
    "—"
    (let [total (long (Math/round (double seconds)))
          hours (quot total 3600)
          minutes (quot (rem total 3600) 60)
          secs (rem total 60)]
      (if (pos? hours)
        (str hours ":" (two-digits minutes) ":" (two-digits secs))
        (str minutes ":" (two-digits secs))))))

(defn duration-text
  "Return the Duration text of a Sessions row.

  Inputs: a list item from `/api/recordings` (:status, :duration_s).
  Returns \"\" for a session that is recording now (its Status says
  Recording), otherwise `format-duration` of :duration_s."
  [{:keys [status duration_s]}]
  (if (= "active" status)
    ""
    (format-duration duration_s)))
