// algebraic datatypes, by generating jvm bytecode
package trufflestg.data

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.frame.FrameSlotTypeException
import org.graalvm.nativeimage.ImageInfo
import org.intelligence.asm.*
import trufflestg.frame.*
import trufflestg.panic
import trufflestg.stg.Stg
import java.lang.ClassCastException
import java.lang.IndexOutOfBoundsException
import java.lang.reflect.Field
import java.lang.reflect.Modifier


// annoyingly the construction needs to be done here to tie the knot :(
class TyCon private constructor(
  val name: FullName,
  src: Stg.STyCon
) {
  @CompilerDirectives.CompilationFinal(dimensions = 1)
  val cons: Array<DataConInfo> = src.dataCons.mapIndexed { ix, x ->
    DataConInfo.build(x, FullName(name.unitId, name.module, x.name), this, ix)
  }.toTypedArray()

  // non-null if nth con has no arguments, used for tagToEnum#
  @CompilerDirectives.CompilationFinal(dimensions = 1)
  val zeroArgCons: Array<DataCon?> = cons.map { if (it is ZeroArgDataConInfo) it.singleton else null }.toTypedArray()

  val numZeroArgCons: Int = zeroArgCons.filterNotNull().size
  // any non-nulls in zeroArgCons?
  val hasZeroArgCons: Boolean = numZeroArgCons != 0

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

abstract class DataCon : DataFrame {
  // java doesn't have abstract fields, so we gotta do this :(
  abstract fun getInfo(): DataConInfo
  // TODO: add getTag to DataCon? only if i'm actually using it (how i decide to implement dataToTag#)
}

// used to optimize dataToTag# for zero-arg data cons
class ZeroArgDataCon(
  val _info: ZeroArgDataConInfo,
  val tag: Int
) : DataCon() {
  override fun getInfo() = _info

  override fun getValue(slot: Int) {
    CompilerDirectives.transferToInterpreter()
    throw IndexOutOfBoundsException()
  }
}

// TODO: use ArrayMappedDataFrame?
class ArrayDataCon(
  val _info: DataConInfo,
  val tag: Int,
  val args: Array<Any>
): DataCon() {
  override fun getInfo(): DataConInfo = _info
  override fun getValue(slot: Slot): Any? = args[slot]
}

abstract class DataConInfo(
  val src: Stg.SDataCon,
  val name: FullName,
  val type: TyCon,
  val tag: Int,
  val size: Int
) {
  abstract fun build(args: Array<Any>): DataCon
  // possibly very unsafe
  abstract fun unsafeCast(x: Any?): DataCon
  abstract fun tryIs(x: Any?): DataCon?

  companion object {
    fun build(src: Stg.SDataCon, name: FullName, type: TyCon, tag: Int): DataConInfo {
      // number of fields
      val size: Int = when (src.rep) {
        is Stg.DataConRep.AlgDataCon -> src.rep.x.size
        is Stg.DataConRep.UnboxedTupleCon -> src.rep.x
      }
      return when {
        size == 0 -> ZeroArgDataConInfo(src, name, type, tag, size)
        !ImageInfo.inImageCode() -> DynamicClassDataConInfo(src, name, type, tag, size)
        else -> ArrayDataConInfo(src, name, type, tag, size)
      }
    }
  }
}

class ZeroArgDataConInfo(
  src: Stg.SDataCon,
  name: FullName,
  type: TyCon,
  tag: Int,
  size: Int
) : DataConInfo(src, name, type, tag, size) {
  val singleton = ZeroArgDataCon(this, tag)

  override fun build(args: Array<Any>): DataCon = singleton
  override fun unsafeCast(x: Any?): DataCon = singleton
  override fun tryIs(x: Any?): DataCon? = if (x === singleton) singleton else null
}

class ArrayDataConInfo(
  src: Stg.SDataCon,
  name: FullName,
  type: TyCon,
  tag: Int,
  size: Int
): DataConInfo(src, name, type, tag, size) {
  override fun build(args: Array<Any>): DataCon = ArrayDataCon(this, tag, args)
  override fun unsafeCast(x: Any?): DataCon = CompilerDirectives.castExact(x, ArrayDataCon::class.java)
  override fun tryIs(x: Any?): DataCon? = if (x is ArrayDataCon && x._info === this && x.tag == tag) x else null
}

class DynamicClassDataConInfo(
  src: Stg.SDataCon,
  name: FullName,
  type: TyCon,
  // constructor index for dataToTag#
  tag: Int,
  size: Int
) : DataConInfo(src, name, type, tag, size) {
  override fun build(args: Array<Any>): DataCon = builder!!.build(args) as DataCon
  // TODO: could maybe use OptimizedCallTarget.unsafeCast here
  override fun unsafeCast(x: Any?): DataCon = CompilerDirectives.castExact(x, klass)
  override fun tryIs(x: Any?): DataCon? =
    // TODO: use CompilerDirectives.isExact once its released
    if (klass!!.isInstance(x)) CompilerDirectives.castExact(x, klass) else null

  private val klass: Class<DataCon>? = if (size == 0) null else run {
    // TODO: better mangling
    fun mangle(x: String) = x.replace("""[\[\]]""".toRegex(), "_")
    val nm = mangle("trufflestg.types.${name.unitId}.${name.module}.${type.name.name}.${name.name}")
    val nm2 = nm.replace('.','/')
    val sig: Array<FieldInfo> = when (src.rep) {
      is Stg.DataConRep.UnboxedTupleCon -> Array(src.rep.x) { objectFieldInfo }
        // TODO:
        // panic("attempt to build an unboxed tuple data con?")
      is Stg.DataConRep.AlgDataCon -> src.rep.x.map { when (it) {
        is Stg.PrimRep.IntRep -> stgIntFieldInfo
        is Stg.PrimRep.WordRep -> stgWordFieldInfo
        is Stg.PrimRep.LiftedRep -> objectFieldInfo
        // TODO: set field type when we know it (bang patterns)
        // need to add bang pattern & type info to ghc-wpc
        else -> {
//          println("todo PrimRep $it")
          objectFieldInfo
        }
      } }.toTypedArray()
    }
    val kls = `class`(public, nm2) {
      superName = "trufflestg/data/DataCon"
      frameBody(sig, type(DataCon::class))
      method(public and final, +DataConInfo::class, "getInfo") { asm {
        getstatic(type(nm2), "_info", +DataConInfo::class)
        areturn
      }}
      field(public and static, +DataConInfo::class, "_info")
    }.loadClass(nm) { when (it) {
      "trufflestg.data.DataCon" -> DataCon::class.java
      "trufflestg.data.DataConInfo" -> DataConInfo::class.java
      "trufflestg.data.StgInt" -> StgInt::class.java
      "trufflestg.data.StgWord" -> StgWord::class.java
      else -> TODO(it)
    }}

    // TODO: figure out a better way to do this (set _info = this for kls)
    val field = kls.getDeclaredField("_info")
    field.set(null, this)
    val modifiers = Field::class.java.getDeclaredField("modifiers")
    modifiers.isAccessible = true
    modifiers.setInt(field, field.modifiers and Modifier.FINAL)
    modifiers.isAccessible = false

    kls as Class<DataCon>
  }

  private val builder: DataFrameBuilder? = if (size == 0) null else run {
    val builderKlass = factory(klass as Class<DataFrame>).loadClass("${klass.name}Builder") {
      when (it) {
        klass.name -> klass
        else -> TODO(it)
      }}
    builderKlass.constructors[0].newInstance() as DataFrameBuilder
  }
}





