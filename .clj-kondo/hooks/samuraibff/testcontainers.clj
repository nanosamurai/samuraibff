(ns hooks.samuraibff.testcontainers
  (:require
    [clj-kondo.hooks-api :as api]))

(defn- single-sym-binding->let-bindings
  "Turn macro bindings like `[container]` into a valid `let` binding vector node.

  clj-kondo expects an even number of forms in the binding vector.
  We use `nil` as a placeholder value since only the symbol matters for analysis."
  [bindings-node]
  (let [children (:children bindings-node)
        sym-node (first children)]
    (api/vector-node [sym-node (api/token-node nil)])))

(defn with-localstack
  "clj-kondo hook for `samuraibff.testcontainers.localstack/with-localstack`.

  Expands the macro into a `let` form for static analysis purposes."
  [{:keys [node]}]
  (let [[_ bindings & body] (:children node)
        let-bindings (single-sym-binding->let-bindings bindings)]
    {:node (api/list-node
            (list* (api/token-node 'let)
                   let-bindings
                   body))}))

(defn with-postgres
  "clj-kondo hook for `samuraibff.testcontainers.postgres/with-postgres`.

  Expands the macro into a `let` form for static analysis purposes."
  [{:keys [node]}]
  (let [[_ bindings & body] (:children node)
        let-bindings (single-sym-binding->let-bindings bindings)]
    {:node (api/list-node
            (list* (api/token-node 'let)
                   let-bindings
                   body))}))
