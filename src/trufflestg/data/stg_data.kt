package trufflestg.data

import trufflestg.panic
import trufflestg.stg.Stg
import com.oracle.truffle.api.CompilerDirectives
import java.lang.ref.WeakReference
import kotlin.reflect.KClass

// JvmField should make PE faster and might help graal

// everything here is temporary until i implement unboxed fields etc
// TODO: sealed class for possible haskell values
// TODO: CompilerDirectives.ValueType

// VoidRep
object VoidInh

object NullAddr

//
//// data constr
//

data class FullName(
  val unitId: String,
  val module: String,
  val name: String
) {
  override fun toString(): String = "$unitId:$module.$name"
}

// annoyingly the construction needs to be done here to tie the knot :(
class TyCon private constructor(
  val name: FullName,
  src: Stg.STyCon
) {
  val cons: Array<DataCon> = src.dataCons.map {
    DataCon(FullName(name.unitId, name.module, it.name), this)
  }.toTypedArray()

  override fun equals(other: Any?): Boolean = this === other
  override fun hashCode(): Int = name.hashCode()

  companion object {
    // TODO: should the various global vars (this, fcall global mem, probably more) be stored in Context?
    val knownTyCons: MutableMap<FullName, TyCon> = mutableMapOf()

    fun parse(name: FullName, src: Stg.STyCon): TyCon {
      val x = TyCon(name, src)
      val y = knownTyCons[name]
      if (y != null) {
        // TODO: check also whatever else i put in DataCon
        if(!x.cons.zip(y.cons).all { (a, b) -> a.name == b.name }) {
          panic("trying to register a type with the same name but different constructors??")
        }
        return y
      }
      knownTyCons[name] = x
      return x
    }
  }
}

// TODO: don't export constructor
data class DataCon(
  val name: FullName,
  val ty: TyCon
) {
  override fun equals(other: Any?): Boolean = this === other
  override fun hashCode(): Int = name.hashCode()
}


// TODO: use the unboxed frame stuff or such to generate a class per con, also don't forget to intern nullary constructors
@CompilerDirectives.ValueType
class StgData(
  @JvmField val con: DataCon,
  @JvmField val args: Array<Any>
)

// Int#, Word#, Char#, etc
// note that currently ghc only has Int#, not Int32# etc, so we only need StgInt
// FIXME ghc actually does have various Int*# variants, but they aren't used in Int32 etc? what is going on?
@CompilerDirectives.ValueType
data class StgInt(@JvmField val x: Long) {
  fun toInt(): Int = x.toInt()
  operator fun compareTo(y: StgInt): Int = x.compareTo(y.x)
}
@CompilerDirectives.ValueType
data class StgWord(@JvmField val x: ULong)
// TODO: should x be UInt?
@CompilerDirectives.ValueType
data class StgChar(@JvmField val x: Int)

// for now an Addr# is an offset into an array
class StgAddr(
  @JvmField val arr: ByteArray,
  @JvmField val offset: Int
) {
  operator fun get(ix: Int): Byte = arr[offset + ix]
  operator fun set(ix: StgInt, y: Byte) { arr[offset + ix.x.toInt()] = y }

  fun asArray(): ByteArray = arr.copyOfRange(offset, arr.size)
}

// afaict this guy can be mutable (& freeze & unfreeze operate on it)?
class StgArray(
  @JvmField val arr: Array<Any>
) {
  operator fun get(ix: StgInt): Any = arr[ix.toInt()]
  operator fun set(y: StgInt, value: Any) { arr[y.toInt()] = value }
}

class StgMutableByteArray(
  @JvmField val arr: ByteArray
) {
  @JvmField var frozen: Boolean = false
}

//class StgByteArray(
//  val arr: ByteArray
//)

// TODO
class StgMVar(
  @JvmField var full: Boolean,
  @JvmField var value: Any?
)

class StgMutVar(
  @JvmField var x: Any
)


class HaskellException(val x: Any): Exception()


// unlike haskell weakrefs, java weakrefs:
// A reference from the value to the key does keep the key alive.
// FIXME: implement this, see WeakHashMap code
// Weak#
class WeakRef(
  @JvmField val key: Any,
  @JvmField val value: Any,
  @JvmField val finalizer: Any? = null
)

// TODO: don't store these in closures & etc, only use them in functions, ensureVirtualized
@CompilerDirectives.ValueType
class UnboxedTuple(
  @JvmField val x: Array<Any>
)

// ThreadId#
class ThreadId(
  @JvmField val id: Long
)


// TODO: store a weakref here
class StablePtr(
  @JvmField var x: Any?
)
