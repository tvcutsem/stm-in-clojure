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

;; version 4 - MVCC with fine-grained locking and support for commute
;; Improvements over v3:
;; - no more single global commit-lock for all transactions
;;   Instead uses fine-grained locking (one lock / mc-ref)

(ns stm.v4-mvcc-fine-grained
  (:use (clojure (set :only [union]))))

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
    :written-refs (atom #{}), ; set of written-to refs
    :commutes (atom {}), ; map: ref -> seq of commute-fns
    :ensures (atom #{}) }) ; set of ensure-d refs

(defn find-entry-before-or-on
  "returns an entry in history-chain whose write-pt <= read-pt,
   or nil if no such entry exists"
  [history-chain read-pt]
  (some (fn [pair]
          (if (and pair (<= (:write-point pair) read-pt))
            pair)) history-chain))

; history lists of mc-refs are ordered youngest to eldest
(defn most-recent-value [ref]
  (:value (first @(:history-list ref))))

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
      (let [ref-entry
            ; acquire read-lock to ensure ref is not modified by a committing tx
            (locking (:lock mc-ref)
              (find-entry-before-or-on
                @(:history-list mc-ref) (:read-point tx)))]
        (if (not ref-entry)
          ; if such an entry was not found, retry
          (tx-retry))
        (let [in-tx-value (:value ref-entry)]
          (swap! in-tx-values assoc mc-ref in-tx-value) ; cache the value
          in-tx-value))))) ; save and return the ref's value

(defn tx-write
  "write val to ref inside transaction tx"
  [tx ref val]
  ; can't set a ref after it has already been commuted
  (if (contains? @(:commutes tx) ref)
    (throw (IllegalStateException. "can't set after commute on " ref)))
  (swap! (:in-tx-values tx) assoc ref val)
  (swap! (:written-refs tx) conj ref)
  val)

(defn tx-ensure
  "ensure ref inside transaction tx"
  [tx ref]
  ; mark this ref as being ensure-d
  (swap! (:ensures tx) conj ref))

(defn tx-commute
  "commute ref inside transaction tx"
  [tx ref fun args]
  ; apply fun to the in-tx-value
  ; or the most recent value if not read/written before
  (let [in-tx-values @(:in-tx-values tx)
        res (apply fun (if (contains? in-tx-values ref)
                         (in-tx-values ref) 
                         (most-recent-value ref)) args)]
    ; retain the result as an in-transaction-value
    (swap! (:in-tx-values tx) assoc ref res)
    ; mark the ref as being commuted,
    ; storing fun and args because it will be re-executed at commit time
    (swap! (:commutes tx) (fn [commutes]
                            (assoc commutes ref
                              (cons (fn [val] (apply fun val args))
                                    (commutes ref)))))
    res))

(defn with-ref-locks-do
  "acquires the write-lock on all refs, then executes fun"
  [refs fun]
  (if (empty? refs)
    (fun)
    (locking (:lock (first refs))
      (with-ref-locks-do (next refs) fun))))

; When committing, lock all written-to, commuted and ensured mc-refs
; in a consistent locking order (based on their :id)
; (could store mc-refs in a (sorted-set), obviates the need for explicit sorting
; during commit, but initial experiments suggest that the sorting step
; is not the bottleneck)
(defn tx-commit
  "returns normally if tx committed successfully, throws RetryEx otherwise"
  [tx]
  (let [written-refs @(:written-refs tx)
        ensured-refs @(:ensures tx)
        commuted-refs @(:commutes tx)]
    (when (not-every? empty? [written-refs ensured-refs commuted-refs])
      (with-ref-locks-do (sort-by :id <
                           (union written-refs
                                  ensured-refs
                                  (keys commuted-refs)))
        (fn []
          ; validate both written-refs and ensured-refs
          ; Note: no need to validate commuted-refs
          (doseq [ref (union written-refs ensured-refs)]
            (if (> (:write-point (first @(:history-list ref)))
                   (:read-point tx))
              (tx-retry)))
        
          ; if validation OK, re-apply commutes based on most recent value
          (doseq [[commuted-ref commute-fns] commuted-refs]
            ; if a ref has been written to (by set/alter as well as commute),
            ; its in-transaction-value will be correct so we don't need to set it
            (when (not (contains? written-refs commuted-ref))
              (swap! (:in-tx-values tx) assoc commuted-ref
                ; apply each commute-fn to the result of the previous commute-fn,
                ; starting with the most recent value
                ((reduce comp commute-fns) (most-recent-value commuted-ref)))))

          (let [in-tx-values @(:in-tx-values tx)
                new-write-point (swap! GLOBAL_WRITE_POINT inc)]
            ; make in-tx-value of all written-to or commuted refs public
            (doseq [ref (union written-refs (keys commuted-refs))]
              (swap! (:history-list ref)
                (fn [prev-history-list]
                  ; add a new entry to the front of the history list...
                  (cons {:value (in-tx-values ref)
                         :write-point new-write-point}
                        ; ... and remove the eldest
                        (butlast prev-history-list)))))))))))
  ; Note: if a transaction didn't write, commute or ensure any refs,
  ; it automatically commits

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

; global counter to label and sort mc-refs 
(def REF_ID (atom 0))

;; === MC-STM public API ===

; mc-ref is now a map:
; :id is used to order the mc-refs (to guarantee a consistent locking order)
; :lock is used to guarantee atomicity of commits
; :history-list is an atom containing a list of length MAX_HISTORY, containing
; {:value, :write-point} pairs, potentially followed by trailing nil values.
; Pairs are ordered latest :write-point first, oldest :write-point last
(defn mc-ref [val]
    {:id (swap! REF_ID inc)
     :lock (new Object)
     :history-list (atom (cons { :value val
                                 :write-point @GLOBAL_WRITE_POINT }
                         DEFAULT_HISTORY_TAIL))})

(defn mc-deref [ref]
  (if (nil? *current-transaction*)
      ; reading a ref outside of a transaction
      (most-recent-value ref)
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

(defn mc-commute [ref fun & args]
    (if (nil? *current-transaction*)
      (throw (IllegalStateException. "can't commute mc-ref outside transaction"))
      (tx-commute *current-transaction* ref fun args)))

(defn mc-ensure [ref]
    (if (nil? *current-transaction*)
      (throw (IllegalStateException. "can't ensure mc-ref outside transaction"))
      (tx-ensure *current-transaction* ref)))

(defmacro mc-dosync [& exps]
  `(mc-sync (fn [] ~@exps)))

(defn mc-sync [fun]
  (if (nil? *current-transaction*)
      (tx-run (make-transaction) fun)
      (fun))) ; nested dosync blocks implicitly run in the parent transaction