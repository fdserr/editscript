(ns editscript.diff.quick
  (:require [clojure.set :as set]
            [editscript.core :refer :all]))

(set! *unchecked-math* :warn-on-boxed)

(declare diff*)

(defn- diff-map [script path a b]
  (reduce-kv
   (fn [_ ka va]
     (let [path' (conj path ka)]
       (if (contains? b ka)
        (diff* script path' va (get b ka))
        (diff* script path' va :editscript.core/nil))))
   nil
   a)
  (reduce-kv
   (fn [_ kb vb]
     (when-not (contains? a kb)
       (diff* script (conj path kb) :editscript.core/nil vb)))
   nil
   b))

(defn- vec-edits*
  "Based on 'Wu, S. et al., 1990, An O(NP) Sequence Comparison Algorithm,
  Information Processing Letters, 35:6, p317-23.'"
  [a b n m]
  (let [delta (- n m)
        snake (fn [k x]
                (loop [x x y (- x k)]
                  (let [ax (get a x) by (get b y)]
                    (if (and (< x n)
                             (< y m)
                             (= (type ax) (type by))
                             (= ax by))
                      (recur (inc x) (inc y))
                      x))))
        fp-fn (fn [fp k]
                (let [[dk-1 vk-1] (get fp (dec k) [-1 []])
                      dk-1        (inc dk-1)
                      [dk+1 vk+1] (get fp (inc k) [-1 []])
                      x           (max dk-1 dk+1)
                      sk          (snake k x)
                      ops         (let [es (if (> dk-1 dk+1)
                                             (conj vk-1 :-)
                                             (conj vk+1 :+))]
                                    (if (> sk x)
                                      (conj es (- sk x))
                                      es))]
                  (assoc! fp k [sk ops])))
        fp    (loop [p 0 fp (transient {})]
                (let [fp (loop [k (* -1 p) fp fp]
                           (if (< k delta)
                             (recur (inc k) (fp-fn fp k))
                             fp))
                      fp (loop [k (+ delta p) fp fp]
                           (if (< delta k)
                             (recur (dec k) (fp-fn fp k))
                             fp))
                      fp (fp-fn fp delta)]
                  (if-not (= n (first (get fp delta)))
                    (recur (inc p) fp)
                    (persistent! fp))))]
    (-> fp (get delta) second rest)))

(defn- swap-ops [edits] (vec (map (fn [op] (case op :+ :- :- :+ op)) edits)))

(defn min+plus->replace
  "Turn isolated consecutive `:-` `:+` into a `:r`,
  do not convert if there's `:-` in front, as it is ambiguous"
  [v]
  {:pre [(vector? v)]}
  (let [n (count v)]
    (loop [r (transient []) i -1 j 0 k 1]
      (let [ei (get v i) ej (get v j) ek (get v k)]
       (cond
         (and (= ej :-)
              (= ek :+)
              (not= ei :-)) (recur (conj! r :r) (+ i 2) (+ j 2) (+ k 2))
         (>= j n)           (persistent! r)
         :else              (recur (conj! r ej) (inc i) (inc j) (inc k)))))))

(defn vec-edits [a b]
  (let [n (count a)
        m (count b)
        v (if (< n m)
            (swap-ops (vec-edits* b a m n))
            (vec-edits* a b n m))]
    (-> v vec min+plus->replace)))

(defn- diff-vec [script path a b]
  (reduce
   (fn [{:keys [ia ia' ib] :as m} op]
     (case op
       :- (do (diff* script (conj path ia') (get a ia) :editscript.core/nil)
              (assoc! m :ia (inc ia)))
       :+ (do (diff* script (conj path ia') :editscript.core/nil (get b ib))
              (assoc! m :ia' (inc ia') :ib (inc ib)))
       :r (do (diff* script (conj path ia') (get a ia) (get b ib))
              (assoc! m :ia (inc ia) :ia' (inc ia') :ib (inc ib)))
       (assoc! m :ia (+ ia op) :ia' (+ ia' op) :ib (+ ib op))))
   (transient {:ia 0 :ia' 0 :ib 0})
   (vec-edits a b)))

(defn- diff-set [script path a b]
  (doseq [va (set/difference a b)]
    (diff* script (conj path va) va :editscript.core/nil))
  (doseq [vb (set/difference b a)]
    (diff* script (conj path vb) :editscript.core/nil vb)))

(defn- diff-lst [script path a b]
  (diff-vec script path (vec a) (vec b)))

(defn diff* [script path a b]
  (when-not (identical? a b)
    (case (get-type a)
      :nil (add-data script path b)
      :map (case (get-type b)
             :nil (delete-data script path)
             :map (diff-map script path a b)
             (replace-data script path b))
      :vec (case (get-type b)
             :nil (delete-data script path)
             :vec (diff-vec script path a b)
             (replace-data script path b))
      :set (case (get-type b)
             :nil (delete-data script path)
             :set (diff-set script path a b)
             (replace-data script path b))
      :lst (case (get-type b)
             :nil (delete-data script path)
             :lst (diff-lst script path a b)
             (replace-data script path b))
      :val (case (get-type b)
             :nil (delete-data script path)
             (when-not (= a b)
               (replace-data script path b))))))

(defn diff
  "Create an EditScript that represents the difference between `b` and `a`
  This algorithm is fast, but it does not guarantee the EditScript is minimal"
  [a b]
  (let [script (->EditScript [] 0 0 0)]
    (diff* script [] a b)
    script))