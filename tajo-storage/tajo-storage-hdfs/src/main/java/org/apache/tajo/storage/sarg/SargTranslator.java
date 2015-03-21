package org.apache.tajo.storage.sarg;

import java.util.List;

public interface SargTranslator<T> {

  T translate(List<PredicateLeaf> leafs);
}
