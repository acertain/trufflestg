package trufflestg.frame

import com.oracle.truffle.api.frame.FrameSlotTypeException

typealias Slot = Int
// these are all the distinctions the JVM cares about
// TODO: i'm currently only using getValue, and probably won't use any of the others
// TODO: make this an abstract class: casting to superclasses should be faster than casting to interfaces
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