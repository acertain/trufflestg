package trufflestg.frame

import com.oracle.truffle.api.frame.FrameSlotTypeException

typealias Slot = Int
// these are all the distinctions the JVM cares about
// TODO: i'm currently only using getValue, and probably won't use any of the others
// TODO: make this an abstract class: casting to superclasses should be faster than casting to interfaces
interface DataFrame {
  fun getValue(slot: Slot): Any?
}