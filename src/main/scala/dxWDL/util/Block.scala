/*

 Split a graph into sub-blocks, each of which contains a bunch of toplevel
expressions and one:
   1) call to workflow or task
or 2) conditional with one or more calls inside it
or 3) scatter with one or more calls inside it.

     For example:

     call x
     Int a
     Int b
     call y
     call z
     String buf
     Float x
     scatter {
       call V
     }

     =>

     block
     1        [call x, Int a, Int b]
     2        [call y]
     3        [call z]
     4        [String buf, Float x, scatter]
*/

package dxWDL.util

import wom.expression.WomExpression
import wom.graph._
import wom.graph.expression._

// A sorted group of graph nodes, that match some original
// set of WDL statements.
case class Block(nodes : Vector[GraphNode]) {
    def prettyPrint : String = {
        val desc = nodes.map{ node =>
            "    " + WomPrettyPrint.apply(node) + "\n"
        }.mkString("")
        s"""|Block [
            |${desc}
            |]""".stripMargin
    }

    // Check that this block is valid.
    // 1) It can have zero or one top-level calls
    def validate() : Unit = {
        val topLevelCalls: Vector[CallNode] = nodes.collect{
            case x:CallNode => x
        }.toVector
        val numTopLeveleCalls = topLevelCalls.size
        if (numTopLeveleCalls > 1) {
            Utils.error(this.prettyPrint)
            throw new Exception(s"${numTopLeveleCalls} calls in block")
        }
    }
}

object Block {
    // A trivial expression has no operators, it is either a constant WomValue
    // or a single identifier. For example: '5' and 'x' are trivial. 'x + y'
    // is not.
    def isTrivialExpression(expr: WomExpression) : Boolean = {
        val inputs = expr.inputs
        if (inputs.size > 1)
            return false
        if (Utils.isExpressionConst(expr))
            return true
        // The expression may have one input, but could still have an operator.
        // For example: x+1, x + x.
        expr.sourceString == inputs.head
    }

    // The block is a singleton with one statement which is a call. The call
    // has no subexpressions. Note that the call may not provide
    // all the callee's arguments.
    def isCallWithNoSubexpressions(node: GraphNode) : Boolean = {
        node match {
            case call : CallNode =>
                call.inputDefinitionMappings.forall{
                    case (_, expr: WomExpression) => isTrivialExpression(expr)
                    case (_, _) => true
                }
            case _ => false
        }
    }

    // Deep search for all calls in a graph
    def deepFindCalls(nodes: Seq[GraphNode]) : Vector[CallNode] = {
        nodes.foldLeft(Vector.empty[CallNode]) {
            case (accu: Vector[CallNode], call:CallNode) =>
                accu :+ call
            case (accu, ssc:ScatterNode) =>
                accu ++ deepFindCalls(ssc.innerGraph.nodes.toVector)
            case (accu, ifStmt:ConditionalNode) =>
                accu ++ deepFindCalls(ifStmt.innerGraph.nodes.toVector)
            case (accu, _) =>
                accu
        }.toVector
    }

    // Is the call [callName] invoked in one of the nodes, or their
    // inner graphs?
    private def graphContainsCall(callName: String,
                                  nodes: Set[GraphNode]) : Boolean = {
        nodes.exists{
            case callNode: CallNode =>
                callNode.identifier.localName.value == callName
            case cNode: ConditionalNode =>
                graphContainsCall(callName, cNode.innerGraph.nodes)
            case scNode: ScatterNode =>
                graphContainsCall(callName, scNode.innerGraph.nodes)
            case _ => false
        }
    }

    // Find the toplevel graph node that contains this call
    private def findCallByName(callName: String,
                               nodes: Set[GraphNode]) : GraphNode = {
        val topNodeContainingCall = nodes.find{
            case callNode: CallNode =>
                callNode.identifier.localName.value == callName
            case cNode: ConditionalNode =>
                graphContainsCall(callName, cNode.innerGraph.nodes)
            case scNode: ScatterNode =>
                graphContainsCall(callName, scNode.innerGraph.nodes)
            case _ => false
        }
        topNodeContainingCall match {
            case None => throw new Exception(s"Could not find call ${callName}")
            case Some(node) => node
        }
    }

    // Sort the graph into a linear set of blocks, according to
    // dependencies.  Each block is itself sorted. The dependencies
    // impose a partial ordering on the graph. To make it correspond
    // to the original WDL, we attempt to maintain the original line
    // ordering.
    //
    // For example, in the workflow below:
    // workflow foo {
    //    call A
    //    call B
    // }
    // the block splitting algorithm could generate:
    //
    //    1           2
    //  [ call B ]  [ call A ]
    //  [ call A ]  [ call B ]
    //
    // We prefer option #2 because it resembles the original.
    def splitIntoBlocks(graph: Graph, wdlSourceCode: String) :
            (Vector[GraphInputNode],   // inputs
             Vector[Block], // blocks
             Vector[GraphOutputNode]) // outputs
    = {
        //System.out.println(s"SplitIntoBlocks ${nodes.size} nodes")
        assert(graph.nodes.size > 0)
        var rest : Set[GraphNode] = graph.nodes
        var blocks = Vector.empty[Block]
        val callToSrcLine = ParseWomSourceFile.scanForCalls(wdlSourceCode)

        // sort from low to high according to the source lines.
        val callsLoToHi : Vector[(String, Int)] = callToSrcLine.toVector.sortBy(_._2)

        // The first block has the graph inputs
        val inputBlock = graph.inputNodes.toVector
        rest --= inputBlock.toSet

        // The last block has the graph outputs
        val outputBlock = graph.outputNodes.toVector
        rest --= outputBlock.toSet

        // Create a separate block for each call. This maintains
        // the sort order from the origial code.
        //
        for ((callName, _) <- callsLoToHi) {
            assert(!rest.isEmpty)
            val node = findCallByName(callName, rest)

            // Build a vector where the callNode comes LAST
            val ancestors = node.upstreamAncestry.intersect(rest)
            val blockNodes = ancestors.toVector :+ node
            val blockNodesClean =
                blockNodes.filter{ x => !x.isInstanceOf[GraphInputNode] }

            /*System.err.println(s"""|block for call
                                   |  call=${callName}
                                   |${WomPrettyPrint.apply(blockNodesClean.toSeq)}
                                   |
                                   |""".stripMargin)*/
            val crnt = Block(blockNodesClean)
            blocks :+= crnt
            rest = rest -- blockNodesClean.toSet
        }

        val allBlocks =
            if (rest.size > 0) {
                // Add an additional block for anything not belonging to the calls
                blocks :+ Block(rest.toVector)
            } else {
                blocks
            }
        allBlocks.foreach{ b => b.validate() }
        (inputBlock, allBlocks, outputBlock)
    }

    def dbgPrint(inputNodes: Vector[GraphInputNode],   // inputs
                 subBlocks: Vector[Block], // blocks
                 outputNodes: Vector[GraphOutputNode]) // outputs
            : Unit = {
        System.out.println("Inputs [")
        inputNodes.foreach{ node =>
            val desc = WomPrettyPrint.apply(node)
            System.out.println(s"  ${desc}")
        }
        System.out.println("]")
        subBlocks.foreach{ block =>
            System.out.println("Block [")
            block.nodes.foreach{ node =>
                val desc = WomPrettyPrint.apply(node)
                System.out.println(s"  ${desc}")
            }
            System.out.println("]")
        }
        System.out.println("Output [")
        outputNodes.foreach{ node =>
            val desc = WomPrettyPrint.apply(node)
            System.out.println(s"  ${desc}")
        }
        System.out.println("]")
    }

    // A block of nodes that represents a call with no subexpressions. These
    // can be compiled directly into a dx:workflow stage.
    //
    // For example, the WDL code:
    // call add { input: a=x, b=y }
    //
    // Is represented by the graph:
    // Block [
    //   TaskCallInputExpressionNode(a, x, WomIntegerType, GraphNodeOutputPort(a))
    //   TaskCallInputExpressionNode(b, y, WomIntegerType, GraphNodeOutputPort(b))
    //   CommandCall(add, Set(a, b))
    // ]
    def isSimpleCall(block: Block) : Option[CallNode] = {
        // find the call
        val calls : Seq[CallNode] = block.nodes.collect{
            case cNode : CallNode => cNode
        }
        if (calls.size != 1)
            return None
        val oneCall = calls.head

        // All the other nodes have to the call inputs
        val rest = block.nodes.toSet - oneCall
        val callInputs = oneCall.upstream.toSet
        if (rest != callInputs)
            return None

        // The call inputs have to be simple expressions
        val allSimple = rest.forall{
            case expr: TaskCallInputExpressionNode => isTrivialExpression(expr.womExpression)
            case _ => false
        }

        if (!allSimple)
            return None
        Some(oneCall)
    }

}
