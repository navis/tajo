/**
 * 
 */
package nta.engine.planner.physical;

import java.util.Comparator;

import nta.catalog.Schema;
import nta.datum.Datum;
import nta.engine.parser.QueryBlock.SortKey;
import nta.storage.Tuple;

/**
 * The Comparator class for Tuples
 * 
 * @author Hyunsik Choi
 * 
 * @see Tuple
 */
public class TupleComparator implements Comparator<Tuple> {
  private final int[] sortKeyIds;
  private final boolean[] asc;
  @SuppressWarnings("unused")
  private final boolean[] nullFirsts;

  private Datum left;
  private Datum right;
  private int compVal;

  public TupleComparator(Schema schema, SortKey[] sortKeys, boolean[] nullFirst) {
    this.sortKeyIds = new int[sortKeys.length];
    this.asc = new boolean[sortKeys.length];
    for (int i = 0; i < sortKeys.length; i++) {
      this.sortKeyIds[i] = schema.getColumn(sortKeys[i].getSortKey().getName())
          .getId();
      this.asc[i] = sortKeys[i].isAscending() == true ? true : false;
    }
    this.nullFirsts = nullFirst;
  }

  @Override
  public int compare(Tuple tuple1, Tuple tuple2) {
    for (int i = 0; i < sortKeyIds.length; i++) {
      left = tuple1.get(sortKeyIds[i]);
      right = tuple2.get(sortKeyIds[i]);
      if (asc[i]) {
        compVal = left.compareTo(right);
      } else {
        compVal = right.compareTo(left);
      }

      if (compVal == -1 || compVal == 1) {
        return compVal;
      }
    }
    return 0;
  }
}