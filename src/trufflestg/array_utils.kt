
package trufflestg.array_utils

import java.nio.ByteBuffer

@OptIn(ExperimentalUnsignedTypes::class)
fun UInt.toByteArray(): ByteArray = toInt().toByteArray()
fun Long.toByteArray(): ByteArray = ByteBuffer.allocate(8).putLong(this).array()
fun Int.toByteArray(): ByteArray = ByteBuffer.allocate(4).putInt(this).array()

fun ByteArray.write(offset: Int, x: ByteArray) { System.arraycopy(x, 0, this, offset, x.size) }
fun ByteArray.write(offset: Int, x: Long) { write(offset,x.toByteArray()) }
fun ByteArray.write(offset: Int, x: Int) { write(offset,x.toByteArray()) }
@OptIn(ExperimentalUnsignedTypes::class)
fun ByteArray.write(offset: Int, x: UInt) { write(offset,x.toByteArray()) }




// more efficient or truffle friendly of standard array functions:

fun append(xs: Array<out Any>, ys: Array<out Any>): Array<Any> = appendL(xs, xs.size, ys, ys.size)
fun consAppend(x: Any, xs: Array<out Any>, ys: Array<out Any>): Array<Any> = consAppendL(x, xs, xs.size, ys, ys.size)
private fun cons(x: Any, xs: Array<out Any?>): Array<Any?> = consL(x, xs, xs.size)

// kotlin emits null checks in fn preamble for all nullable args
// here it effects dispatch fast path, so xs & ys need to be nullable
fun appendL(xs: Array<out Any>?, xsSize: Int, ys: Array<out Any>?, ysSize: Int): Array<Any> {
  val zs = arrayOfNulls<Any>(xsSize + ysSize)
  System.arraycopy(xs, 0, zs, 0, xsSize)
  System.arraycopy(ys, 0, zs, xsSize, ysSize)
  return zs as Array<Any>
}

fun consAppendL(x: Any, xs: Array<out Any>?, xsSize: Int, ys: Array<out Any>?, ysSize: Int): Array<Any> {
  val zs = appendLSkip(1, xs, xsSize, ys, ysSize)
  zs[0] = x
  return zs
}

fun appendLSkip(skip: Int, xs: Array<out Any>?, xsSize: Int, ys: Array<out Any>?, ysSize: Int): Array<Any> {
  val zs = arrayOfNulls<Any>(skip + xsSize + ysSize)
  System.arraycopy(xs, 0, zs, skip, xsSize)
  System.arraycopy(ys, 0, zs, skip + xsSize, ysSize)
  return zs as Array<Any>
}

fun consL(x: Any, xs: Array<out Any?>, xsSize: Int): Array<Any?> {
  val ys = arrayOfNulls<Any>(xsSize + 1)
  ys[0] = x
  System.arraycopy(xs, 0, ys, 1, xsSize)
  return ys
}


private fun consTake(x: Any, n: Int, xs: Array<out Any?>): Array<Any?> {
  val ys = arrayOfNulls<Any>(n + 1)
  ys[0] = x
  System.arraycopy(xs, 0, ys, 1, n)
  return ys
}

fun drop(k: Int, xs: Array<out Any>): Array<Any> {
  val ys = arrayOfNulls<Any>(xs.size - k)
  System.arraycopy(xs, k, ys, 0, xs.size - k)
  return ys as Array<Any>
}

inline fun<reified T> take(k: Int, xs: Array<out T>): Array<T> {
  val ys = arrayOfNulls<T>(k)
  System.arraycopy(xs, 0, ys, 0, k)
  return ys as Array<T>
}


inline fun<S, reified T> map(xs: Array<out S>, f: (x: S) -> T): Array<T> {
  val ys = arrayOfNulls<T>(xs.size)
  xs.forEachIndexed { ix, x -> ys[ix] = f(x) }
  return ys as Array<T>
}

