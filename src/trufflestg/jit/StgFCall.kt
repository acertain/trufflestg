package trufflestg.jit

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import trufflestg.data.*
import trufflestg.panic
import trufflestg.stg.Stg
import com.oracle.truffle.api.frame.VirtualFrame
import trufflestg.Language
import trufflestg.array_utils.*
import java.security.MessageDigest

object GlobalStore {
  var GHCConcSignalSignalHandlerStore: Any? = null
}


val zeroBytes: ByteArray = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)

var globalHeap = ByteArray(0)

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

var md5: MessageDigest? = null

val primFCalls: Map<String, () -> StgPrimOp> = mapOf(
  // TODO: do something safer! (use x to store MessageDigest object?)
  "__hsbase_MD5Init" to wrap2 { x: StgAddr, y: VoidInh ->
    if (md5 != null) panic("")
    md5 = MessageDigest.getInstance("MD5")
    y
  },
  "__hsbase_MD5Update" to wrap4 { _: StgAddr, y: StgAddr, z: StgInt, v: VoidInh ->
    val c = y.arr.copyOfRange(y.offset, y.offset + z.x.toInt())
    md5!!.update(c)
    v
  },
  "__hsbase_MD5Final" to wrap3 { out: StgAddr, _: StgAddr, v: VoidInh ->
    out.arr.write(out.offset, md5!!.digest())
    md5 = null
    v
  },

  "errorBelch2" to wrap3 { x: StgAddr, y: StgAddr, v: VoidInh ->
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
      if ((args[0] as StgInt).x == 1L) return UnboxedTuple(arrayOf(StgInt(1L)))
      panic("todo: fdReady")
    }
  } },

  "rtsSupportsBoundThreads" to wrap1 { _: VoidInh -> UnboxedTuple(arrayOf(StgInt(0L))) },

  "ghczuwrapperZC20ZCbaseZCSystemziPosixziInternalsZCwrite" to wrap4 { x: StgInt, y: StgAddr, z: StgWord, v: VoidInh ->
    // stdout
    if (x.x == 1L) {
      val s = y.asArray().copyOfRange(0, z.x.toInt())
      print(String(s))
      UnboxedTuple(arrayOf(StgInt(z.x.toLong())))
    } else {
      panic("nyi ghczuwrapperZC20ZCbaseZCSystemziPosixziInternalsZCwrite")
    }
  },

  "hs_free_stable_ptr" to wrap2 { x: StablePtr, _: VoidInh ->
    // FIXME: getting deRefStablePtr after freeStablePtr, so i'm disabling this for now
//    x.x = null
    UnboxedTuple(arrayOf())
  },
  "localeEncoding" to wrap1 { _: VoidInh ->
    UnboxedTuple(arrayOf(StgAddr("UTF-8".toByteArray() + zeroBytes, 0)))
  },
  "stg_sig_install" to wrap4 { x: StgInt, y: StgInt, z: NullAddr, _: VoidInh ->
    // TODO
    UnboxedTuple(arrayOf(StgInt(-1)))
  },
  "getProgArgv" to wrap3 { argc: StgAddr, argv: StgAddr, v: VoidInh ->
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
  "u_towupper" to wrap2 { x: StgInt, w: VoidInh ->
    UnboxedTuple(arrayOf(StgInt(Character.toUpperCase(x.x.toInt()).toLong())))
  },
  "u_gencat" to wrap2 { x: StgInt, w: VoidInh ->
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
  "u_iswalpha" to wrap2 { x: StgInt, w: VoidInh ->
    UnboxedTuple(arrayOf(StgInt(when (Character.getType(x.x.toInt()).toByte()) {
      Character.UPPERCASE_LETTER -> 1
      Character.LOWERCASE_LETTER -> 1
      Character.TITLECASE_LETTER -> 1
      Character.MODIFIER_LETTER -> 1
      Character.OTHER_LETTER -> 1
      else -> 0
    }.toLong())))
  }
)
