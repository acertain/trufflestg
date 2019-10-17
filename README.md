# cadenza

[![Travis Continuous Integration Status][travis-img]][travis]
![Most used language][top-language-img]

This package will eventually provide a toy normalized-by-evaluation lambda calculus implementation in java using truffle.

cadenza | \ kə-ˈden-zə \ (noun) 1. a solo section, usually in a concerto or similar work before the final coda, that is used to display the performer's technique, sometimes at considerable length

Nothing seems to say Java to me moreso than considerable length, so that seems appropriate.

## running

* `gradle run` should just download GraalVM CE automatically and use it to run the launcher for testing.

* `gradle nativeImage` should eventually produce a native executable for the compiler. (Once I figure out how to include everything.)

## running by hand

You can either [Install GraalVM][graalvm] yourself or choose to `gradle extractGraalTooling`, which will produce a cached copy of GraalVM CE.

* Set your `JAVA_HOME`. If you used `gradle extractGraalTooling` it should point to something in `~/.gradle/caches/com.palantir.graal/19.2.0.1/graalvm-ce-19.2.0.1`

* Once you've built the jar with `gradle jar`, `java -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI -Dtruffle.class.path.append=build/libs/cadenza.jar -jar build/libs/cadenza.jar` seems to run the top level application without polyglot complaining about not having the language installed. IntelliJ has different IDEAs about what the arguments should be when you run it in debug more, however.

* `make component` should install things with `gu`, to make it so this is a viable language we could use in other polyglot repls easily. I'm missing some steps, though. (Likely I need to rebuild polyglot?)

Contribution
============

Unless you explicitly state otherwise, any contribution intentionally submitted
for inclusion in the work by you shall be licensed as per [LICENSE.txt][license], without any
additional terms or conditions.

Contact Information
===================

Contributions and bug reports are welcome!

Please feel free to contact me through github or on the ##coda or #haskell IRC channels on irc.freenode.net.

-Edward Kmett

 [graalvm]: https://www.graalvm.org/downloads
 [travis]: http://travis-ci.org/ekmett/cadenza
 [travis-img]: https://secure.travis-ci.org/ekmett/cadenza.png?branch=master
 [top-language-img]: https://img.shields.io/github/languages/top/ekmett/cadenza
 [license]: https://raw.githubusercontent.com/ekmett/cadenza/master/LICENSE.txt
