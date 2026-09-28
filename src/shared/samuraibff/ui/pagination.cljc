(ns samuraibff.ui.pagination
  "Small, shared pagination calculations for the Sessions page.")

(def page-sizes [20 30 50])

(defn page-info
  "Return bounds for a non-negative total, zero-based page, and allowed page size.

  Clamps the page after deletions and normalizes unsupported sizes to 20.
  Returns :page, :page-size, :pages, :offset, :from, :to, :previous?, and :next?.
  Empty results have one empty page and the range 0–0."
  [total page page-size]
  (let [size (if (some #{page-size} page-sizes) page-size 20)
        pages (max 1 (quot (+ total (dec size)) size))
        page (max 0 (min page (dec pages)))
        offset (* page size)]
    {:page page :page-size size :pages pages :offset offset
     :from (if (pos? total) (inc offset) 0)
     :to (min total (+ offset size))
     :previous? (pos? page) :next? (< page (dec pages))}))
