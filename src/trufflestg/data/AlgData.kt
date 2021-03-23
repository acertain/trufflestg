// algebraic datatypes, by generating jvm bytecode
package trufflestg.data

import com.oracle.truffle.api.CompilerDirectives
import org.intelligence.asm.*
import trufflestg.frame.*
import trufflestg.panic
import trufflestg.stg.Stg
import java.lang.reflect.Field
import java.lang.reflect.Modifier


// annoyingly the construction needs to be done here to tie the knot :(
class TyCon private constructor(
  val name: FullName,
  src: Stg.STyCon
) {
  val cons: Array<DataConInfo> = src.dataCons.mapIndexed { ix, x ->
    DataConInfo(FullName(name.unitId, name.module, x.name), this, x.rep, ix)
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

abstract class DataCon : DataFrame {
  // java doesn't have abstract fields, so we gotta do this :(
  abstract fun getInfo(): DataConInfo
}



class DataConInfo internal constructor(
  val name: FullName,
  val type: TyCon,
  // not actually how it's stored
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
  val klass: Class<DataCon> = run {
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

  private val builder: DataFrameBuilder = run {
    val builderKlass = factory(klass as Class<DataFrame>).loadClass("${klass.name}Builder") {
      when (it) {
        klass.name -> klass
        else -> TODO(it)
      }} as Class<DataFrameBuilder>
    builderKlass.constructors[0].newInstance() as DataFrameBuilder
  }

  // TODO: return singleton when size == 0, use ptr equality for size == 0 in AlgAlts
  fun build(args: Array<Any>): DataCon = builder.build(args) as DataCon
}





