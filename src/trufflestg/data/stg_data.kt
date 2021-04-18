package trufflestg.data

import trufflestg.panic
import trufflestg.stg.Stg
import com.oracle.truffle.api.CompilerDirectives
import org.intelligence.diagnostics.Severity
import org.intelligence.diagnostics.error
import org.intelligence.pretty.Pretty
import java.lang.ref.WeakReference
import kotlin.reflect.KClass
import java.nio.ByteBuffer

// JvmField should make PE faster and might help graal

// everything here is temporary until i implement unboxed fields etc
// TODO: sealed (or just abstract?) class for possible haskell values
// TODO: CompilerDirectives.ValueType

// VoidRep
object VoidInh

object NullAddr


data class FullName(
  val unitId: String,
  val module: String,
  val name: String
) {
  override fun toString(): String = "$unitId:$module.$name"
}

// Int#, Word#, Char#, etc
// note that currently ghc only has Int#, not Int32# etc, so we only need StgInt
// FIXME ghc actually does have various Int*# variants, but they aren't used in Int32 etc? what's going on?
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
// TODO: make arr private & add more methods to avoid errors
class StgAddr(
  @JvmField val arr: ByteArray,
  @JvmField val offset: Int
) {
  operator fun get(ix: Int): Byte = arr[offset + ix]
  operator fun set(ix: StgInt, y: Byte) { arr[offset + ix.x.toInt()] = y }

  fun asArray(): ByteArray = arr.copyOfRange(offset, arr.size)
  fun asBuffer(): ByteBuffer = ByteBuffer.wrap(arr, offset, arr.size - offset)
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

  fun asBuffer(): ByteBuffer = ByteBuffer.wrap(arr)
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


class HaskellException(val x: Any) : RuntimeException() {
  // TODO
  override fun toString(): String =
    stackTrace?.getOrNull(0)?.let {
      Pretty.ppString {
        error(Severity.todo, it.fileName, it.lineNumber, null, null, it.methodName)
      }
    } ?: super.toString()
}


// unlike haskell weakrefs, java weakrefs:
// A reference from the value to the key does keep the key alive.
// FIXME: implement this, see WeakHashMap code
// Weak#
class WeakRef(
  @JvmField val key: Any,
  @JvmField val value: Any,
  @JvmField val finalizer: Any? = null
)

// TODO: don't store these in closures & etc, only use them in functions, maybe ensureVirtualized
// TODO: UnboxedTuple1 etc?
@CompilerDirectives.ValueType
class UnboxedTuple(
  @JvmField val x: Array<Any>
) {
  override fun toString(): String = "(# " + x.joinToString(", ") { it.toString() } + " #)"
}

// ThreadId#
class ThreadId(
  @JvmField val id: Long
)


// TODO: store a weakref here
class StablePtr(
  @JvmField var x: Any?
)
