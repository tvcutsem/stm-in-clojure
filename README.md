STM in Clojure
==============

What? A meta-circular implementation of Software Transactional Memory in Clojure.

Why? For educational purposes. My goal is for this STM implementation to enable people interested in Clojure to gain a better understanding of its STM primitives, especially the more advanced onces like support for commute and ensure.

Overview
--------

For ease of understanding, our library develops the meta-circular STM system as a series of versions, each more complicated than the previous one:

- simple revision-no based STM, without support for "internal" consistency.
- MVCC-based STM with coarse-grained lock (one commit at a time)
- MVCC-based STM with support for commute and ensure
- MVCC-based STM with fine-grained locking (supporting concurrent commits)

Slides
------

A slide set accompanying this code can be found at:
TODO insert link
