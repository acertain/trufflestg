package cadenza.data

import cadenza.panic
import cadenza.stg_types.Stg
import com.oracle.truffle.api.CompilerDirectives
import java.lang.ref.WeakReference
import kotlin.reflect.KClass

// everything here is temporary until i implement unboxed fields etc
// TODO: sealed class for possible haskell values

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


// so doing this by DataConId doesn't work, we need to do it by path + con name
// TODO: use the unboxed frame stuff or such to generate a class per con, also don't forget to intern nullary constructors
class StgData(
  val con: DataCon,
  val args: Array<Any>
)

// Int#, Word#, Char#, etc
// note that currently ghc only has Int#, not Int32# etc, so we only need StgInt
// FIXME ghc actually does have various Int*# variants, but they aren't used in Int32 etc? what is going on?
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
  operator fun set(ix: StgInt, y: Byte) { arr[offset + ix.x.toInt()] = y }

  fun asArray(): ByteArray = arr.copyOfRange(offset, arr.size)
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
) {
  var frozen: Boolean = false
}

//class StgByteArray(
//  val arr: ByteArray
//)

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
  val value: Any,
  val finalizer: Any? = null
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
