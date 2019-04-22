(ns clj-kondo.impl.linters
  {:no-doc true}
  (:require
   [clj-kondo.impl.utils :refer [some-call node->line
                                 tag call]]
   [clojure.string :as str]
   [rewrite-clj.node.protocols :as node]
   [clj-kondo.impl.utils :refer [parse-string]]))

(set! *warn-on-reflection* true)

;;;; redundant let
;; TODO: move to call specific linters

(defn redundant-let* [{:keys [:children] :as expr}
                      parent-let?]
  (let [current-let? (some-call expr let)]
    (cond (and current-let? parent-let?)
          [expr]
          current-let?
          (let [;; skip let keywords and bindings
                children (nnext children)]
            (concat (redundant-let* (first children) current-let?)
                    (mapcat #(redundant-let* % false) (rest children))))
          :else (mapcat #(redundant-let* % false) children))))

(defn redundant-let [filename parsed-expressions]
  (map #(node->line filename % :warning :redundant-let "redundant let")
       (redundant-let* parsed-expressions false)))

;;;; redundant do
;; TODO: move to call specific linters

(defn redundant-do* [{:keys [:children] :as expr}
                     parent-do?]
  (let [implicit-do? (some-call expr fn defn defn-
                                let loop binding with-open
                                doseq try)
        current-do? (some-call expr do)]
    (cond (and current-do? (or parent-do?
                               (and (not= :unquote-splicing
                                          (tag (second children)))
                                    (<= (count children) 2))))
          [expr]
          :else (mapcat #(redundant-do* % (or implicit-do? current-do?)) children))))

(defn redundant-do [filename parsed-expressions]
  (map #(node->line filename % :warning :redundant-do "redundant do")
       (redundant-do* parsed-expressions false)))

;;;; map linter

(defn key-value
  "We only support tokens as key values for now."
  [node]
  (case (node/tag node)
    :token (node/string node)
    nil))

(defn lint-map-keys [expr]
  (let [children (:children expr)
        duplicate-keys (:findings
                        (reduce
                         (fn [{:keys [:seen] :as acc} key-expr]
                           (if-let [k (key-value key-expr)]
                             (if (contains? seen k)
                               (update acc :findings conj
                                       (node->line "_" key-expr :error :duplicate-map-key
                                                   (str "duplicate key " k)))
                               (update acc :seen conj k))
                             acc))
                         {:seen #{}
                          :findings []}
                         (take-nth 2 children)))
        missing-value (if (odd? (count children))
                        (let [last-child (last children)]
                          [(node->line "_" last-child :error :missing-map-value
                                       (str "missing value for key " (key-value last-child)))])
                        [])]
    (into duplicate-keys missing-value)))

;;;; end map linter

;;;; set linter

(defn lint-set [expr]
  (let [children (:children expr)
        duplicate-keys (:findings
                        (reduce
                         (fn [{:keys [:seen] :as acc} set-element]
                           (if-let [k (key-value set-element)]
                             (if (contains? seen k)
                               (update acc :findings conj
                                       (node->line "_" set-element :error :duplicate-set-key
                                                   (str "duplicate set element " k)))
                               (update acc :seen conj k))
                             acc))
                         {:seen #{}
                          :findings []}
                         children))]
    duplicate-keys))

;;;; end set linter

(defn lint-def* [filename expr in-def?]
  (let [fn-name (call expr)
        simple-fn-name (when fn-name (symbol (name fn-name)))]
    ;; TODO: it would be nicer if we could have the qualified calls of this expression somehow
    ;; so we wouldn't have to deal with these primitive expressions anymore
    (when-not (= 'case simple-fn-name)
      (let [current-def? (contains? '#{expr def defn defn- deftest defmacro} fn-name)
            new-in-def? (and (not (contains? '#{:syntax-quote :quote}
                                             (node/tag expr)))
                             (or in-def? current-def?))]
        (if (and in-def? current-def?)
          [(node->line filename expr :warning :inline-def "inline def")]
          (when (:children expr)
            (mapcat #(lint-def* filename % new-in-def?) (:children expr))))))))

(defn lint-def [filename expr]
  (mapcat #(lint-def* filename % true) (:children expr)))

(defn lint-cond [filename expr]
  (let [last-condition
        (->> expr :children
             (take-last 2) first :k)]
    (when (not= :else last-condition)
      [(node->line filename expr :warning :cond-without-else "cond without :else")])))

(defn lint-deftest [config filename expr]
  (let [calls (nnext (:children expr))]
    (for [c calls
          :let [fn-name (some-> c :children first :string-value)]
          :when (and fn-name
                     (not (when-let [excluded (-> config :missing-test-assertion :exclude)]
                            (contains? excluded (symbol fn-name))))
                     (or (= "=" fn-name) (str/ends-with? fn-name "?")))]
      (node->line filename c :warning :missing-test-assertion "missing test assertion"))))

(defn lint-specific-calls [config filename call called-fn]
  (case (:ns called-fn)
    (clojure.core cljs.core)
    (case (:name called-fn)
      (cond) (lint-cond filename (:expr call))
      (def defn defn- defmacro) (lint-def filename (:expr call))
      [])
    (clojure.test cljs.test)
    (case (:name called-fn)
      (deftest) (lint-def filename (:expr call))
      [])
    #_#_[clojure.test deftest] (lint-deftest config filename (:expr call))
    #_#_[cljs.test deftest] (lint-deftest config filename (:expr call))
    []))

(defn resolve-call [idacs call fn-ns fn-name]
  (let [call-lang (:lang call)
        base-lang (or (:base-lang call) call-lang) ;; .cljc, .cljs or .clj file
        caller-ns (:ns call)
        ;; this call was unqualified and inferred as a function in the same namespace until now
        unqualified? (:unqualified? call)
        same-ns? (= caller-ns fn-ns)]
    (case [base-lang call-lang]
      [:clj :clj] (or (get-in idacs [:clj :defs fn-ns fn-name])
                      (get-in idacs [:cljc :defs fn-ns :clj fn-name]))
      [:cljs :cljs] (or (get-in idacs [:cljs :defs fn-ns fn-name])
                        ;; when calling a function in the same ns, it must be in
                        ;; another file, hence qualified via a require
                        ;; an exception to this would be :refer :all, but this doesn't exist in CLJS
                        (when-not (and same-ns? unqualified?)
                          (or
                           ;; cljs func in another cljc file
                           (get-in idacs [:cljc :defs fn-ns :cljs fn-name])
                           ;; maybe a macro?
                           (get-in idacs [:clj :defs fn-ns fn-name])
                           (get-in idacs [:cljc :defs fn-ns :clj fn-name]))))
      ;; calling a clojure function from cljc
      [:cljc :clj] (or (get-in idacs [:clj :defs fn-ns fn-name])
                       (get-in idacs [:cljc :defs fn-ns :clj fn-name]))
      ;; calling function in a CLJS conditional from a CLJC file
      [:cljc :cljs] (or (get-in idacs [:cljs :defs fn-ns fn-name])
                        (get-in idacs [:cljc :defs fn-ns :cljs fn-name])
                        ;; could be a macro
                        (get-in idacs [:clj :defs fn-ns fn-name])
                        (get-in idacs [:cljc :defs fn-ns :clj fn-name])))))

(defn lint-calls
  "Lints calls for arity errors, private calls errors. Also dispatches to call-specific linters."
  [idacs config]
  (let [findings (for [lang [:clj :cljs :cljc]
                       ns-sym (keys (get-in idacs [lang :calls]))
                       call (get-in idacs [lang :calls ns-sym])
                       :let [fn-name (:name call)
                             caller-ns (:ns call)
                             fn-ns (:resolved-ns call)
                             called-fn
                             (or (resolve-call idacs call fn-ns fn-name)
                                 ;; we resolved this call against the
                                 ;; same namespace, because it was
                                 ;; unqualified
                                 (when (= caller-ns fn-ns)
                                   (some #(resolve-call idacs call % fn-name)
                                         (into (vec
                                                (keep (fn [[ns excluded]]
                                                        (when-not (contains? excluded fn-name)
                                                          ns))
                                                      (-> call :ns-lookup :refer-alls)))
                                               (when (not (:clojure-excluded? call))
                                                 [(case lang
                                                    :clj 'clojure.core
                                                    :cljs 'cljs.core
                                                    :cljc 'clojure.core)])))))
                             fn-ns (:ns called-fn)]
                       :when called-fn
                       :let [;; a macro in a CLJC file with the same namespace
                             ;; in that case, looking at the row and column is
                             ;; not reliable.  we may look at the lang of the
                             ;; call and the lang of the function def context in
                             ;; the case of in-ns, the bets are off. we may
                             ;; support in-ns in a next version.
                             valid-order? (if (and (= caller-ns
                                                      fn-ns)
                                                   (= (:base-lang call)
                                                      (:base-lang called-fn))
                                                   ;; some built-ins may not have a row and col number
                                                   (:row called-fn))
                                            (or (> (:row call) (:row called-fn))
                                                (and (= (:row call) (:row called-fn))
                                                     (> (:col call) (:col called-fn))))
                                            true)]
                       :when valid-order?
                       :let [arity (:arity call)
                             filename (:filename call)
                             fixed-arities (:fixed-arities called-fn)
                             var-args-min-arity (:var-args-min-arity called-fn)
                             errors
                             (into
                              [(when-not
                                   (or (contains? fixed-arities arity)
                                       (and var-args-min-arity (>= arity var-args-min-arity))
                                       (when-let [excluded (-> config :invalid-arity :exclude)]
                                         (contains? excluded
                                                    (symbol (str fn-ns)
                                                            (str fn-name)))))
                                 {:filename filename
                                  :row (:row call)
                                  :col (:col call)
                                  :level :error
                                  :type :invalid-arity
                                  :message (format "wrong number of args (%s) passed to %s"
                                                   (str (:arity call))
                                                   (str (:ns called-fn) "/" (:name called-fn)))})
                               (when (and (:private? called-fn)
                                          (not= caller-ns
                                                fn-ns))
                                 {:filename filename
                                  :row (:row call)
                                  :col (:col call)
                                  :level :error
                                  :type :private-call
                                  :message (format "call to private function %s"
                                                   (:name call))})]
                              (lint-specific-calls config filename call called-fn))]
                       e errors
                       :when e]
                   e)]
    findings))

;;;; scratch

(comment
  )
