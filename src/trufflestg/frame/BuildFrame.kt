package trufflestg.frame

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.dsl.Cached
import com.oracle.truffle.api.dsl.Specialization
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.nodes.Node
import org.intelligence.asm.*
import org.objectweb.asm.Type
import trufflestg.array_utils.map

// TODO:
//interface Builder<T> { fun build(xs: Array<Any>): T }

interface DataFrameBuilder { fun build(xs: Array<Any>): DataFrame }

fun factory(cls: Class<DataFrame>): ByteArray = `class`(
  public and final, "${cls.name}Builder".replace('.','/')) {
  interfaces = mutableListOf(type(DataFrameBuilder::class).internalName)
  constructor(public) {
    asm {
      aload_0
      invokespecial(type(Object::class), void, "<init>")
      `return`
    }
  }
  method(public and final, +DataFrame::class, "build", `object`.array) {
    asm {
      new(Type.getType(cls))
      dup
      aload_1
      invokespecial(Type.getType(cls), void, "<init>", `object`.array)
      checkcast(+DataFrame::class)
      areturn
    }
  }
}

val builderCache: HashMap<String, DataFrameBuilder> = HashMap()



// make a DataFrame from an Object[]
abstract class BuildFrame : Node() {
  abstract fun execute(fields: Array<Any>): DataFrame

  @Specialization(guards = ["matchesSig(fields, sig)"], limit = "1000000")
  fun build(
    fields: Array<Any>,
    @Cached("getSignature(fields)", dimensions = 1) sig: Array<FieldInfo>,
    @Cached("assembleSig(sig)") cstr: DataFrameBuilder
  ): DataFrame {
    return cstr.build(fields)
  }

  fun assembleSig(sigArr: Array<FieldInfo>): DataFrameBuilder {
    CompilerDirectives.transferToInterpreter()
    val sig = sigArr.map { it.sig }.joinToString("")
    var builder = builderCache[sig]

    if (builder === null) {
      val klass = frame(sig, "trufflestg/frame/dynamic/$sig").loadClass("trufflestg.frame.dynamic.$sig") as Class<DataFrame>
      val builderKlass = factory(klass).loadClass("trufflestg.frame.dynamic.${sig}Builder") {
        when (it) {
          "trufflestg.frame.dynamic.$sig" -> klass
          else -> TODO(it)
        }} as Class<DataFrameBuilder>
      builder = builderKlass.constructors[0].newInstance() as DataFrameBuilder
      builderCache[sig] = builder
    }
    return builder
  }

  @ExplodeLoop
  fun matchesSig(fields: Array<Any>, sig: Array<FieldInfo>): Boolean {
    sig.forEachIndexed { ix, v ->
      if (!v.matches(fields[ix])) {
        return false
      }
    }
    return true
  }

  fun getSignature(fields: Array<Any>): Array<FieldInfo> = map(fields) { FieldInfo.from(it) }
  fun equalsArray(x: ByteArray, y: ByteArray): Boolean = x.contentEquals(y)
}
