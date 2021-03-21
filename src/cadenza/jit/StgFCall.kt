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
      panic{"foreign call nyi $name"}
    }
  }
}

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
    argc.arr.write(0, 1.toByteArray())

    val ix = globalHeap.size.toLong().toByteArray()
    globalHeap += ("2".toByteArray() + zeroBytes)

    val argvIx = globalHeap.size.toLong().toByteArray()
    globalHeap += ix

    argv.arr.write(0,argvIx)
    UnboxedTuple(arrayOf())
  },
  "u_towupper" to wrap2 { x: StgInt, w: RealWorld ->
    UnboxedTuple(arrayOf(StgInt(Character.toUpperCase(x.x.toInt()).toLong())))
  }
)

