package trufflestg.frame

import com.oracle.truffle.api.frame.FrameSlotTypeException

typealias Slot = Int

// these are all the distinctions the JVM cares about
// TODO: add rest of prim fields? i'm currently only using getValue
// should think about if i will actually use anything else
// as is, i make sure getValue inlines, so it's all i need
// afaict the only reason to use the other variants is megamorphic sites where i've only seen one or two types for a slot,
// so calling the specialized getInteger is faster than getValue
// can i do better for such sites?
// ideally, i'd branch on the value of the nth slot, maybe i have a getTag, then use unsafe based on the tag?
// but this is problematic for nonuniform shapes...
// maybe i can use the popcount-based indexing + store the bitmask in the klass?
// TODO: make this an abstract class? casting to superclasses might be faster than casting to interfaces?
interface DataFrame {
  abstract fun getValue(slot: Slot): Any?

  @Throws(FrameSlotTypeException::class)
  abstract fun getDouble(slot: Slot): Double // D
  abstract fun isDouble(slot: Slot): Boolean

  @Throws(FrameSlotTypeException::class)
  abstract fun getFloat(slot: Slot): Float // F
  abstract fun isFloat(slot: Slot): Boolean

  @Throws(FrameSlotTypeException::class)
  abstract fun getInteger(slot: Slot): Int // I
  abstract fun isInteger(slot: Slot): Boolean

  @Throws(FrameSlotTypeException::class)
  abstract fun getLong(slot: Slot): Long // L
  abstract fun isLong(slot: Slot): Boolean

  @Throws(FrameSlotTypeException::class)
  abstract fun getObject(slot: Slot): Any? // O
  abstract fun isObject(slot: Slot): Boolean
}