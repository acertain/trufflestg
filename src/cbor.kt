
package trufflestg.cbor


import trufflestg.stg.Stg
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.nodes.ExplodeLoop
import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.*
import kotlin.reflect.*
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.javaMethod


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


class Cache<K, V>(
  val f: (K) -> V
) {
  val m: HashMap<K, V> = HashMap()
  operator fun get(k: K): V = m.getOrPut(k) { f(k) }
}

class CacheBy<K, I, V>(
  val ix: (K) -> I,
  val f: (K) -> V
) {
  val m: HashMap<I, V> = HashMap()
  operator fun get(k: K): V = m.getOrPut(ix(k)) { f(k) }
}

fun KClass<*>.isInline(): Boolean {
  return !isData &&
    primaryConstructor?.parameters?.size == 1 &&
    java.declaredMethods.any { it.name == "box-impl" }
}

// TODO: use https://docs.oracle.com/en/java/javase/14/docs/api/java.base/java/lang/ClassValue.html here?
val argsMap: Cache<KType,Array<KType?>> = Cache { it.arguments.map { x -> x.type }.toTypedArray() }
val paramsTypes: Cache<KCallable<*>, Array<KType>> = Cache { it.parameters.map { x -> x.type }.toTypedArray() }
val isEnumMap: Cache<Class<*>, Boolean> = Cache { Enum::class.java.isAssignableFrom(it) }
val isInlineMap: CacheBy<KClass<*>, Class<*>, Boolean> = CacheBy({ it.java }) { it.isInline() }
val primaryCtorMap: CacheBy<KClass<*>, Class<*>, KFunction<*>> = CacheBy({ it.java }) { it.primaryConstructor!! }

val longCls = Long::class.java
val intCls = Int::class.java
val byteArrayCls = ByteArray::class.java
val bigIntegerCls = BigInteger::class.java
val rationalCls = Stg.Rational::class.java
val pairCls = Pair::class.java
val tripleCls = Triple::class.java
val optionalCls = Optional::class.java
val stringCls = String::class.java
val booleanCls = Boolean::class.java

@ExperimentalUnsignedTypes
fun deserializeCbor(k: KType, x: Cbor): Any {
  val cls = k.classifier as KClass<*>
  val jcls = cls.java
  val args = argsMap[k]
//  val args = k.arguments
  return when {
    jcls === longCls -> x.long
    jcls === intCls -> x.long.toInt()
    jcls === byteArrayCls -> x.bytearray
    jcls === bigIntegerCls -> if (x.x is Long) BigInteger.valueOf(x.x) else TODO()
    jcls === rationalCls -> Stg.Rational(x[0].long, x[1].long)
    jcls.isArray -> {
      val cls2 = args[0]!!.classifier as KClass<*>
      val arr1 = x.array.map { deserializeCbor(args[0]!!, it) }.toTypedArray()
      val arr2 = java.lang.reflect.Array.newInstance(cls2.java, arr1.size)
      System.arraycopy(arr1, 0, arr2, 0, arr1.size)
      return arr2
    }
    jcls === pairCls -> Pair(deserializeCbor(args[0]!!,x[0]),deserializeCbor(args[1]!!,x[1]))
    jcls === tripleCls -> Triple(
      deserializeCbor(args[0]!!,x[0]),
      deserializeCbor(args[1]!!,x[1]),
      deserializeCbor(args[2]!!,x[2])
    )
    jcls === optionalCls ->
      if (x.array.isEmpty()) Optional.empty()
      else Optional.of(deserializeCbor(args[0]!!,x[0]))
    jcls === booleanCls -> {
      if (x.x !is SimpleValue) { throw BadCbor(k) }
      when (x.x.it.toInt()) {
        20 -> false
        21 -> true
        else -> throw BadCbor(k)
      }
    }
    jcls === stringCls -> if (x.x is String) x.x else String(x.bytearray)
    // newtype in haskell
    isInlineMap[cls] -> {
      val ctor = primaryCtorMap[cls]
      val ty = paramsTypes[ctor][0]
      val m = cls.java.methods.find { it.name == "box-impl" }!!
      m.invoke(null, deserializeCbor(ty,x))!!
    }
    isEnumMap[jcls] -> {
      if (x.array.size != 1) { throw BadCbor(k) }
      cls.java.enumConstants[x[0].long.toInt()]
    }
    cls.isData || cls.isSealed -> {
      val cls2 =
        if (cls.isSealed) cls.sealedSubclasses[x[0].long.toInt()]
        else { if (x[0].long != 0L) { throw BadCbor(k) }; cls }
      val ctor = primaryCtorMap[cls2]
      val xs = paramsTypes[ctor]
      if (xs.size + 1 != x.array.size ) { throw BadCbor(k) }
      val ys = xs.mapIndexed() { i, y -> deserializeCbor(y, x[i+1]) }.toTypedArray()
      ctor.call(*ys)!!
    }
    else -> TODO()
  }
}



