package cadenza.jit

import cadenza.data.*
import cadenza.panic
import cadenza.stg_types.Stg
import com.oracle.truffle.api.frame.VirtualFrame
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.nio.ByteBuffer
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.javaMethod

object GlobalStore {
  var GHCConcSignalSignalHandlerStore: Any? = null
}

fun ByteArray.write(offset: Int, x: ByteArray) {
  System.arraycopy(x, 0, this, offset, x.size)
}
fun Long.toByteArray(): ByteArray = ByteBuffer.allocate(8).putLong(this).array()
fun Int.toByteArray(): ByteArray = ByteBuffer.allocate(4).putInt(this).array()

val zeroBytes: ByteArray = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)

var globalHeap = ByteArray(0)

@Suppress("unused", "FunctionName", "UNUSED_PARAMETER")
object PrimFCalls {
  @JvmStatic fun hs_free_stable_ptr(x: StablePtr, y: VoidInh): Any {
    // FIXME: getting "deRefStablePtr after freeStablePtr"
//    x.x = null
    return UnboxedTuple(arrayOf())
  }
  @JvmStatic fun localeEncoding(x: VoidInh): Any =
    UnboxedTuple(arrayOf(StgAddr("UTF-8".toByteArray() + zeroBytes,0)))
  @JvmStatic fun stg_sig_install(x: StgInt, y: StgInt, z: NullAddr, w: VoidInh): Any =
    UnboxedTuple(arrayOf(StgInt(-1)))
  @JvmStatic fun getProgArgv(argc: StgAddr, argv: StgAddr, v: VoidInh): Any {
    // TODO: put argc & argv into new arrays, make **argc = get_argc(), i think??
    argc.arr.write(0, 1.toByteArray())

    val ix = globalHeap.size.toLong().toByteArray()
    globalHeap += ("2".toByteArray() + zeroBytes)

    val argvIx = globalHeap.size.toLong().toByteArray()
    globalHeap += ix

    argv.arr.write(0, argvIx)
    return UnboxedTuple(arrayOf())
  }

  // TODO: i think this is what ghc does?
  @JvmStatic fun u_towupper(x: StgInt, w: RealWorld): Any =
    UnboxedTuple(arrayOf(StgInt(Character.toUpperCase(x.x.toInt()).toLong())))
}

val lookup: MethodHandles.Lookup = MethodHandles.publicLookup()

val primFCalls: Map<String, MethodHandle> =
  PrimFCalls::class.declaredFunctions.associate {
    val m = it.javaMethod!!
    m.name to lookup.unreflect(m)
  }


class StgFCall(
  val x: Stg.ForeignCall,
  @field:Children val args: Array<Arg>
) : Code(null) {
  val mh: MethodHandle? = primFCalls[(x.ctarget as Stg.CCallTarget.StaticTarget).string]
  val invoker: MethodHandle? = mh?.let { MethodHandles.spreadInvoker(it.type(), 0).bindTo(it) }

  override fun execute(frame: VirtualFrame): Any {
    val xs = map(args) { it.execute(frame) }
    if (x.ctarget is Stg.CCallTarget.DynamicTarget) TODO()
    val op = (x.ctarget as Stg.CCallTarget.StaticTarget).string
    return when (op) {
      // TODO: stub
      "rts_setMainThread" -> UnboxedTuple(arrayOf())
      "getOrSetGHCConcSignalSignalHandlerStore" -> {
        synchronized(GlobalStore) {
          val x = GlobalStore.GHCConcSignalSignalHandlerStore
          return UnboxedTuple(arrayOf(if (x == null) {
            GlobalStore.GHCConcSignalSignalHandlerStore = xs[0]
            xs[0]
          } else { x }))
        }
      }
      else ->
        if (invoker != null) { invoker.invokeExact(xs) }
        else { panic{"$this"} }
    }
  }
}


