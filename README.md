# trufflestg

[![Travis Continuous Integration Status][travis-img]][travis]
![Most used language][top-language-img]

A proof of concept for haskell on truffle, seeing if I can get better performance than ghc

So far, I can't

With that said, this is probably the best (easiest & fastest) way to get haskell on the jvm (if you care about that)

## Running

Use https://github.com/acertain/ghc-whole-program-compiler-project:
- compile normally using ghc-wpc (pinned in that repo, but you might want to try a newer version, as you'll need to build ghc yourself otherwise)
- use `external-stg/app/mkfullpak.hs` on the `Main.o_ghc_stgapp` file
- use `external-stg/app/fullpack-prep-for-truffle.hs` on the `Main.fullpak`
- `./gradlew run --args="path/to/Main.truffleghc/Main args"` or `./gradlew installDist` then `build/install/trufflestg/bin/trufflestg path/to/Main.truffleghc/Main args`

You might need to use graalvm 11 as your jvm too, idk

* `gradle run` will run the launcher out of the local directory without installing anything.

## Installing

* `gradle distZip` or `gradle distTar` will create an archive containing the required runtime jars. However, you'll need to have `JAVA_HOME` set to point to your Graal installation, if you want to use the `trufflestg` script from the installation folder.

## Incomplete

This is *not* a complete haskell implementation:
- Most primops are missing
- FFI doesn't work, and only a few C functions are polyfilled
- There's probably bugs


## Windows XP

In the unlikely event that anybody cares about this project that also uses Windows XP, one of the dependencies used for color pretty printing depends on the "Microsoft Visual C++ 2008 SP1 Redistributable" when invoked on Windows.

You can get a free copy from MS at:

http://www.microsoft.com/en-us/download/details.aspx?displaylang=en&id=5582

## Contribution

Unless you explicitly state otherwise, any contribution intentionally submitted
for inclusion in the work by you shall be licensed as per [LICENSE.txt][license], without any
additional terms or conditions.

Contact Information
===================

Contributions and bug reports are welcome!

Please feel free to contact me through github or on the ##coda or #haskell IRC channels on irc.freenode.net.

-acertain

 [graalvm]: https://www.graalvm.org/downloads
 [travis]: http://travis-ci.org/acertain/trufflestg
 [travis-img]: https://secure.travis-ci.org/acertain/trufflestg.png?branch=master
 [top-language-img]: https://img.shields.io/github/languages/top/acertain/trufflestg
 [license]: https://raw.githubusercontent.com/acertain/trufflestg/master/LICENSE.txt
