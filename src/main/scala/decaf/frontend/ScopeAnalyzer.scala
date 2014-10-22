package decaf.frontend

import scala.collection.mutable.{HashMap, Set}
import scala.collection.{DefaultMap, AbstractMap}
import scala.util.Try

/**
 * Created by hawk on 10/20/14.
 */
object ScopeAnalyzer extends DecafAST {

  def scope(ast: ASTNode,
            symbolTable: ForkTable[ASTIdentifier,ASTNode] = new ForkTable[ASTIdentifier,ASTNode]): ForkTable[ASTIdentifier,ASTNode] = {
  }

}

/**
 * Scala re-implementation of Max's ClobberTable
 * Created by hawk on 10/15/14.
 */
class ForkTable[K, V](val parent: ForkTable[K, V] = null) extends AbstractMap[K, V] with DefaultMap[K, V] {
  val whiteouts = Set[K]()
  val back = HashMap[K, V]()

  def put(key: K, value: V): Option[V] = {
    if (whiteouts contains key) whiteouts -= key
    back.put(key, value)
  }

  override def get(key: K): Option[V] = if (whiteouts contains key) {
    None
  } else if (parent != null && (parent contains key)) {
    parent get key
  } else {
    back get key
  }

  def remove(key: K): Option[V] = {
    if (back contains key) {
      back remove key
    }
    else {
      if (parent contains key) whiteouts += key
      None
    }
  }

  override def iterator = back.iterator
  
  def chainContains(key: K): Boolean = (back contains key) || ((!(whiteouts contains key)) && parent != null && (parent chainContains key))

  override def contains(key: K): Boolean = (back contains key)

  override def apply(key: K) = back(key)

  def fork(): ForkTable[K, V] = new ForkTable[K, V](parent = this)
}
