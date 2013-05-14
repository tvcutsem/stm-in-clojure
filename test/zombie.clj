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

(ns test.zombie)

; (use 'stm.v0-native)
; (use 'stm.v1-simple)
; (use 'stm.v2-mvcc)
; (use 'stm.v3-mvcc-commute)
; (use 'stm.v4-mvcc-fine-grained)
(use 'stm.v5-mvcc-fine-grained-barging)

(def count-div-by-zero (atom 0))
(def count-inconsistent-result (atom 0))

(defn zombie-experiment []
	; invariant: x = 2y
	(def x (mc-ref 4))
	(def y (mc-ref 2))
	
	(def T1 (Thread. (fn []
	                   (mc-dosync
	                     (mc-alter x * 2)
	                     (mc-alter y * 2)))))
	(def T2 (Thread. (fn []
	                   (mc-dosync
                       (try
                         (let [res (/ 1 (- (mc-deref x) (mc-deref y)))]
                           (if (not (or (= res (/ 1 4)) (= res (/ 1 2))))
                             (swap! count-inconsistent-result inc)))
                         (catch ArithmeticException e
                           (swap! count-div-by-zero inc)))))))
	(.start T1) (.start T2)
	(.join T1) (.join T2))

(time (dotimes [i 1000] (zombie-experiment)))
(println "division by zero observed: " @count-div-by-zero)
(println "inconsistent results observed: " @count-inconsistent-result)

; -----------------------------------------------------
; observed behavior on:
; Clojure 1.2.0-RC2
; JDK 1.6.0
; Mac OS X 10.6.7
; Macbook, 2.4 GHz Intel Core 2 Duo, 4 GB 1067 MHz DDR3
; -----------------------------------------------------

; v0-native:
; never throwns a Divide by zero exception, uses MVCC
; "Elapsed time: 786.845 msecs"
; division by zero observed:  0
; inconsistent results observed:  0

; v1-simple:
; sometimes crashes with a Divide by zero exception, as expected
; v1 does not ensure 'internal consistency' within a zombie transaction
; "Elapsed time: 733.318 msecs"
; division by zero observed:  15
; inconsistent results observed:  1

; v2-mvcc:
; never throws a Divide by zero exception, internal consistency is guaranteed
; "Elapsed time: 802.118 msecs"
; division by zero observed:  0
; inconsistent results observed:  0

; v3-mvcc-commute:
; never throws a Divide by zero exception, internal consistency is guaranteed
; "Elapsed time: 828.632 msecs"
; division by zero observed:  0
; inconsistent results observed:  0

; v4-mvcc-fine-grained:
; never throws a Divide by zero exception, internal consistency is guaranteed
; "Elapsed time: 946.854 msecs"
; division by zero observed:  0
; inconsistent results observed:  0