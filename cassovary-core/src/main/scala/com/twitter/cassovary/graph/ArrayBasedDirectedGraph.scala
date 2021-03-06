package com.twitter.cassovary.graph

import com.google.common.annotations.VisibleForTesting
import com.twitter.cassovary.graph.StoredGraphDir._
import com.twitter.cassovary.graph.node._
import com.twitter.cassovary.util._
import com.twitter.finagle.stats.{DefaultStatsReceiver, Stat}
import com.twitter.logging.Logger
import com.twitter.util.Future.when
import com.twitter.util.{Await, Future, FuturePool}

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable

/**
 * Construct an array based directed graph based on a set of edges. The graph can be constructed in
 * the following ways based on the stored direction:
 * 1. If OnlyIn or OnlyOut edges need to be kept, supply only those edges in the edges iterableSeq
 * 2. If BothInOut edges need to be kept, supply only the outgoing edges in the edges iterableSeq
 */

/**
 * This case class holds a node's id, all its out edges, and the max
 * id of itself and ids of nodes in its out edges
 */
case class NodeIdEdgesMaxId(id: Int, edges: Array[Int], maxId: Int)

object NodeIdEdgesMaxId {
  def apply(id: Int, edges: Array[Int]) =
    new NodeIdEdgesMaxId(id, edges, edges.foldLeft[Int](id)((x, y) => x max y))
}

/**
 * ArrayBasedDirectedGraph can be stored with neighbors sorted or not. Therefore there
 * are 3 strategies of loading a graph from input:
 *   `AlreadySorted` - creates a graph with sorted neighbors from sorted input
 *   `SortWhileReading` - creates a graph with sorted neighbors sorting them
 *                        while reading
 *   `LeaveUnsorted` - creates graph with unsorted neighbors (default)
 */
object NeighborsSortingStrategy extends Enumeration {
  type NeighborsSortingStrategy = Value

  val AlreadySorted = Value
  val SortWhileReading = Value
  val LeaveUnsorted = Value
}

object ArrayBasedDirectedGraph {
  import NeighborsSortingStrategy._

  /**
   * @param forceSparseRepr if Some(true), the code saves storage at the expense of speed
   *                        by using HashMap instead of Array. If Some(false), chooses
   *                        Array instead. If None, the code calculates whether the graph
   *                        is sparse based on the number of nodes and maximum node id.
   * @return
   */
  def apply(iteratorSeq: Seq[Iterable[NodeIdEdgesMaxId]],
      parallelismLimit: Int,
      storedGraphDir: StoredGraphDir,
      neighborsSortingStrategy: NeighborsSortingStrategy = LeaveUnsorted,
      forceSparseRepr: Option[Boolean] = None): ArrayBasedDirectedGraph = {
    val constructor = new ArrayBasedDirectedGraphConstructor(iteratorSeq,
      parallelismLimit, storedGraphDir, neighborsSortingStrategy, forceSparseRepr)
    constructor()
  }

  @VisibleForTesting
  def apply(iterable: Iterable[NodeIdEdgesMaxId],
            storedGraphDir: StoredGraphDir,
            neighborsSortingStrategy: NeighborsSortingStrategy): ArrayBasedDirectedGraph = {
    apply(Seq(iterable), 1, storedGraphDir, neighborsSortingStrategy)
  }

  // A private convenience class encapsulating a concurrent representation of the collection of nodes
  private class NodeCollection(forceSparsity: Option[Boolean],
      val maxNodeId: Int, val numNodes: Int, val numEdges: Long) {

    val considerGraphSparse: Boolean = forceSparsity getOrElse {
      // sparse if number of nodes is much less than maxNodeId, AND
      // number of edges is also less than maxNodeId. If number of edges
      // were similar to or greater than maxNodeId, then the extra overhead of allocating
      // an array of size maxNodeId is not too much relative to the storage occupied by
      // the edges themselves
      (numNodes * 8 < maxNodeId) && (numEdges < 4 * maxNodeId)
    }

    private val table = Int2ObjectMap[Node](considerGraphSparse, Some(numNodes), Some(maxNodeId),
      isConcurrent = false)

    def nodeIdsIterator = table.keysIterator
    def nodesIterator = table.valuesIterator

    def apply(id: Int) = table(id)
    def get(id: Int) = table.get(id)
    def contains(id: Int) = table.contains(id)

    def add(node: Node): Unit = {
      val id = node.id
      assert(table.get(id) == None, s"Duplicate node $id detected")
      table.put(id, node)
    }

  }

  /**
   * Constructs array based directed graph
   */
  private class ArrayBasedDirectedGraphConstructor(
      iterableSeq: Seq[Iterable[NodeIdEdgesMaxId]],
      parallelismLimit: Int,
      storedGraphDir: StoredGraphDir,
      neighborsSortingStrategy: NeighborsSortingStrategy,
      forceSparseRepr: Option[Boolean]
      ) {
    private lazy val log = Logger.get()
    private val statsReceiver = DefaultStatsReceiver

    private val emptyArray = Array[Int]()

    private val futurePool = new BoundedFuturePool(FuturePool.unboundedPool, parallelismLimit)

    /**
     * This case class holds either a part of the total graph loaded in one thread
     * (T = Node) or the whole graph (T = Seq[Node])
     */
    private case class GraphInfo[T](nodesOutEdges: Seq[T], //nodes explicitly given to us
        maxNodeId: Int, // across all nodes (explicitly or implicitly given)
        numNodes: Int, // only number of nodes explicitly given to us
        numEdges: Long)

    /**
     * Construct an array-based graph from an sequence of `NodeIdEdgesMaxId` iterables
     * This function builds the array-based graph from a seq of nodes with out edges
     * using the following steps:
     * 0. read from file and construct a sequence of Nodes
     * 1. create an array of nodes and mark all positions in the array where there is a node
     * 2. instantiate nodes that only have in-edges (thus has not been created in the input)
     * next steps apply only if in-edge is needed
     * 3. calculate in-edge array sizes
     * 4. (if in-edge dir only) remove out edges
     * 5. instantiate in-edge arrays
     * 6. iterate over the sequence of nodes again, instantiate in-edges
     */
    def apply(): ArrayBasedDirectedGraph = {

      val result: Future[ArrayBasedDirectedGraph] = for {
        graphInfo <- fillOutEdges(iterableSeq)
        allNodeIdsSet <- markEmptyNodes(graphInfo)
        nodeCollection <- createExplicitlyGivenNodes(graphInfo, allNodeIdsSet.size)
        nodesWithNoOutEdges <- createEmptyNodes(nodeCollection, allNodeIdsSet)
        _ <- when(storedGraphDir == StoredGraphDir.BothInOut) {
          fillMissingInEdges(nodeCollection, graphInfo.nodesOutEdges, nodesWithNoOutEdges)
        }
      } yield
        new ArrayBasedDirectedGraph(nodeCollection, storedGraphDir)

      val graph = Await.result(result)
      log.debug("Finished building graph")
      graph
    }

    /**
     * Reads `iterableSeq`'s edges, creates nodes and puts them in an `ArrayBuffer[Seq[Node]]`.
     * In every node only edges directly read from input are set.
     * @return Future with information for the graph
     */
    private def fillOutEdges(iterableSeq: Seq[Iterable[NodeIdEdgesMaxId]]):
    Future[GraphInfo[Seq[Node]]] = {
      log.debug("loading nodes and out edges from file in parallel")
      val nodesOutEdges = new mutable.ArrayBuffer[Seq[Node]]
      var maxNodeIdAll = 0
      var numEdgesAll = 0L
      var numNodesAll = 0

      val outEdgesAll: Future[Seq[GraphInfo[Node]]] = Stat.time(
        statsReceiver.stat("graph_dump_load_partial_nodes_and_out_edges_parallel")) {
        Future.collect(iterableSeq.map(i => readOutEdges(i.iterator)))
      }

      outEdgesAll.map {
        // aggregate across parts
        case outEdgesOnePart => outEdgesOnePart.foreach {
          case GraphInfo(nodesInPart, maxIdInPart, numNodesInPart, numEdgesInPart) =>
            nodesOutEdges += nodesInPart
            maxNodeIdAll = maxNodeIdAll max maxIdInPart
            numNodesAll += numNodesInPart
            numEdgesAll += numEdgesInPart
        }
        GraphInfo[Seq[Node]](nodesOutEdges, maxNodeId = maxNodeIdAll,
          numNodes = numNodesAll, numEdges = numEdgesAll)
      }
    }

    /**
     * Reads out edges from iterator and returns `GraphInfo` object.
     */
    private def readOutEdges(iterator: Iterator[NodeIdEdgesMaxId]):
    Future[GraphInfo[Node]] = futurePool {
      Stat.time(statsReceiver.stat("graph_load_read_out_edge_from_dump_files")) {
        val nodesWithEdges = new mutable.ArrayBuffer[Node]
        var newMaxId = 0
        var numEdges = 0L

        iterator foreach { item =>
          val id = item.id
          newMaxId = newMaxId max item.maxId
          numEdges += item.edges.length
          val edges = if (neighborsSortingStrategy == SortWhileReading) item.edges.sorted else item.edges
          val newNode = ArrayBasedDirectedNode(id, edges, storedGraphDir,
            neighborsSortingStrategy != LeaveUnsorted)
          nodesWithEdges += newNode
        }
        GraphInfo[Node](nodesWithEdges, maxNodeId = newMaxId, numNodes = nodesWithEdges.length,
          numEdges = numEdges)
      }
    }

    /**
     * Mark those node ids that have any incoming edge to help determine which nodes have
     * some incoming but no outgoing edges. We are calling these nodes "empty nodes".
     */
    private def markEmptyNodes(graphInfo: GraphInfo[Seq[Node]]): Future[ArrayBackedSet] = {
      log.debug("in markEmptyNodes")
      val allNodeIdsSet = new ArrayBackedSet(graphInfo.maxNodeId)
      Stat.time(statsReceiver.stat("graph_load_mark_create_empty_nodes")) {
        Future.join(
          graphInfo.nodesOutEdges.map(nodes =>
            futurePool {
              nodes foreach { node =>
                allNodeIdsSet.add(node.id)
                val neighborIds = storedGraphDir match {
                  case StoredGraphDir.OnlyIn => node.inboundNodes()
                  case _ => node.outboundNodes()
                }
                neighborIds foreach { i => allNodeIdsSet.add(i) }
              }
            })
        ) map { _ => allNodeIdsSet}
      }
    }

    /**
     * Create nodes that were explicitly given in input files. Note that
     * we are doing this serially in one thread deliberately to not
     * have to create a concurrent representation in nodeCollection.
     * @return a representation of the collection of nodes
     */
    private def createExplicitlyGivenNodes(graphInfo: GraphInfo[Seq[Node]],
        numNodesTotal: Int): Future[NodeCollection] = futurePool {
      log.debug("in createExplicitlyGivenNodes")
      val nodeCollection = new NodeCollection(forceSparseRepr, graphInfo.maxNodeId,
        numNodesTotal, graphInfo.numEdges)
      graphInfo.nodesOutEdges.map { nodes =>
        nodes foreach { node => nodeCollection.add(node) }
      }
      nodeCollection
    }

    /**
     * Create implicit aka empty nodes that were not given in input but appeared
     * as neighbors of the explicit nodes
     * @return Seq of these empty nodes
     */
    private def createEmptyNodes(nodeColl: NodeCollection,
        allNodeIdsSet: ArrayBackedSet): Future[Seq[Node]] = futurePool {
      /* now create these empty nodes */
      val nodesWithNoOutEdges = new mutable.ArrayBuffer[Node]()
      allNodeIdsSet.foreach { i =>
        if (nodeColl.get(i).isEmpty) {
          val node = ArrayBasedDirectedNode(i, emptyArray, storedGraphDir,
            neighborsSortingStrategy != LeaveUnsorted)
          nodeColl.add(node)
          if (storedGraphDir == StoredGraphDir.BothInOut)
            nodesWithNoOutEdges += node
        }
      }
      nodesWithNoOutEdges
    }

    private def fillMissingInEdges(nodeColl: NodeCollection, nodesOutEdges: Seq[Seq[Node]],
                                   nodesWithNoOutEdges: Seq[Node]):
    Future[Unit] = {
      log.debug("calculating in edges sizes")

      val inEdgeSizes = Int2ObjectMap[AtomicInteger](nodeColl.considerGraphSparse,
        Some(nodeColl.numNodes), Some(nodeColl.maxNodeId), isConcurrent = false)
      nodeColl.nodeIdsIterator foreach { i => inEdgeSizes.update(i, new AtomicInteger()) }

      def addInEdge(dest: Int, source: Int): Unit = {
        val inEdgeIndex = inEdgeSizes(dest).getAndIncrement
        nodeColl(dest).asInstanceOf[FillingInEdgesBiDirectionalNode].inEdges(inEdgeIndex) = source
      }

      def incEdgeSize(id: Int) { inEdgeSizes(id).incrementAndGet() }

      def getAndResetEdgeSize(id: Int) = {
        val sz = inEdgeSizes(id).intValue()
        if (sz > 0) inEdgeSizes(id).set(0)
        sz
      }

       // Calculates sizes of incoming edges arrays.
      def findInEdgesSizes(nodesOutEdges: Seq[Seq[Node]]): Future[Unit] = {
        Stat.time(statsReceiver.stat("graph_load_find_in_edge_sizes")) {

          val futures = nodesOutEdges map {
            nodes => futurePool {
              nodes foreach {
                node => node.outboundNodes foreach { outEdge =>
                  incEdgeSize(outEdge)
                }
              }
            }
          }

          Future.join(futures)
        }
      }

      def instantiateInEdges(): Future[Unit] = {
        log.debug("instantiate in edges")
        Stat.time(statsReceiver.stat("graph_load_instantiate_in_edge_arrays")) {
          val futures = (nodesOutEdges.iterator ++ Iterator(nodesWithNoOutEdges)).map {
            (nodes: Seq[Node]) => futurePool {
              nodes foreach { node =>
               // reset inEdgesSizes, and use it as index pointer of
               // the current insertion place when adding in edges
                val edgeSize = getAndResetEdgeSize(node.id)
                if (edgeSize > 0) {
                  node.asInstanceOf[FillingInEdgesBiDirectionalNode].createInEdges(edgeSize)
                }
              }
            }
          }.toSeq
          Future.join(futures)
        }
      }

      def populateInEdges(): Future[Unit] = {
        log.debug("populate in edges")
        Stat.time(statsReceiver.stat("graph_load_read_in_edge_from_dump_files")) {
          val futures = nodesOutEdges.map {
            (nodes: Seq[Node]) => futurePool {
              nodes foreach { node =>
                node.outboundNodes foreach { outEdge =>
                  addInEdge(outEdge, node.id)
                }
              }
            }
          }
          Future.join(futures)
        }
      }

      def finishInEdgesFilling(): Future[Unit] = {
        log.debug("finishing filling")
        Stat.time(statsReceiver.stat("finishing_filling_in_edges")) {
          val futures = nodesOutEdges.map {
            nodes => futurePool {
              nodes.foreach {
                node =>
                  node.asInstanceOf[FillingInEdgesBiDirectionalNode].sortInNeighbors()
              }
            }
          }
          Future.join(futures)
        }
      }

      for {
        _ <- findInEdgesSizes(nodesOutEdges)
        _ <- instantiateInEdges()
        _ <- populateInEdges()
        _ <- when(neighborsSortingStrategy != LeaveUnsorted) (finishInEdgesFilling())
      } yield ()

    }
  }
}


/**
 * This class is an implementation of the directed graph trait that is backed by an array
 * The private constructor takes as its input a list of (@see Node) nodes, then stores
 * nodes in an array. It also builds all edges which are also stored in array.
 *
 * @param nodeCollection the collection of nodes with edges instantiated
 * @param storedGraphDir the graph direction(s) stored
 */
class ArrayBasedDirectedGraph private (nodeCollection: ArrayBasedDirectedGraph.NodeCollection,
                              val storedGraphDir: StoredGraphDir) extends DirectedGraph[Node] {

  override lazy val maxNodeId = nodeCollection.maxNodeId

  val nodeCount = nodeCollection.numNodes
  val edgeCount = if (isBiDirectional) 2* nodeCollection.numEdges else nodeCollection.numEdges

  def iterator = nodeCollection.nodesIterator

  def getNodeById(id: Int) = {
    if ( (id < 0) || (id > maxNodeId)) {
      None
    } else nodeCollection.get(id)
  }
}
