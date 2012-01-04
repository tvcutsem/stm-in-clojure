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

(ns test.writeskew)

; (use 'stm.v0-native)
; (use 'stm.v1-simple)
; (use 'stm.v2-mvcc)
; (use 'stm.v3-mvcc-commute)
; (use 'stm.v4-mvcc-fine-grained)
(use 'stm.v5-mvcc-fine-grained-barging)

(def count-write-skews (atom 0))

; write skew example, inspiration taken from R. Mark Volkmann's article:
; http://java.ociweb.com/mark/stm/article.html

; constraint: @cats + @dogs <= 3
; 2 threads: john, mary
; @cats = 1, @dogs = 1
; john: (alter cats inc), while concurrently mary: (alter dogs inc)
; both are allowed to commit (no conflicts) => @cats + @dogs = 4
; to avoid: john must call (ensure dogs), mary must call (ensure cats)
(defn write-skew-experiment [ensure-fnA ensure-fnB]
	(def cats (mc-ref 1))
	(def dogs (mc-ref 1))
	(def john (Thread. (fn []
	                     (mc-dosync
                         (ensure-fnA dogs)
	                       (if (< (+ (mc-deref cats) (mc-deref dogs)) 3)
	                         (mc-alter cats inc))))))
	(def mary (Thread. (fn []
	                     (mc-dosync
                         (ensure-fnB cats)
	                       (if (< (+ (mc-deref cats) (mc-deref dogs)) 3)
	                         (mc-alter dogs inc))))))
	(doseq [p [john mary]] (.start p))
	(doseq [p [john mary]] (.join p))
	(if (> (+ (mc-deref cats) (mc-deref dogs)) 3)
	  (swap! count-write-skews inc)))

(println "no thread calls ensure: wrong")
(dotimes [i 10000] (write-skew-experiment identity identity))
(println "write skews detected: " @count-write-skews " (expect >1)")
(reset! count-write-skews 0)
(println "some threads call ensure: still wrong")
(dotimes [i 10000] (write-skew-experiment mc-ensure identity))
(println "write skews detected: " @count-write-skews " (expect >1)")
(reset! count-write-skews 0)
(println "all threads call ensure: correct")
(dotimes [i 10000] (write-skew-experiment mc-ensure mc-ensure))
(println "write skews detected: " @count-write-skews " (expect 0)")
(reset! count-write-skews 0)

; -----------------------------------------------------
; observed behavior on:
; Clojure 1.2.0-RC2
; JDK 1.6.0
; Mac OS X 10.6.7
; Macbook, 2.4 GHz Intel Core 2 Duo, 4 GB 1067 MHz DDR3
; -----------------------------------------------------

; v0-native:
;no thread calls ensure: wrong
;write skews detected:  5  (expect >1)
;some threads call ensure: still wrong
;write skews detected:  2  (expect >1)
;all threads call ensure: correct
;write skews detected:  0  (expect 0)

; v1-simple:
; without mc-ensure: does not report write skew
; the revision-based STM system does not suffer from write skew
; (it always checks all read and all written refs for updated revisions)
;no thread calls ensure: wrong
;write skews detected:  0  (expect >1)
;some threads call ensure: still wrong
;write skews detected:  0  (expect >1)
;all threads call ensure: correct
;write skews detected:  0  (expect 0)

; v2-mvcc:
; with naive mc-ensure, write skews are prevented
;no thread calls ensure: wrong
;write skews detected:  298  (expect >1)
;some threads call ensure: still wrong
;write skews detected:  0  (expect >1)
;all threads call ensure: correct
;write skews detected:  0  (expect 0)

; v3-mvcc-commute
; with proper mc-ensure, write skews are prevented
;no thread calls ensure: wrong
;write skews detected:  311  (expect >1)
;some threads call ensure: still wrong
;write skews detected:  10  (expect >1)
;all threads call ensure: correct
;write skews detected:  0  (expect 0)

; v4-mvcc-fine-grained
; with proper mc-ensure, write skews are prevented
;no thread calls ensure: wrong
;write skews detected:  1199  (expect >1)
;some threads call ensure: still wrong
;write skews detected:  11  (expect >1)
;all threads call ensure: correct
;write skews detected:  0  (expect 0)