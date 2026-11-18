(ns open.atlas.common.ui.pagination
  (:require [uix.core :refer [$ defui]]))

(defui pager [{:keys [data busy? on-page on-size label]}]
  (let [{:keys [page size total pages] :or {page 1 size 20 total 0 pages 1}} data]
    ($ :nav.pagination {:aria-label (or label "Pagination")}
       ($ :span (str (if (zero? total) 0 (inc (* (dec page) size))) "–" (min total (* page size)) " of " total))
       ($ :label "Per page "
          ($ :select {:value size :disabled busy? :aria-label (str (or label "Results") " per page")
                      :on-change #(on-size (js/parseInt (.. % -target -value) 10))}
             ($ :option {:value 20} "20") ($ :option {:value 50} "50")))
       ($ :button {:disabled (or busy? (<= page 1)) :on-click #(on-page (dec page))} "Previous")
       ($ :span (str "Page " page " of " pages))
       ($ :button {:disabled (or busy? (>= page pages)) :on-click #(on-page (inc page))} "Next"))))
