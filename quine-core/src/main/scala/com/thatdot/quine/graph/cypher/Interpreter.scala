package com.thatdot.quine.graph.cypher

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import org.apache.pekko.actor.{Actor, ActorRef}
import org.apache.pekko.pattern.extended.ask
import org.apache.pekko.util.Timeout

import cats.implicits._
import com.google.common.collect.MinMaxPriorityQueue

import com.thatdot.common.logging.Log.{LazySafeLogging, LogConfig, Safe, SafeLoggableInterpolator}
import com.thatdot.common.quineid.QuineId
import com.thatdot.quine.graph.EdgeEvent.{EdgeAdded, EdgeRemoved}
import com.thatdot.quine.graph.PropertyEvent.{PropertyRemoved, PropertySet}
import com.thatdot.quine.graph.cypher.Query._
import com.thatdot.quine.graph.cypher.SkipOptimizingActor._
import com.thatdot.quine.graph.messaging.CypherMessage.{CheckOtherHalfEdge, QueryContextResult, QueryPackage}
import com.thatdot.quine.graph.messaging.LiteralMessage.{DeleteNodeCommand, RemoveHalfEdgeCommand}
import com.thatdot.quine.graph.messaging.{QuineIdOps, QuineRefOps}
import com.thatdot.quine.graph.{BaseNodeActor, CypherOpsGraph, NamespaceId, PropertyEvent, SkipOptimizerKey}
import com.thatdot.quine.model.{EdgeDirection, HalfEdge, Milliseconds, PropertyValue, QuineIdProvider, QuineValue}
import com.thatdot.quine.util.InterpM
import com.thatdot.quine.util.Log.implicits._
import com.thatdot.quine.util.MonadHelpers._

// An interpreter that runs against the graph as a whole, rather than "inside" the graph
// INV: Thread-safe
trait GraphExternalInterpreter extends CypherInterpreter[Location.External] with LazySafeLogging {

  def node: Option[BaseNodeActor] = None

  implicit val self: ActorRef = ActorRef.noSender
  final def interpret(
    query: Query[Location.External],
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] =
    try query match {
      case query: Empty => interpretEmpty(query, context)
      case query: Unit => interpretUnit(query, context)
      case query: AnchoredEntry => interpretAnchoredEntry(query, context)
      case query: ArgumentEntry => interpretArgumentEntry(query, context)
      case query: LoadCSV => interpretLoadCSV(query, context)
      case query: Union[Location.External @unchecked] => interpretUnion(query, context)
      case query: Or[Location.External @unchecked] => interpretOr(query, context)
      case query: ValueHashJoin[Location.External @unchecked] => interpretValueHashJoin(query, context)
      case query: SemiApply[Location.External @unchecked] => interpretSemiApply(query, context)
      case query: Apply[Location.External @unchecked] => interpretApply(query, context)
      case query: Optional[Location.External @unchecked] => interpretOptional(query, context)
      case query: Filter[Location.External @unchecked] => interpretFilter(query, context)
      case query: Skip[Location.External @unchecked] => interpretSkip(query, context)
      case query: Limit[Location.External @unchecked] => interpretLimit(query, context)
      case query: Sort[Location.External @unchecked] => interpretSort(query, context)
      case query: Return[Location.External @unchecked] => interpretReturn(query, context)
      case query: Distinct[Location.External @unchecked] => interpretDistinct(query, context)
      case query: Unwind[Location.External @unchecked] => interpretUnwind(query, context)
      case query: AdjustContext[Location.External @unchecked] => interpretAdjustContext(query, context)
      case query: EagerAggregation[Location.External @unchecked] => interpretEagerAggregation(query, context)
      case query: Delete => interpretDelete(query, context)
      case query: ProcedureCall => interpretProcedureCall(query, context)
      case query: SubQuery[Location.External @unchecked] => interpretSubQuery(query, context)
      case query: RecursiveSubQuery[Location.External @unchecked] => interpretRecursiveSubquery(query, context)
    } catch {
      case NonFatal(e) => InterpM.raise(e)
    }

  override private[quine] def interpretReturn(query: Return[Location.External], context: QueryContext)(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] =
    query match {
      /** This query is potentially suitable for drop-based optimizations: It has either:
        * a LIMIT (which may imply it will be one of a batch of queries)
        * a SKIP (which may imply SKIP queries issued as part of a batch )
        *
        * And this query does *not* have any ORDER BY or DISTINCT to postprocess the results through
        * TODO the normalization step could handle ORDER BY / DISTINCT to enable those query forms, if their structures
        * were made deterministic (eg, ensuring there are no randomly-generated variable names)
        */
      case Return(toReturn @ _, None, None, drop, take, columns @ _)
          if !bypassSkipOptimization
            && (drop.isDefined || take.isDefined)
            && query.toReturn.isReadOnly =>
        /** as this is executed at query runtime, all parameters should be in scope: In particular, [[queryNormalized]]
          * will have no [[Expr.Parameter]]s remaining, making it a valid [[SkipOptimizingActor]] `queryFamily`
          */
        val parameterSubstitutions = parameters.params.zipWithIndex.map { case (paramValue, index) =>
          Expr.Parameter(index) -> paramValue
        }.toMap
        val queryNormalized = query.substitute(parameterSubstitutions)
        val toReturnNormalized = queryNormalized.toReturn
        val skipOptimizerActor =
          graph.cypherOps.skipOptimizerCache.get(SkipOptimizerKey(toReturnNormalized, namespace, atTime))
        val requestedSource =
          (skipOptimizerActor ? (ResumeQuery(
            queryNormalized,
            context,
            parameters,
            restartIfAppropriate = true,
            _,
          ))).mapTo[Either[SkipOptimizationError, InterpM[CypherException, QueryContext]]]

        InterpM.futureInterpMUnsafe(requestedSource.map(_.left.map { err =>
          // Expected for, eg, subqueries. Otherwise, probably indicates end user behavior that isn't compatible with current pagination impl
          logger.info(
            log"QueryManagerActor refused to process query. Falling back to naive interpreter. " +
            log"Re-running the same query " +
            (if (err.retriable) log"may not " else log"will ") +
            log"have the same result. Cause: ${err.msg}",
          )
          interpretRecursive(query.delegates.naiveStack, context)(parameters, logConfig)
        }.merge)(cypherEc))
      case _ =>
        super.interpretReturn(query, context)
    }

  final private[cypher] def interpretAnchoredEntry(
    query: AnchoredEntry,
    context: QueryContext,
  )(implicit
    parameters: Parameters,
  ): InterpM[CypherException, QueryContext] = InterpM.liftUnsafeThunk {

    val qids: InterpM[CypherException, QuineId] = query.entry match {
      case EntryPoint.AllNodesScan =>
        InterpM.liftUnsafe(graph.enumerateAllNodeIds(namespace))

      case EntryPoint.NodeById(ids) =>
        InterpM[CypherException, QuineId](ids)
    }
    qids.flatMap { (qid: QuineId) =>
      InterpM
        .futureSourceUnsafe(
          qid ? (QueryPackage(query.andThen, parameters, context, _)),
        )
        .map(_.result)
    }
  }

  final private[cypher] def interpretArgumentEntry(
    query: ArgumentEntry,
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] = {
    val other: QuineId = getQuineId(query.node.evalUnsafe(context)) match {
      case Some(other) => other
      case None => return InterpM.empty
    }

    InterpM
      .lazyFutureSourceUnsafe(() =>
        other ? (QueryPackage(query.andThen, parameters, context, _)),
      ) //Todo: Make sure this isn't throwing exceptions
      .map(_.result)
  }
}

/** an interpreter that runs over a particular timestamp "off the graph" (ie, an [[GraphExternalInterpreter]]
  */
class AtTimeInterpreter(
  val graph: CypherOpsGraph,
  val namespace: NamespaceId,
  val atTime: Option[Milliseconds],
  val bypassSkipOptimization: Boolean,
) extends GraphExternalInterpreter {

  protected val cypherEc: ExecutionContext = graph.nodeDispatcherEC

  protected val cypherProcessTimeout: Timeout = graph.cypherQueryProgressTimeout

  implicit val idProvider: QuineIdProvider = graph.idProvider
}

/** A specific [[AtTimeInterpreter]] for the thoroughgoing present. Logically, there is one of these per graph.
  *
  * @see [[graph.cypherOps.currentMomentInterpreter]]
  * @param graph
  */
class ThoroughgoingInterpreter(graph: CypherOpsGraph, namespace: NamespaceId)
    extends AtTimeInterpreter(graph, namespace, None, bypassSkipOptimization = true)

// Knows what to do with in-node queries
trait OnNodeInterpreter
    extends CypherInterpreter[Location.OnNode]
    with Actor
    with BaseNodeActor
    with QuineIdOps
    with QuineRefOps
    with LazySafeLogging {

  def node: Option[BaseNodeActor] = Some(this)

  protected val cypherEc: ExecutionContext = context.dispatcher

  implicit protected def cypherProcessTimeout: Timeout = graph.cypherQueryProgressTimeout

  // opt out of reusing SKIP-ed over queries when interpreting the thoroughgoing present
  def bypassSkipOptimization: Boolean = atTime.isEmpty

  /** Executes/interprets a `Query` AST.
    *
    * WARNING: `interpret` should never be called from a `Source` or a `Future`. See also `interpretRecursive`.
    *          The concern here is that this some variants of `Query` manipulate node local state (e.g. SetProperties)
    *
    * @param query Compiled cypher query AST.
    * @param context variables in scope
    * @param parameters query constants in scope
    * @return back-pressured source of results
    */
  final def interpret(
    query: Query[Location.OnNode],
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] =
    try query match {
      case query: Empty => interpretEmpty(query, context)
      case query: Unit => interpretUnit(query, context)
      case query: AnchoredEntry => interpretAnchoredEntry(query, context)
      case query: ArgumentEntry => interpretArgumentEntry(query, context)
      case query: Expand => interpretExpand(query, context)
      case query: LocalNode => interpretLocalNode(query, context)
      case query: GetDegree => interpretGetDegree(query, context)
      case query: LoadCSV => interpretLoadCSV(query, context)
      case query: Union[Location.OnNode @unchecked] => interpretUnion(query, context)
      case query: Or[Location.OnNode @unchecked] => interpretOr(query, context)
      case query: ValueHashJoin[Location.OnNode @unchecked] => interpretValueHashJoin(query, context)
      case query: SemiApply[Location.OnNode @unchecked] => interpretSemiApply(query, context)
      case query: Apply[Location.OnNode @unchecked] => interpretApply(query, context)
      case query: Optional[Location.OnNode @unchecked] => interpretOptional(query, context)
      case query: Filter[Location.OnNode @unchecked] => interpretFilter(query, context)
      case query: Skip[Location.OnNode @unchecked] => interpretSkip(query, context)
      case query: Limit[Location.OnNode @unchecked] => interpretLimit(query, context)
      case query: Sort[Location.OnNode @unchecked] => interpretSort(query, context)
      case query: Return[Location.OnNode @unchecked] => interpretReturn(query, context)
      case query: Distinct[Location.OnNode @unchecked] => interpretDistinct(query, context)
      case query: Unwind[Location.OnNode @unchecked] => interpretUnwind(query, context)
      case query: AdjustContext[Location.OnNode @unchecked] => interpretAdjustContext(query, context)
      case query: SetProperty => interpretSetProperty(query, context)
      case query: SetProperties => interpretSetProperties(query, context)
      case query: SetEdge => interpretSetEdge(query, context)
      case query: SetLabels => interpretSetLabels(query, context)
      case query: EagerAggregation[Location.OnNode @unchecked] => interpretEagerAggregation(query, context)
      case query: Delete => interpretDelete(query, context)
      case query: ProcedureCall => interpretProcedureCall(query, context)
      case query: SubQuery[Location.OnNode @unchecked] => interpretSubQuery(query, context)
      case query: RecursiveSubQuery[Location.OnNode @unchecked] => interpretRecursiveSubquery(query, context)
    } catch {
      case NonFatal(e) => InterpM.raise(e)
    }

  final private def labelsProperty: Symbol = graph.labelsProperty

  def graph: CypherOpsGraph

  /* By the time we get to interpreting the inner query (possibly multiple
   * times), the node will have moved on to processing other messages. It is
   * therefore critical to explicitly queue up the query back in the node's
   * mailbox if there is any chance the query will touch node state.
   */
  override private[quine] def interpretRecursive(
    query: Query[Location.OnNode],
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] =
    // TODO: This can be optimized by calling `interpret` here directly `if (!query.canDirectlyTouchNode)`, except
    //       that it must be guaranteed to be run single-threaded on an actor while a message is being processed.
    InterpM
      .lazyFutureSourceUnsafe[CypherException, QueryContextResult] { () =>
        qidAtTime ? (QueryPackage(query, parameters, context, _))
      }
      .map(_.result)

  final private[cypher] def interpretAnchoredEntry(
    query: AnchoredEntry,
    context: QueryContext,
  )(implicit
    parameters: Parameters,
  ): InterpM[CypherException, QueryContext] =
    InterpM.liftUnsafe(
      graph.cypherOps.continueQuery(query, parameters, namespace, atTime, context),
    )

  final private[cypher] def interpretArgumentEntry(
    query: ArgumentEntry,
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] = {
    val evaled = query.node.evalUnsafe(context)
    val other: QuineId = getQuineId(evaled) match {
      case Some(other) => other
      case None =>
        val becauseNullSuffix =
          if (evaled == Expr.Null)
            // If the expression evaluates to null, we can take a guess at why.
            Safe(
              " This is likely due to a null value being passed as an argument to idFrom() or locIdFrom().",
            )
          else {
            // The user probably just did something silly like `WHERE id(n) = { areMapsIds: false }`
            Safe("")
          }
        logger.warn(
          log"""Tried to use expression as QuineId: ${query.node}, but evaluates to: $evaled.
               |Aborting query and returning 0 rows.$becauseNullSuffix""".cleanLines,
        )
        return InterpM.empty
    }

    /** Apply(this, that)
      *
      * ArgumentEntry {
      *   andThen: that
      * }
      *
      * Apply(ArgumentEntry(Apply(LocalNode, SetProperties)), Apply(ArgumentEntry, Apply(AE, Apply(AE, Apply(AE, ))))
      *
      * ArgumentEntry(Apply(SetProperties, ArgumentEntry(...)))
      * AE(Apply(_, AE(Apply(_, )))
      */
    if (other == qid) {
      interpret(query.andThen, context)
    } else {
      InterpM
        .lazyFutureSourceUnsafe { () =>
          other ? (QueryPackage(query.andThen, parameters, context, _))
        }
        .map(_.result)
    }
  }

  final private[cypher] def interpretExpand(
    expand: Expand,
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] = {

    val myQid = qid
    val Expand(edgeName, toNode, _, bindRelation, range, visited, andThen, _) = expand

    if (visited.size > graph.maxCypherExpandVisitedCount) {
      throw CypherException.Runtime(
        s"Variable length relationship pattern exceeded maximum traversal length ${graph.maxCypherExpandVisitedCount} (update upper bound of length in relationship pattern)",
      )
    }

    /* There is no such thing as an undirected edge in Cypher: `(n)--(m)` means
     * either `(n)-->(m)` or `(n)<--(m)`
     */
    val direction: Option[EdgeDirection] = expand.direction match {
      case EdgeDirection.Undirected => None
      case directed => Some(directed)
    }

    /* Compute the other end of the edge, if available */
    val literalFarNodeId: Option[QuineId] = toNode map { (toNode: Expr) =>
      val otherVal = toNode.evalUnsafe(context)
      getQuineId(otherVal) getOrElse {
        throw CypherException.TypeMismatch(
          expected = Seq(Type.Node),
          actualValue = otherVal,
          context = "one extremity of an edge we are expanding to",
        )
      }
    }

    // Get edges matching the direction / name constraint.
    val halfEdgesIterator: Iterator[HalfEdge] = (edgeName, direction, literalFarNodeId) match {
      case (None, None, None) =>
        edges.all
      case (None, None, Some(id)) =>
        edges.matching(id)
      case (None, Some(dir), None) =>
        edges.matching(dir)
      case (None, Some(dir), Some(id)) =>
        edges.matching(dir, id)
      case (Some(names), None, None) =>
        names.iterator.flatMap(edges.matching(_))
      case (Some(names), None, Some(id)) =>
        names.iterator.flatMap(edges.matching(_, id))
      case (Some(names), Some(dir), None) =>
        names.iterator.flatMap(edges.matching(_, dir))
      case (Some(names), Some(dir), Some(id)) =>
        names.iterator.flatMap(edges.matching(_, dir, id))
    }
    val filteredHalfEdgesIterator = if (visited.isEmpty) {
      halfEdgesIterator // Usual case, unless doing a variable-length match
    } else {
      halfEdgesIterator.filterNot(visited.contains(myQid, _))
    }

    /* As tempting as it may be to always use `Source.fromIterator`, we must not
     * do this unless the node is historical (so the edge collection effectively
     * immutable), else we would be closing over mutable node state (and
     * multiple threads can concurrently access edges).
     */
    val halfEdgesSource: InterpM[CypherException, HalfEdge] = if (atTime.nonEmpty) {
      InterpM.fromIterator(() => filteredHalfEdgesIterator)
    } else {
      InterpM(filteredHalfEdgesIterator.toVector)
    }

    halfEdgesSource.flatMap {
      // Undirected edges don't exist for Cypher :)
      case HalfEdge(_, EdgeDirection.Undirected, _) => InterpM.empty[CypherException, QueryContext]

      case halfEdge @ HalfEdge(sym, dir, halfEdgeFarNode) =>
        val newContext = bindRelation match {
          case None => context

          // TODO: record properties
          case Some(asName) if range.isEmpty =>
            val rel = Expr.Relationship(myQid, sym, Map.empty, halfEdgeFarNode)
            val rel2 = if (dir == EdgeDirection.Outgoing) rel else rel.reflect
            context + (asName -> rel2)

          case Some(asName) =>
            context + (asName -> Expr.List(visited.addEdge(myQid, halfEdge).relationships))
        }

        // source that produces the result of running the andThen query on the remote node
        lazy val andThenSource: InterpM[CypherException, QueryContext] = InterpM
          .futureSourceUnsafe {
            halfEdgeFarNode ? (ref =>
              CheckOtherHalfEdge(
                halfEdge = halfEdge.reflect(myQid),
                action = None,
                query = andThen,
                parameters = parameters,
                context = newContext,
                replyTo = ref,
              ),
            )
          }
          .map(_.result)

        // source that produces the result of recursively running this expand query on the remote node
        lazy val recursiveExpandSource: InterpM[CypherException, QueryContext] = InterpM
          .futureSourceUnsafe {
            halfEdgeFarNode ? (ref =>
              CheckOtherHalfEdge(
                halfEdge = halfEdge.reflect(myQid),
                action = None,
                query = expand.copy(
                  visited = visited.addEdge(myQid, halfEdge),
                ),
                parameters = parameters,
                context = context,
                replyTo = ref,
              ),
            )
          }
          .map(_.result)

        range match {
          case None => andThenSource
          case Some(range) =>
            // Match the far node (if it is in range)
            val andThenMatch =
              if (
                range match {
                  case (Some(lower), None) => visited.size + 1L >= lower
                  case (None, Some(upper)) => visited.size + 1L <= upper
                  case (Some(lower), Some(upper)) => visited.size + 1L >= lower && visited.size + 1L <= upper
                  case (None, None) => false
                }
              ) andThenSource
              else InterpM.empty[CypherException, QueryContext]
            // Recursively expand the same query for a variable-length edge
            // (if relatives of the far node will be in range)
            val recursiveMatch = {
              if (
                range match {
                  case (_, Some(upper)) => visited.size + 2L <= upper
                  case (_, None) => true
                }
              ) recursiveExpandSource
              else InterpM.empty[CypherException, QueryContext]
            }
            andThenMatch ++ recursiveMatch
        }
    }
  }

  final private[cypher] def interpretLocalNode(
    query: LocalNode,
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] = {
    val requiredPropsOpt: Option[Map[String, Value]] = query.propertiesOpt.map { expr =>
      expr.evalUnsafe(context) match {
        case Expr.Map(map) => map
        case other =>
          throw CypherException.TypeMismatch(
            expected = Seq(Type.Map),
            actualValue = other,
            context = "property map to check on a node",
          )
      }
    }

    val cypherProps: Map[Symbol, Value] = properties.flatMap { case (key, value) =>
      value.deserialized.toOption.map(v => key -> Expr.fromQuineValue(v))
    }

    // Weed out cases where the node is missing a required property values
    def missingRequiredProp: Boolean = requiredPropsOpt.exists { requiredProps =>
      requiredProps.exists { case (key, expectedValue) =>
        !cypherProps.get(Symbol(key)).exists(_ == expectedValue)
      }
    }
    if (missingRequiredProp) {
      return InterpM.empty
    }

    // Get all of the labels on the node
    val labels = getLabels() match {
      case Some(lbls) => lbls
      case None => return InterpM.empty // TODO: should we error/warn here?
    }

    // Check whether the node has the required labels
    if (query.labelsOpt.exists(expectedLabels => !expectedLabels.toSet.subsetOf(labels))) {
      return InterpM.empty
    }

    val newContext = query.bindName match {
      case None => context
      case Some(asName) =>
        val realProperties = cypherProps - labelsProperty
        context + (asName -> Expr.Node(qid, labels, realProperties))
    }
    InterpM.single(newContext)
  }

  // TODO: check the other end of half edges?
  final private[cypher] def interpretGetDegree(
    query: GetDegree,
    context: QueryContext,
  ): InterpM[CypherException, QueryContext] = {
    val degree: Int = query.edgeName match {
      case None => edges.matching(query.direction).size
      case Some(n) => edges.matching(n, query.direction).size
    }

    val newContext = context + (query.bindName -> Expr.Integer(degree.toLong))
    InterpM.single(newContext)
  }

  final private[cypher] def interpretSetProperty(
    query: SetProperty,
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] = {
    val newValue = query.newValue.map(_.evalUnsafe(context)).filterNot(_ == Expr.Null)
    val event = newValue match {
      case None =>
        // remove the property
        properties.get(query.key) match {
          case Some(oldValue) => Some(PropertyRemoved(query.key, oldValue))
          case None =>
            // there already was no property at query.key -- no-op
            None
        }
      case Some(value) => Some(PropertySet(query.key, PropertyValue(Expr.toQuineValue(value).getOrThrow)))
    }
    val newContext = context.get(query.nodeVar) match {
      case Some(node: Expr.Node) =>
        val newNodeValue = newValue match {
          case Some(updatedPropertyValue) =>
            node.copy(properties = node.properties + (query.key -> updatedPropertyValue))
          case None => node.copy(properties = node.properties - query.key)
        }
        context + (query.nodeVar -> newNodeValue)
      case _ => // node variable not in context as a node, so return unchanged.
        context
    }

    InterpM
      .liftFutureUnsafe(processPropertyEvents(event.toList))
      .map(_ => newContext)
  }

  final private[cypher] def interpretSetProperties(
    query: SetProperties,
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] = {
    val map: Map[Symbol, Value] = query.properties.evalUnsafe(context) match {
      case Expr.Map(map) => map.map { case (k, v) => Symbol(k) -> v } // set n = {...} / n += {...}
      case Expr.Node(_, _, props) => props // set n = m / n += m
      case Expr.Relationship(_, _, props, _) => props
      case otherVal =>
        throw CypherException.TypeMismatch(
          expected = Seq(Type.Map, Type.Node, Type.Relationship),
          actualValue = otherVal,
          context = "properties set on node",
        )
    }

    sealed trait Change
    case class Add(key: Symbol, value: Value) extends Change
    case class Remove(key: Symbol) extends Change

    // Build up the full set to events to process before processing them

    // Optionally drop existing properties
    val changesToResetNode =
      if (query.includeExisting) List()
      else
        properties.collect {
          case (key, _) if !(map.contains(key) || labelsProperty == key) =>
            Remove(key)
        }

    // Add all the new properties (or remove, for any NULL values)
    val changesToSetProperties = map.flatMap { case (key, pv) =>
      pv match {
        case Expr.Null =>
          // setting a property to null: remove that property
          properties.get(key) match {
            case Some(_) => Some(Remove(key))
            case None =>
              // property already didn't exist -- no-op
              None
          }
        case value =>
          // setting a property to a value
          Some(Add(key, value))
      }
    }
    val propertyChanges: List[Change] = (changesToResetNode ++ changesToSetProperties).toList

    val propertyChangeEvents: List[PropertyEvent] = propertyChanges.map {
      case Add(key, value) => PropertySet(key, PropertyValue(Expr.toQuineValue(value).getOrThrow))
      case Remove(key) => PropertyRemoved(key, properties.getOrElse(key, PropertyValue(QuineValue.Null)))
    }

    val newContext: QueryContext = context.get(query.nodeVar) match {
      case Some(node: Expr.Node) =>
        val newProperties = propertyChanges.foldLeft(node.properties) {
          case (props, Add(key, value)) => props + (key -> value)
          case (props, Remove(key)) => props - key
        }
        context + (query.nodeVar -> node.copy(properties = newProperties))
      case _ =>
        // node variable was not in context as a node, so return unchanged.
        context
    }

    InterpM.liftFutureUnsafe(
      processPropertyEvents(propertyChangeEvents).map(_ => newContext)(ExecutionContext.parasitic),
    )
  }

  final private[quine] def interpretSetEdge(
    query: SetEdge,
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] = {
    // Figure out what the other end of the edge is
    val otherVal = query.target.evalUnsafe(context)
    val other: QuineId = getQuineId(otherVal).getOrElse {
      throw CypherException.TypeMismatch(
        expected = Seq(Type.Node),
        actualValue = otherVal,
        context = "one extremity of an edge we are modifying",
      )
    }

    // Add the half-edge locally
    val edge: HalfEdge = HalfEdge(query.label, query.direction, other)
    val event = if (query.add) EdgeAdded(edge) else EdgeRemoved(edge)
    val setThisHalf = processEdgeEvents(event :: Nil)

    val newContext = query.bindRelation match {
      case None => context

      // TODO: record properties
      case Some(asName) =>
        val rel = Expr.Relationship(qid, query.label, Map.empty, other)
        val rel2 = if (query.direction == EdgeDirection.Outgoing) rel else rel.reflect
        context + (asName -> rel2)
    }

    // Rest of the query (along with instructions for the other half edge)
    val setOtherHalf = other ? (CheckOtherHalfEdge(
      halfEdge = edge.reflect(qid),
      action = Some(query.add),
      query = query.andThen,
      parameters,
      newContext,
      _,
    ))

    InterpM
      .futureSourceUnsafe(setThisHalf.flatMap(_ => setOtherHalf)(cypherEc))
      .map(_.result)
  }

  final private[quine] def interpretSetLabels(
    query: SetLabels,
    context: QueryContext,
  ): InterpM[CypherException, QueryContext] = {
    // get current label value
    val currentLabelValue = getLabels() match {
      case Some(lbls) => lbls
      case None => return InterpM.empty // TODO: should we error/warn here?
    }

    // Compute new label value
    val newLabelValue = if (query.add) {
      currentLabelValue ++ query.labels
    } else {
      currentLabelValue -- query.labels
    }

    // Set new label value
    val setLabelsFut = setLabels(newLabelValue)

    val newContext = context.get(query.nodeVar) match {
      case Some(node: Expr.Node) =>
        context + (query.nodeVar -> node.copy(labels = newLabelValue))
      case _ =>
        // node variable was not in context as a node, so return unchanged.
        context
    }
    InterpM.liftFutureUnsafe(setLabelsFut.map(_ => newContext)(cypherEc))
  }
}

/** @tparam Start the most specific Location this interpreter can handle. That is, if this interpreter runs on a node
  *               thread, [[Location.OnNode]] (see: OnNodeInterpreter). If this interpreter runs off-node,
  *               [[Location.External]] (see: AnchoredInterpreter). Bear in mind that CypherInterpreter is contravariant
  *               in Start, so a CypherInterpreter[OnNode] is also a CypherInterpreter[Anywhere], but not a
  *               CypherInterpreter[External] nor a CypherInterpreter[Location]
  */
trait CypherInterpreter[-Start <: Location] extends ProcedureExecutionLocation {

  import Query._

  protected def cypherEc: ExecutionContext

  def namespace: NamespaceId

  implicit protected def cypherProcessTimeout: Timeout

  protected def bypassSkipOptimization: Boolean

  /** Interpret a Cypher query into a [[Source]] of query results
    *
    * @note a [[Source]] can be run many times (possible 0 times), so this method is really just
    * creating 'instructions' for running the query as opposed to actually running it
    *
    * @param query Cypher query
    * @param context variables in scope
    * @param parameters query constants in scope
    * @return back-pressured source of results
    */
  def interpret(
    query: Query[Start],
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext]

  /** When calling [[interpret]] recursively, if the call is not being done
    * synchoronously, use [[interpretRecursive]] instead. For instance:
    *
    * {{{
    * // `interpret` is called synchronously
    * interpret(myQuery.subQuery1).flatMapConcat { x =>
    *
    *   // `interpretRecursive` will be called asynchronously as the stream runs!
    *   interpretRecursive(myQuery.subQuery2)
    * }
    */
  private[quine] def interpretRecursive(
    query: Query[Start],
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] = interpret(query, context)

  private object ValueQid {
    def unapply(value: Value): Option[QuineId] = for {
      quineValue <- Expr.toQuineValue(value).toOption
      quineId <- idProvider.valueToQid(quineValue)
    } yield quineId
  }

  /** Try to pull a node ID from an expression
    *
    * @return ID extracted from expression
    */
  final private[quine] def getQuineId(expr: Value): Option[QuineId] = expr match {
    case Expr.Node(other, _, _) => Some(other)
    case ValueQid(qid) => Some(qid)

    // TODO: are these honest? (they _are_ user visible - `MATCH (n) WHERE id(n) = bytes("CAFEBABE") RETURN n`)
    case Expr.Bytes(id, representsId @ _) => Some(QuineId(id)) // used by `FreshNodeId`

    // TODO: find a more principled way to do this, see [[IdFunc]]
    case Expr.Str(strId) =>
      idProvider.qidFromPrettyString(strId) match {
        case Failure(_) => None
        case Success(qid) => Some(qid)
      }

    case _ => None
  }

  final private[quine] def interpretEmpty(
    query: Empty,
    context: QueryContext,
  ): InterpM[CypherException, QueryContext] = InterpM.empty

  final private[quine] def interpretUnit(
    query: Unit,
    context: QueryContext,
  ): InterpM[CypherException, QueryContext] = InterpM.single(context)

  final private[quine] def interpretLoadCSV(
    query: LoadCSV,
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] = {
    def splitCols(line: String): Array[String] = {
      val rowBuilder = Array.newBuilder[String]
      val cellBuilder = new mutable.StringBuilder()
      var inQuoted: Boolean = false
      val characterIterator = line.iterator

      while (characterIterator.hasNext)
        characterIterator.next() match {
          case '"' if inQuoted =>
            if (!characterIterator.hasNext) {
              inQuoted = false
              rowBuilder += cellBuilder.result()
              return rowBuilder.result()
            }
            characterIterator.next() match {
              case '"' =>
                cellBuilder += '"'

              case c if c == query.fieldTerminator =>
                inQuoted = false
                rowBuilder += cellBuilder.result()
                cellBuilder.clear()

              // TODO: warn on this state?
              case c =>
                inQuoted = false
                cellBuilder += c
            }

          case '"' =>
            inQuoted = true

          case c if !inQuoted && c == query.fieldTerminator =>
            rowBuilder += cellBuilder.result()
            cellBuilder.clear()

          case c =>
            cellBuilder += c
        }

      rowBuilder += cellBuilder.result()
      rowBuilder.result()
    }

    val url: String = query.urlString.evalUnsafe(context).asString("LOAD CSV clause").getOrThrow
    val lineIterator = scala.io.Source.fromURL(url).getLines()

    val csvRows: InterpM[CypherException, QueryContext] = if (query.withHeaders) {
      val headerLine: Array[String] = splitCols(lineIterator.next())
      InterpM.fromIterator(() =>
        lineIterator.map { (line: String) =>
          val lineMap = Expr.Map {
            headerLine
              .zip(splitCols(line))
              .map { case (header, value) => header -> Expr.Str(value) }
              .toMap
          }
          context + (query.variable -> lineMap)
        },
      )
    } else {
      InterpM.fromIterator(() =>
        lineIterator.map { (line: String) =>
          val lineList = Expr.List {
            splitCols(line).toVector.map(Expr.Str)
          }
          context + (query.variable -> lineList)
        },
      )
    }

    csvRows
  }

  final private[quine] def interpretUnion(
    query: Union[Start],
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] = {
    val lhsResult = interpret(query.unionLhs, context)
    val rhsResult = interpret(query.unionRhs, context)
    lhsResult ++ rhsResult
  }

  final private[quine] def interpretOr(
    query: Or[Start],
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] = {
    val lhsResult = interpret(query.tryFirst, context)
    val rhsResult = interpret(query.trySecond, context)
    lhsResult orElse rhsResult
  }

  final private[quine] def interpretSemiApply[NextLocation <: Start](
    query: SemiApply[NextLocation],
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] = {
    val results = interpret(query.acceptIfThisSucceeds, context)
    val keepFut = query.inverted match {
      case false => results.take(1).fold(false)((_acc, _other) => true)
      case true => results.take(1).fold(true)((_acc, _other) => false)
    }
    keepFut.flatMap {
      case true => InterpM.single(context)
      case false => InterpM.empty
    }
  }

  final private[cypher] def interpretApply(
    query: Apply[Start],
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] =
    interpret(query.startWithThis, context)
      .flatMap(interpretRecursive(query.thenCrossWithThis, _))

  final private[quine] def interpretValueHashJoin(
    query: ValueHashJoin[Start],
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] = {
    val lhsResults = interpret(query.joinLhs, context)
    val rhsResults = interpret(query.joinRhs, context)

    lhsResults
      .fold(Map.empty[Value, List[QueryContext]]) { (acc, qc) =>
        val key = query.lhsProperty.evalUnsafe(qc)
        val value = qc :: acc.getOrElse(key, List.empty)
        acc + (key -> value)
      }
      .flatMap { (leftMap: Map[Value, List[QueryContext]]) =>
        rhsResults.mapConcat { (newContext: QueryContext) =>
          val rhsVal = query.rhsProperty.evalUnsafe(newContext)
          val matchingProp = leftMap.getOrElse(rhsVal, List.empty).map(_ ++ newContext)
          matchingProp
        }
      }
  }

  final private[quine] def interpretOptional[NextLocation <: Start](
    query: Optional[NextLocation],
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] = {
    val result = interpret(query.query, context)
    result.orElse(InterpM.single(context))
  }

  final private[quine] def interpretFilter(
    query: Filter[Start],
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] =
    interpret(query.toFilter, context).filter { (qc: QueryContext) =>
      /* This includes boolean expressions that are used as predicates in the
       * `WHERE` clause. In this case, anything that is not true is interpreted
       * as being false.
       */
      query.condition.evalUnsafe(qc) match {
        case Expr.True => true
        case Expr.List(l) => l.nonEmpty
        case _ => false
      }
    }

  final private[quine] def interpretSkip(
    query: Skip[Start],
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] = {
    // TODO: type error if number is not positive
    val skip = query.drop.evalUnsafe(context).asLong("SKIP clause").getOrThrow
    interpret(query.toSkip, context).drop(skip)
  }

  final private[quine] def interpretLimit(
    query: Limit[Start],
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] = {
    // TODO: type error if number is not positive
    val limit = query.take.evalUnsafe(context).asLong("LIMIT clause").getOrThrow
    interpret(query.toLimit, context).take(limit)
  }

  final private[quine] def interpretSort(
    query: Sort[Start],
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] = {
    val sourceToSort = interpret(query.toSort, context)

    // We need lazily to ensure that we don't re-use `priorityQueue` across materializations
    InterpM.lazyInterpM { () =>
      // The ordering will evaluate the query context on all columns
      // Because `orderingBy` can throw an exception, adding/removing items from this queue can throw a CypherException
      // but the InterpM[CypherException, _] will correctly handle CypherExceptions that are thrown
      val priorityQueue = collection.mutable.PriorityQueue.empty(QueryContext.orderingBy(query.by))

      sourceToSort
        .fold(priorityQueue)(_ += _)
        .flatMap { queue =>
          InterpM.fromIterator(() =>
            new Iterator[QueryContext] {
              def hasNext = priorityQueue.nonEmpty
              def next() = priorityQueue.dequeue()
            },
          )
        }
    }
  }

  private[quine] def interpretReturn(
    query: Return[Start],
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] =
    query match {
      case Return(toReturn, Some(orderBy), None, None, Some(take), columns @ _) =>
        // TODO this code can handle Some(drop) too with only very minor modification
        val capacity = take.evalUnsafe(context).asLong("RETURN clause's LIMIT").getOrThrow
        val sourceToTop = interpret(toReturn, context)

        // We need lazily to ensure that we don't re-use `priorityQueue` across materializations
        InterpM.lazyInterpM { () =>
          // The `maximumSize` evicts the largest element whenever the queue gets too big
          // The ordering is inverted so smaller elements appear larger (and get evicted first)
          val priorityQueue: MinMaxPriorityQueue[QueryContext] = MinMaxPriorityQueue
            .orderedBy(QueryContext.orderingBy(orderBy).reversed)
            .maximumSize(capacity.toInt)
            .create()

          sourceToTop
            .fold(priorityQueue) { (queue, elem) => queue.add(elem); queue }
            .flatMap { queue =>
              InterpM
                .fromIterator(() =>
                  new Iterator[QueryContext] {
                    def hasNext = !queue.isEmpty
                    def next() = queue.removeFirst()
                  },
                )
                .take(capacity)
            }
        }
      case fallback @ Return(_, _, _, _, _, _) =>
        interpret(fallback.delegates.naiveStack, context)(parameters, logConfig)
    }

  final private[quine] def interpretDistinct(
    query: Distinct[Start],
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] = {
    val sourceToDedup = interpret(query.toDedup, context)

    // We need lazily to ensure that we don't re-use `seen` across materializations
    InterpM.lazyInterpM { () =>
      val seen = collection.mutable.Set.empty[Seq[Value]]

      sourceToDedup.filter { (qc: QueryContext) =>
        seen.add(query.by.map(_.evalUnsafe(qc)))
      }
    }
  }

  final private[quine] def interpretUnwind(
    query: Unwind[Start],
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] = {

    /* Deciding how to unwind the value is a peculiar process. The Neo4j Cypher
     * manual claims that unwinding anything that is not [[Expr.Null]] or
     * [[Expr.List]] should result in an error. However, on the same page, they
     * give the following example:
     *
     * ```
     * WITH \[\[1, 2\],\[3, 4\], 5\] AS nested
     * UNWIND nested AS x
     * UNWIND x AS y       // At some point, 5 goes through here and returns 5!
     * RETURN y
     * ==> [ { y: 1 }, { y: 2 }, { y: 3 }, { y: 4 }, { y: 5 } ]
     * ```
     *
     * Alec's interpretation of the manual is as follows: if Cypher can detect
     * at query planning time that `UNWIND` is receiving a non-list, it will
     * produce an error. If not, the runtime will unwind any invalid value to
     * a one row output containing just the value.
     */
    val list: Vector[Value] = query.listExpr.evalUnsafe(context) match {
      case Expr.Null => Vector()
      case Expr.List(l) => l
      case path: Expr.Path => path.toList.list
      case otherVal => Vector(otherVal) // see above comment for why this isn't a type error
    }

    InterpM(list)
      .map((elem: Value) => context + (query.as -> elem))
      .flatMap(interpretRecursive(query.unwindFrom, _))
  }

  private[quine] def interpretAdjustContext(
    query: AdjustContext[Start],
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] =
    interpret(query.adjustThis, context).map { (qc: QueryContext) =>
      val removed = query.dropExisting match {
        case true => QueryContext.empty
        case false => qc
      }
      removed ++ QueryContext(query.toAdd.map { case (k, e) => k -> e.evalUnsafe(qc) }.toMap)
    }

  /* I (Alec) find this aggregation behaviour somewhat un-intuitive. [Here is a
   * webpage that details the aggregating behaviour][0], hopefully convincing you
   * that this _is_ the correct behaviour
   *
   * [0]: https://neo4j.com/docs/cypher-manual/current/functions/aggregating/
   */
  final private[quine] def interpretEagerAggregation(
    query: EagerAggregation[Start],
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] = {
    val (criteriaSyms: Vector[Symbol], criteriaExprs: Vector[Expr]) = query.aggregateAlong.unzip
    val (aggregateSyms: Vector[Symbol], aggregators: Vector[Aggregator]) = query.aggregateWith.unzip
    val sourceToAggregate = interpret(query.toAggregate, context)

    /* This condition is subtle; unless we have at least one criteria along
     * which to group, _there will always be exactly one result_.
     *
     * Motivating example:
     *
     *   - `UNWIND [] AS N RETURN    count(*)` returns `[ { count(*): 0 } ]`
     *   - `UNWIND [] AS N RETURN N, count(*)` returns `[]`
     */
    if (criteriaSyms.isEmpty) {

      // We need lazily to ensure that we don't re-use `aggregatedStates` across materializations
      InterpM.lazyInterpM { () =>
        val aggregatedStates = aggregators.map(_.aggregate())

        sourceToAggregate
          .fold(aggregatedStates) { (states, result) =>
            for (state <- states)
              state.visitRow(result)
            states
          }
          .map { aggregateValues =>
            val newCtx = QueryContext(
              aggregateSyms.zip(aggregateValues.map(_.result())).toMap,
            )
            if (query.keepExisting) context ++ newCtx else newCtx
          }
      }
    } else {

      // We need lazily to ensure that we don't re-use `aggregatedStates` across materializations
      InterpM.lazyInterpM { () =>
        val aggregatedStates = collection.mutable.Map.empty[Vector[Value], Vector[AggregateState]]

        sourceToAggregate
          .fold(aggregatedStates) { (buckets, result) =>
            val keys = criteriaExprs.map(_.evalUnsafe(result))
            val states = buckets.getOrElseUpdate(keys, aggregators.map(_.aggregate()))
            for (state <- states)
              state.visitRow(result)
            buckets
          }
          .mapConcat { buckets =>
            buckets.toVector.map { case (criteriaValues, aggregateValues) =>
              val newCtx = QueryContext(
                criteriaSyms.zip(criteriaValues).toMap ++
                aggregateSyms.zip(aggregateValues.map(_.result())),
              )
              if (query.keepExisting) context ++ newCtx else newCtx
            }
          }
      }
    }
  }

  final private[quine] def interpretDelete(
    query: Delete,
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] =
    query.toDelete.evalUnsafe(context) match {
      case Expr.Null => InterpM.empty

      case Expr.Node(qid, _, _) =>
        val completed = (qid ? (DeleteNodeCommand(deleteEdges = query.detach, _))).flatten
          .flatMap {
            case DeleteNodeCommand.Success => Future.successful(())
            case DeleteNodeCommand.Failed(n) =>
              Future.failed(
                CypherException.ConstraintViolation(
                  s"Node $qid cannot be deleted since it still has $n relationships.",
                ),
              )
          }(cypherEc)
        InterpM.liftFutureUnsafe(completed).map(_ => context)

      case Expr.Relationship(from, name, _, to) =>
        val he = HalfEdge(name, EdgeDirection.Outgoing, to)
        val firstHalf = (from ? (RemoveHalfEdgeCommand(he, _))).flatten
        val secondHalf = (to ? (RemoveHalfEdgeCommand(he.reflect(from), _))).flatten
        InterpM.liftFutureUnsafe(firstHalf.zip(secondHalf)).map(_ => context)

      // case Expr.Path => TODO

      case otherVal =>
        throw CypherException.TypeMismatch(
          expected = Seq(Type.Node, Type.Relationship, Type.Path),
          actualValue = otherVal,
          context = "target for deletion",
        )
    }

  final private[quine] def interpretProcedureCall(
    query: ProcedureCall,
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] = {

    // Remap the procedure outputs and add existing input columns
    val makeResultRow: Vector[Value] => QueryContext = query.returns match {
      case None =>
        val variables = query.procedure.outputColumns.variables
        (outputs: Vector[Value]) => context ++ variables.view.zip(outputs.view)
      case Some(remaps) =>
        val indices: Vector[(Symbol, Int)] = remaps.view.map { case (orig, out) =>
          out -> query.procedure.outputColumns.variables.indexOf(orig)
        }.toVector
        (outputs: Vector[Value]) => context ++ indices.view.map { case (key, idx) => key -> outputs(idx) }
    }

    InterpM
      .liftUnsafe(
        query.procedure
          .call(context, query.arguments.map(_.evalUnsafe(context)), this)(parameters, cypherProcessTimeout, logConfig),
      )
      .named(s"cypher-procedure-${query.procedure.name}")
      .map(makeResultRow)
  }

  final private[quine] def interpretSubQuery(
    query: SubQuery[Start],
    context: QueryContext,
  )(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] =
    /* Variable scoping here is tricky:
     *
     *   - subquery runs against only the imported subcontext
     *   - for non-unit subqueries, subquery output columns get _prepended_ to existing columns
     *     (unlike `with` or `unwind`)
     *   - for unit subqueries, the subquery outputs exactly the same columns (and the same rows)
     *     as it receives -- i.e., each call to `interpret` produces a singleton Source of only the
     *     parent context
     *
     * Collisions between subquery column outputs and existing columns are ruled out statically.
     */
    if (query.isUnitSubquery) {
      interpret(query.subQuery, context.subcontext(query.importedVariables))
        .fold(context)((_, originalContext) => originalContext)
    } else {
      interpret(query.subQuery, context.subcontext(query.importedVariables)).map(_ ++ context)
    }

  final private[quine] def interpretRecursiveSubquery(query: RecursiveSubQuery[Start], context: QueryContext)(implicit
    parameters: Parameters,
    logConfig: LogConfig,
  ): InterpM[CypherException, QueryContext] = {
    val initialRecursiveContexts: InterpM[CypherException, (QueryContext, Map[Symbol, Type])] =
      interpretRecursive(query.initialVariables.setup, context).map { primedRow =>
        val initialVariableBindings = query.initialVariables.initialValues.view.mapValues(_.evalUnsafe(primedRow))
        val row = QueryContext.empty ++ initialVariableBindings
        val expectedTypes = initialVariableBindings.mapValues(_.typ).toMap
        row -> expectedTypes
      }
    // Recursive function that runs the recursive subquery. Runs off-thread, so must use `interpret` and not
    // `interpretRecursive`
    // QU-1947 we should be retaining a cache of the QueryContexts we've seen so far to detect (and error out of)
    //   an infinite loop
    def run(context: QueryContext, expectedTypes: Map[Symbol, Type]): InterpM[CypherException, QueryContext] = {
      case class PartialResults(wip: Seq[QueryContext], done: Seq[QueryContext])

      // First, invoke the subquery given the context we were provided
      val results = interpret(query.innerQuery, context)
        .map { resultContext =>
          // NB this row-by-row typecheck is inefficient, but it's the only way to get the error messages right
          // We may want to consider making this toggleable between "errors that cite the place the problem occurred"
          // vs "errors that cite the wrong spot but are cheap". In either case, we should get errors on the same
          // queries.

          val illegalTypes = for {
            (outputVariable, value) <- resultContext.environment.view
            if query.variableMappings.outputToInput.contains(outputVariable) // if this is a recursive variable
            actualType = value.typ
            expectedType = expectedTypes(query.variableMappings.outputToInput(outputVariable))
            if !expectedType.assignableFrom(actualType)
            plainVariable = query.variableMappings.outputToPlain(outputVariable)
          } yield CypherException.TypeMismatch(
            expected = Seq(expectedType),
            actualValue = value,
            context = s"recursive subquery return value (variable `${plainVariable.name}`)",
          )

          illegalTypes.headOption.foreach(throw _) // fail the query if there are any type errors

          resultContext
        }
      val folded: InterpM[CypherException, PartialResults] = results
        .fold(PartialResults(Nil, Nil)) { case (PartialResults(wip, done), resultContext) =>
          // partition this invocation of the subquery into 2 classes of rows: "WIP" (done = false) and "done" (done = true)
          if (query.doneExpression.evalUnsafe(resultContext) == Expr.True) PartialResults(wip, done :+ resultContext)
          else PartialResults(wip :+ resultContext, done)
        }
      val doneThenRecursedResults: InterpM[CypherException, QueryContext] = folded.flatMap {
        case PartialResults(wip, done) =>
          // Return the "done" rows as-is, then recursively run the "WIP" rows
          val doneSource: InterpM[CypherException, QueryContext] = InterpM(done)
          // For the "wip" rows (those that haven't yet hit the termination condition) filter the columns down to only
          // those explicitly specified as recursive inputs, then recurse
          val wipSource: InterpM[CypherException, QueryContext] = InterpM(wip)
            .map { resultContext =>
              // Downsample each context to only the relevant variables, rebinding them from their "output" names to their
              // "input" names in the process
              val reboundRecursiveVariables = resultContext.environment.collect {
                case (k, v) if query.variableMappings.outputToInput.contains(k) =>
                  query.variableMappings.outputToInput(k) -> v
              }
              QueryContext(reboundRecursiveVariables)
            }
            .flatMap(row => run(row, expectedTypes))
          doneSource.concat(wipSource)
      }
      doneThenRecursedResults
    }

    initialRecursiveContexts
      .flatMap { case (initialRow, expectedTypes) =>
        run(initialRow, expectedTypes)
      }
      .map(_ ++ context)
  }
}
