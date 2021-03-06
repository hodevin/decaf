package com.meteorcode.common

import scala.collection.{AbstractMap, DefaultMap, mutable}

  /**
   * Scala re-implementation of Max's ClobberTable˚
   * Created by hawk on 10/15/14.
   */
  class ForkTable[K, V](var parent: ForkTable[K, V] = null) extends AbstractMap[K, V] with DefaultMap[K, V] {
    val whiteouts = mutable.Set[K]()
    val back = mutable.HashMap[K, V]()

    def put(key: K, value: V): Option[V] = {
      if (whiteouts contains key) whiteouts -= key
      back.put(key, value)
    }

    def reparent(nparent: ForkTable[K,V]): Unit = {
      this.parent = nparent
    }

    override def get(key: K): Option[V] = if (whiteouts contains key) {
      None
    } else if (this.contains(key)) {
      back get key
    } else if(parent != null && (parent chainContains key)) {
      parent get key
    } else {
      None
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

    override def contains(key: K): Boolean = back contains key

    override def apply(key: K) = back(key)

    def fork(): ForkTable[K, V] = new ForkTable[K, V](parent = this)
    def prettyprint(indentLevel: Int) = (" "*indentLevel) + this.keys.foldLeft[String](""){(acc, key) => acc + "\n" + (" " * indentLevel) + s"$key ==> ${this.get(key).getOrElse("")}"}
  }
