package trufflestg.frame

class SimpleDataFrame(vararg val data: Any?) : DataFrame {
  override fun getValue(slot: Slot) = data[slot]
}
