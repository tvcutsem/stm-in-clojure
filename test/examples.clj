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

;; simple usage examples of MC-STM

(ns test.examples
  ;(:use clojure.contrib.test-is)
  (:use clojure.test)
  (:import (java.util.concurrent Executors)))

; (use 'stm.v0-native)
; (use 'stm.v1-simple)
; (use 'stm.v2-mvcc)
; (use 'stm.v3-mvcc-commute)
; (use 'stm.v4-mvcc-fine-grained)
(use 'stm.v5-mvcc-fine-grained-barging)

;; === Bank account transfer ===

(defn transfer [amount from to]
  (mc-dosync
    (mc-alter from - amount)
    (mc-alter to + amount)))

(deftest transfer-test []
	(def accountA (mc-ref 1500))
	(def accountB (mc-ref 200))
	
  (is (= 1500 (mc-deref accountA)))
  (is (= 200 (mc-deref accountB)))
    
	(transfer 100 accountA accountB)
 
	(is (= 1400 (mc-deref accountA)))
	(is (= 300 (mc-deref accountB))))

;; === Contention ===

; From clojure.org/concurrent_programming by Rich Hickey:
; In this example a vector of Refs containing integers is created (refs),
; then a set of threads are set up (pool) to run a number of iterations of
; incrementing every Ref (tasks). This creates extreme contention,
; but yields the correct result. No locks!
(defn test-stm [nitems nthreads niters]
  (let [refs  (map mc-ref (replicate nitems 0))
        pool  (Executors/newFixedThreadPool nthreads)
        tasks (map (fn [t]
                      (fn []
                        (dotimes [n niters]
                          (mc-dosync
                            (doseq [r refs]
                              (mc-alter r + 1 t))))))
                   (range nthreads))]
    (doseq [future (.invokeAll pool tasks)]
      (.get future))
    (.shutdown pool)
    (map mc-deref refs)))

(deftest contention-test
  ; 10 threads increment each of 10 refs 10000 times
  ; each ref should be incremented by 550000 in total = 
  ;   (* 10000 (+ 1 2 3 4 5 6 7 8 9 10))
  ; -> (550000 550000 550000 550000 550000 550000 550000 550000 550000 550000)
  (let [res (time (test-stm 10 10 10000))]
   (is (= (count res) 10))
   (is (every? (fn [r] (= r 550000)) res))))


;; === Vector swap ===

; From http://clojure.org/refs by Rich Hickey:
; In this example a vector of references to vectors is created, each containing
; (initially sequential) unique numbers. Then a set of threads are started that
; repeatedly select two random positions in two random vectors and swap them,
; in a transaction. No special effort is made to prevent the inevitable
; conflicts other than the use of transactions.
(defn vector-swap [nvecs nitems nthreads niters]
  (let [vec-refs (vec (map (comp mc-ref vec)
                           (partition nitems (range (* nvecs nitems)))))
        swap #(let [v1 (rand-int nvecs)
                    v2 (rand-int nvecs)
                    i1 (rand-int nitems)
                    i2 (rand-int nitems)]
                (mc-dosync
                 (let [temp (nth (mc-deref (vec-refs v1)) i1)]
                   (mc-alter (vec-refs v1) assoc i1
                     (nth (mc-deref (vec-refs v2)) i2))
                   (mc-alter (vec-refs v2) assoc i2 temp))))
        check-distinct #(do
                 ; (prn (map mc-deref vec-refs))
                 (is (= (* nvecs nitems)
                       (count (distinct
                                (apply concat (map mc-deref vec-refs)))))))]
    (check-distinct)
    (dorun (apply pcalls (repeat nthreads #(dotimes [_ niters] (swap)))))
    (check-distinct)))

(deftest vector-swap-test
  (time (vector-swap 100 10 10 100000)))


(run-tests)
(shutdown-agents) ; shutdown thread pool used by pcalls

; -----------------------------------------------------
; observed behavior on:
; Clojure 1.2.0-RC2
; JDK 1.6.0
; Mac OS X 10.6.7
; Macbook, 2.4 GHz Intel Core 2 Duo, 4 GB 1067 MHz DDR3
; -----------------------------------------------------

; contention-test:
; v0-native:            "Elapsed time: 2731.11 msecs"
; v1-simple:            "Elapsed time: 8105.424 msecs"  (x2.96)
; v2-mvcc:              "Elapsed time: 10714.035 msecs" (x3.92)
; v3-mvcc-commute:      "Elapsed time: 9751.687 msecs"  (x3.57)
; v4-mvcc-fine-grained: "Elapsed time: 16174.881 msecs" (x5.92)

; vector-swap:          
; v0-native:            "Elapsed time: 5180.694 msecs"
; v1-simple:            "Elapsed time: 16037.275 msecs" (x3.09)
; v2-mvcc:              "Elapsed time: 29760.412 msecs" (x5.74)
; v3-mvcc-commute:      "Elapsed time: 31856.438 msecs" (x6.14)
; v4-mvcc-fine-grained: "Elapsed time: 21752.742 msecs" (x4.19)