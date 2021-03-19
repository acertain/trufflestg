package cadenza.stg


import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.typeOf


@ExperimentalUnsignedTypes
fun decodeCbor(x: ByteBuffer): Any {
  val b = x.get().toUByte().toInt()
  val mt = b shr 5
  val ai = b and 0x1f

  val arg: Long? = when (ai) {
    24 -> x.get().toUByte().toLong()
    25 -> x.getShort().toUShort().toLong()
    26 -> x.getInt().toUInt().toLong()
    27 -> x.getLong()
    31 -> null
    28, 29, 30 -> throw Exception("invalid cbor")
    else -> ai.toLong()
  }

  return when (mt) {
    0 -> arg!!
    1 -> -1 - arg!!
    2 -> if (arg != null) {
      val l = arg.toInt()
      val arr = ByteArray(l)
      x.get(arr, 0, l)
      arr
    } else {
      TODO()
    }
    3 -> if (arg != null) {
      val l = arg.toInt()
      val arr = ByteArray(l)
      x.get(arr, 0, l)
      String(arr)
    } else {
      TODO()
    }
    4 ->
      if (arg != null) { Array(arg.toInt()) { decodeCbor(x) } }
      else {
        val arr = mutableListOf<Any>()
        while (x.get(x.position()) != 0xff.toByte()) {
          val y = decodeCbor(x)
          arr += y
        }
        x.position(x.position() + 1)
        arr.toTypedArray()
      }
    7 -> when {
      arg == null -> throw Exception("invalid cbor")
      arg <= 23 -> SimpleValue(arg.toUByte())
      else -> TODO()
    }
    else -> {
      print(Pair(mt, arg))
      TODO()
    }
  }
}

data class SimpleValue(val it: UByte)



class Cbor(val x: Any?) {
  operator fun get(y: Int): Cbor = Cbor((x as Array<*>)[y])

  val long: Long
    get() = x as Long
  val array: Array<Cbor>
    get() = (x as Array<*>).map{ Cbor(it) }.toTypedArray()
  val string: String
    get() = x as String
  val bytearray: ByteArray
    get() = x as ByteArray
}


class BadCbor(k: KType) : Exception()

fun KClass<*>.isInline(): Boolean {
  return !isData &&
    primaryConstructor?.parameters?.size == 1 &&
    java.declaredMethods.any { it.name == "box-impl" }
}

@ExperimentalUnsignedTypes
fun deserializeCbor(k: KType, x: Cbor): Any {
  if (k.classifier !is KClass<*>) {throw Exception("bad deserialize call")}
  val cls = k.classifier as KClass<*>
  val args = k.arguments
  return when {
    cls == Long::class -> x.long
    cls == Int::class -> x.long.toInt()
    cls == ByteArray::class -> x.bytearray
    cls == BigInteger::class -> if (x.x is Long) BigInteger.valueOf(x.x) else TODO()
    cls.java.isArray -> {
      val cls2 = args[0].type!!.classifier as KClass<*>
      val arr1 = x.array.map { deserializeCbor(args[0].type!!, it) }.toTypedArray()
      val arr2 = java.lang.reflect.Array.newInstance(cls2.java, arr1.size)
      System.arraycopy(arr1, 0, arr2, 0, arr1.size)
      return arr2
    }
    cls == Pair::class -> Pair(deserializeCbor(args[0].type!!,x[0]),deserializeCbor(args[1].type!!,x[1]))
    cls == Triple::class -> Triple(
      deserializeCbor(args[0].type!!,x[0]),
      deserializeCbor(args[1].type!!,x[1]),
      deserializeCbor(args[2].type!!,x[2])
    )
    cls == Optional::class ->
      if (x.array.isEmpty()) Optional.empty()
      else Optional.of(deserializeCbor(args[0].type!!,x[0]))
    cls == Boolean::class -> {
      if (x.x !is SimpleValue) { throw BadCbor(k) }
      when (x.x.it.toInt()) {
        20 -> false
        21 -> true
        else -> throw BadCbor(k)
      }
    }
    cls == String::class -> if (x.x is String) x.x else String(x.bytearray)
    // newtype in haskell
    cls.isInline() -> {
      val ctor = cls.primaryConstructor!!
      val ty = ctor.parameters[0].type
      ctor.call(deserializeCbor(ty,x))
    }
    cls.isSubclassOf(Enum::class) -> {
      if (x.array.size != 1) { throw BadCbor(k) }
      cls.java.enumConstants[x[0].long.toInt()]
    }
    cls.isData || cls.isSealed -> {
      val cls2 =
        if (cls.isSealed) cls.sealedSubclasses[x[0].long.toInt()]
        else { if (x[0].long != 0L) { throw BadCbor(k) }; cls }
      val ctor = cls2.primaryConstructor!!
      val xs = ctor.parameters
      if (xs.size + 1 != x.array.size ) { throw BadCbor(k) }
      val ys = xs.mapIndexed() { i, y -> deserializeCbor(y.type, x[i+1]) }.toTypedArray()
      ctor.call(*ys)
    }
    else -> TODO()
  }
}

