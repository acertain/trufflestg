// algebraic datatypes, by generating jvm bytecode
package trufflestg.data

import com.oracle.truffle.api.CompilerDirectives
import org.intelligence.asm.*
import trufflestg.frame.*
import trufflestg.panic
import trufflestg.stg.Stg
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
    DataConInfo(FullName(name.unitId, name.module, x.name), this, x.rep, ix)
  }.toTypedArray()

  // non-null if nth con has no arguments
  @CompilerDirectives.CompilationFinal(dimensions = 1)
  val zeroArgCons: Array<DataCon?> = cons.map { it.zeroArgCon }.toTypedArray()

  val numZeroArgCons: Int = zeroArgCons.filterNotNull().size
  // any non-nulls in singletons?
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
  val _info: DataConInfo,
  val tag: Int
) : DataCon() {
  override fun getInfo() = _info

  override fun isDouble(slot: Slot): Boolean = false
  override fun isFloat(slot: Slot): Boolean = false
  override fun isInteger(slot: Slot): Boolean = false
  override fun isLong(slot: Slot): Boolean = false
  override fun isObject(slot: Slot): Boolean = false

  override fun getValue(slot: Int) {
    CompilerDirectives.transferToInterpreter()
    throw IndexOutOfBoundsException()
  }
  override fun getDouble(slot: Slot): Double {
    CompilerDirectives.transferToInterpreter()
    throw IndexOutOfBoundsException()
  }
  override fun getFloat(slot: Slot): Float {
    CompilerDirectives.transferToInterpreter()
    throw IndexOutOfBoundsException()
  }
  override fun getInteger(slot: Slot): Int {
    CompilerDirectives.transferToInterpreter()
    throw IndexOutOfBoundsException()
  }
  override fun getLong(slot: Slot): Long {
    CompilerDirectives.transferToInterpreter()
    throw IndexOutOfBoundsException()
  }
  override fun getObject(slot: Slot): Any? {
    CompilerDirectives.transferToInterpreter()
    throw IndexOutOfBoundsException()
  }
}


class DataConInfo internal constructor(
  val name: FullName,
  val type: TyCon,
  // not neccesarially actually how it's stored
  val rep: Stg.DataConRep,
  // constructor index for dataToTag#
  val tag: Int
) {
  // number of fields
  val size: Int = when (rep) {
    is Stg.DataConRep.AlgDataCon -> rep.x.size
    is Stg.DataConRep.UnboxedTupleCon -> rep.x
  }

  // TODO: unbox fields, and set field type when we know it can't be a thunk (bang patterns)
  // ghc might not be telling us about bang patterns though :(
  val klass: Class<DataCon>? = if (size == 0) null else run {
    // TODO: better mangling
    fun mangle(x: String) = x.replace("""[\[\]]""".toRegex(), "_")
    val nm = mangle("trufflestg.types.${name.unitId}.${name.module}.${type.name.name}.${name.name}")
    val nm2 = nm.replace('.','/')
    val sig = "O".repeat(size)
    val kls = `class`(public, nm2) {
      superName = "trufflestg/data/DataCon"
      frameBody(sig, type(DataCon::class))
      method(public and final, +DataConInfo::class, "getInfo") { asm {
        getstatic(type(nm2), "info", +DataConInfo::class)
        areturn
      }}
      field(public and static, +DataConInfo::class, "info")
    }.loadClass(nm) { when (it) {
      "trufflestg.data.DataCon" -> DataCon::class.java
      "trufflestg.data.DataConInfo" -> DataConInfo::class.java
      else -> TODO(it)
    }}

    val field = kls.getDeclaredField("info")
    field.set(null, this)
    val modifiers = Field::class.java.getDeclaredField("modifiers")
    // TODO: make sure the final from here gets picked up by the jit
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
      }} as Class<DataFrameBuilder>
    builderKlass.constructors[0].newInstance() as DataFrameBuilder
  }

  // null if size > 0
  val zeroArgCon: ZeroArgDataCon? = if (size == 0) ZeroArgDataCon(this, tag) else null

  fun build(args: Array<Any>): DataCon =
    if (size == 0) zeroArgCon!! else builder!!.build(args) as DataCon
}





