STM in Clojure
==============

What? A meta-circular implementation of [Software Transactional Memory](http://clojure.org/refs) 
in Clojure (MC-STM for short).

Why? For educational purposes. My goal is for this STM implementation to enable people
interested in Clojure to gain a better understanding of its STM primitives, especially
the more advanced onces like support for commute and ensure.

My primary goal has been clarity of code, not performance. From crude micro-benchmarks,
my estimate is that the meta-circular implementation is 3-6x slower than the built-in STM
version.

Overview
--------

For ease of understanding, our library develops the meta-circular STM system as a series of 
versions, each more complicated than the previous one:

- simple revision-no based STM, without support for "internal" consistency.
- [MVCC](http://en.wikipedia.org/wiki/Multiversion_concurrency_control)-based STM with 
  coarse-grained lock (one commit at a time)
- MVCC-based STM with support for [commute](http://clojure.github.com/clojure/clojure.core-api.html#clojure.core/commute) and [ensure](http://clojure.github.com/clojure/clojure.core-api.html#clojure.core/ensure)
- MVCC-based STM with fine-grained locking (supporting concurrent commits)

Before loading the example files, make sure you compiled `stm/RetryEx.clj` so that
Clojure can find the generated exception class.

Slides
------

A slide set accompanying this code can be found on [SlideShare](http://www.slideshare.net/tvcutsem/) (embedded below).
The slides and code were originally developed for my Masters course on
[multicore programming](http://soft.vub.ac.be/~tvcutsem/multicore).

<div style="width:425px" id="__ss_7630493"><strong style="display:block;margin:12px 0 4px"><a href="http://www.slideshare.net/tvcutsem/stm-inclojure" title="Stm in-clojure">Stm in-clojure</a></strong><object id="__sse7630493" width="425" height="355"><param name="movie" value="http://static.slidesharecdn.com/swf/ssplayer2.swf?doc=stm-in-clojure-110414130822-phpapp02&stripped_title=stm-inclojure&userName=tvcutsem" /><param name="allowFullScreen" value="true"/><param name="allowScriptAccess" value="always"/><embed name="__sse7630493" src="http://static.slidesharecdn.com/swf/ssplayer2.swf?doc=stm-in-clojure-110414130822-phpapp02&stripped_title=stm-inclojure&userName=tvcutsem" type="application/x-shockwave-flash" allowscriptaccess="always" allowfullscreen="true" width="425" height="355"></embed></object><div style="padding:5px 0 12px">View more <a href="http://www.slideshare.net/">presentations</a> from <a href="http://www.slideshare.net/tvcutsem">Tom Van Cutsem</a>.</div></div>

Example
-------

MC-STM's API mimics Clojure's built-in API. Just prefix all core functions such as `dosync`, 
`ref` and `alter` with `mc-`:

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