; Copyright (c) 2011-2013, Tom Van Cutsem, Vrije Universiteit Brussel
; All rights reserved.
;
; Redistribution and use in source and binary forms, with or without
; modification, are permitted provided that the following conditions are met:
;    * Redistributions of source code must retain the above copyright
;      notice, this list of conditions and the following disclaimer.
;    * Redistributions in binary form must reproduce the above copyright
;      notice, this list of conditions and the following disclaimer in the
;      documentation and/or other materials provided with the distribution.
;    * Neither the name of the Vrije Universiteit Brussel nor the
;      names of its contributors may be used to endorse or promote products
;      derived from this software without specific prior written permission.
;
;THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
;ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
;WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
;DISCLAIMED. IN NO EVENT SHALL VRIJE UNIVERSITEIT BRUSSEL BE LIABLE FOR ANY
;DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
;(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
;LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
;ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
;(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
;SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

;; MC-STM: meta-circular STM in Clojure
;; Multicore Programming
;; (c) 2011-2013, Tom Van Cutsem

;; version 2 - multi-version concurrency control STM with history lists
;; Improvements over v1:
;; - internal consistency is now guaranteed, thanks to MVCC
;;   (storing multiple historical values per mc-ref)
;;
;; Limitations:
;; - a single global commit-lock for all transactions
;;   (= severe bottleneck, but makes it easy to validate and commit)
;; - naive support for commute
;; - naive support for ensure (to prevent write skew)

(ns stm.v2-mvcc)

;; === MC-STM internals ===

; a thread-local var that holds the current transaction executed by this thread
; if the thread does not execute a transaction, this is set to nil
(def ^:dynamic *current-transaction* nil)

; global counter, incremented every time a transaction commits successfully
(def GLOBAL_WRITE_POINT (atom 0))

; maximum amount of older values stored, per mc-ref
(def MAX_HISTORY 10)

(defn make-transaction
  "create and return a new transaction data structure"
  []
  { :read-point @GLOBAL_WRITE_POINT,
    :in-tx-values (atom {}), ; map: ref -> any value
    :written-refs (atom #{}) }) ; set of refs

(defn find-entry-before-or-on
  "returns an entry in history-chain whose write-pt <= read-pt,
   or nil if no such entry exists"
  [history-chain read-pt]
  (some (fn [pair]
          (if (and pair (<= (:write-point pair) read-pt))
            pair)) history-chain))

; history lists of mc-refs are ordered youngest to eldest
(def most-recent first)

(defn tx-retry
  "immediately abort and retry the current transaction"
  []
  (throw (new stm.RetryEx)))

(defn tx-read
  "read the value of ref inside transaction tx"
  [tx mc-ref]
  (let [in-tx-values (:in-tx-values tx)]
    (if (contains? @in-tx-values mc-ref)
      (@in-tx-values mc-ref) ; return the in-tx-value
      ; search the history chain for entry with write-point <= tx's read-point
      (let [ref-entry (find-entry-before-or-on @mc-ref (:read-point tx))]
        (if (not ref-entry)
          ; if such an entry was not found, retry
          (tx-retry))
        (let [in-tx-value (:value ref-entry)]
          (swap! in-tx-values assoc mc-ref in-tx-value) ; cache the value
          in-tx-value))))) ; save and return the ref's value

(defn tx-write
  "write val to ref inside transaction tx"
  [tx mc-ref val]
  (swap! (:in-tx-values tx) assoc mc-ref val)
  (swap! (:written-refs tx) conj mc-ref)
  val)

; a single global lock for all transactions to acquire on commit
; we use the monitor of a fresh empty Java object
; all threads share the same root-binding, so will acquire the same lock
(def COMMIT_LOCK (new java.lang.Object))

(defn tx-commit
  "returns normally if tx committed successfully, throws RetryEx otherwise"
  [tx]
  (let [written-refs @(:written-refs tx)]
    (when (not (empty? written-refs))
      (locking COMMIT_LOCK
        (doseq [written-ref written-refs]
          (if (> (:write-point (most-recent @written-ref))
                (:read-point tx))
            (tx-retry)))

        ; if validation OK, make in-tx-value of all written refs public    
        (let [in-tx-values @(:in-tx-values tx)
              new-write-point (inc @GLOBAL_WRITE_POINT)]
          (doseq [ref written-refs]
            (swap! ref (fn [history-chain]
                         ; add a new entry to the front of the history list...
                         (cons {:value (in-tx-values ref)
                                :write-point new-write-point}
                           ; ... and remove the eldest
                           (butlast history-chain)))))
          (swap! GLOBAL_WRITE_POINT inc)))))) ; make the new write-point public
      ; Note: if a transaction didn't write any refs, it automatically commits

; this function is a little more complicated than it needs to be because
; it can't tail-recursively call itself from within a catch-clause.
; The inner let either returns the value of the transaction, wrapped in a map,
; or nil, to indicate that the transaction must be retried.
; The outer let tests for nil and if so, calls the function tail-recursively
(defn tx-run
  "runs zero-argument fun as the body of transaction tx."
  [tx fun]
  (let [res (binding [*current-transaction* tx]
              (try
                (let [result (fun)]
                  (tx-commit tx)
                  ; commit succeeded, return result
                  {:result result}) ; wrap result, as it may be nil
                (catch stm.RetryEx e
                  nil)))]
    (if res
      (:result res)
      (recur (make-transaction) fun)))) ; tx aborted, retry with fresh tx

; default empty history list, shared by all fresh mc-refs
(def DEFAULT_HISTORY_TAIL (repeat (dec MAX_HISTORY) nil))

;; === MC-STM public API ===

; mc-ref is now a list of length MAX_HISTORY, containing
; {:value, :write-point} pairs, potentially followed by trailing nil values.
; Pairs are ordered latest :write-point first, oldest :write-point last
(defn mc-ref [val]
  (atom (cons {:value val :write-point @GLOBAL_WRITE_POINT}
              DEFAULT_HISTORY_TAIL)))

(defn mc-deref [ref]
  (if (nil? *current-transaction*)
      ; reading a ref outside of a transaction
      (:value (most-recent @ref))
      ; reading a ref inside a transaction
      (tx-read *current-transaction* ref)))

(defn mc-ref-set [ref newval]
  (if (nil? *current-transaction*)
      ; writing a ref outside of a transaction
      (throw (IllegalStateException. "can't set mc-ref outside transaction"))
      ; writing a ref inside a transaction
      (tx-write *current-transaction* ref newval)))
    
(defn mc-alter [ref fun & args]
  (mc-ref-set ref (apply fun (mc-deref ref) args)))

; naive but correct implementation of commute
; naive because two transactions that commute the same ref will be
; in conflict, while they should not be (that's the whole point of commute)
(defn mc-commute [ref fun & args]
  (apply mc-alter ref fun args))

; naive implementation of ensure
; naive because two transactions that ensure the same ref will
; be in conflict, while they should not be
(defn mc-ensure [ref]
  (mc-alter ref identity))

(defmacro mc-dosync [& exps]
  `(mc-sync (fn [] ~@exps)))

(defn mc-sync [fun]
  (if (nil? *current-transaction*)
      (tx-run (make-transaction) fun)
      (fun))) ; nested dosync blocks implicitly run in the parent transaction