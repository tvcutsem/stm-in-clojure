; Copyright (c) 2011, Tom Van Cutsem, Vrije Universiteit Brussel
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
;; (c) 2011, Tom Van Cutsem

;; version 1 - simple revision-based STM
;; based on Daniel Spiwak's article:
;;  http://www.codecommit.com/blog/scala/software-transactional-memory-in-scala

;; Limitations:
;; - internal consistency is not guaranteed
;;   (a transaction may read a value for a ref before another transaction T
;;    committed, and read a value for another ref after T committed,
;;    leading to potentially mutually inconsistent ref values)
;; - a single global commit-lock for all transactions
;;   (= bottleneck, but makes it easy to validate and commit)
;;   (note: lock only acquired on commit, not while running the transaction!)
;; - naive support for commute
;; - naive support for ensure (to prevent write skew)

(ns stm.v1-simple)

;; === MC-STM internals ===

; a thread-local var that holds the current transaction executed by this thread
; if the thread does not execute a transaction, this is set to nil
(def *current-transaction* nil)

(def NEXT_TRANSACTION_ID (atom 0))

(defn make-transaction []
  "create and return a new transaction data structure"
  { :id (swap! NEXT_TRANSACTION_ID inc),
    :in-tx-values (atom {}),    ; map: ref -> any value
    :written-refs (atom #{}),   ; set of refs
    :last-seen-rev (atom {}) }) ; map: ref -> revision id

(defn tx-read [tx ref]
  "read the value of ref inside transaction tx"
  (let [in-tx-values (:in-tx-values tx)]
    (if (contains? @in-tx-values ref)
      (@in-tx-values ref) ; return the in-tx-value
      ; important: read both ref's value and revision atomically
      (let [{in-tx-value :value
             read-revision :revision} @ref]
        ; cache the value
        (swap! in-tx-values assoc ref in-tx-value)
        ; remember first revision read
        (swap! (:last-seen-rev tx) assoc ref read-revision)
        in-tx-value)))) ; save and return the ref's value

(defn tx-write [tx ref val]
  "write val to ref inside transaction tx"
  (swap! (:in-tx-values tx) assoc ref val)
  (swap! (:written-refs tx) conj ref)
  (if (not (contains? @(:last-seen-rev tx) ref))
    ; remember first revision written
    (swap! (:last-seen-rev tx) assoc ref (:revision @ref)))
  val)

; a single global lock for all transactions to acquire on commit
; we use the monitor of a fresh empty Java object
; all threads share the same root-binding, so will acquire the same lock
(def COMMIT_LOCK (new java.lang.Object))

(defn tx-commit [tx]
  "returns a boolean indicating whether tx committed successfully"
  (let [validate
        (fn [refs]
          (every? (fn [ref]
                    (= (:revision @ref)
                       (@(:last-seen-rev tx) ref))) refs))]
  
  (locking COMMIT_LOCK
    (let [in-tx-values @(:in-tx-values tx)
          success (validate (keys in-tx-values))]
      (if success
        ; if validation OK, make in-tx-value of all written refs public
        (doseq [ref @(:written-refs tx)]
          (swap! ref assoc
            :value (in-tx-values ref)
            :revision (:id tx) )))
      success))))

(defn tx-run [tx fun]
  "runs zero-argument fun as the body of transaction tx"
  (let [result (binding [*current-transaction* tx] (fun))]
    (if (tx-commit tx)
        result ; commit succeeded, return result
        (recur (make-transaction) fun)))) ; commit failed, retry with fresh tx

;; === MC-STM public API ===

(defn mc-ref [val]
  (atom {:value val
         :revision 0}))

; Note: an alternative but perhaps less accessible way to code up these
; functions would be to redefine mc-deref and mc-ref-set in the scope of
; a running transaction using 'binding', avoiding the if-test
(defn mc-deref [ref]
  (if (nil? *current-transaction*)
      ; reading a ref outside of a transaction
      (:value @ref)
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