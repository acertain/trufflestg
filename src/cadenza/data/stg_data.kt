package cadenza.data

import cadenza.stg_types.Stg
import com.oracle.truffle.api.CompilerDirectives
import java.lang.ref.WeakReference
import kotlin.reflect.KClass

// everything here is temporary until i implement unboxed fields etc
// TODO: sealed class for possible haskell values?

// until i implement unboxed frames & data types, i'm representing VoidRep like this
object VoidInh
// and unboxed tuples as arrays!

// nyaa
object RealWorld

object NullAddr


// data constr

data class DataCon private constructor(
  val unitId: Stg.UnitId,
  val module: Stg.ModuleName,
  val name: String
) {
  companion object {
    val knownCstrs: MutableMap<DataCon, DataCon> = mutableMapOf()

    operator fun invoke(
      unitId: Stg.UnitId,
      module: Stg.ModuleName,
      name: String
    ): DataCon {
      val c = DataCon(unitId, module, name)
      if (c in knownCstrs) return knownCstrs[c]!!
      knownCstrs[c] = c
      return c
    }

    operator fun invoke(
      unitId: String,
      module: String,
      name: String
    ): DataCon = invoke(Stg.UnitId(unitId), Stg.ModuleName(module), name)
  }
}


// so doing this by DataConId doesn't work, we need to do it by path + con name
class StgData(
  val con: DataCon,
  val args: Array<Any>
)

data class StgInt(val x: Long) {
  fun toInt(): Int = x.toInt()
  operator fun compareTo(y: StgInt): Int = x.compareTo(y.x)
}
data class StgWord(val x: ULong)
// TODO: should x be UInt?
data class StgChar(val x: Int)

// for us an Addr# must be an offset into an array
class StgAddr(
  val arr: ByteArray,
  val offset: Int
) {
  operator fun get(ix: Int): Byte = arr[offset + ix]
}

// afaict this guy can be mutable (& freeze & unfreeze operate on it)?
class StgArray(
  val arr: Array<Any>
) {
  operator fun get(ix: StgInt): Any = arr[ix.toInt()]
  operator fun set(y: StgInt, value: Any) { arr[y.toInt()] = value }
}

class StgMutableByteArray(
  val arr: ByteArray
)

class StgByteArray(
  @CompilerDirectives.CompilationFinal(dimensions = 1) val arr: ByteArray
)

// TODO
class StgMVar(
  var full: Boolean,
  var value: Any?
)

class StgMutVar(
  var x: Any
)


class HaskellException(val x: Any): Exception()


// unlike haskell weakrefs, java weakrefs:
// A reference from the value to the key does keep the key alive.
// FIXME: implement this, see WeakHashMap code
// Weak#
class WeakRef(
  val key: Any,
  val value: Any
)

// TODO: don't store these in closures & etc, only use them in functions, ensureVirtualized
class UnboxedTuple(
  val x: Array<Any>
)

// ThreadId#
class ThreadId(
  val id: Long
)


// TODO: store a weakref here
class StablePtr(
  var x: Any?
)
