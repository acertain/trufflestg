package stg.nodes;

import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.GenerateWrapper;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.ProbeNode;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

import com.oracle.truffle.api.nodes.UnexpectedResultException;

@NodeInfo(shortName = "argi")
public class StgArgInt extends StgArgNode {
  int value;
  public StgArgInt(int value) { this.value = value; }

  @Override
  public Object execute(@SuppressWarnings("unused") VirtualFrame frame) { return value; }

  @Override
  public int executeInteger(@SuppressWarnings("unused") VirtualFrame frame) throws UnexpectedResultException { return value; } 
}