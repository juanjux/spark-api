package tech.sourced.api

import org.apache.spark.internal.Logging
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.plans.{Inner, JoinType}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.types._

/**
  * Logical plan rule to transform joins of [[GitRelation]]s into a single [[GitRelation]]
  * that will use chainable iterators for better performance. Rather than obtaining all the
  * data from each table in isolation, it will reuse already filtered data from the previous
  * iterator.
  */
object SquashGitRelationJoin extends Rule[LogicalPlan] {
  /** @inheritdoc */
  def apply(plan: LogicalPlan): LogicalPlan = plan transformUp {
    // Joins are only applicable per repository, so we can push down completely
    // the join into the datasource
    case q@Join(_, _, _, _) =>
      val jd = GitOptimizer.getJoinData(q)
      if (!jd.valid) {
        return q
      }

      jd match {
        case JoinData(filters, joinConditions, projectExprs, attributes, Some(sqlc), _) =>
          val relation = LogicalRelation(
            GitRelation(
              sqlc,
              GitOptimizer.attributesToSchema(attributes), joinConditions
            ),
            attributes,
            None
          )

          val node = filters match {
            case Some(filter) => Filter(filter, relation)
            case None => relation
          }

          // If the projection is empty, just return the filter
          if (projectExprs.nonEmpty) {
            Project(projectExprs, node)
          } else {
            node
          }
        case _ => q
      }

    // Remove two consecutive projects and replace it with the outermost one.
    case Project(list, Project(_, child)) =>
      Project(list, child)
  }
}

/**
  * Contains all the data gathered from a join node in the logical plan.
  *
  * @param filterExpression   any expression filters mixed with ANDs below the join
  * @param joinCondition      all the join conditions mixed with ANDs
  * @param projectExpressions expressions for the projection
  * @param attributes         list of attributes
  * @param sqlContext         SQL context
  * @param valid              if the data is valid or not
  */
case class JoinData(filterExpression: Option[Expression] = None,
                    joinCondition: Option[Expression] = None,
                    projectExpressions: Seq[NamedExpression] = Nil,
                    attributes: Seq[AttributeReference] = Nil,
                    sqlContext: Option[SQLContext] = None,
                    valid: Boolean = false)

/**
  * Support methods for optimizing [[GitRelation]]s.
  */
object GitOptimizer extends Logging {
  private val supportedJoinTypes: Seq[JoinType] = Inner :: Nil

  /**
    * Reports whether the given join is supported.
    *
    * @param j join
    * @return is supported or not
    */
  private def isJoinSupported(j: Join): Boolean = supportedJoinTypes.contains(j.joinType)

  /**
    * Retrieves all the unsupported conditions in the join.
    *
    * @param join  Join
    * @param left  left relation
    * @param right right relation
    * @return unsupported conditions
    */
  private def getUnsupportedConditions(join: Join,
                                       left: LogicalRelation,
                                       right: LogicalRelation) = {
    val leftReferences = left.references.baseSet
    val rightReferences = right.references.baseSet
    val joinReferences = join.references.baseSet
    joinReferences -- leftReferences -- rightReferences
  }

  /**
    * Returns the data about a join to perform optimizations on it.
    *
    * @param j join to get the data from
    * @return join data
    */
  private[api] def getJoinData(j: Join): JoinData = {
    // left and right ends in a GitRelation
    val leftRel = getGitRelation(j.left)
    val rightRel = getGitRelation(j.right)

    // Not a valid Join to optimize GitRelations
    if (leftRel.isEmpty || rightRel.isEmpty || !isJoinSupported(j)) {
      logWarning("Join cannot be optimized. It doesn't have GitRelations in both sides, " +
        "or the Join type is not supported.")
      return JoinData()
    }

    // Check Join conditions. They must be all conditions related with GitRelations
    val unsupportedConditions = getUnsupportedConditions(j, leftRel.get, rightRel.get)
    if (unsupportedConditions.nonEmpty) {
      logWarning(s"Join cannot be optimized. Obtained unsupported " +
        s"conditions: $unsupportedConditions")
      return JoinData()
    }

    // Check if the Join contains all valid Nodes
    val jd: Seq[JoinData] = j.map {
      case jm@Join(_, _, _, condition) =>
        if (jm == j) {
          JoinData(valid = true, joinCondition = condition)
        } else {
          logWarning(s"Join cannot be optimized. Invalid node: $jm")
          JoinData()
        }
      case Filter(cond, _) =>
        JoinData(Some(cond), valid = true)
      case Project(namedExpressions, _) =>
        JoinData(None, projectExpressions = namedExpressions, valid = true)
      case LogicalRelation(GitRelation(sqlc, _, joinCondition, schemaSource), out, _) =>

        // Add metadata to attributes
        val processedOut = schemaSource match {
          case Some(ss) => out
            .map(_.withMetadata(
              new MetadataBuilder()
                .putString("source", ss).build()).asInstanceOf[AttributeReference])
          case None => out
        }

        JoinData(
          None,
          valid = true,
          joinCondition = joinCondition,
          attributes = processedOut,
          sqlContext = Some(sqlc)
        )
      case other =>
        logWarning(s"Join cannot be optimized. Invalid node: $other")
        JoinData()
    }

    mergeJoinData(jd)
  }

  /**
    * Reduce all join data into one single join data.
    *
    * @param data sequence of join data to be merged
    * @return merged join data
    */
  private def mergeJoinData(data: Seq[JoinData]): JoinData = {
    data.reduce((jd1, jd2) => {
      // get all filter expressions
      val exprOpt: Option[Expression] = mixExpressions(
        jd1.filterExpression,
        jd2.filterExpression,
        And
      )
      // get all join conditions
      val joinConditionOpt: Option[Expression] = mixExpressions(
        jd1.joinCondition,
        jd2.joinCondition,
        And
      )

      // get just one sqlContext if any
      val sqlcOpt = (jd1.sqlContext, jd2.sqlContext) match {
        case (Some(l), _) => Some(l)
        case (_, Some(r)) => Some(r)
        case _ => None
      }

      JoinData(
        exprOpt,
        joinConditionOpt,
        jd1.projectExpressions ++ jd2.projectExpressions,
        jd1.attributes ++ jd2.attributes,
        sqlcOpt,
        jd1.valid && jd2.valid
      )
    })
  }

  /**
    * Mixes the two given expressions with the given join function if both exist
    * or returns the one that exists otherwise.
    *
    * @param l            left expression
    * @param r            right expression
    * @param joinFunction function used to join them
    * @return an optional expression
    */
  private def mixExpressions(l: Option[Expression],
                             r: Option[Expression],
                             joinFunction: (Expression, Expression) => Expression):
  Option[Expression] = {
    (l, r) match {
      case (Some(expr1), Some(expr2)) => Some(joinFunction(expr1, expr2))
      case (None, None) => None
      case (le, None) => le
      case (None, re) => re
    }
  }

  /**
    * Returns the first git relation found in the given logical plan, if any.
    *
    * @param lp logical plan
    * @return git relation, or none if there is no such relation
    */
  def getGitRelation(lp: LogicalPlan): Option[LogicalRelation] =
    lp.find {
      case LogicalRelation(GitRelation(_, _, _, _), _, _) => true
      case _ => false
    } map (_.asInstanceOf[LogicalRelation])

  /**
    * Creates a schema from a list of attributes.
    *
    * @param attributes list of attributes
    * @return resultant schema
    */
  def attributesToSchema(attributes: Seq[AttributeReference]): StructType =
    StructType(
      attributes
        .map((a: Attribute) => StructField(a.name, a.dataType, a.nullable, a.metadata))
        .toArray
    )
}
