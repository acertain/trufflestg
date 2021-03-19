package cadenza.jit

import cadenza.data.*
import cadenza.stg_types.Stg
import com.oracle.truffle.api.frame.VirtualFrame
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.javaMethod

object GlobalStore {
  var GHCConcSignalSignalHandlerStore: Any? = null
}

@Suppress("unused")
object PrimFCalls {
  @JvmStatic fun hs_free_stable_ptr(x: StablePtr, y: VoidInh): Any {
    // FIXME: getting "deRefStablePtr after freeStablePtr"
//    x.x = null
    return UnboxedTuple(arrayOf())
  }
  @JvmStatic fun localeEncoding(x: VoidInh): Any =
    UnboxedTuple(arrayOf(StgAddr("UTF-8".toByteArray() + 0x0,0)))
  @JvmStatic fun stg_sig_install(x: StgInt, y: StgInt, z: NullAddr, w: VoidInh): Any =
    UnboxedTuple(arrayOf(StgInt(-1)))
  @JvmStatic fun getProgArgv(argc: StgAddr, argv: StgAddr, v: VoidInh): Any =
    // TODO: put argc & argv into new arrays, make **argc = get_argc(), i think??
    UnboxedTuple(arrayOf())
}

val primFCalls: Map<String, MethodHandle> =
  PrimFCalls::class.declaredFunctions.associate {
    val m = it.javaMethod!!
    m.name to lookup.unreflect(m)
  }


class StgFCall(
  val x: Stg.ForeignCall,
  val args: Array<Arg>
) : Code(null) {
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
      in primFCalls -> {
        val mh = primFCalls[op]!!
        MethodHandles.spreadInvoker(mh.type(), 0).invokeExact(mh, xs)
      }
      else -> TODO("$x")
    }
  }

}

