package trufflestg.jit

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.exception.AbstractTruffleException
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.interop.ExceptionType
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import com.oracle.truffle.api.nodes.Node
import trufflestg.Language
import trufflestg.array_utils.*
import trufflestg.data.*
import trufflestg.panic
import trufflestg.stg.Stg
import java.security.MessageDigest

class StgFCall(
  val x: Stg.ForeignCall,
  @field:Children val args: Array<Arg>
) : Code(null) {
  val name: String = (x.ctarget as Stg.CCallTarget.StaticTarget).string
  @field:Child var opNode: StgPrimOp? = primFCalls[name]?.let { it() }

  override fun execute(frame: VirtualFrame): Any {
    val xs = map(args) { it.execute(frame) }
    if (opNode != null) {
      return opNode!!.run(frame, xs)
    } else {
      panic{"foreign call nyi $x ${xs.contentToString()}"}
    }
  }
}

fun ByteArray.asCString(): String = String(copyOfRange(0, indexOf(0x00)))
val posix = jnr.posix.POSIXFactory.getPOSIX()
val zeroBytes: ByteArray = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)

@ExportLibrary(InteropLibrary::class)
class TruffleStgExitException(val status: Int) : AbstractTruffleException() {
  @ExportMessage fun getExceptionType(): ExceptionType = ExceptionType.EXIT
  @ExportMessage fun getExceptionExitStatus(): Int = status
}

// mutable state
var md5: MessageDigest? = null
object GlobalStore {
  var GHCConcSignalSignalHandlerStore: Any? = null
}
var globalHeap = ByteArray(0)


@OptIn(kotlin.ExperimentalUnsignedTypes::class)
val primFCalls: Map<String, () -> StgPrimOp> = mapOf(
  // TODO: do something safer! (use x to store MessageDigest object?)
  "__hsbase_MD5Init" to wrap2Boundary { x: StgAddr, y: VoidInh ->
    if (md5 != null) panic("")
    md5 = MessageDigest.getInstance("MD5")
    y
  },
  "__hsbase_MD5Update" to wrap4 { _: StgAddr, y: StgAddr, z: StgInt, v: VoidInh ->
    val c = y.arr.copyOfRange(y.offset, y.offset + z.x.toInt())
    md5!!.update(c)
    v
  },
  "__hsbase_MD5Final" to wrap3Boundary { out: StgAddr, _: StgAddr, v: VoidInh ->
    out.arr.write(out.offset, md5!!.digest())
    md5 = null
    v
  },

  "errorBelch2" to wrap3Boundary { x: StgAddr, y: StgAddr, v: VoidInh ->
    // TODO: this is supposed to be printf
    System.err.println("errorBelch2: ${x.asArray().asCString()} ${y.asArray().asCString()}")
    v
  },

  "rts_setMainThread" to wrap2 { x: WeakRef, _: VoidInh -> UnboxedTuple(arrayOf()) },
  "getOrSetGHCConcSignalSignalHandlerStore" to wrap2 { a: StablePtr, _: VoidInh ->
    synchronized(GlobalStore) {
      val x = GlobalStore.GHCConcSignalSignalHandlerStore
      UnboxedTuple(arrayOf(if (x == null) {
        GlobalStore.GHCConcSignalSignalHandlerStore = a
        a
      } else { x }))
    }
  },
  // TODO
  "isatty" to wrap2 { x: StgInt, _: VoidInh -> UnboxedTuple(arrayOf(StgInt(if (x.x in 0..2) 1L else 0L))) },
  "fdReady" to { object : StgPrimOp(5) {
    // FIXME: implement this, might need to use JNI
    override fun run(frame: VirtualFrame, args: Array<Any>): Any {
      val fd = (args[0] as StgInt).x
      if (fd == 1L || fd == 0L) return UnboxedTuple(arrayOf(StgInt(1L)))
      panic("todo: fdReady ${args[0]}")
    }
  } },

  "rtsSupportsBoundThreads" to wrap1 { _: VoidInh -> UnboxedTuple(arrayOf(StgInt(0L))) },

  "ghczuwrapperZC20ZCbaseZCSystemziPosixziInternalsZCwrite" to wrap4Boundary { x: StgInt, y: StgAddr, z: StgWord, _: VoidInh ->
    // stdout
    if (x.x == 1L) {
      val s = y.asArray().copyOfRange(0, z.x.toInt())
      print(String(s))
      UnboxedTuple(arrayOf(StgInt(z.x.toLong())))
    } else {
      panic("nyi ghczuwrapperZC20ZCbaseZCSystemziPosixziInternalsZCwrite")
    }
  },
  "ghczuwrapperZC22ZCbaseZCSystemziPosixziInternalsZCread" to wrap4Boundary { x: StgInt, y: StgAddr, z: StgWord, _: VoidInh ->
    UnboxedTuple(arrayOf(StgInt(posix.read(x.x.toInt(), y.asBuffer(), z.x.toLong()))))
  },

  "shutdownHaskellAndExit" to wrap3Boundary { x: StgInt, _: StgInt, _: VoidInh -> throw TruffleStgExitException(x.x.toInt()) },

  "hs_free_stable_ptr" to wrap2 { x: StablePtr, _: VoidInh ->
    // FIXME: getting deRefStablePtr after freeStablePtr, so i'm disabling this for now
//    x.x = null
    UnboxedTuple(arrayOf())
  },
  "localeEncoding" to wrap1Boundary { _: VoidInh ->
    UnboxedTuple(arrayOf(StgAddr("UTF-8".toByteArray() + zeroBytes, 0)))
  },
  "stg_sig_install" to wrap4 { x: StgInt, y: StgInt, z: NullAddr, _: VoidInh ->
    // TODO
    UnboxedTuple(arrayOf(StgInt(-1)))
  },
  "getProgArgv" to wrap3Boundary { argc: StgAddr, argv: StgAddr, _: VoidInh ->
    val args = arrayOf(
      "trufflestg",
      *Language.currentContext().env.applicationArguments
    )

    val ixs = args.map { a ->
      val ix = globalHeap.size
      globalHeap += (a.toByteArray() + 0x00)
      ix
    }

    val argvIx = globalHeap.size
    ixs.forEach { globalHeap += it.toLong().toByteArray() }

    argc.arr.write(argc.offset, ixs.size.toByteArray())
    argv.arr.write(argv.offset, argvIx.toLong().toByteArray())
    UnboxedTuple(arrayOf())
  },
  "u_towupper" to wrap2 { x: StgInt, _: VoidInh ->
    UnboxedTuple(arrayOf(StgInt(Character.toUpperCase(x.x.toInt()).toLong())))
  },
  "u_gencat" to wrap2 { x: StgInt, _: VoidInh ->
    UnboxedTuple(arrayOf(StgInt(when (Character.getType(x.x.toInt()).toByte()) {
      // in java's order, mapping to haskell's order
      Character.UNASSIGNED -> 29
      Character.UPPERCASE_LETTER -> 0
      Character.LOWERCASE_LETTER -> 1
      Character.TITLECASE_LETTER -> 2
      Character.MODIFIER_LETTER -> 3
      Character.OTHER_LETTER -> 4
      Character.NON_SPACING_MARK -> 5
      Character.ENCLOSING_MARK -> 7
      Character.COMBINING_SPACING_MARK -> 6
      Character.DECIMAL_DIGIT_NUMBER -> 8
      Character.LETTER_NUMBER -> 9
      Character.OTHER_NUMBER -> 10
      Character.SPACE_SEPARATOR -> 22
      Character.LINE_SEPARATOR -> 23
      Character.PARAGRAPH_SEPARATOR -> 24
      Character.CONTROL -> 25
      Character.FORMAT -> 26
      Character.PRIVATE_USE -> 28
      Character.SURROGATE -> 27
      Character.DASH_PUNCTUATION -> 12
      Character.START_PUNCTUATION -> 13
      Character.END_PUNCTUATION -> 14
      Character.CONNECTOR_PUNCTUATION -> 11
      Character.OTHER_PUNCTUATION -> 17
      Character.MATH_SYMBOL -> 18
      Character.CURRENCY_SYMBOL -> 19
      Character.MODIFIER_SYMBOL -> 20
      Character.OTHER_SYMBOL -> 21
      Character.INITIAL_QUOTE_PUNCTUATION -> 15
      Character.FINAL_QUOTE_PUNCTUATION -> 16
      else -> panic("java.lang.Character.getType: unknown category")
    }.toLong())))
  },
  "u_iswalpha" to wrap2 { x: StgInt, _: VoidInh ->
    UnboxedTuple(arrayOf(StgInt(when (Character.getType(x.x.toInt()).toByte()) {
      Character.UPPERCASE_LETTER -> 1
      Character.LOWERCASE_LETTER -> 1
      Character.TITLECASE_LETTER -> 1
      Character.MODIFIER_LETTER -> 1
      Character.OTHER_LETTER -> 1
      else -> 0
    }.toLong())))
  },
  "u_iswupper" to wrap2 { x: StgInt, _: VoidInh ->
    UnboxedTuple(arrayOf(StgInt(when (Character.getType(x.x.toInt()).toByte()) {
      Character.UPPERCASE_LETTER -> 1
      Character.TITLECASE_LETTER -> 1
      else -> 0
    }.toLong())))
  },
  "u_iswlower" to wrap2 { x: StgInt, _: VoidInh ->
    UnboxedTuple(arrayOf(StgInt(when (Character.getType(x.x.toInt()).toByte()) {
      Character.LOWERCASE_LETTER -> 1
      else -> 0
    }.toLong())))
  },
  "u_iswalnum" to wrap2 { x: StgInt, _: VoidInh ->
    UnboxedTuple(arrayOf(StgInt(when (Character.getType(x.x.toInt()).toByte()) {
      Character.UPPERCASE_LETTER -> 1
      Character.LOWERCASE_LETTER -> 1
      Character.TITLECASE_LETTER -> 1
      Character.MODIFIER_LETTER -> 1
      Character.OTHER_LETTER -> 1
      Character.OTHER_NUMBER -> 1
      Character.DECIMAL_DIGIT_NUMBER -> 1
      Character.LETTER_NUMBER -> 1
      else -> 0
    }.toLong())))
  }
)


// lots of foreign calls need to be boundaries cause truffle doesn't like lots of the java stdlib
inline fun <reified X, reified Y : Any> wrap1Boundary(crossinline f: (X) -> Y): () -> StgPrimOp = {
  object : StgPrimOp1() { @CompilerDirectives.TruffleBoundary override fun execute(x: Any): Any = f((x as? X)!!) }
}
inline fun <reified X, reified Y, reified Z : Any> wrap2Boundary(crossinline f: (X, Y) -> Z): () -> StgPrimOp = {
  object : StgPrimOp2() { @CompilerDirectives.TruffleBoundary override fun execute(x: Any, y: Any): Any = f((x as? X)!!, (y as? Y)!!) }
}
inline fun <reified X, reified Y, reified Z, reified W : Any> wrap3Boundary(crossinline f: (X, Y, Z) -> W): () -> StgPrimOp = {
  object : StgPrimOp3() { @CompilerDirectives.TruffleBoundary override fun execute(x: Any, y: Any, z: Any): Any = f((x as? X)!!, (y as? Y)!!, (z as? Z)!!) }
}
inline fun <reified X, reified Y, reified Z, reified W : Any, reified A> wrap4Boundary(crossinline f: (X, Y, Z, A) -> W): () -> StgPrimOp = {
  object : StgPrimOp4() { @CompilerDirectives.TruffleBoundary override fun execute(x: Any, y: Any, z: Any, a: Any): Any = f((x as? X)!!, (y as? Y)!!, (z as? Z)!!, (a as? A)!!) }
}
