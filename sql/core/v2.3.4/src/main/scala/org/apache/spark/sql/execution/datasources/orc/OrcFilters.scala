/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.orc

import org.apache.hadoop.hive.ql.io.sarg.{PredicateLeaf, SearchArgument}
import org.apache.hadoop.hive.ql.io.sarg.SearchArgument.Builder
import org.apache.hadoop.hive.ql.io.sarg.SearchArgumentFactory.newBuilder
import org.apache.hadoop.hive.serde2.io.HiveDecimalWritable

import org.apache.spark.sql.sources.{And, Filter}
import org.apache.spark.sql.types._

/**
 * Helper object for building ORC `SearchArgument`s, which are used for ORC predicate push-down.
 *
 * Due to limitation of ORC `SearchArgument` builder, we had to end up with a pretty weird double-
 * checking pattern when converting `And`/`Or`/`Not` filters.
 *
 * An ORC `SearchArgument` must be built in one pass using a single builder.  For example, you can't
 * build `a = 1` and `b = 2` first, and then combine them into `a = 1 AND b = 2`.  This is quite
 * different from the cases in Spark SQL or Parquet, where complex filters can be easily built using
 * existing simpler ones.
 *
 * The annoying part is that, `SearchArgument` builder methods like `startAnd()`, `startOr()`, and
 * `startNot()` mutate internal state of the builder instance.  This forces us to translate all
 * convertible filters with a single builder instance. However, before actually converting a filter,
 * we've no idea whether it can be recognized by ORC or not. Thus, when an inconvertible filter is
 * found, we may already end up with a builder whose internal state is inconsistent.
 *
 * For example, to convert an `And` filter with builder `b`, we call `b.startAnd()` first, and then
 * try to convert its children.  Say we convert `left` child successfully, but find that `right`
 * child is inconvertible.  Alas, `b.startAnd()` call can't be rolled back, and `b` is inconsistent
 * now.
 *
 * The workaround employed here is that, for `And`/`Or`/`Not`, we first try to convert their
 * children with brand new builders, and only do the actual conversion with the right builder
 * instance when the children are proven to be convertible.
 *
 * P.S.: Hive seems to use `SearchArgument` together with `ExprNodeGenericFuncDesc` only.  Usage of
 * builder methods mentioned above can only be found in test code, where all tested filters are
 * known to be convertible.
 */
private[sql] object OrcFilters {
  private[sql] def buildTree(filters: Seq[Filter]): Option[Filter] = {
    filters match {
      case Seq() => None
      case Seq(filter) => Some(filter)
      case Seq(filter1, filter2) => Some(And(filter1, filter2))
      case _ => // length > 2
        val (left, right) = filters.splitAt(filters.length / 2)
        Some(And(buildTree(left).get, buildTree(right).get))
    }
  }

  // Since ORC 1.5.0 (ORC-323), we need to quote for column names with `.` characters
  // in order to distinguish predicate pushdown for nested columns.
  private def quoteAttributeNameIfNeeded(name: String) : String = {
    if (!name.contains("`") && name.contains(".")) {
      s"`$name`"
    } else {
      name
    }
  }

  /**
   * Create ORC filter as a SearchArgument instance.
   */
  def createFilter(schema: StructType, filters: Seq[Filter]): Option[SearchArgument] = {
    val dataTypeMap = schema.map(f => f.name -> f.dataType).toMap
    for {
      // Combines all convertible filters using `And` to produce a single conjunction
      conjunction <- buildTree(convertibleFilters(schema, dataTypeMap, filters))
      // Then tries to build a single ORC `SearchArgument` for the conjunction predicate
      builder <- buildSearchArgument(dataTypeMap, conjunction, newBuilder)
    } yield builder.build()
  }

  def convertibleFilters(
      schema: StructType,
      dataTypeMap: Map[String, DataType],
      filters: Seq[Filter]): Seq[Filter] = {
    for {
      filter <- filters
      _ <- buildSearchArgument(dataTypeMap, filter, newBuilder())
    } yield filter
  }

  /**
   * Return true if this is a searchable type in ORC.
   * Both CharType and VarcharType are cleaned at AstBuilder.
   */
  private def isSearchableType(dataType: DataType) = dataType match {
    case BinaryType => false
    case _: AtomicType => true
    case _ => false
  }

  /**
   * Get PredicateLeafType which is corresponding to the given DataType.
   */
  private def getPredicateLeafType(dataType: DataType) = dataType match {
    case BooleanType => PredicateLeaf.Type.BOOLEAN
    case ByteType | ShortType | IntegerType | LongType => PredicateLeaf.Type.LONG
    case FloatType | DoubleType => PredicateLeaf.Type.FLOAT
    case StringType => PredicateLeaf.Type.STRING
    case DateType => PredicateLeaf.Type.DATE
    case TimestampType => PredicateLeaf.Type.TIMESTAMP
    case _: DecimalType => PredicateLeaf.Type.DECIMAL
    case _ => throw new UnsupportedOperationException(s"DataType: ${dataType.catalogString}")
  }

  /**
   * Cast literal values for filters.
   *
   * We need to cast to long because ORC raises exceptions
   * at 'checkLiteralType' of SearchArgumentImpl.java.
   */
  private def castLiteralValue(value: Any, dataType: DataType): Any = dataType match {
    case ByteType | ShortType | IntegerType | LongType =>
      value.asInstanceOf[Number].longValue
    case FloatType | DoubleType =>
      value.asInstanceOf[Number].doubleValue()
    case _: DecimalType =>
      val decimal = value.asInstanceOf[java.math.BigDecimal]
      val decimalWritable = new HiveDecimalWritable(decimal.longValue)
      decimalWritable.mutateEnforcePrecisionScale(decimal.precision, decimal.scale)
      decimalWritable
    case _ => value
  }

  /**
   * Build a SearchArgument and return the builder so far.
   */
  private def buildSearchArgument(
      dataTypeMap: Map[String, DataType],
      expression: Filter,
      builder: Builder): Option[Builder] = {
    createBuilder(dataTypeMap, expression, builder, canPartialPushDownConjuncts = true)
  }

  /**
   * @param dataTypeMap a map from the attribute name to its data type.
   * @param expression the input filter predicates.
   * @param builder the input SearchArgument.Builder.
   * @param canPartialPushDownConjuncts whether a subset of conjuncts of predicates can be pushed
   *                                    down safely. Pushing ONLY one side of AND down is safe to
   *                                    do at the top level or none of its ancestors is NOT and OR.
   * @return the builder so far.
   */
  private def createBuilder(
      dataTypeMap: Map[String, DataType],
      expression: Filter,
      builder: Builder,
      canPartialPushDownConjuncts: Boolean): Option[Builder] = {
    def getType(attribute: String): PredicateLeaf.Type =
      getPredicateLeafType(dataTypeMap(attribute))

    import org.apache.spark.sql.sources._

    expression match {
      case And(left, right) =>
        // At here, it is not safe to just convert one side and remove the other side
        // if we do not understand what the parent filters are.
        //
        // Here is an example used to explain the reason.
        // Let's say we have NOT(a = 2 AND b in ('1')) and we do not understand how to
        // convert b in ('1'). If we only convert a = 2, we will end up with a filter
        // NOT(a = 2), which will generate wrong results.
        //
        // Pushing one side of AND down is only safe to do at the top level or in the child
        // AND before hitting NOT or OR conditions, and in this case, the unsupported predicate
        // can be safely removed.
        val leftBuilderOption =
          createBuilder(dataTypeMap, left, newBuilder, canPartialPushDownConjuncts)
        val rightBuilderOption =
          createBuilder(dataTypeMap, right, newBuilder, canPartialPushDownConjuncts)
        (leftBuilderOption, rightBuilderOption) match {
          case (Some(_), Some(_)) =>
            for {
              lhs <- createBuilder(dataTypeMap, left,
                builder.startAnd(), canPartialPushDownConjuncts)
              rhs <- createBuilder(dataTypeMap, right, lhs, canPartialPushDownConjuncts)
            } yield rhs.end()

          case (Some(_), None) if canPartialPushDownConjuncts =>
            createBuilder(dataTypeMap, left, builder, canPartialPushDownConjuncts)

          case (None, Some(_)) if canPartialPushDownConjuncts =>
            createBuilder(dataTypeMap, right, builder, canPartialPushDownConjuncts)

          case _ => None
        }

      case Or(left, right) =>
        for {
          _ <- createBuilder(dataTypeMap, left, newBuilder, canPartialPushDownConjuncts = false)
          _ <- createBuilder(dataTypeMap, right, newBuilder, canPartialPushDownConjuncts = false)
          lhs <- createBuilder(dataTypeMap, left,
            builder.startOr(), canPartialPushDownConjuncts = false)
          rhs <- createBuilder(dataTypeMap, right, lhs, canPartialPushDownConjuncts = false)
        } yield rhs.end()

      case Not(child) =>
        for {
          _ <- createBuilder(dataTypeMap, child, newBuilder, canPartialPushDownConjuncts = false)
          negate <- createBuilder(dataTypeMap,
            child, builder.startNot(), canPartialPushDownConjuncts = false)
        } yield negate.end()

      // NOTE: For all case branches dealing with leaf predicates below, the additional `startAnd()`
      // call is mandatory.  ORC `SearchArgument` builder requires that all leaf predicates must be
      // wrapped by a "parent" predicate (`And`, `Or`, or `Not`).

      case EqualTo(attribute, value) if isSearchableType(dataTypeMap(attribute)) =>
        val quotedName = quoteAttributeNameIfNeeded(attribute)
        val castedValue = castLiteralValue(value, dataTypeMap(attribute))
        Some(builder.startAnd().equals(quotedName, getType(attribute), castedValue).end())

      case EqualNullSafe(attribute, value) if isSearchableType(dataTypeMap(attribute)) =>
        val quotedName = quoteAttributeNameIfNeeded(attribute)
        val castedValue = castLiteralValue(value, dataTypeMap(attribute))
        Some(builder.startAnd().nullSafeEquals(quotedName, getType(attribute), castedValue).end())

      case LessThan(attribute, value) if isSearchableType(dataTypeMap(attribute)) =>
        val quotedName = quoteAttributeNameIfNeeded(attribute)
        val castedValue = castLiteralValue(value, dataTypeMap(attribute))
        Some(builder.startAnd().lessThan(quotedName, getType(attribute), castedValue).end())

      case LessThanOrEqual(attribute, value) if isSearchableType(dataTypeMap(attribute)) =>
        val quotedName = quoteAttributeNameIfNeeded(attribute)
        val castedValue = castLiteralValue(value, dataTypeMap(attribute))
        Some(builder.startAnd().lessThanEquals(quotedName, getType(attribute), castedValue).end())

      case GreaterThan(attribute, value) if isSearchableType(dataTypeMap(attribute)) =>
        val quotedName = quoteAttributeNameIfNeeded(attribute)
        val castedValue = castLiteralValue(value, dataTypeMap(attribute))
        Some(builder.startNot().lessThanEquals(quotedName, getType(attribute), castedValue).end())

      case GreaterThanOrEqual(attribute, value) if isSearchableType(dataTypeMap(attribute)) =>
        val quotedName = quoteAttributeNameIfNeeded(attribute)
        val castedValue = castLiteralValue(value, dataTypeMap(attribute))
        Some(builder.startNot().lessThan(quotedName, getType(attribute), castedValue).end())

      case IsNull(attribute) if isSearchableType(dataTypeMap(attribute)) =>
        val quotedName = quoteAttributeNameIfNeeded(attribute)
        Some(builder.startAnd().isNull(quotedName, getType(attribute)).end())

      case IsNotNull(attribute) if isSearchableType(dataTypeMap(attribute)) =>
        val quotedName = quoteAttributeNameIfNeeded(attribute)
        Some(builder.startNot().isNull(quotedName, getType(attribute)).end())

      case In(attribute, values) if isSearchableType(dataTypeMap(attribute)) =>
        val quotedName = quoteAttributeNameIfNeeded(attribute)
        val castedValues = values.map(v => castLiteralValue(v, dataTypeMap(attribute)))
        Some(builder.startAnd().in(quotedName, getType(attribute),
          castedValues.map(_.asInstanceOf[AnyRef]): _*).end())

      case _ => None
    }
  }
}
