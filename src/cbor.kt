
package trufflestg.cbor


import trufflestg.stg.Stg
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.nodes.ExplodeLoop
import trufflestg.Memoize
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

fun KClass<*>.isInline(): Boolean {
  return !isData &&
    primaryConstructor?.parameters?.size == 1 &&
    java.declaredMethods.any { it.name == "box-impl" }
}

val deserializeCbor = Memoize(::deserializeCborInner)

class HashCons<T> {
  val m: MutableMap<T, T> = mutableMapOf()

  operator fun get(k: T): T {
    if (k in m) return m[k]!!
    m[k] = k
    return k
  }
}

class TruffleCache<K, V>(
  val f: (K) -> V
) {
  val hashConsKeys = HashCons<K>()
  val hashConsValues = HashCons<V>()

  @CompilerDirectives.CompilationFinal(dimensions = 1) var entries: Array<Pair<K, V>> = arrayOf()
  @ExplodeLoop
  operator fun get(k: K): V {
    for ((x,y) in entries) {
      if (x === k) return y
    }
    CompilerDirectives.transferToInterpreterAndInvalidate()
    val k2 = hashConsKeys[k]
    val y = hashConsValues[f(k2)]
    entries += Pair(k2, y)
    return y
  }
}

val clsifierMap: TruffleCache<KType,KClass<*>> = TruffleCache { it.classifier as KClass<*> }
val argsMap: TruffleCache<KType,Array<KType?>> = TruffleCache { it.arguments.map { x -> x.type }.toTypedArray() }
val sealedSubclassesMap: TruffleCache<KClass<*>,Array<KClass<*>>> = TruffleCache { it.sealedSubclasses.toTypedArray() }
val paramsTypes: TruffleCache<KCallable<*>, Array<KType>> = TruffleCache { it.parameters.map { x -> x.type }.toTypedArray() }
val isEnumMap: TruffleCache<KClass<*>, Boolean> = TruffleCache { it.isSubclassOf(Enum::class) }
val primaryCtorMap: TruffleCache<KClass<*>, KFunction<*>> = TruffleCache { it.primaryConstructor!! }
val ctorMap: TruffleCache<KFunction<*>, java.lang.reflect.Constructor<*>> = TruffleCache { it.javaConstructor!! }
val methMap: TruffleCache<KFunction<*>, java.lang.reflect.Method> = TruffleCache { it.javaMethod!! }

val longCls = Long::class
val intCls = Int::class
val byteArrayCls = ByteArray::class
val bigIntegerCls = BigInteger::class
// FIXME: haskell's serializing as Rationals, but this is just extended-precision floats
val rationalCls = Stg.Rational::class
val pairCls = Pair::class
val tripleCls = Triple::class
val optionalCls = Optional::class
val stringCls = String::class
val booleanCls = Boolean::class

@ExperimentalUnsignedTypes
fun deserializeCborInner(k: KType, x: Cbor): Any {
  val cls = clsifierMap[k]
  val args = argsMap[k]
//  val args = k.arguments
  return when {
    cls == longCls -> x.long
    cls == intCls -> x.long.toInt()
    cls == byteArrayCls -> x.bytearray
    cls == bigIntegerCls -> if (x.x is Long) BigInteger.valueOf(x.x) else TODO()
    cls == rationalCls -> Stg.Rational(x[0].long, x[1].long)
    cls.java.isArray -> {
      val cls2 = clsifierMap[args[0]!!]
      val arr1 = x.array.map { deserializeCbor(args[0]!!, it) }.toTypedArray()
      val arr2 = java.lang.reflect.Array.newInstance(cls2.java, arr1.size)
      System.arraycopy(arr1, 0, arr2, 0, arr1.size)
      return arr2
    }
    cls == pairCls -> Pair(deserializeCbor(args[0]!!,x[0]),deserializeCbor(args[1]!!,x[1]))
    cls == tripleCls -> Triple(
      deserializeCbor(args[0]!!,x[0]),
      deserializeCbor(args[1]!!,x[1]),
      deserializeCbor(args[2]!!,x[2])
    )
    cls == optionalCls ->
      if (x.array.isEmpty()) Optional.empty()
      else Optional.of(deserializeCbor(args[0]!!,x[0]))
    cls == booleanCls -> {
      if (x.x !is SimpleValue) { throw BadCbor(k) }
      when (x.x.it.toInt()) {
        20 -> false
        21 -> true
        else -> throw BadCbor(k)
      }
    }
    cls == stringCls -> if (x.x is String) x.x else String(x.bytearray)
    // newtype in haskell
    cls.isInline() -> {
      val ctor = primaryCtorMap[cls]
      val ty = paramsTypes[ctor][0]
      val m = cls.java.methods.find { it.name == "box-impl" }!!
      m.invoke(null, deserializeCbor(ty,x))!!
    }
    isEnumMap[cls] -> {
      if (x.array.size != 1) { throw BadCbor(k) }
      cls.java.enumConstants[x[0].long.toInt()]
    }
    cls.isData || cls.isSealed -> {
      val cls2 =
        if (cls.isSealed) sealedSubclassesMap[cls][x[0].long.toInt()]
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



