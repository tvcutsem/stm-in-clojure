STM in Clojure
==============

What? A meta-circular implementation of [Software Transactional Memory](http://clojure.org/refs) in Clojure (MC-STM for short).

Why? For educational purposes. My goal is for this STM implementation to enable people interested in Clojure to gain a better understanding of its STM primitives, especially the more advanced onces like support for commute and ensure.

Overview
--------

For ease of understanding, our library develops the meta-circular STM system as a series of versions, each more complicated than the previous one:

- simple revision-no based STM, without support for "internal" consistency.
- [MVCC](http://en.wikipedia.org/wiki/Multiversion_concurrency_control)-based STM with coarse-grained lock (one commit at a time)
- MVCC-based STM with support for [commute](http://clojure.github.com/clojure/clojure.core-api.html#clojure.core/commute) and [ensure](http://clojure.github.com/clojure/clojure.core-api.html#clojure.core/ensure)
- MVCC-based STM with fine-grained locking (supporting concurrent commits)

Slides
------

A slide set accompanying this code can be found at:
TODO insert link

Example
-------

MC-STM's API mimics Clojure's built-in API. Just prefix all core functions such as `dosync`, `ref` and `alter` with `mc-`:

    (use 'clojure.contrib.test-is)

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
      
    (run-tests)