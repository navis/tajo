/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tajo.storage.sarg;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tajo.algebra.BinaryOperator;
import org.apache.tajo.catalog.CatalogUtil;
import org.apache.tajo.catalog.FunctionDesc;
import org.apache.tajo.common.TajoDataTypes;
import org.apache.tajo.datum.DateDatum;
import org.apache.tajo.datum.Datum;
import org.apache.tajo.plan.expr.BetweenPredicateEval;
import org.apache.tajo.plan.expr.BinaryEval;
import org.apache.tajo.plan.expr.ConstEval;
import org.apache.tajo.plan.expr.EvalNode;
import org.apache.tajo.plan.expr.EvalType;
import org.apache.tajo.plan.expr.FieldEval;
import org.apache.tajo.plan.expr.FunctionEval;
import org.apache.tajo.plan.expr.InEval;
import org.apache.tajo.plan.expr.IsNullEval;
import org.apache.tajo.plan.expr.RowConstantEval;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The implementation of SearchArguments.
 *
 * Shamelessly copied from hive. All credits belong to them
 */
public class TajoSearchArgument implements SearchArgument {

  private static final Log LOG = LogFactory.getLog(TajoSearchArgument.class);

  static final class PredicateLeafImpl implements PredicateLeaf {
    private final Operator operator;
    private final Type type;
    private final String columnName;
    private final Object literal;
    private final List<Object> literalList;

    PredicateLeafImpl() {
      operator = null;
      type = null;
      columnName = null;
      literal = null;
      literalList = null;
    }

    PredicateLeafImpl(Operator operator,
                      Type type,
                      String columnName,
                      Object literal,
                      List<Object> literalList) {
      this.operator = operator;
      this.type = type;
      this.columnName = columnName;
      this.literal = literal;
      this.literalList = literalList;
    }

    @Override
    public Operator getOperator() {
      return operator;
    }

    @Override
    public Type getType(){
      return type;
    }

    @Override
    public String getColumnName() {
      return columnName;
    }

    @Override
    public Object getLiteral(FileFormat format) {
      // To get around a kryo 2.22 bug while deserialize a Timestamp into Date
      // (https://github.com/EsotericSoftware/kryo/issues/88)
      // When we see a Date, convert back into Timestamp
      if (literal instanceof java.util.Date) {
        return new Timestamp(((java.util.Date) literal).getTime());
      }

      switch (format) {
        case ORC:
          // adapt base type to what orc needs
          if (literal instanceof Integer) {
            return Long.valueOf(literal.toString());
          }
          return literal;
        case PARQUET:
          return literal;
        default:
          throw new RuntimeException(
            "File format " + format + "is not support to build search arguments");
      }
    }

    @Override
    public List<Object> getLiteralList(FileFormat format) {
      switch (format) {
        case ORC:
          return getOrcLiteralList();
        case PARQUET:
          return getParquetLiteralList();
        default:
          throw new RuntimeException("File format is not support to build search arguments");
      }
    }

    private List<Object> getOrcLiteralList() {
      // no need to cast
      if (literalList == null || literalList.size() == 0 || !(literalList.get(0) instanceof
          Integer)) {
        return literalList;
      }
      List<Object> result = new ArrayList<Object>();
      for (Object o : literalList) {
        result.add(Long.valueOf(o.toString()));
      }
      return result;
    }

    private List<Object> getParquetLiteralList() {
      return literalList;
    }

    @Override
    public String toString() {
      StringBuilder buffer = new StringBuilder();
      buffer.append('(');
      buffer.append(operator);
      buffer.append(' ');
      buffer.append(columnName);
      if (literal != null) {
        buffer.append(' ');
        buffer.append(literal);
      } else if (literalList != null) {
        for(Object lit: literalList) {
          buffer.append(' ');
          buffer.append(lit.toString());
        }
      }
      buffer.append(')');
      return buffer.toString();
    }

    private static boolean isEqual(Object left, Object right) {
      if (left == right) {
        return true;
      } else if (left == null || right == null) {
        return false;
      } else {
        return left.equals(right);
      }
    }

    @Override
    public boolean equals(Object other) {
      if (other == null || other.getClass() != getClass()) {
        return false;
      } else if (other == this) {
        return true;
      } else {
        PredicateLeafImpl o = (PredicateLeafImpl) other;
        return operator == o.operator &&
            type == o.type &&
            columnName.equals(o.columnName) &&
            isEqual(literal, o.literal) &&
            isEqual(literalList, o.literalList);
      }
    }

    @Override
    public int hashCode() {
      return operator.hashCode() +
             type.hashCode() * 17 +
             columnName.hashCode() * 3 * 17+
             (literal == null ? 0 : literal.hashCode()) * 101 * 3 * 17 +
             (literalList == null ? 0 : literalList.hashCode()) *
                 103 * 101 * 3 * 17;
    }
  }

  static class ExpressionTree {
    static enum Operator {OR, AND, NOT, LEAF, CONSTANT}
    private final Operator operator;
    private final List<ExpressionTree> children;
    private final int leaf;
    private final TruthValue constant;

    ExpressionTree() {
      operator = null;
      children = null;
      leaf = 0;
      constant = null;
    }

    ExpressionTree(Operator op, ExpressionTree... kids) {
      operator = op;
      children = new ArrayList<ExpressionTree>();
      leaf = -1;
      this.constant = null;
      Collections.addAll(children, kids);
    }

    ExpressionTree(int leaf) {
      operator = Operator.LEAF;
      children = null;
      this.leaf = leaf;
      this.constant = null;
    }

    ExpressionTree(TruthValue constant) {
      operator = Operator.CONSTANT;
      children = null;
      this.leaf = -1;
      this.constant = constant;
    }

    ExpressionTree(ExpressionTree other) {
      this.operator = other.operator;
      if (other.children == null) {
        this.children = null;
      } else {
        this.children = new ArrayList<ExpressionTree>();
        for(ExpressionTree child: other.children) {
          children.add(new ExpressionTree(child));
        }
      }
      this.leaf = other.leaf;
      this.constant = other.constant;
    }

    TruthValue evaluate(TruthValue[] leaves) {
      TruthValue result = null;
      switch (operator) {
        case OR:
          for(ExpressionTree child: children) {
            result = child.evaluate(leaves).or(result);
          }
          return result;
        case AND:
          for(ExpressionTree child: children) {
            result = child.evaluate(leaves).and(result);
          }
          return result;
        case NOT:
          return children.get(0).evaluate(leaves).not();
        case LEAF:
          return leaves[leaf];
        case CONSTANT:
          return constant;
        default:
          throw new IllegalStateException("Unknown operator: " + operator);
      }
    }

    private boolean isMultiLiteralsOperator(PredicateLeaf.Operator op) {
      return (op == PredicateLeaf.Operator.IN) || (op == PredicateLeaf.Operator.BETWEEN);
    }

    @Override
    public String toString() {
      StringBuilder buffer = new StringBuilder();
      switch (operator) {
        case OR:
          buffer.append("(or");
          for(ExpressionTree child: children) {
            buffer.append(' ');
            buffer.append(child.toString());
          }
          buffer.append(')');
          break;
        case AND:
          buffer.append("(and");
          for(ExpressionTree child: children) {
            buffer.append(' ');
            buffer.append(child.toString());
          }
          buffer.append(')');
          break;
        case NOT:
          buffer.append("(not ");
          buffer.append(children.get(0));
          buffer.append(')');
          break;
        case LEAF:
          buffer.append("leaf-");
          buffer.append(leaf);
          break;
        case CONSTANT:
          buffer.append(constant);
          break;
      }
      return buffer.toString();
    }

    Operator getOperator() {
      return operator;
    }

    List<ExpressionTree> getChildren() {
      return children;
    }
  }

  static class ExpressionBuilder {
    // max threshold for CNF conversion. having >8 elements in andList will be converted to maybe
    private static final int CNF_COMBINATIONS_THRESHOLD = 256;
    private final List<PredicateLeaf> leaves = new ArrayList<PredicateLeaf>();

    /**
     * Get the type of the given expression node.
     * @param expr the expression to get the type of
     * @return int, string, or float or null if we don't know the type
     */
    private static PredicateLeaf.Type getType(EvalNode expr) {
      return getType(expr.getValueType());
    }

    private static PredicateLeaf.Type getType(TajoDataTypes.DataType type) {
      switch (type.getType()) {
        case BIT:
        case INT1:
        case INT2:
        case INT4:
          return PredicateLeaf.Type.INTEGER;
        case INT8:
          return PredicateLeaf.Type.LONG;
        case CHAR:
        case VARCHAR:
        case TEXT:
          return PredicateLeaf.Type.STRING;
        case FLOAT4:
        case FLOAT8:
          return PredicateLeaf.Type.FLOAT;
        case DATE:
          return PredicateLeaf.Type.DATE;
        case TIMESTAMP:
          return PredicateLeaf.Type.TIMESTAMP;
        case BOOLEAN:
          return PredicateLeaf.Type.BOOLEAN;
        default:
      }
      return null;
    }

    private static Object boxLiteral(ConstEval lit) {
      return boxLiteral(lit.getValue());
    }

    private static List<Object> boxLiterals(EvalNode... evals) {
      List<Object> literals = new ArrayList<Object>(evals.length);
      for (EvalNode datum : evals) {
        if (!(datum instanceof ConstEval)) {
          return null;
        }
        literals.add(boxLiteral((ConstEval)datum));
      }
      return literals;
    }

    private static List<Object> boxLiterals(Datum... data) {
      List<Object> literals = new ArrayList<Object>(data.length);
      for (Datum datum : data) {
        literals.add(boxLiteral(datum));
      }
      return literals;
    }

    private static Object boxLiteral(Datum datum) {
      PredicateLeaf.Type type = getType(CatalogUtil.newSimpleDataType(datum.type()));
      switch (type) {
        case BOOLEAN:
          return datum.asBool();
        case INTEGER:
          return datum.asInt4();
        case LONG:
          return datum.asInt8();
        case STRING:
          return StringUtils.stripEnd(datum.toString(), null);
        case FLOAT:
          return datum.asFloat8();
        case DATE:
        case TIMESTAMP:
        case DECIMAL:
          return datum;
        default:
          throw new IllegalArgumentException("Unknown literal " + datum.type());
      }
    }

    private static Object getLiteral(EvalNode expr, int index) {
      EvalNode child = expr.getChild(index);
      if (child instanceof ConstEval) {
        return boxLiteral((ConstEval) child);
      }
      return null;
    }

    private ExpressionTree createLeaf(PredicateLeaf.Operator operator,
                                      EvalNode expression,
                                      List<PredicateLeaf> leafCache) {
      int variable = 0;
      EvalNode evalNode = expression.getChild(variable);
      if (evalNode.getType() != EvalType.FIELD) {
        evalNode = expression.getChild(++variable);
        if (evalNode == null || !(evalNode instanceof FieldEval)) {
          return new ExpressionTree(TruthValue.YES_NO_NULL);
        }
      }
      String columnName = ((FieldEval)evalNode).getColumnName();
      PredicateLeaf.Type type = getType(evalNode);
      if (type == null) {
        return new ExpressionTree(TruthValue.YES_NO_NULL);
      }

      Object literal = null;
      List<Object> literalList = null;
      switch (operator) {
        case IS_NULL:
          break;
        case IN:
          RowConstantEval values = (RowConstantEval)expression.getChild(1);
          literalList = boxLiterals(values.getValues());
          break;
        case BETWEEN:
          BetweenPredicateEval between = (BetweenPredicateEval)expression;
          literalList = boxLiterals(between.getBegin(), between.getEnd());
          if (literalList == null) {
            return new ExpressionTree(TruthValue.YES_NO_NULL);
          }
          break;
        default:
          literal = getLiteral(expression, variable == 0 ? 1 : 0);
          if (literal == null) {
            return new ExpressionTree(TruthValue.YES_NO_NULL);
          }
          break;
      }
      // if the variable was on the right, we need to swap things around
      boolean needSwap = false;
      if (variable != 0) {
        if (operator == PredicateLeaf.Operator.LESS_THAN) {
          needSwap = true;
          operator = PredicateLeaf.Operator.LESS_THAN_EQUALS;
        } else if (operator == PredicateLeaf.Operator.LESS_THAN_EQUALS) {
          needSwap = true;
          operator = PredicateLeaf.Operator.LESS_THAN;
        }
      }
      leafCache.add(new PredicateLeafImpl(operator, type, columnName,
          literal, literalList));
      ExpressionTree result = new ExpressionTree(leafCache.size() - 1);
      if (needSwap) {
        result = negate(result);
      }
      return result;
    }

    private ExpressionTree negate(ExpressionTree expr) {
      ExpressionTree result = new ExpressionTree(ExpressionTree.Operator.NOT);
      result.children.add(expr);
      return result;
    }

    private ExpressionTree addChildren(ExpressionTree result,
                             EvalNode node,
                             List<PredicateLeaf> leafCache) {
      for(EvalNode child: node) {
        result.children.add(parse(child, leafCache));
      }
      return result;
    }

    private ExpressionTree newTree(ExpressionTree.Operator type, ExpressionTree... kids) {
      return new ExpressionTree(type, kids);
    }

    /**
     * Do the recursive parse of the Hive EvalNode into our ExpressionTree.
     * @param expression the Hive EvalNode
     * @return the non-normalized ExpressionTree
     */
    private ExpressionTree parse(EvalNode expression,
                                 List<PredicateLeaf> leafCache) {
      switch (expression.getType()) {
        case OR:
          return addChildren(newTree(ExpressionTree.Operator.OR), expression, leafCache);
        case AND:
          return addChildren(newTree(ExpressionTree.Operator.AND), expression, leafCache);
        case NOT:
          return addChildren(newTree(ExpressionTree.Operator.NOT), expression, leafCache);
        case EQUAL:
          return createLeaf(PredicateLeaf.Operator.EQUALS, expression, leafCache);
        case NOT_EQUAL:
          return negate(createLeaf(PredicateLeaf.Operator.EQUALS, expression, leafCache));
        case LTH:
          return createLeaf(PredicateLeaf.Operator.LESS_THAN, expression, leafCache);
        case GEQ:
          return negate(createLeaf(PredicateLeaf.Operator.LESS_THAN, expression, leafCache));
        case LEQ:
          return createLeaf(PredicateLeaf.Operator.LESS_THAN_EQUALS, expression, leafCache);
        case GTH:
          return negate(createLeaf(PredicateLeaf.Operator.LESS_THAN_EQUALS, expression, leafCache));
        case IS_NULL:
          ExpressionTree isNull = createLeaf(PredicateLeaf.Operator.IS_NULL, expression, leafCache);
          return ((IsNullEval)expression).isNot() ? negate(isNull) : isNull;
        case IN:
          ExpressionTree in = createLeaf(PredicateLeaf.Operator.IN, expression, leafCache);
          return ((InEval)expression).isNot() ? negate(in) : in;
        case BETWEEN:
          ExpressionTree between = createLeaf(PredicateLeaf.Operator.BETWEEN, expression, leafCache);
          return ((BetweenPredicateEval)expression).isNot() ? negate(between) : between;
        default:
          return new ExpressionTree(TruthValue.YES_NO_NULL);
      }
    }

    /**
     * Push the negations all the way to just before the leaves. Also remove
     * double negatives.
     * @param root the expression to normalize
     * @return the normalized expression, which may share some or all of the
     * nodes of the original expression.
     */
    static ExpressionTree pushDownNot(ExpressionTree root) {
      if (root.operator == ExpressionTree.Operator.NOT) {
        ExpressionTree child = root.children.get(0);
        switch (child.operator) {
          case NOT:
            return pushDownNot(child.children.get(0));
          case CONSTANT:
            return  new ExpressionTree(child.constant.not());
          case AND:
            root = new ExpressionTree(ExpressionTree.Operator.OR);
            for(ExpressionTree kid: child.children) {
              root.children.add(pushDownNot(new
                  ExpressionTree(ExpressionTree.Operator.NOT, kid)));
            }
            break;
          case OR:
            root = new ExpressionTree(ExpressionTree.Operator.AND);
            for(ExpressionTree kid: child.children) {
              root.children.add(pushDownNot(new ExpressionTree
                  (ExpressionTree.Operator.NOT, kid)));
            }
            break;
          // for leaf, we don't do anything
          default:
            break;
        }
      } else if (root.children != null) {
        // iterate through children and push down not for each one
        for(int i=0; i < root.children.size(); ++i) {
          root.children.set(i, pushDownNot(root.children.get(i)));
        }
      }
      return root;
    }

    /**
     * Remove MAYBE values from the expression. If they are in an AND operator,
     * they are dropped. If they are in an OR operator, they kill their parent.
     * This assumes that pushDownNot has already been called.
     * @param expr The expression to clean up
     * @return The cleaned up expression
     */
    static ExpressionTree foldMaybe(ExpressionTree expr) {
      if (expr.children != null) {
        for(int i=0; i < expr.children.size(); ++i) {
          ExpressionTree child = foldMaybe(expr.children.get(i));
          if (child.constant == TruthValue.YES_NO_NULL) {
            switch (expr.operator) {
              case AND:
                expr.children.remove(i);
                i -= 1;
                break;
              case OR:
                // a maybe will kill the or condition
                return child;
              default:
                throw new IllegalStateException("Got a maybe as child of " +
                  expr);
            }
          } else {
            expr.children.set(i, child);
          }
        }
        if (expr.children.isEmpty()) {
          return new ExpressionTree(TruthValue.YES_NO_NULL);
        }
      }
      return expr;
    }

    /**
     * Generate all combinations of items on the andList. For each item on the
     * andList, it generates all combinations of one child from each and
     * expression. Thus, (and a b) (and c d) will be expanded to: (or a c)
     * (or a d) (or b c) (or b d). If there are items on the nonAndList, they
     * are added to each or expression.
     * @param result a list to put the results onto
     * @param andList a list of and expressions
     * @param nonAndList a list of non-and expressions
     */
    private static void generateAllCombinations(List<ExpressionTree> result,
                                                List<ExpressionTree> andList,
                                                List<ExpressionTree> nonAndList
                                               ) {
      List<ExpressionTree> kids = andList.get(0).children;
      if (result.isEmpty()) {
        for(ExpressionTree kid: kids) {
          ExpressionTree or = new ExpressionTree(ExpressionTree.Operator.OR);
          result.add(or);
          for(ExpressionTree node: nonAndList) {
            or.children.add(new ExpressionTree(node));
          }
          or.children.add(kid);
        }
      } else {
        List<ExpressionTree> work = new ArrayList<ExpressionTree>(result);
        result.clear();
        for(ExpressionTree kid: kids) {
          for(ExpressionTree or: work) {
            ExpressionTree copy = new ExpressionTree(or);
            copy.children.add(kid);
            result.add(copy);
          }
        }
      }
      if (andList.size() > 1) {
        generateAllCombinations(result, andList.subList(1, andList.size()),
            nonAndList);
      }
    }

    /**
     * Convert an expression so that the top level operator is AND with OR
     * operators under it. This routine assumes that all of the NOT operators
     * have been pushed to the leaves via pushdDownNot.
     * @param root the expression
     * @return the normalized expression
     */
    static ExpressionTree convertToCNF(ExpressionTree root) {
      if (root.children != null) {
        // convert all of the children to CNF
        int size = root.children.size();
        for(int i=0; i < size; ++i) {
          root.children.set(i, convertToCNF(root.children.get(i)));
        }
        if (root.operator == ExpressionTree.Operator.OR) {
          // a list of leaves that weren't under AND expressions
          List<ExpressionTree> nonAndList = new ArrayList<ExpressionTree>();
          // a list of AND expressions that we need to distribute
          List<ExpressionTree> andList = new ArrayList<ExpressionTree>();
          for(ExpressionTree child: root.children) {
            if (child.operator == ExpressionTree.Operator.AND) {
              andList.add(child);
            } else if (child.operator == ExpressionTree.Operator.OR) {
              // pull apart the kids of the OR expression
              for(ExpressionTree grandkid: child.children) {
                nonAndList.add(grandkid);
              }
            } else {
              nonAndList.add(child);
            }
          }
          if (!andList.isEmpty()) {
            if (checkCombinationsThreshold(andList)) {
              root = new ExpressionTree(ExpressionTree.Operator.AND);
              generateAllCombinations(root.children, andList, nonAndList);
            } else {
              root = new ExpressionTree(TruthValue.YES_NO_NULL);
            }
          }
        }
      }
      return root;
    }

    private static boolean checkCombinationsThreshold(List<ExpressionTree> andList) {
      int numComb = 1;
      for (ExpressionTree tree : andList) {
        numComb *= tree.children.size();
        if (numComb > CNF_COMBINATIONS_THRESHOLD) {
          return false;
        }
      }
      return true;
    }

    /**
     * Converts multi-level ands and ors into single level ones.
     * @param root the expression to flatten
     * @return the flattened expression, which will always be root with
     *   potentially modified children.
     */
    static ExpressionTree flatten(ExpressionTree root) {
      if (root.children != null) {
        // iterate through the index, so that if we add more children,
        // they don't get re-visited
        for(int i=0; i < root.children.size(); ++i) {
          ExpressionTree child = flatten(root.children.get(i));
          // do we need to flatten?
          if (child.operator == root.operator &&
              child.operator != ExpressionTree.Operator.NOT) {
            boolean first = true;
            for(ExpressionTree grandkid: child.children) {
              // for the first grandkid replace the original parent
              if (first) {
                first = false;
                root.children.set(i, grandkid);
              } else {
                root.children.add(++i, grandkid);
              }
            }
          } else {
            root.children.set(i, child);
          }
        }
        // if we have a singleton AND or OR, just return the child
        if ((root.operator == ExpressionTree.Operator.OR ||
             root.operator == ExpressionTree.Operator.AND) &&
            root.children.size() == 1) {
          return root.children.get(0);
        }
      }
      return root;
    }

    /**
     * Iterates through the expression, finding all of the leaves. It creates
     * the leaves list with each unique leaf that is found in the expression.
     * The expression is updated with the new leaf ids for each leaf.
     * @param expr the expression to find the leaves in
     * @param leafCache the list of all of the leaves
     * @param lookup a map that is used to uniquify the leaves
     * @return The potentially modified expression
     */
    private ExpressionTree buildLeafList(ExpressionTree expr,
                                         List<PredicateLeaf> leafCache,
                                         Map<PredicateLeaf,
                                         ExpressionTree> lookup) {
      if (expr.children != null) {
        for(int i=0; i < expr.children.size(); ++i) {
          expr.children.set(i, buildLeafList(expr.children.get(i), leafCache,
              lookup));
        }
      } else if (expr.operator == ExpressionTree.Operator.LEAF) {
        PredicateLeaf leaf = leafCache.get(expr.leaf);
        ExpressionTree val = lookup.get(leaf);
        if (val == null) {
          val = new ExpressionTree(leaves.size());
          lookup.put(leaf, val);
          leaves.add(leaf);
        }
        return val;
      }
      return expr;
    }

    /**
     * Builds the expression and leaf list from the original predicate.
     * @param expression the expression to translate
     * @return The normalized expression.
     */
    ExpressionTree expression(FunctionEval expression) {
      List<PredicateLeaf> leafCache = new ArrayList<PredicateLeaf>();
      ExpressionTree expr = parse(expression, leafCache);
      return expression(expr, leafCache);
    }

    /**
     * Builds the expression and optimized leaf list from a non-normalized
     * expression. Sets the leaves field with the unique leaves.
     * @param expr non-normalized expression
     * @param leaves non-unique leaves
     * @return the normalized expression
     */
    ExpressionTree expression(ExpressionTree expr,
                              List<PredicateLeaf> leaves) {
      expr = pushDownNot(expr);
      expr = foldMaybe(expr);
      expr = flatten(expr);
      expr = convertToCNF(expr);
      expr = flatten(expr);
      expr =  buildLeafList(expr, leaves,
          new HashMap<PredicateLeaf, ExpressionTree>());
      return expr;
    }

    List<PredicateLeaf> getLeaves() {
      return leaves;
    }
  }

  private final List<PredicateLeaf> leaves;
  private final ExpressionTree expression;

  TajoSearchArgument(FunctionEval expr) {
    if (expr == null) {
      leaves = new ArrayList<PredicateLeaf>();
      expression = null;
    } else {
      ExpressionBuilder builder = new ExpressionBuilder();
      expression = builder.expression(expr);
      leaves = builder.getLeaves();
    }
  }

  TajoSearchArgument() {
    leaves = null;
    expression = null;
  }

  TajoSearchArgument(ExpressionTree expression, List<PredicateLeaf> leaves) {
    this.expression = expression;
    this.leaves = leaves;
  }

  @Override
  public List<PredicateLeaf> getLeaves() {
    return leaves;
  }

  @Override
  public TruthValue evaluate(TruthValue[] leaves) {
    return expression == null ? TruthValue.YES : expression.evaluate(leaves);
  }

  ExpressionTree getExpression() {
    return expression;
  }

  @Override
  public String toString() {
    StringBuilder buffer = new StringBuilder();
    for(int i=0; i < leaves.size(); ++i) {
      buffer.append("leaf-");
      buffer.append(i);
      buffer.append(" = ");
      buffer.append(leaves.get(i).toString());
      buffer.append('\n');
    }
    buffer.append("expr = ");
    buffer.append(expression);
    return buffer.toString();
  }

  private static class BuilderImpl implements Builder {
    private final Deque<ExpressionTree> currentTree =
        new ArrayDeque<ExpressionTree>();
    private final List<PredicateLeaf> leaves = new ArrayList<PredicateLeaf>();
    private ExpressionTree root = null;

    @Override
    public Builder startOr() {
      ExpressionTree node = new ExpressionTree(ExpressionTree.Operator.OR);
      if (currentTree.size() != 0) {
        ExpressionTree parent = currentTree.getFirst();
        parent.children.add(node);
      }
      currentTree.addFirst(node);
      return this;
    }

    @Override
    public Builder startAnd() {
      ExpressionTree node = new ExpressionTree(ExpressionTree.Operator.AND);
      if (currentTree.size() != 0) {
        ExpressionTree parent = currentTree.getFirst();
        parent.children.add(node);
      }
      currentTree.addFirst(node);
      return this;
    }

    @Override
    public Builder startNot() {
      ExpressionTree node = new ExpressionTree(ExpressionTree.Operator.NOT);
      if (currentTree.size() != 0) {
        ExpressionTree parent = currentTree.getFirst();
        parent.children.add(node);
      }
      currentTree.addFirst(node);
      return this;
    }

    @Override
    public Builder end() {
      root = currentTree.removeFirst();
      if (root.children.size() == 0) {
        throw new IllegalArgumentException("Can't create expression " + root +
            " with no children.");
      }
      if (root.operator == ExpressionTree.Operator.NOT &&
          root.children.size() != 1) {
        throw new IllegalArgumentException("Can't create not expression " +
            root + " with more than 1 child.");
      }
      return this;
    }

    private static Object boxLiteral(Object literal) {
      if (literal instanceof String ||
          literal instanceof Long ||
          literal instanceof Double ||
          literal instanceof DateDatum ||
          literal instanceof Timestamp ||
          literal instanceof BigDecimal ||
          literal instanceof Boolean) {
        return literal;
      } else if (literal instanceof Byte ||
          literal instanceof Short ||
          literal instanceof Integer) {
        return Long.valueOf(literal.toString());
      } else if (literal instanceof Float) {
        // to avoid change in precision when upcasting float to double
        // we convert the literal to string and parse it as double. (HIVE-8460)
        return Double.parseDouble(literal.toString());
      } else {
        throw new IllegalArgumentException("Unknown type for literal " +
            literal);
      }
    }

    private static PredicateLeaf.Type getType(Object literal) {
      if (literal instanceof Byte ||
          literal instanceof Short ||
          literal instanceof Integer) {
        return PredicateLeaf.Type.INTEGER;
      } else if(literal instanceof Long){
        return PredicateLeaf.Type.LONG;
      } else if (literal instanceof String) {
        return PredicateLeaf.Type.STRING;
      } else if (literal instanceof Float ||
          literal instanceof Double) {
        return PredicateLeaf.Type.FLOAT;
      } else if (literal instanceof DateDatum) {
        return PredicateLeaf.Type.DATE;
      } else if (literal instanceof Timestamp) {
        return PredicateLeaf.Type.TIMESTAMP;
      } else if (literal instanceof Boolean) {
        return PredicateLeaf.Type.BOOLEAN;
      }
      throw new IllegalArgumentException("Unknown type for literal " + literal);
    }

    @Override
    public Builder lessThan(String column, Object literal) {
      ExpressionTree parent = currentTree.getFirst();
      Object box = boxLiteral(literal);
      PredicateLeaf leaf =
          new PredicateLeafImpl(PredicateLeaf.Operator.LESS_THAN,
              getType(box), column, box, null);
      leaves.add(leaf);
      parent.children.add(new ExpressionTree(leaves.size() - 1));
      return this;
    }

    @Override
    public Builder lessThanEquals(String column, Object literal) {
      ExpressionTree parent = currentTree.getFirst();
      Object box = boxLiteral(literal);
      PredicateLeaf leaf =
          new PredicateLeafImpl(PredicateLeaf.Operator.LESS_THAN_EQUALS,
              getType(box), column, box, null);
      leaves.add(leaf);
      parent.children.add(new ExpressionTree(leaves.size() - 1));
      return this;
    }

    @Override
    public Builder equals(String column, Object literal) {
      ExpressionTree parent = currentTree.getFirst();
      Object box = boxLiteral(literal);
      PredicateLeaf leaf =
          new PredicateLeafImpl(PredicateLeaf.Operator.EQUALS,
              getType(box), column, box, null);
      leaves.add(leaf);
      parent.children.add(new ExpressionTree(leaves.size() - 1));
      return this;
    }

    @Override
    public Builder nullSafeEquals(String column, Object literal) {
      ExpressionTree parent = currentTree.getFirst();
      Object box = boxLiteral(literal);
      PredicateLeaf leaf =
          new PredicateLeafImpl(PredicateLeaf.Operator.NULL_SAFE_EQUALS,
              getType(box), column, box, null);
      leaves.add(leaf);
      parent.children.add(new ExpressionTree(leaves.size() - 1));
      return this;
    }

    @Override
    public Builder in(String column, Object... literal) {
      ExpressionTree parent = currentTree.getFirst();
      if (literal.length == 0) {
        throw new IllegalArgumentException("Can't create in expression with "
            + "no arguments");
      }
      List<Object> argList = new ArrayList<Object>();
      for(Object lit: literal){
        argList.add(boxLiteral(lit));
      }

      PredicateLeaf leaf =
          new PredicateLeafImpl(PredicateLeaf.Operator.IN,
              getType(argList.get(0)), column, null, argList);
      leaves.add(leaf);
      parent.children.add(new ExpressionTree(leaves.size() - 1));
      return this;
    }

    @Override
    public Builder isNull(String column) {
      ExpressionTree parent = currentTree.getFirst();
      PredicateLeaf leaf =
          new PredicateLeafImpl(PredicateLeaf.Operator.IS_NULL,
              PredicateLeaf.Type.STRING, column, null, null);
      leaves.add(leaf);
      parent.children.add(new ExpressionTree(leaves.size() - 1));
      return this;
    }

    @Override
    public Builder between(String column, Object lower, Object upper) {
      ExpressionTree parent = currentTree.getFirst();
      List<Object> argList = new ArrayList<Object>();
      argList.add(boxLiteral(lower));
      argList.add(boxLiteral(upper));
      PredicateLeaf leaf =
          new PredicateLeafImpl(PredicateLeaf.Operator.BETWEEN,
              getType(argList.get(0)), column, null, argList);
      leaves.add(leaf);
      parent.children.add(new ExpressionTree(leaves.size() - 1));
      return this;
    }

    @Override
    public SearchArgument build() {
      if (currentTree.size() != 0) {
        throw new IllegalArgumentException("Failed to end " +
            currentTree.size() + " operations.");
      }
      ExpressionBuilder internal = new ExpressionBuilder();
      ExpressionTree normalized = internal.expression(root, leaves);
      return new TajoSearchArgument(normalized, internal.getLeaves());
    }
  }

  public static Builder newBuilder() {
    return new BuilderImpl();
  }
}
