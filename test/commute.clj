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

;; alter vs. commute

(ns test.commute
  (:import (java.util.concurrent Executors)))

; (use 'stm.v0-native)
; (use 'stm.v1-simple)
; (use 'stm.v2-mvcc)
; (use 'stm.v3-mvcc-commute)
; (use 'stm.v4-mvcc-fine-grained)
(use 'stm.v5-mvcc-fine-grained-barging)

;; === Contention ===

; Adapted from clojure.org/concurrent_programming by Rich Hickey:
; In this example a vector of Refs containing integers is created (refs),
; then a set of threads are set up (pool) to run a number of iterations of
; incrementing every Ref (tasks). This creates extreme contention,
; but yields the correct result. No locks!

; update-fn is one of mc-alter or mc-commute
(defn test-stm [nitems nthreads niters update-fn]
  (let [num-tries (atom 0)
        refs  (map mc-ref (replicate nitems 0))
        pool  (Executors/newFixedThreadPool nthreads)
        tasks (map (fn [t]
                      (fn []
                        (dotimes [n niters]
                          (mc-dosync
                            (swap! num-tries inc)
                            (doseq [r refs]
                              (update-fn r + 1 t))))))
                   (range nthreads))]
    (doseq [future (.invokeAll pool tasks)]
      (.get future))
    (.shutdown pool)
    {:result (map mc-deref refs)
     :retries (- @num-tries (* nthreads niters)) }))

; 10 threads increment each of 10 refs 10000 times
; each ref should be incremented by 550000 in total = 
;   (* 10000 (+ 1 2 3 4 5 6 7 8 9 10))
; -> (550000 550000 550000 550000 550000 550000 550000 550000 550000 550000)

; using mc-alter
(let [res (time (test-stm 10 10 10000 mc-alter))]
  (assert (= (count (:result res)) 10))
  (assert (every? (fn [r] (= r 550000)) (:result res)))
  (println "num retries using alter: " (:retries res)))

; using mc-commute
(let [res (time (test-stm 10 10 10000 mc-commute))]
  (assert (= (count (:result res)) 10))
  (assert (every? (fn [r] (= r 550000)) (:result res)))
  (println "num retries using commute: " (:retries res)))

; -----------------------------------------------------
; observed behavior on:
; Clojure 1.2.0-RC2
; JDK 1.6.0
; Mac OS X 10.6.7
; Macbook, 2.4 GHz Intel Core 2 Duo, 4 GB 1067 MHz DDR3
; -----------------------------------------------------

; for all timings below except v4, clearly the retries provoked by
; alter are not dominating the total running time, since
; the total execution time is virtually the same
; (even for v3 which implements commute properly)

; v0-native:
; alter: "Elapsed time: 2872.917 msecs"
; num retries using alter:  117439
; commute: "Elapsed time: 2852.853 msecs"
; num retries using commute:  0

; v1-simple:
; naive implementation of commute provides no benefit
; alter: "Elapsed time: 6514.418 msecs"
; num retries using alter:  84265
; commute: "Elapsed time: 6325.194 msecs"
; num retries using commute:  93888

; v2-mvcc:
; naive implementation of commute provides no benefit
; alter: "Elapsed time: 10744.366 msecs"
; num retries using alter:  107795
; commute: "Elapsed time: 9478.161 msecs"
; num retries using commute:  97313

; v3-mvcc-commute:
; proper implementation of commute: 0 retries
; alter: "Elapsed time: 11264.848 msecs"
; num retries using alter:  75726
; commute: "Elapsed time: 10269.281 msecs"
; num retries using commute:  0

; v4-mvcc-fine-grained:
; using commute halves the total running time
; unlike alter, commute does not acquire a lock on first read of an mc-ref
; alter: "Elapsed time: 16065.477 msecs"
; num retries using alter:  94028
; commute: "Elapsed time: 8238.209 msecs"
; num retries using commute:  0