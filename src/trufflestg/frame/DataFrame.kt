package trufflestg.frame

typealias Slot = Int

// TODO: make this an abstract class: casting to superclasses should be faster than casting to interfaces
interface DataFrame {
  fun getValue(slot: Slot): Any?
}