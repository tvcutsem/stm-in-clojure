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

(ns test.disjoint)

;(use 'stm.v0-native)
;(use 'stm.v1-simple)
;(use 'stm.v2-mvcc)
;(use 'stm.v3-mvcc-commute)
;(use 'stm.v4-mvcc-fine-grained)
(use 'stm.v5-mvcc-fine-grained-barging)
 
(def count-illegal-states (atom 0))

; T1 and T2 update disjoint sets of references
; Goal: measure difference in speed between coarse-grained and fine-grained
; locking MC-STM implementations
(defn disjoint-experiment [n]
	(def x (mc-ref 0))
	(def y (mc-ref 0))
	(def z (mc-ref 0))
	
	(def T1 (Thread. (fn []
                    (dotimes [i n]
                      (mc-dosync
                        (mc-alter x inc)
                        (mc-alter y inc))))))
	(def T2 (Thread. (fn []
                    (dotimes [i n]
                      (mc-dosync
                        (mc-alter z inc))))))
  ;(def T3 (Thread. (fn []
  ;                   (dotimes [i (* 2 n)]
  ;                     (mc-dosync
  ;                       (if (not (= (mc-deref x) (mc-deref y)))
  ;                         (swap! count-illegal-states inc)))))))
	(.start T1) (.start T2) ; (.start T3)
	(.join T1) (.join T2)   ; (.join T3)
  (if (not (and (= (mc-deref x) n)
                (= (mc-deref y) n)
                (= (mc-deref z) n)))
    (swap! count-illegal-states inc)))

(time (dotimes [i 100] (disjoint-experiment 1000)))
(println "illegal states observed: " @count-illegal-states)

; -----------------------------------------------------
; observed behavior on:
; Clojure 1.2.0-RC2
; JDK 1.6.0
; Mac OS X 10.6.7
; Macbook, 2.4 GHz Intel Core 2 Duo, 4 GB 1067 MHz DDR3
; -----------------------------------------------------

; v0-native:
; "Elapsed time: 1649.156 msecs"
; illegal states observed:  0

; v1-simple:
; "Elapsed time: 3480.902 msecs"
; illegal states observed:  0

; v2-mvcc:
; "Elapsed time: 5119.528 msecs"
; illegal states observed:  0

; v3-mvcc-commute:
; "Elapsed time: 5284.51 msecs" (warm: "Elapsed time: 4496.661 msecs")
; illegal states observed:  0

; v4-mvcc-fine-grained:
; "Elapsed time: 6177.718 msecs" (warm: "Elapsed time: 3572.095 msecs")
; illegal states observed:  0
; despite larger overhead of fine-grained locking, v4 achieves a better
; total running time compared to v3 in this example, as more transactions
; can commit in parallel (disjoint write sets)