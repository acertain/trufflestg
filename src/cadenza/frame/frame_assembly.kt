package cadenza.frame

// cadenza.aot?

import cadenza.array_utils.map
import cadenza.jit.Code
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.dsl.Cached
import com.oracle.truffle.api.dsl.Specialization
import com.oracle.truffle.api.frame.FrameSlotTypeException
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.nodes.Node
import org.intelligence.asm.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.LabelNode


// manufacture cadenza.frame.dynamic.IIO, OIO, etc.

// an immutable dataframe modeling the different backing storage types allowed by the jvm

// throw an exception type that has a default constructor
private fun assembleThrow(asm: Block, exceptionType: Type) = asm.run {
  new(exceptionType)
  dup
  invokespecial(exceptionType, void, "<init>")
  athrow
}

// TODO: add CompilerDirectives.ValueType to the generated class
fun frame(signature: String) : ByteArray = `class`(public,"cadenza/frame/dynamic/$signature") {
  interfaces = mutableListOf(type(DataFrame::class).internalName)
  val types = signature.map { FieldInfo.of(it) }.toTypedArray()
  val N = types.size
  val members = types.indices.map { "_$it" }.toTypedArray()

  fun isMethod(name: String, predicate: (FieldInfo) -> Boolean) {
    method(public and final, boolean, name, +Slot::class) {
      asm {
        when {
          types.all(predicate) -> { iconst_1; ireturn }
          !types.any(predicate) -> { iconst_0; ireturn }
          else -> {
            val no = LabelNode()
            val yes = LabelNode()
            iload_1
            lookupswitch(no, *types.mapIndexedNotNull { i, v -> if (predicate(v)) i to yes else null }.toTypedArray())
            add(no)
            iconst_0
            ireturn
            add(yes)
            iconst_1
            ireturn
          }
        }
      }
    }
  }

  fun getMethod(resultType: Type, name: String, predicate: (FieldInfo) -> Boolean) {
    method(public and final, resultType, name, +Slot::class) {
      throws(+FrameSlotTypeException::class)
      asm {
        types.mapIndexedNotNull { i, v -> if(predicate(v)) i to LabelNode() else null }.toTypedArray().let {
          if (it.isNotEmpty()) {
            val defaultLabel = LabelNode()
            aload_0
            iload_1
            lookupswitch(defaultLabel, *it)
            it.forEach { (i, label) ->
              add(label)
              val t = types[i]
              getfield(type, members[i], t.type)
              t.ret(this)
            }
            add(defaultLabel)
          }
        }
        assembleThrow(this, +FrameSlotTypeException::class)
      }
    }
  }

  for (i in types.indices)
    field(public and final,types[i].type,members[i])

  constructor(public, parameterTypes = *types.map{it.type}.toTypedArray()) {
    asm.`return` {
      aload_0
      invokespecial(type(Object::class), void, "<init>")
      for (i in types.indices) {
        aload_0
        types[i].load(this, i+1)
        putfield(type, members[i], types[i].type)
      }
    }
  }

  constructor(public, parameterTypes = *arrayOf(`object`.array)) {
    asm.`return` {
      aload_0
      invokespecial(type(Object::class), void, "<init>")
      iconst_0
      istore_2

      // FIXME: is this wrong? i think it needs to unbox? (only works for object as is?)
      for (i in types.indices) {
        aload_0
        aload_1
        iload_2
        types[i].aload(this)
        putfield(type, members[i], types[i].type)
        iinc(2)
      }
    }
  }

  isMethod("isInteger") { it.isInteger }
  isMethod("isLong") { it.isLong }
  isMethod("isFloat") { it.isFloat }
  isMethod("isDouble") { it.isDouble }

  getMethod(int, "getInteger") { it.isInteger }
  getMethod(long, "getLong") { it.isLong }
  getMethod(float, "getFloat") { it.isFloat }
  getMethod(double, "getDouble") { it.isDouble }
  getMethod(`object`, "getObject") { it.isObject }

  method(public and final, `object`, "getValue", +Slot::class) {
    asm {
      val defaultLabel = LabelNode()
      val labels = members.map { LabelNode() }.toTypedArray()
      aload_0
      iload_1
      tableswitch(0,N-1,defaultLabel,*labels)
      for (i in labels.indices) {
        add(labels[i])
        getfield(type,members[i],types[i].type)
        types[i].box(this)
        areturn
      }
      add(defaultLabel)
      assembleThrow(this, +IndexOutOfBoundsException::class)
    }
  }

  // purely for debugging
  method(public and final, int, "getSize") {
    asm.ireturn {
      push(types.size)
    }
  }
}

val child: AnnotationNode get() = annotationNode(+Node.Child::class)

val code = +Code::class

interface DataFrameBuilder { fun build(xs: Array<Any>): DataFrame }
fun factory(sig: String, cls: Class<DataFrame>): ByteArray = `class`(
  public and final, "cadenza/frame/dynamic/${sig}Builder") {
  interfaces = mutableListOf(type(DataFrameBuilder::class).internalName)
  constructor(public) { asm {
    aload_0
    invokespecial(type(Object::class), void, "<init>")
    `return`
  }}
  method(public and `final`, +DataFrame::class,"build", `object`.array) {
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


fun ByteArray.loadClassWith(className: String, lookup: (String) -> Class<*>?) : Class<*> {
  val classBuffer = this
  return object : ClassLoader(Int::class.java.classLoader) {
    override fun findClass(name: String): Class<*>? = when (name) {
      className -> defineClass(name, classBuffer, 0, classBuffer.size)
      else -> lookup(name)
    }
  }.loadClass(className)
}

fun ByteArray.loadClass(className: String,
    f: ((String) -> Class<*>) = { TODO("loadClass: unknown class $it") }): Class<*> =
  loadClassWith(className) {
    when (it) {
      "cadenza.frame.DataFrame" -> DataFrame::class.java
      "cadenza.frame.DataFrameBuilder" -> DataFrameBuilder::class.java
      "com.oracle.truffle.api.frame.FrameSlotTypeException" -> FrameSlotTypeException::class.java
      else -> f(it)
    }
  }


val builderCache: HashMap<String, DataFrameBuilder> = HashMap()

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
      val klass = frame(sig).loadClass("cadenza.frame.dynamic.$sig") as Class<DataFrame>
      val builderKlass = factory(sig, klass).loadClass("cadenza.frame.dynamic.${sig}Builder") {
        when (it) {
          "cadenza.frame.dynamic.$sig" -> klass
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