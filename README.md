STM in Clojure
==============

*What?* A meta-circular implementation of [Software Transactional Memory](http://clojure.org/refs) in Clojure (MC-STM for short).

*Why?* For educational purposes. My goal is for this STM implementation to enable people
interested in Clojure to gain a better understanding of its STM primitives (`ref`, `deref`,
`alter`, `dosync`), especially the more advanced parts like commutative updates via `commute`
and preventing write skew via `ensure`.

Overview
--------

MC-STM represents Clojure refs in terms of Clojure's atoms. Those familiar with Clojure will
recognize that refs can be updated in a coordinated way via software transactions. Clojure atoms, on the other hand, support only uncoordinated updates (i.e. given two atoms, Clojure provides no built-in support for updating the two atoms atomically). MC-STM represents its refs in terms of Clojure atoms, and builds its own STM support on top in order to provide atomicity and isolation.

For ease of understanding, we developed the meta-circular STM in a series of 
versions, each more complicated than the previous one:

- [`v1_simple.clj`](https://github.com/tvcutsem/stm-in-clojure/blob/master/stm/v1_simple.clj): simple revision-number based STM, no support for "internal" consistency (that is: transactions may have an inconsistent view of the global ref state).
- [`v2_mvcc.clj`](https://github.com/tvcutsem/stm-in-clojure/blob/master/stm/v2_mvcc.clj): [MVCC](http://en.wikipedia.org/wiki/Multiversion_concurrency_control)-based STM with 
  coarse-grained lock (only one transaction can commit at a time)
- [`v3_mvcc_commute.clj`](https://github.com/tvcutsem/stm-in-clojure/blob/master/stm/v3_mvcc_commute.clj): MVCC-based STM with support for [commute](http://clojure.github.com/clojure/clojure.core-api.html#clojure.core/commute) and [ensure](http://clojure.github.com/clojure/clojure.core-api.html#clojure.core/ensure).
- [`v4_mvcc_fine_grained.clj`](https://github.com/tvcutsem/stm-in-clojure/blob/master/stm/v4_mvcc_fine_grained.clj): MVCC-based STM with fine-grained locking (each ref is guarded by its own lock. Transactions that modify disjoint sets of refs can commit concurrently).
- [`v5_mvcc_fine_grained_barging.clj`](https://github.com/tvcutsem/stm-in-clojure/blob/master/stm/v5_mvcc_fine_grained_barging.clj): MVCC-based STM with fine-grained locking and barging (transactions eagerly detect write conflicts and try to preempt other transactions. Transactions are prioritized to ensure liveness.)

My primary goal has been clarity of code, not performance. From crude micro-benchmarks,
my rough estimate is that these meta-circular implementations are 3-6x slower than the 
built-in STM.

If you're interested in finding out the key principles behind MVCC, upon which Clojure's 
STM implementation is based, I suggest studying
[`version 2`](https://github.com/tvcutsem/stm-in-clojure/blob/master/stm/v2_mvcc.clj). 
It's less than 200 LOC of Clojure code.

Before loading the example files, make sure you compile `stm/RetryEx.clj` by evaluating

    (compile 'stm.RetryEx)
    
This should generate the necessary Java class files in the "classes" directory.
Then run the examples with this "classes" directory on the JVM classpath.

Slides
------

A slide set accompanying this code can be found [here](http://soft.vub.ac.be/~tvcutsem/talks/presentations/STM-in-Clojure.pdf) (pdf) (also available on [SlideShare](http://www.slideshare.net/tvcutsem/stm-inclojure)).
The slides and code were originally developed for my Masters course on
[multicore programming](http://soft.vub.ac.be/~tvcutsem/multicore).

Example
-------

MC-STM's API mimics Clojure's built-in API. Just prefix all core functions such as `dosync`, 
`ref` and `alter` with `mc-`:

    (use 'stm.v2-mvcc)
    (use 'clojure.contrib.test-is)

    (defn transfer [amount from to]
      (mc-dosync
        (mc-alter from - amount)
        (mc-alter to + amount)))

    (deftest transfer-test
      (def accountA (mc-ref 1500))
      (def accountB (mc-ref 200))

      (is (= 1500 (mc-deref accountA)))
      (is (= 200 (mc-deref accountB)))

      (transfer 100 accountA accountB)

      (is (= 1400 (mc-deref accountA)))
      (is (= 300 (mc-deref accountB))))
      
    (run-tests)
    
Acknowledgements
----------------

I was inspired by Daniel Spiwak's blog post on [STM in Scala](http://www.codecommit.com/blog/scala/software-transactional-memory-in-scala).
Thanks also to R. Mark Volkmann for his excellent article on [STM in Clojure](http://java.ociweb.com/mark/stm/article.html).

Feedback
--------

I welcome any feedback on possible improvements to this code, especially
with respect to coding style, Clojure idioms, performance improvements, etc.

E-mail can be sent to my `tomvc.be` gmail account, or ping me on
Twitter ([@tvcutsem](http://twitter.com/tvcutsem)).