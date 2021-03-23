package trufflestg.frame

// cadenza.aot?

import trufflestg.array_utils.map
import trufflestg.jit.Code
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.dsl.Cached
import com.oracle.truffle.api.dsl.Specialization
import com.oracle.truffle.api.frame.FrameSlotTypeException
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.nodes.Node
import org.intelligence.asm.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LabelNode


// manufacture trufflestg.frame.dynamic.IIO, OIO, etc.

// an immutable dataframe modeling the different backing storage types allowed by the jvm

// throw an exception type that has a default constructor
private fun assembleThrow(asm: Block, exceptionType: Type) = asm.run {
  new(exceptionType)
  dup
  invokespecial(exceptionType, void, "<init>")
  athrow
}

fun ClassNode.frameBody(signature: String, superCls: Type = type(Object::class)) {
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
      invokespecial(superCls, void, "<init>")
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

      invokespecial(superCls, void, "<init>")
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
    if (N == 0) {
      asm {
        assembleThrow(this, +IndexOutOfBoundsException::class)
      }
    } else {
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
  }

  // purely for debugging
  method(public and final, int, "getSize") {
    asm.ireturn {
      push(types.size)
    }
  }
}

// TODO: add CompilerDirectives.ValueType to the generated class
fun frame(signature: String, name: String) : ByteArray = `class`(public,name) {
  frameBody(signature)
}

val child: AnnotationNode get() = annotationNode(+Node.Child::class)

val code = +Code::class



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
      "trufflestg.frame.DataFrame" -> DataFrame::class.java
      "trufflestg.frame.DataFrameBuilder" -> DataFrameBuilder::class.java
      "com.oracle.truffle.api.frame.FrameSlotTypeException" -> FrameSlotTypeException::class.java
      else -> f(it)
    }
  }


