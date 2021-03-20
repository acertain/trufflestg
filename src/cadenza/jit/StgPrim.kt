
package cadenza.jit

import cadenza.data.*
import cadenza.panic
import cadenza.stg_types.Stg
import cadenza.todo
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.dsl.ReportPolymorphism
import com.oracle.truffle.api.dsl.Specialization
import com.oracle.truffle.api.dsl.TypeSystemReference
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.nodes.Node
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.nio.ByteBuffer
import kotlin.reflect.KCallable
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.functions
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.reflect


class StgPrim(
  val op: String,
  @field:Children val args: Array<Arg>
) : Code(null) {
  @field:Node.Child
  var opNode: StgPrimOp? = primOpss[op]?.let { it() }

  @ExplodeLoop
  internal fun args(frame: VirtualFrame): Array<Any> = map(args) { it.execute(frame) }

  override fun execute(frame: VirtualFrame): Any {
    val xs = args(frame)
    if (opNode != null) {
      if (args.size != opNode!!.arity) panic{"StgPrim $op bad arity"}
      return opNode!!.run(frame, xs)
    }
    panic{"$op nyi"}
  }
}

// TODO: should prims of type -> IO () return VoidInh or UnboxedTuple(arrayOf()) ?
val primOpss: Map<String, () -> StgPrimOp> = mapOf(
  // TODO: CallWhnf
  "catch#" to wrap3 { x: Closure, y: Closure, z: VoidInh ->
    // TODO: do i need to box the return value as a unboxed tuple?
    try { x.call(arrayOf(RealWorld)) }
    // TODO: should this catch all non-truffle exceptions?
    catch (e: HaskellException) { y.call(arrayOf(e.x, RealWorld)) }
  },
  "maskAsyncExceptions#" to wrap2 { x: Closure, _: VoidInh -> x.call(arrayOf(RealWorld)) },
  "unmaskAsyncExceptions#" to wrap2 { x: Closure, _: VoidInh -> x.call(arrayOf(RealWorld)) },

  "+#" to wrap2 { x: StgInt, y: StgInt -> StgInt(x.x + y.x) },
  "-#" to wrap2 { x: StgInt, y: StgInt -> StgInt(x.x - y.x) },
  "<=#" to wrap2 { x: StgInt, y: StgInt -> StgInt(if (x.x <= y.x) 1L else 0L) },
  ">#" to wrap2 { x: StgInt, y: StgInt -> StgInt(if (x.x > y.x) 1L else 0L) },

  "newMVar#" to wrap1 { x: VoidInh -> UnboxedTuple(arrayOf(StgMVar(false, null))) },
  "putMVar#" to wrap3 { x: StgMVar, y: Any, z: VoidInh ->
    x.full = true
    x.value = y
    z
  },
  "takeMVar#" to wrap2 { x: StgMVar, y: VoidInh ->
    if (!x.full) todo
    val w = x.value!!
    x.full = false
    x.value = null
    UnboxedTuple(arrayOf(w))
  },

  "newArray#" to wrap3 { x: StgInt, y: Any, _: VoidInh -> UnboxedTuple(arrayOf(StgArray(Array(x.toInt()) { y }))) },
  "readArray#" to wrap3 { x: StgArray, y: StgInt, _: VoidInh -> UnboxedTuple(arrayOf(x[y])) },
  "writeArray#" to wrap4 { x: StgArray, y: StgInt, z: Any, w: VoidInh -> x[y] = z; w },

  "eqAddr#" to wrap2 { x: Any, y: Any -> StgInt(if (x === y) 1L else 0L) },

  "myThreadId#" to wrap1 { x: VoidInh -> UnboxedTuple(arrayOf(ThreadId(Thread.currentThread().id))) },

  "makeStablePtr#" to wrap2 { x: Any, _: VoidInh -> UnboxedTuple(arrayOf(StablePtr(x))) },

  "mkWeakNoFinalizer#" to wrap3 { x: Any, y: Any, _: VoidInh -> UnboxedTuple(arrayOf(WeakRef(x, y))) },

  // TODO
  "getMaskingState#" to wrap1 { _: VoidInh -> UnboxedTuple(arrayOf(StgInt(0))) },
  // TODO
  "noDuplicate#" to wrap1 { x: Any -> x },

  "deRefStablePtr#" to wrap2 { x: StablePtr, _: VoidInh ->
    if (x.x == null) panic("deRefStablePtr after freeStablePtr")
    UnboxedTuple(arrayOf(x.x!!))
  },

  "newMutVar#" to wrap2 { x: Any, _: VoidInh -> UnboxedTuple(arrayOf(StgMutVar(x))) },
  "readMutVar#" to wrap2 { x: StgMutVar, _: VoidInh -> UnboxedTuple(arrayOf(x.x)) },

  "leChar#" to wrap2 { x: StgChar, y: StgChar -> StgInt(if (x.x <= y.x) 1L else 0L) },
  "ord#" to wrap1 { x: StgChar -> StgInt(x.x.toLong()) }, // TODO: should this be an unsigned conversion?
  "chr#" to wrap1 { x: StgInt -> StgChar(x.x.toInt()) }, // TODO: ^

  "int2Word#" to wrap1 { x: StgInt -> StgWord(x.x.toULong()) },
  "word2Int#" to wrap1 { x: StgWord -> StgInt(x.x.toLong()) },
  "narrow8Word#" to wrap1 { x: StgWord -> StgWord(x.x.toUByte().toULong()) },

  "leWord#" to wrap2 { x: StgWord, y: StgWord -> StgInt(if (x.x < y.x) 1L else 0L) },

  "eqChar#" to wrap2 { x: StgChar, y: StgChar -> StgInt(if (x.x == y.x) 1L else 0L) },

  // just reads a byte
  // TODO: make sure this is right (maybe should be unsigned)?
  "indexCharOffAddr#" to wrap2 { x: StgAddr, y: StgInt ->
    val off = x.offset + y.x.toInt()
    if (off >= x.arr.size) { StgChar(0) }
      else StgChar(x.arr[off].toInt()) },

  "readInt8OffAddr#" to wrap3 { x: StgAddr, y: StgInt, z: VoidInh ->
    UnboxedTuple(arrayOf(StgInt(x[y.toInt()].toLong()))) },
  "readInt32OffAddr#" to wrap3 { x: StgAddr, y: StgInt, z: VoidInh ->
    UnboxedTuple(arrayOf(StgInt(ByteBuffer.wrap(x.arr).getInt(y.toInt()).toLong()))) },
  "readAddrOffAddr#" to wrap3 { x: StgAddr, y: StgInt, z: VoidInh ->
    val l = ByteBuffer.wrap(x.arr).getLong(y.toInt()).toInt()
    // FIXME: need to not have globalHeap reallocate...
    val w = StgAddr(globalHeap, l)
    UnboxedTuple(arrayOf(w))
  },

  "plusAddr#" to wrap2 { x: StgAddr, y: StgInt -> StgAddr(x.arr, x.offset + y.x.toInt()) },

  "uncheckedIShiftL#" to wrap2 { x: StgInt, y: StgInt -> StgInt(x.x shl y.x.toInt()) },

  // TODO?
  "touch#" to wrap2 { x: StgByteArray, y: VoidInh -> y },

  "raise#" to wrap1 { e: Any -> (throw HaskellException(e)) as VoidInh },
  "raiseIO#" to wrap2 { e: Any, y: VoidInh -> (throw HaskellException(e)) as VoidInh },


  // TODO: actually pin & align? only matters if we want to use native code
  // FIXME: or for readAddrOffAddr, or if casted to Addr?
  "newAlignedPinnedByteArray#" to wrap3 { x: StgInt, alignment: StgInt, v: VoidInh ->
    UnboxedTuple(arrayOf(StgMutableByteArray(ByteArray(x.toInt())))) },
  "unsafeFreezeByteArray#" to wrap2 { arr: StgMutableByteArray, v: VoidInh ->
    UnboxedTuple(arrayOf(StgByteArray(arr.arr))) },
  "byteArrayContents#" to wrap1 { arr: StgByteArray -> StgAddr(arr.arr, 0) }
)


// this makes a new real (static) class & returns the constructor
inline fun <reified X, reified Y> wrap1(crossinline f: (X) -> Y): () -> StgPrimOp = {
  object : StgPrimOp1() { override fun execute(x: Any): Any = f(x as X) as Any }
}
inline fun <reified X, reified Y, reified Z> wrap2(crossinline f: (X, Y) -> Z): () -> StgPrimOp = {
  object : StgPrimOp2() { override fun execute(x: Any, y: Any): Any = f(x as X, y as Y) as Any }
}
inline fun <reified X, reified Y, reified Z, reified W> wrap3(crossinline f: (X, Y, Z) -> W): () -> StgPrimOp = {
  object : StgPrimOp3() { override fun execute(x: Any, y: Any, z: Any): Any = f(x as X, y as Y, z as Z) as Any }
}
inline fun <reified X, reified Y, reified Z, reified W, reified A> wrap4(crossinline f: (X, Y, Z, A) -> W): () -> StgPrimOp = {
  object : StgPrimOp4() { override fun execute(x: Any, y: Any, z: Any, a: Any): Any = f(x as X, y as Y, z as Z, a as A) as Any }
}


@TypeSystemReference(DataTypes::class)
@ReportPolymorphism
abstract class StgPrimOp(val arity: Int) : Node() {
  abstract fun run(frame: VirtualFrame, args: Array<Any>): Any
}


abstract class StgPrimOp1() : StgPrimOp(1) {
  abstract fun execute(x: Any): Any
  override fun run(frame: VirtualFrame, args: Array<Any>): Any = execute(args[0])
}
abstract class StgPrimOp2() : StgPrimOp(2) {
  abstract fun execute(x: Any, y: Any): Any
  override fun run(frame: VirtualFrame, args: Array<Any>): Any = execute(args[0], args[1])
}
abstract class StgPrimOp3() : StgPrimOp(3) {
  abstract fun execute(x: Any, y: Any, z: Any): Any
  override fun run(frame: VirtualFrame, args: Array<Any>): Any = execute(args[0], args[1], args[2])
}
abstract class StgPrimOp4() : StgPrimOp(4) {
  abstract fun execute(x: Any, y: Any, z: Any, a: Any): Any
  override fun run(frame: VirtualFrame, args: Array<Any>): Any = execute(args[0], args[1], args[2], args[3])
}
