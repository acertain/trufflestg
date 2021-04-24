package trufflestg.frame

import trufflestg.panic
import org.intelligence.asm.*
import org.objectweb.asm.Type
import trufflestg.data.StgInt
import trufflestg.data.StgWord

sealed class FieldInfo(val sig: Char, val type: Type, val klass: Class<*>) {
  open val signature: String get() = sig.toString()
  abstract fun box(asm: Block)
  abstract fun unbox(asm: Block)
  abstract fun matches(o: Any?): Boolean
  companion object {
    fun of(c: Char) = when (c) {
      'I' -> intFieldInfo
      'F' -> floatFieldInfo
      'O' -> objectFieldInfo
      'L' -> longFieldInfo
      'D' -> doubleFieldInfo
      else -> panic("unknown field type")
    }
    fun from(x: Any?): FieldInfo = when (x) {
      is Int -> intFieldInfo
      is Float -> floatFieldInfo
      is Long -> longFieldInfo
      is Double -> doubleFieldInfo
      is StgInt -> stgIntFieldInfo
      is StgWord -> stgWordFieldInfo
      // TODO: think if i want to unbox any of the other types in stg_data.kt
      // also known class (to avoid isinstance checks)
      else -> objectFieldInfo
    }
  }
}

private object intFieldInfo : FieldInfo('I', int, Int::class.java) {
  override fun box(asm: Block) = asm.invokestatic(+Integer::class, +Integer::class, "valueOf", int)
  override fun unbox(asm: Block) = asm.invokevirtual(+Integer::class, int, "intValue")
  override fun matches(o: Any?): Boolean = o is Int
}

private object floatFieldInfo : FieldInfo('F', float, Float::class.java) {
  override fun box(asm: Block) = asm.invokestatic(+Float::class, +Float::class, "valueOf", float)
  override fun unbox(asm: Block) = asm.invokevirtual(+Float::class, float, "floatValue")
  override fun matches(o: Any?): Boolean = o is Float
}

object objectFieldInfo : FieldInfo('O', `object`, Object::class.java) {
  override fun box(asm: Block) {}
  override fun unbox(asm: Block) {}
  override fun matches(o: Any?): Boolean = true
  override val signature: String get() = "Ljava/lang/Object;"
}

private object longFieldInfo : FieldInfo('L', long, Long::class.java) {
  override fun box(asm: Block) = asm.invokestatic(+Long::class, +Long::class, "valueOf", long)
  override fun unbox(asm: Block) = asm.invokevirtual(+Long::class, long, "longValue")
  override fun matches(o: Any?): Boolean = o is Long
}

private object doubleFieldInfo : FieldInfo('D', double, Double::class.java) {
  override fun box(asm: Block) = asm.invokestatic(+Double::class, +Double::class, "valueOf", double)
  override fun unbox(asm: Block) = asm.invokevirtual(+Double::class, double, "doubleValue")
  override fun matches(o: Any?): Boolean = o is Double
}

object stgIntFieldInfo : FieldInfo('L', long, StgInt::class.java) {
  override fun box(asm: Block) = asm.invokestatic(+StgInt::class, +StgInt::class, "box", long)
  override fun unbox(asm: Block) = asm.invokevirtual(+StgInt::class, long, "unbox")
  override fun matches(o: Any?): Boolean = o is StgInt
}

object stgWordFieldInfo : FieldInfo('L', long, StgWord::class.java) {
  override fun box(asm: Block) = asm.invokestatic(+StgWord::class, +StgWord::class, "box", long)
  override fun unbox(asm: Block) = asm.invokevirtual(+StgWord::class, long, "unbox")
  override fun matches(o: Any?): Boolean = o is StgWord
}
