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

;; commute after alter

(ns test.commute-after-alter
  (:use clojure.test)
  (:import (java.util.concurrent Executors)))

;(use 'stm.v0-native)
;(use 'stm.v1-simple)
;(use 'stm.v2-mvcc)
;(use 'stm.v3-mvcc-commute)
;(use 'stm.v4-mvcc-fine-grained)
(use 'stm.v5-mvcc-fine-grained-barging)

(deftest test-commute-after-alter
  (let [r (mc-ref 0)]
    (mc-dosync
      (is (= 0 (mc-deref r)))
      (mc-alter r inc)
      (is (= 1 (mc-deref r)))
      (mc-commute r inc)
      (is (= 2 (mc-deref r))))
    (is (= 2 (mc-deref r)))))

(deftest test-commute
  (let [r (mc-ref 0)]
    (mc-dosync
      (is (= 0 (mc-deref r)))
      (mc-commute r inc)
      (is (= 1 (mc-deref r)))
      (mc-commute r inc)
      (is (= 2 (mc-deref r))))
    (is (= 2 (mc-deref r)))))

(deftest test-alter
  (let [r (mc-ref 0)]
    (mc-dosync
      (is (= 0 (mc-deref r)))
      (mc-alter r inc)
      (is (= 1 (mc-deref r)))
      (mc-alter r inc)
      (is (= 2 (mc-deref r))))
    (is (= 2 (mc-deref r)))))

(deftest test-conflicting-commutes
  (let [nitems   10
        niters   10000
        nthreads 10
        refs     (map mc-ref (replicate nitems 0))
        pool     (Executors/newFixedThreadPool nthreads)
        tasks    (map (fn [t]
                        (fn []
                          (dotimes [n niters]
                            (mc-dosync
                              (doseq [r refs]
                                (mc-commute r + 1 t))))))
                    (range nthreads))]
    (doseq [future (.invokeAll pool tasks)]
      (.get future))
    (.shutdown pool)
    (doall
      (for [r refs]
        (is (= (* niters (reduce + (range (inc nthreads))))
               (mc-deref r)))))))

(run-tests)