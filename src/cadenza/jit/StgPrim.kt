
package cadenza.jit

import cadenza.data.*
import cadenza.panic
import cadenza.todo
import com.oracle.truffle.api.dsl.Specialization
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.ref.WeakReference
import kotlin.reflect.KCallable
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.functions
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.reflect


@Suppress("unused")
object PrimOps {
  // TODO: synchronize MVar ops
  @JvmStatic fun putMVar(x: StgMVar, y: Any, z: VoidInh): Any {
    x.full = true
    x.value = y
    // TODO: or should this return UnboxedTuple(arrayOf())?
    return z
  }
  @JvmStatic fun takeMVar(x: StgMVar, y: VoidInh): Any {
    if (!x.full) todo
    val w = x.value!!
    x.full = false
    x.value = null
    return UnboxedTuple(arrayOf(w))
  }

  @JvmStatic fun makeStablePtr(x: Any, y: VoidInh): Any = UnboxedTuple(arrayOf(StablePtr(x)))
  @JvmStatic fun deRefStablePtr(x: StablePtr, y: VoidInh): Any {
    if (x.x == null) panic("deRefStablePtr after freeStablePtr")
    return UnboxedTuple(arrayOf(x.x!!))
  }

  @JvmStatic fun newMutVar(x: Any, y: VoidInh): Any = UnboxedTuple(arrayOf(StgMutVar(x)))
  @JvmStatic fun readMutVar(x: StgMutVar, y: VoidInh): Any = UnboxedTuple(arrayOf(x.x))

  @JvmStatic fun eqAddr(x: Any, y: Any): Any = StgInt(if (x === y) 1L else 0L)

  @OptIn(ExperimentalUnsignedTypes::class)
  // TODO: make sure this should be unsigned
  @JvmStatic fun indexCharOffAddr(x: StgAddr, y: StgInt): Any = StgChar(x[y.toInt()].toUByte().toInt())
  @JvmStatic fun leChar(x: StgChar, y: StgChar): Any = StgInt(if (x.x <= y.x) 1L else 0L)
  @JvmStatic fun ord(x: StgChar): Any = StgInt(x.x.toLong()) // TODO: should this be an unsigned conversion?
  @JvmStatic fun chr(x: StgInt): Any = StgChar(x.x.toInt()) // TODO: ^

  @JvmStatic fun readInt8OffAddr(x: StgAddr, y: StgInt, z: VoidInh): Any = UnboxedTuple(arrayOf(StgInt(x[y.toInt()].toLong())))

  @JvmStatic fun plusAddr(x: StgAddr, y: StgInt): Any = StgAddr(x.arr, x.offset + y.x.toInt())

  @JvmStatic fun uncheckedIShiftL(x: StgInt, y: StgInt): Any = StgInt(x.x shl y.x.toInt())

  // TODO
  @JvmStatic fun maskAsyncExceptions(x: Closure, y: VoidInh): Any = x.call(arrayOf(RealWorld))
  @JvmStatic fun unmaskAsyncExceptions(x: Closure, y: VoidInh): Any = x.call(arrayOf(RealWorld))
  @JvmStatic fun catch(x: Closure, y: Closure, z: VoidInh): Any =
    // TODO: do i need to box the return value as a unboxed tuple?
    try { x.call(arrayOf(RealWorld)) }
    // TODO: should this catch all non-truffle exceptions?
    catch (e: HaskellException) { y.call(arrayOf(e.x, RealWorld)) }

  @JvmStatic fun myThreadId(x: VoidInh): Any = UnboxedTuple(arrayOf(ThreadId(Thread.currentThread().id)))

  @JvmStatic fun raise(e: Any): Any = throw HaskellException(e)
  @JvmStatic fun raiseIO(e: Any, y: VoidInh): Any = throw HaskellException(e)

  @JvmStatic fun readArray(x: StgArray, y: StgInt, z: VoidInh): Any = UnboxedTuple(arrayOf(x[y]))
  @JvmStatic fun writeArray(x: StgArray, y: StgInt, z: Any, w: VoidInh): Any = { x[y] = z; w }

  // TODO: actually pin & align? only matters if we want to use native code
  @JvmStatic fun newAlignedPinnedByteArray(x: StgInt, alignment: StgInt, v: VoidInh): Any =
    UnboxedTuple(arrayOf(StgMutableByteArray(ByteArray(x.toInt()))))
  @JvmStatic fun unsafeFreezeByteArray(arr: StgMutableByteArray, v: VoidInh): Any =
    UnboxedTuple(arrayOf(StgByteArray(arr.arr)))
  @JvmStatic fun byteArrayContents(arr: StgByteArray): Any = StgAddr(arr.arr, 0)
}

val lookup: MethodHandles.Lookup = MethodHandles.publicLookup()
val primOps: Map<String, MethodHandle> =
  PrimOps::class.declaredFunctions.associate {
    val m = it.javaMethod!!
    (m.name + "#") to lookup.unreflect(m)
  }



class StgPrim(
  val op: String,
  val args: Array<Arg>
) : Code(null) {
  override fun execute(frame: VirtualFrame): Any {
    val xs = args.map { it.execute(frame) }.toTypedArray()
    return when (op) {
      "mkWeakNoFinalizer#" -> UnboxedTuple(arrayOf(WeakRef(xs[0], xs[1])))
      // TODO
      "getMaskingState#" -> UnboxedTuple(arrayOf(StgInt(0)))
      // TODO
      "noDuplicate#" -> xs[0]
      "newArray#" -> UnboxedTuple(arrayOf(StgArray(Array((xs[0] as StgInt).toInt()) { xs[1] })))
      "newMVar#" -> UnboxedTuple(arrayOf(StgMVar(false, null)))
      "<=#" -> StgInt(if (xs[0] as StgInt <= xs[1] as StgInt) 1L else 0L)
      ">#" -> StgInt(if (xs[0] as StgInt > xs[1] as StgInt) 1L else 0L)
      "-#" -> StgInt((xs[0] as StgInt).x - (xs[1] as StgInt).x)
      "+#" -> StgInt((xs[0] as StgInt).x + (xs[1] as StgInt).x)
      in primOps -> {
        val mh = primOps[op]!!
        MethodHandles.spreadInvoker(mh.type(), 0).invokeExact(mh, xs)
      }
      else -> TODO("$op")
    }
  }
}

abstract class StgPrimOp: Node() {
  abstract fun run(frame: VirtualFrame, args: Array<Any>): Any
}

abstract class StgPrimOp3: StgPrimOp() {
  abstract fun execute(x: Any?, y: Any?, z: Any?): Any
  override fun run(frame: VirtualFrame, args: Array<Any>): Any {
    if (args.size != 3) { panic("bad StgPrimOp3") }
    return execute(args[0], args[1], args[2])
  }
}

  abstract class PutMVar : StgPrimOp3() {
    @Specialization fun putMVar(x: StgMVar, y: Any, z: VoidInh): Any {
      x.value = y
      x.full = true
      return z
    }
  }
