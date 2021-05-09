@file:Suppress("JAVA_MODULE_DOES_NOT_EXPORT_PACKAGE")
package trufflestg.data

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.TruffleObject
import com.oracle.truffle.api.interop.UnsupportedMessageException
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import jdk.internal.misc.Unsafe
import org.intelligence.diagnostics.Severity
import org.intelligence.diagnostics.error
import org.intelligence.pretty.Pretty
import trufflestg.array_utils.write
import trufflestg.jit.asCString
import java.lang.reflect.Constructor
import java.nio.ByteBuffer
import java.nio.ByteOrder

// TODO: sealed (or just abstract?) class for possible haskell values
// TODO: CompilerDirectives.ValueType

// VoidRep
object VoidInh

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
@ExportLibrary(InteropLibrary::class)
data class StgInt(@JvmField val x: Long) : TruffleObject {
  fun toInt(): Int = x.toInt()
  operator fun compareTo(y: StgInt): Int = x.compareTo(y.x)

  @ExportMessage fun isNumber(): Boolean = true
  @ExportMessage fun fitsInLong(): Boolean = true
  @ExportMessage fun asLong(): Long = x

  @ExportMessage fun fitsInByte(): Boolean = false
  @ExportMessage fun fitsInShort(): Boolean = false
  @ExportMessage fun fitsInInt(): Boolean = false
  @ExportMessage fun fitsInFloat(): Boolean = false
  @ExportMessage fun fitsInDouble(): Boolean = false

  @ExportMessage fun asByte(): Byte { throw UnsupportedMessageException.create() }
  @ExportMessage fun asShort(): Short { throw UnsupportedMessageException.create() }
  @ExportMessage fun asInt(): Int { throw UnsupportedMessageException.create() }
  @ExportMessage fun asFloat(): Float { throw UnsupportedMessageException.create() }
  @ExportMessage fun asDouble(): Double { throw UnsupportedMessageException.create() }

  fun unbox(): Long = x
  companion object { @JvmStatic fun box(x: Long): StgInt = StgInt(x) }
}

@CompilerDirectives.ValueType
@OptIn(ExperimentalUnsignedTypes::class)
@ExportLibrary(InteropLibrary::class)
data class StgWord(@JvmField val x: ULong) : TruffleObject {
  fun asChar(): Int = x.toInt()

  @ExportMessage fun isNumber(): Boolean = true
  @ExportMessage fun fitsInLong(): Boolean = true
  // TODO: is this right??
  @ExportMessage fun asLong(): Long = x.toLong()

  @ExportMessage fun fitsInByte(): Boolean = false
  @ExportMessage fun fitsInShort(): Boolean = false
  @ExportMessage fun fitsInInt(): Boolean = false
  @ExportMessage fun fitsInFloat(): Boolean = false
  @ExportMessage fun fitsInDouble(): Boolean = false

  @ExportMessage fun asByte(): Byte { throw UnsupportedMessageException.create() }
  @ExportMessage fun asShort(): Short { throw UnsupportedMessageException.create() }
  @ExportMessage fun asInt(): Int { throw UnsupportedMessageException.create() }
  @ExportMessage fun asFloat(): Float { throw UnsupportedMessageException.create() }
  @ExportMessage fun asDouble(): Double { throw UnsupportedMessageException.create() }

  fun unbox(): Long = x.toLong()
  companion object { @JvmStatic fun box(x: Long): StgWord = StgWord(x.toULong()) }
}

@CompilerDirectives.ValueType
data class StgDouble(@JvmField val x: Double)

val unsafe: Unsafe = Unsafe.getUnsafe()

val directBufferCtor: Constructor<*> = run {
  val x = Class.forName("java.nio.DirectByteBuffer").getDeclaredConstructor(java.lang.Long.TYPE, java.lang.Integer.TYPE)
  x.isAccessible = true
  x
}
fun newDirectByteBuffer(addr: Long, cap: Int): ByteBuffer {
  return (directBufferCtor.newInstance(addr, cap) as ByteBuffer).order(ByteOrder.LITTLE_ENDIAN)
}

sealed class StgAddr {
  abstract fun getRange(x: IntRange): ByteArray
  abstract fun write(offset: Int, data: ByteArray)

  // x is always in bytes
  abstract fun readByte(x: Int): Byte
  abstract fun readInt(x: Int): Int
  abstract fun readLong(x: Int): Long
  abstract fun writeByte(x: Int, y: Byte)
  abstract fun writeInt(x: Int, y: Int)
  abstract fun writeLong(x: Int, y: Long)

  abstract fun addOffset(x: Int): StgAddr

  abstract fun asCString(): String

  companion object {
    fun fromArray(x: ByteArray) = StgArrayOffsetAddr(x, 0)
    val nullAddr: StgAddr = StgFFIAddr(0L)
  }

  // for now an Addr# is an offset into an array
  // TODO: make arr private & add more methods to avoid errors
  class StgArrayOffsetAddr(
          @JvmField val arr: ByteArray,
          @JvmField val offset: Int
  ): StgAddr() {
    override fun equals(other: Any?): Boolean = other is StgArrayOffsetAddr && arr === other.arr && offset == other.offset

    override fun getRange(x: IntRange): ByteArray = arr.copyOfRange(offset + x.first, offset + x.last + 1)
    override fun write(offset: Int, data: ByteArray) = arr.write(offset, data)
    override fun readByte(x: Int): Byte = arr[offset + x]
    override fun readInt(x: Int): Int = ByteBuffer.wrap(arr).getInt(offset + x)
    override fun readLong(x: Int): Long = ByteBuffer.wrap(arr).getLong(offset + x)
    override fun writeByte(x: Int, y: Byte) { arr[offset + x] = y }
    override fun writeInt(x: Int, y: Int) = arr.write(offset + x, y)
    override fun writeLong(x: Int, y: Long) = arr.write(offset + x, y)

    override fun addOffset(x: Int): StgAddr = StgArrayOffsetAddr(arr, offset + x)

    override fun asCString(): String = asArray().asCString()

    fun asArray() = arr.copyOfRange(offset, arr.size)

    operator fun get(ix: Int): Byte = arr[offset + ix]
    operator fun set(ix: StgInt, y: Byte) { arr[offset + ix.x.toInt()] = y }

  }

  @ExportLibrary(InteropLibrary::class)
  class StgFFIAddr(@JvmField val addr: Long): StgAddr(), TruffleObject {
    override fun equals(other: Any?): Boolean = other is StgFFIAddr && addr == other.addr
    override fun toString(): String = "StgFFIAddr($addr)"

    @ExportMessage fun isPointer(): Boolean = true
    @ExportMessage fun asPointer(): Long = addr

    // TODO: unsafe's doc implies it's undefined to use it on addresses not from the jvm, figure out if that is actually true and if so replace it with something else

    override fun getRange(x: IntRange): ByteArray {
      val size = x.last - x.first + 1
      val arr = ByteArray(size)
      unsafe.copyMemory(null, addr, arr, Unsafe.ARRAY_BYTE_BASE_OFFSET.toLong(), size.toLong())
      return arr
    }
    override fun write(offset: Int, data: ByteArray) { unsafe.copyMemory(data, (Unsafe.ARRAY_BYTE_BASE_OFFSET + offset).toLong(), null, addr, data.size.toLong()) }

    override fun readByte(x: Int): Byte = unsafe.getByte(addr + x)
    override fun readInt(x: Int): Int = unsafe.getInt(addr + x)
    override fun readLong(x: Int): Long = unsafe.getLong(addr + x)

    override fun writeByte(x: Int, y: Byte) = unsafe.putByte(addr + x, y)
    override fun writeInt(x: Int, y: Int) = unsafe.putInt(addr + x, y)
    override fun writeLong(x: Int, y: Long) = unsafe.putLong(addr + x, y)

    override fun addOffset(x: Int): StgAddr = StgFFIAddr(addr + x)

    override fun asCString(): String {
      TODO("Not yet implemented")
    }
  }
}


// afaict this guy can be mutable (& freeze & unfreeze operate on it)?
// :/
class StgArray(
  @JvmField val arr: Array<Any>
) {
  operator fun get(ix: StgInt): Any = arr[ix.toInt()]
  operator fun set(y: StgInt, value: Any) { arr[y.toInt()] = value }
}

sealed class StgByteArray {
  abstract fun asAddr(): StgAddr

  class StgJvmByteArray(@JvmField val arr: ByteArray) : StgByteArray() {
    override fun asAddr(): StgAddr = StgAddr.StgArrayOffsetAddr(arr, 0)
  }
  class StgPinnedByteArray(@JvmField val len: Long) : StgByteArray() {
    @JvmField val addr: Long = unsafe.allocateMemory(len)
    class StgFFIByteArrayCleanup(private val addr: Long) : Runnable {
      override fun run() { unsafe.freeMemory(addr) }
    }
    init { jdk.internal.ref.Cleaner.create(this, StgFFIByteArrayCleanup(addr)) }

    override fun asAddr(): StgAddr = StgAddr.StgFFIAddr(addr)
  }
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
  @JvmField @CompilerDirectives.CompilationFinal(dimensions = 1) val x: Array<Any>
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

