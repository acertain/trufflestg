package cadenza.jit

import cadenza.data.*
import cadenza.panic
import cadenza.stg_types.Stg
import com.oracle.truffle.api.frame.VirtualFrame
import cadenza.array_utils.*
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

//  val mh: MethodHandle? = primFCalls[]
//  val invoker: MethodHandle? = mh?.let { MethodHandles.spreadInvoker(it.type(), 0).bindTo(it) }

  override fun execute(frame: VirtualFrame): Any {
    val xs = map(args) { it.execute(frame) }
//    if (x.ctarget is Stg.CCallTarget.DynamicTarget) TODO()
    if (opNode != null) {
      return opNode!!.run(frame, xs)
    } else {
      panic{"foreign call nyi $name ${xs.contentToString()}"}
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

  "hs_free_stable_ptr" to wrap2 { x: StablePtr, _: VoidInh ->
    // FIXME: getting "deRefStablePtr after freeStablePtr", so i'm disabling this for now
//    x.x = null
    UnboxedTuple(arrayOf())
  },
  "localeEncoding" to wrap1 { _: VoidInh ->
    UnboxedTuple(arrayOf(StgAddr("UTF-8".toByteArray() + zeroBytes, 0)))
  },
  "stg_sig_install" to wrap4 { x: StgInt, y: StgInt, z: NullAddr, _: VoidInh ->
    // TODOs
    UnboxedTuple(arrayOf(StgInt(-1)))
  },
  "getProgArgv" to wrap3 { argc: StgAddr, argv: StgAddr, v: VoidInh ->
    // TODO: put argc & argv into new arrays, make **argc = get_argc(), i think??
    argc.arr.write(0, 2.toByteArray())

    val ix = globalHeap.size.toLong().toByteArray()
    // FIXME this breaks something (/something is broken that this reveals)
//    globalHeap += ("fakeprogramname".toByteArray() + 0x00)
    globalHeap += ("2".toByteArray() + 0x00)
    globalHeap += ("2".toByteArray() + 0x00)

    // TODO: need to write one ptr per arg
    val argvIx = globalHeap.size.toLong().toByteArray()
    globalHeap += ix

    argv.arr.write(0,argvIx)
    UnboxedTuple(arrayOf())
  },
  "u_towupper" to wrap2 { x: StgInt, w: RealWorld ->
    UnboxedTuple(arrayOf(StgInt(Character.toUpperCase(x.x.toInt()).toLong())))
  },
  "u_gencat" to wrap2 { x: StgInt, w: RealWorld ->
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
  "u_iswalpha" to wrap2 { x: StgInt, w: RealWorld ->
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
