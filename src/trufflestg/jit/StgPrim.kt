
package trufflestg.jit

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.ExactMath
import com.oracle.truffle.api.dsl.TypeSystemReference
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.nodes.Node
import trufflestg.array_utils.map
import trufflestg.array_utils.write
import trufflestg.data.*
import trufflestg.panic
import trufflestg.todo
import java.nio.ByteBuffer
import kotlin.math.absoluteValue
import kotlin.math.pow


class StgPrim(
  val type: TyCon?,
  val op: String,
  @field:Children val args: Array<Arg>
) : Code(null) {
  @field:Node.Child
  var opNode: StgPrimOp? = primOps[op]?.let { it() }

  @ExplodeLoop
  internal fun args(frame: VirtualFrame): Array<Any> = map(args) { it.execute(frame) }

  override fun execute(frame: VirtualFrame): Any {
    val xs = args(frame)
    if (opNode != null) {
      if (args.size != opNode!!.arity) panic{"StgPrim $op bad arity"}
      val x = opNode!!.run(frame, xs)
      // println("$op ${xs.contentToString()} = $x")
      return x
    }
    panic{"$op nyi"}
  }
}

// TODO: should prims of type -> IO () return VoidInh or UnboxedTuple(arrayOf()) ?
// TODO: understand strictness of prims taking lifted types
// i think usually we need to whnf ourselves?
@Suppress("LocalVariableName")
@OptIn(ExperimentalUnsignedTypes::class)
val primOps: Map<String, () -> StgPrimOp> = mapOf(
  "gtChar#" to wrap2 { x: StgWord, y: StgWord -> StgInt(if (x.x > y.x) 1L else 0L) },
  "geChar#" to wrap2 { x: StgWord, y: StgWord -> StgInt(if (x.x >= y.x) 1L else 0L) },
  "eqChar#" to wrap2 { x: StgWord, y: StgWord -> StgInt(if (x.x == y.x) 1L else 0L) },
  "neChar#" to wrap2 { x: StgWord, y: StgWord -> StgInt(if (x.x != y.x) 1L else 0L) },
  "ltChar#" to wrap2 { x: StgWord, y: StgWord -> StgInt(if (x.x < y.x) 1L else 0L) },
  "leChar#" to wrap2 { x: StgWord, y: StgWord -> StgInt(if (x.x <= y.x) 1L else 0L) },

  "ord#" to wrap1 { x: StgWord -> StgInt(x.x.toLong()) }, // TODO: is this right?

  "catch#" to { object : StgPrimOp(3) {
    @field:Child var callWhnf1 = CallWhnf(1, false)
    @field:Child var callWhnf2 = CallWhnf(2, false)
    override fun run(frame: VirtualFrame, args: Array<Any>): Any =
      // TODO: do i need to box the return value as a unboxed tuple?
      try { callWhnf1.execute(frame, args[0], arrayOf(VoidInh)) }
      // TODO: should this catch java exceptions?
      catch (e: HaskellException) { callWhnf2.execute(frame, args[1], arrayOf(e.x, VoidInh)) }
  }},

  // TODO: implement these
  "maskAsyncExceptions#" to { object : StgPrimOp(2) {
    @field:Child var callWhnf = CallWhnf(1, false)
    override fun run(frame: VirtualFrame, args: Array<Any>): Any = callWhnf.execute(frame, args[0], arrayOf(VoidInh))
  }},
  "unmaskAsyncExceptions#" to { object : StgPrimOp(2) {
    @field:Child var callWhnf = CallWhnf(1, false)
    override fun run(frame: VirtualFrame, args: Array<Any>): Any = callWhnf.execute(frame, args[0], arrayOf(VoidInh))
  }},

  "+#" to wrap2 { x: StgInt, y: StgInt -> StgInt(x.x + y.x) },
  "-#" to wrap2 { x: StgInt, y: StgInt -> StgInt(x.x - y.x) },
  "negateInt#" to wrap1 { x: StgInt -> StgInt(-x.x) },
  "*#" to wrap2 { x: StgInt, y: StgInt -> StgInt(x.x * y.x) },
  "<=#" to wrap2 { x: StgInt, y: StgInt -> StgInt(if (x.x <= y.x) 1L else 0L) },
  ">#" to wrap2 { x: StgInt, y: StgInt -> StgInt(if (x.x > y.x) 1L else 0L) },
  "<#" to wrap2 { x: StgInt, y: StgInt -> StgInt(if (x.x < y.x) 1L else 0L) },
  ">=#" to wrap2 { x: StgInt, y: StgInt -> StgInt(if (x.x >= y.x) 1L else 0L) },
  "==#" to wrap2 { x: StgInt, y: StgInt -> StgInt(if (x.x == y.x) 1L else 0L) },

  "andI#" to wrap2 { x: StgInt, y: StgInt -> StgInt(x.x and y.x) },
  "orI#" to wrap2 { x: StgInt, y: StgInt -> StgInt(x.x or y.x) },
  "xorI#" to wrap2 { x: StgInt, y: StgInt -> StgInt(x.x xor y.x) },

  "addIntC#" to wrap2 { x: StgInt, y: StgInt ->
    val r = x.x + y.x
    UnboxedTuple(arrayOf(StgInt(r), StgInt(if(x.x xor r and (y.x xor r) < 0) 1L else 0L)))
  },
  "subIntC#" to wrap2 { x: StgInt, y: StgInt ->
    val r = x.x - y.x
    UnboxedTuple(arrayOf(StgInt(r), StgInt(if(x.x xor r and (y.x xor r) < 0) 1L else 0L)))
  },

  "timesInt2#" to wrap2 { x: StgInt, y: StgInt ->
    val hi = ExactMath.multiplyHigh(x.x, y.x)
    val lo = x.x * y.x
    UnboxedTuple(arrayOf(StgInt(
      if (hi != (lo shr 63)) 1L else 0L
    ), StgInt(hi), StgInt(lo)))
  },

  "quotRemInt#" to wrap2 { x: StgInt, y: StgInt ->
    UnboxedTuple(arrayOf(StgInt(x.x / y.x), StgInt(x.x % y.x)))
  },

  "newMVar#" to wrap1 { _: VoidInh -> UnboxedTuple(arrayOf(StgMVar(false, null))) },
  "putMVar#" to wrap3 { x: StgMVar, y: Any, z: VoidInh ->
    x.full = true
    x.value = y
    z
  },
  "takeMVar#" to wrap2 { x: StgMVar, _: VoidInh ->
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

  "myThreadId#" to wrap1 { _: VoidInh -> UnboxedTuple(arrayOf(ThreadId(Thread.currentThread().id))) },

  "makeStablePtr#" to wrap2 { x: Any, _: VoidInh -> UnboxedTuple(arrayOf(StablePtr(x))) },

  "mkWeakNoFinalizer#" to wrap3 { x: Any, y: Any, _: VoidInh -> UnboxedTuple(arrayOf(WeakRef(x, y))) },
  "mkWeak#" to wrap4 { x: Any, y: Any, z: Any, _: VoidInh -> UnboxedTuple(arrayOf(WeakRef(x, y, z))) },

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
  "writeMutVar#" to wrap3 { x: StgMutVar, y: Any, v: VoidInh -> x.x = y; v },

  "chr#" to wrap1 { x: StgInt -> StgWord(x.x.toULong()) }, // TODO: is this right?

  "int2Word#" to wrap1 { x: StgInt -> StgWord(x.x.toULong()) },
  "word2Int#" to wrap1 { x: StgWord -> StgInt(x.x.toLong()) },
  "narrow8Word#" to wrap1 { x: StgWord -> StgWord(x.x.toUByte().toULong()) },
  "narrow32Int#" to wrap1 { x: StgInt -> StgInt(x.x.toInt().toLong()) },

  "gtWord#" to wrap2 { x: StgWord, y: StgWord -> StgInt(if (x.x > y.x) 1L else 0L) },
  "geWord#" to wrap2 { x: StgWord, y: StgWord -> StgInt(if (x.x >= y.x) 1L else 0L) },
  "eqWord#" to wrap2 { x: StgWord, y: StgWord -> StgInt(if (x.x == y.x) 1L else 0L) },
  "neWord#" to wrap2 { x: StgWord, y: StgWord -> StgInt(if (x.x != y.x) 1L else 0L) },
  "ltWord#" to wrap2 { x: StgWord, y: StgWord -> StgInt(if (x.x < y.x) 1L else 0L) },
  "leWord#" to wrap2 { x: StgWord, y: StgWord -> StgInt(if (x.x <= y.x) 1L else 0L) },

  "plusWord#" to wrap2 { x: StgWord, y: StgWord -> StgWord(x.x + y.x) },

  "minusWord#" to wrap2 { x: StgWord, y: StgWord -> StgWord(x.x - y.x) },

  "timesWord#" to wrap2 { x: StgWord, y: StgWord -> StgWord(x.x * y.x) },

  "int2Double#" to wrap1 { x: StgInt -> StgDouble(x.x.toDouble()) },

  ">##" to wrap2 { x: StgDouble, y: StgDouble -> StgInt(if (x.x > y.x) 1L else 0L) },
  ">=##" to wrap2 { x: StgDouble, y: StgDouble -> StgInt(if (x.x >= y.x) 1L else 0L) },
  "==##" to wrap2 { x: StgDouble, y: StgDouble -> StgInt(if (x.x == y.x) 1L else 0L) },
  "/=##" to wrap2 { x: StgDouble, y: StgDouble -> StgInt(if (x.x != y.x) 1L else 0L) },
  "<##" to wrap2 { x: StgDouble, y: StgDouble -> StgInt(if (x.x < y.x) 1L else 0L) },
  "<=##" to wrap2 { x: StgDouble, y: StgDouble -> StgInt(if (x.x <= y.x) 1L else 0L) },

  "+##" to wrap2 { x: StgDouble, y: StgDouble -> StgDouble(x.x + y.x) },
  "-##" to wrap2 { x: StgDouble, y: StgDouble -> StgDouble(x.x - y.x) },
  "*##" to wrap2 { x: StgDouble, y: StgDouble -> StgDouble(x.x * y.x) },
  "/##" to wrap2 { x: StgDouble, y: StgDouble -> StgDouble(x.x / y.x) },

  "negateDouble#" to wrap1 { x: StgDouble -> StgDouble(-x.x) },
  "fabsDouble#" to wrap1 { x: StgDouble -> StgDouble(x.x.absoluteValue) },

  "sqrtDouble#" to wrap1 { x: StgDouble -> StgDouble(kotlin.math.sqrt(x.x)) },

  "**##" to wrap2 { x: StgDouble, y: StgDouble -> StgDouble(x.x.pow(y.x)) },

  // copied from __decodeDouble_2Int in rts/StgPrimFloat.c
  "decodeDouble_2Int#" to wrap1 { x: StgDouble ->
    val DMSBIT: UInt = 0x80000000U
    val DHIGHBIT: UInt = 0x00100000U
    val DMINEXP = java.lang.Double.MIN_EXPONENT - 53

    val b = java.lang.Double.doubleToRawLongBits(x.x)

    var low = b.toUInt()
    var high = (b ushr 32).toUInt()

    if (low == 0U && (high and DMSBIT.inv()) == 0U) {
      UnboxedTuple(arrayOf(StgInt(0L), StgWord(0UL), StgWord(0UL), StgWord(0UL)))
    } else {
      var iexp = ((high shr 20) and 0x7ffU).toInt() + DMINEXP
      val sign = high.toInt()

      high = high and (DHIGHBIT - 1U)
      if (iexp != DMINEXP) {
        high = high or DHIGHBIT
      } else {
        iexp++
        while (high and DHIGHBIT == 0U) {
          println("$iexp $high $low")
          high = high shl 1
          if (low and DMSBIT != 0U) high++
          low = low shl 1
          iexp--
        }
      }
      UnboxedTuple(arrayOf(StgInt(if (sign < 0) -1L else 1L), StgWord(high.toULong()), StgWord(low.toULong()), StgInt(iexp.toLong())))
    }
  },

  "tagToEnum#" to { object : StgPrimOp1() {
    // TODO: need to do this lazily because parent isn't set at initialization
    @CompilerDirectives.CompilationFinal(dimensions = 1)
    var cons: Array<DataCon?>? = null
    override fun execute(x: Any): Any {
      if (cons === null) {
        CompilerDirectives.transferToInterpreterAndInvalidate()
        cons = (parent as? StgPrim)!!.type!!.zeroArgCons
      }
      return cons!![(x as? StgInt)!!.x.toInt()]!!
    }
    }
  },
  "dataToTag#" to { object : StgPrimOp1() {
    @CompilerDirectives.CompilationFinal var type: TyCon? = null
    @ExplodeLoop
    override fun execute(x: Any): Any {
      var ty = type
      if (ty === null) {
        CompilerDirectives.transferToInterpreterAndInvalidate()
        ty = (x as DataCon).getInfo().type
        type = ty
      }
      // could move this above getting ty? so that zero-arg cons don't need to deopt here
      if (ty.hasZeroArgCons) {
        // TODO: should this be <= 2 or such?
        if (ty.numZeroArgCons == 1) {
          ty.zeroArgCons.forEachIndexed { ix, y ->
            if (y !== null && x === y) return ix
          }
        } else {
          if (x is ZeroArgDataCon) return StgInt(x.tag.toLong())
        }
      }
      // TODO: profile which cons we've seen?
      // TODO: do a virtual call to getInfo().tag for big types?
      ty.cons.forEachIndexed { ix, c ->
        if (c.size != 0) {
          if (c.tryIs(x) !== null) { return StgInt(ix.toLong()) }
        }
      }
      panic("bad dataToTag#")
    }
  } },

  // just reads a byte
  // TODO: make sure this is right (maybe should be unsigned)?
  "indexCharOffAddr#" to wrap2 { x: StgAddr, y: StgInt ->
    val off = x.offset + y.x.toInt()
    if (off >= x.arr.size) { StgWord(0UL) }
      else StgWord(x.arr[off].toUByte().toULong()) },

  "readInt8OffAddr#" to wrap3 { x: StgAddr, y: StgInt, _: VoidInh ->
    UnboxedTuple(arrayOf(StgInt(x[y.toInt()].toLong()))) },
  "readInt32OffAddr#" to wrap3 { x: StgAddr, y: StgInt, _: VoidInh ->
    UnboxedTuple(arrayOf(StgInt(ByteBuffer.wrap(x.arr).getInt(x.offset+4*y.toInt()).toLong()))) },
  "readWord8OffAddr#" to wrap3 { x: StgAddr, y: StgInt, _: VoidInh ->
    UnboxedTuple(arrayOf(StgWord(x[y.toInt()].toULong()))) },
  "readAddrOffAddr#" to wrap3 { x: StgAddr, y: StgInt, _: VoidInh ->
    val l = ByteBuffer.wrap(x.arr).getLong(x.offset + 8*y.toInt()).toInt()
    // FIXME: need to not have globalHeap reallocate...
    val w = StgAddr(globalHeap, l)
    UnboxedTuple(arrayOf(w))
  },
  "writeWord8OffAddr#" to wrap4 { x: StgAddr, y: StgInt, z: StgWord, v: VoidInh -> x[y] = z.x.toByte(); v },

  "readWideCharOffAddr#" to wrap3 { x: StgAddr, y: StgInt, _: VoidInh ->
    UnboxedTuple(arrayOf(StgWord(ByteBuffer.wrap(x.arr).getInt(x.offset+4*y.toInt()).toUInt().toULong()))) },
  "writeWideCharOffAddr#" to wrap4 { x: StgAddr, y: StgInt, z: StgWord, v: VoidInh ->
    x.arr.write(x.offset + 4*y.x.toInt(), z.x.toUInt()); v },

  "writeWordArray#" to wrap4 { x: StgMutableByteArray, y: StgInt, z: StgWord, v: VoidInh ->
    x.arr.write(8*y.toInt(), z.x.toLong()); v },

  "indexWordArray#" to wrap2 { x: StgMutableByteArray, y: StgInt ->
    StgWord(x.asBuffer().getLong(y.toInt()*8).toULong())
  },

  "plusAddr#" to wrap2 { x: StgAddr, y: StgInt -> StgAddr(x.arr, x.offset + y.x.toInt()) },

  // TODO: make sure these are right
  "uncheckedIShiftL#" to wrap2 { x: StgInt, y: StgInt -> StgInt(x.x shl y.x.toInt()) },
  "uncheckedIShiftRL#" to wrap2 { x: StgInt, y: StgInt -> StgInt(x.x ushr y.x.toInt()) },
  "uncheckedIShiftRA#" to wrap2 { x: StgInt, y: StgInt -> StgInt(x.x shr y.x.toInt()) },
  "uncheckedShiftL#" to wrap2 { x: StgWord, y: StgInt -> StgWord(x.x shl y.x.toInt()) },
  "uncheckedShiftRL#" to wrap2 { x: StgWord, y: StgInt -> StgWord(x.x shr y.x.toInt()) },

  "or#" to wrap2 { x: StgWord, y: StgWord -> StgWord(x.x or y.x) },
  "and#" to wrap2 { x: StgWord, y: StgWord -> StgWord(x.x and y.x) },
  "xor#" to wrap2 { x: StgWord, y: StgWord -> StgWord(x.x xor y.x) },
  "not#" to wrap1 { x: StgWord -> StgWord(x.x.inv()) },

  // TODO?
  "touch#" to wrap2 { x: Any, y: VoidInh -> y },

  // FIXME: should tail call here when we're in tail position (& also probably for other primops that use CallWhnf)
  "seq#" to { object : StgPrimOp(2) {
    @field:Child var callWhnf = CallWhnf(0, false)
    override fun run(frame: VirtualFrame, args: Array<Any>): Any = callWhnf.execute(frame, args[0], arrayOf())
  }},

  // TODO: make stack trace on throw a flag
  // actually should store stack trace on the exception/in a weakhashmap & hijack exception printing
  "raise#" to wrap1 { e: Any ->
    println("$e thrown")
    printStackTrace()
    throw HaskellException(e)
  },
  "raiseIO#" to wrap2 { e: Any, _: VoidInh ->
    println("$e thrown")
    printStackTrace()
    throw HaskellException(e)
  },

  "newByteArray#" to wrap2 { x: StgInt, _: VoidInh ->
    UnboxedTuple(arrayOf(StgMutableByteArray(ByteArray(x.toInt()))))
  },
  // TODO: actually pin & align? only matters if we want to use native code
  // FIXME: or for readAddrOffAddr, or if casted to Addr?
  "newAlignedPinnedByteArray#" to wrap3 { x: StgInt, alignment: StgInt, _: VoidInh ->
    UnboxedTuple(arrayOf(StgMutableByteArray(ByteArray(x.toInt())))) },
  "newPinnedByteArray#" to wrap2 { x: StgInt, _: VoidInh ->
    UnboxedTuple(arrayOf(StgMutableByteArray(ByteArray(x.toInt())))) },
  "unsafeFreezeByteArray#" to wrap2 { arr: StgMutableByteArray, _: VoidInh ->
    arr.frozen = true
    UnboxedTuple(arrayOf(arr)) },
  "byteArrayContents#" to wrap1 { arr: StgMutableByteArray -> StgAddr(arr.arr, 0) },

  "sizeofByteArray#" to wrap1 { x: StgMutableByteArray -> StgInt(x.arr.size.toLong()) }
)


// this makes a class per call site
inline fun <reified X, reified Y : Any> wrap1(crossinline f: (X) -> Y): () -> StgPrimOp = {
  object : StgPrimOp1() { override fun execute(x: Any): Any = f((x as? X)!!) }
}
inline fun <reified X, reified Y, reified Z : Any> wrap2(crossinline f: (X, Y) -> Z): () -> StgPrimOp = {
  object : StgPrimOp2() { override fun execute(x: Any, y: Any): Any = f((x as? X)!!, (y as? Y)!!) }
}
inline fun <reified X, reified Y, reified Z, reified W : Any> wrap3(crossinline f: (X, Y, Z) -> W): () -> StgPrimOp = {
  object : StgPrimOp3() { override fun execute(x: Any, y: Any, z: Any): Any = f((x as? X)!!, (y as? Y)!!, (z as? Z)!!) }
}
inline fun <reified X, reified Y, reified Z, reified W : Any, reified A> wrap4(crossinline f: (X, Y, Z, A) -> W): () -> StgPrimOp = {
  object : StgPrimOp4() { override fun execute(x: Any, y: Any, z: Any, a: Any): Any = f((x as? X)!!, (y as? Y)!!, (z as? Z)!!, (a as? A)!!) }
}


@TypeSystemReference(DataTypes::class)
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
