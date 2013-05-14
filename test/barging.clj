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

;; Test the effect of barging on some pathological transactions that
;; take long to complete, yet contend for a shared ref

;; These tests are adapted versions of those originally written by
;; Reinout Stevens, see:
;; http://reinoutstevens.wordpress.com/2011/06/23/extending-stm-with-barging/

(ns test.barging
  (:use clojure.test)
  (:import (java.util.concurrent Executors)))

; (use 'stm.v0-native)
; (use 'stm.v1-simple)
; (use 'stm.v2-mvcc)
; (use 'stm.v3-mvcc-commute)
; (use 'stm.v4-mvcc-fine-grained)
(use 'stm.v5-mvcc-fine-grained-barging)

;; any long-running computation will do
(defn fib [x]
  (if (< x 2)
    1
    (+ (fib (- x 1))
       (fib (- x 2)))))

(defn test-barging [nthreads niters]
  (let [ref (mc-ref 0)
        pool (Executors/newFixedThreadPool nthreads)
        nrRetries (atom 0)
        tasks (map (fn [t]
                    (fn []
                      (dotimes [n niters]
                        (mc-dosync
                          (swap! nrRetries inc)
                          (fib 22)
                          (mc-alter ref inc)
                          (fib 15)))))
                (range nthreads))]
    (doseq [future (.invokeAll pool tasks)]
      (.get future))
     (.shutdown pool)
     (println "retries: " (- @nrRetries (* nthreads niters)))
     (mc-deref ref)))

(deftest barge-test
  (time (is (= (* 10 500) (test-barging 10 500)))))

(defn mutate-ref-then-set-finished
  [ref finished retry]
  (swap! retry inc)
  (dotimes [n 100000]
    (mc-alter ref inc))
  (mc-ref-set finished true))

(defn mutate-ref-until-finished [finished ref]
  (when-not (mc-deref finished)
    (mc-dosync
      (mc-alter ref inc))
    (recur finished ref)))

(defn test-liveness [nthreads]
  (let [ref (mc-ref 0)
        finished (mc-ref false)
        nrRetries (atom 0)
        pool (Executors/newFixedThreadPool (inc nthreads)) ; +1 task, see below
        nrRetries (atom 0)
        tasks (map
                (fn [t]
                     (fn []
                       (mutate-ref-until-finished finished ref)))
                (range nthreads))]
    (doseq [future (.invokeAll pool
      ; we could also reverse this list and list mutate-ref-then-set-finished
      ; as the last task. This lowers its priority. In that case, the
      ; thread pool must have size nthreads+1, otherwise all nthreads threads
      ; will only execute mututae-ref-until-finished forever, and the last task
      ; never gets run
                     (cons (fn []
                       (mc-dosync
                         (mutate-ref-then-set-finished ref finished nrRetries)))
                       tasks))]
      (.get future))
    (.shutdown pool)
    (println "retries: " @nrRetries)
    (mc-deref ref)))

(deftest liveness-test
  (time (test-liveness 10)))

(run-tests)