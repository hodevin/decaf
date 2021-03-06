package decaf.frontend

import com.meteorcode.common.ForkTable
import decaf.AST._
import decaf.AST.annotations._

import scala.util.control.Breaks
import scala.util.parsing.input.Position

case class SemanticException(message: String, pos: Position) extends Exception(message) {
  lazy val lineOfCode = pos.longString.replaceAll("\n\n", "\n")

  override def toString: String = s"*** Error line ${pos.line}.\n$lineOfCode\n$message\n"

}

class IllegalClassInheritanceCycle(className: String, where: Position)
  extends SemanticException(
    s"*** Illegal cyclic class inheritance involving $className on line ${where.line}",
    where
  )

class ConflictingDeclException(name: String, where: Position)
  extends SemanticException(
    s"*** Declaration of '$name' here conflicts with declaration on line ${where.line}",
    where
  )

class UndeclaredTypeException(name: String, where: Position, what: String="type")
  extends SemanticException(s"*** No declaration found for $what '$name'", where)

class TypeSignatureException(name: String, where: Position)
  extends SemanticException(s"** Method '$name' must match inherited type signature", where)

class InvalidTestException(where: Position)
  extends SemanticException("*** Test expression must have boolean type", where)

class UnimplementedInterfaceException(which: String, interface: String, where: Position)
  extends SemanticException(
    s"*** Class '$which' does not implement entire interface '$interface'",
    where
  )

class TypeErrorException(what: String, where: Position) extends SemanticException(what, where)

class IncompatibleReturnException(got: String,
                                  expected: String,
                                  where: Position)
  extends SemanticException(s"*** Incompatible return : $got given, $expected expected", where)


case class ScopeNode(table: ScopeTable,
                     boundName: String,
                     var parent: Option[ScopeNode] = None,
                     statement: ASTNode) {
  var children = List[ScopeNode]()
  def child(boundName: String, stmt: ASTNode): ScopeNode = {
    val c = new ScopeNode(table.fork(), boundName, Some(this), stmt)
    children = children :+ c
    c
  }

  private def removeChild(other:ScopeNode): Unit = {
    this.children = children.filter({z => other != z})
  }

  private def addChild(other:ScopeNode): Unit = {
    this.children = this.children :+ other
  }

  def insideLoop: Boolean = if (this.boundName == "Loop body"){ true } else { parent.exists(_.insideLoop) }

  def reparent(nParent: ScopeNode): List[Exception] = {
    if(nParent == this) List(new IllegalArgumentException("ERROR: Scope attempted to mount itself as parent!"))
    else {
      if (this.parent.isDefined) {
        val oldParent = this.parent.get
        oldParent.removeChild(this)
      }
      nParent.addChild(this)
      this.table.reparent(nParent.table)
      Nil
    }
  }

  override def toString = stringify(0)
  def stringify(indentLevel: Int): String = {
    val s = new StringBuilder
    s ++= (" "*indentLevel) + boundName + ":"
    s ++= table.prettyprint(indentLevel + 2)
    if(children.length > 0) {
      s ++= s"\n${" " * indentLevel}\\\\"
      s ++= children.foldLeft[String]("") { (acc, child) => acc + "\n" + child.stringify(indentLevel + 2)}
      s ++= s"\n${" " * indentLevel}//"
    }
    s.toString()
  }

}
/**
 * Contains the Semantic Analysis stuff
 *
 * ==Design Decisions==
 * While we have attempted to follow the Decaf specification faithfully, there are some cases
 * in which our implementation's output differs from the sample output provided by
 * Dr. Jumadinova, due to differing interpretations of the specification and/or cases in which
 * we thought the specified behaviour was not conducive to effective programming in Decaf.
 *
 *  + Since the Decaf specification states that "Decaf does not support encapsulation", we chose
 *    not to implement the `*** Crayon field 'color' only accessible within class scope` error
 *    that is present in some of the sample output for this assignment. We instead chose to make
 *    all names globally visible, rather than making all variables private and all methods public,
 *    as we determined that the latter option sounds dangerously close to encapsulation.
 *  + Although the Decaf spec does not support type lifting, we chose to implement type lifting
 *    from Integer to Double. This is because Decaf has no casting mechanism, and therefore, there
 *    is no way to ever perform operations on mixed numeric operands. Therefore, we do not generate
 *    the `*** Incompatible operands: double / int` that is present in some sample output.
 *  + The samples provided call for the type of a logical expression to always be logical, even
 *    when one or both operands is a TypeError. We've decided that this is wrong and inconsistent
 *    with the way TypeErrors are propagated elsewhere in Decaf. Therefore, `LogicalExpr.typeof()`
 *    forwards TypeError just as every other `Expr` does.
 *  + The samples call for a "*** No declaration for identifier" error to be generated when a program
 *    attempts to treat a method as though it were a field that can be accessed. We think that
 *    the semantics of this error message isn't very helpful, since the identifier IS declared,
 *    it just isn't bound to something that can be accessed. Therefore, we make an error message
 *    saying "*** Attempt to field access a method" instead.
 *
 * @author Hawk Weisman <hawk@meteorcodelabs.com>
 * @author Max Clive <mattrulz127@gmail.com>
 */
object DecafSemantic {

  /**
   * Walks an [[ASTNode abstract syntax tree]] and decorates each statement in the tree
   * that corresponds to a scope with its' own scope node.
   *
   * This method should be called on the tree before the actual semantic analysis methods
   * ([[pullDeclsToScope()]], [[checkTypes()]], [[checkClasses()]]); if the semantic analysis
   * methods are called on an un-decorated tree, Everything Will Go Wrong.
   *
   * @param tree the [[ASTNode]] to walk
   * @param scope the [[ScopeNode]] corresponding to that [[ASTNode]]
   */
  def decorateScope (tree: ASTNode, scope: ScopeNode): Unit = {
    tree.state = Some(scope)
    tree match {
      case Program(decls, _) => decls.foreach {
        case f: FnDecl => decorateScope(f, scope)
        case v: VarDecl => decorateScope(v, scope)
        case c: ClassDecl => decorateScope(c, scope.child(s"Class Declaration of ${c.name.name}", c))
        case i: InterfaceDecl => decorateScope(i, scope.child(s"Interface Declaration of ${i.name.name}", i))
      }
      case c: ClassDecl =>
        c.members.foreach {
          case f: FnDecl => decorateScope(f, scope)
          case v: VarDecl => decorateScope(v, scope)
          case _ => //class/interface decls can't be embedded.
        }
      case i: InterfaceDecl => i.members.foreach {
        case f: FnDecl => decorateScope(f, scope)
        case _ => //We shouldn't have any other types of decl in an interface.
      } //If we do, then we hae a problem
      case f: FnDecl =>
        val s = scope.child(s"FnDecl (formals) ${f.name.name}", f)
        f.formals.foreach {
          decorateScope(_, s)
        }
        if (f.body.isDefined) {
          val decls = f.body.get.decls
          val stmts = f.body.get.stmts
          val fs = s.child(s"FnDecl (body) ${f.name.name}", f.body.get)
          f.body.get.state = Some(fs)
          decls.foreach {
            decorateScope(_, fs)
          }
          stmts.foreach {
            case block: StmtBlock => decorateScope(block, fs.child("Subblock", block))
            case con: ConditionalStmt => decorateScope(con, fs.child(con.getName, con))
            case stmt: Stmt => stmt.state = Some(fs)
          }
          // s => if(s.isInstanceOf[StmtBlock]) decorateScope(s, fs.child("Subblock", s)) else s.state = Some(fs)
          //}
        }
      case StmtBlock(decls, stmts, _) =>
        decls.foreach {
          decorateScope(_, scope)
        }
        stmts.foreach {
          case block: StmtBlock => decorateScope(block, scope.child("Subblock", block))
          case con: ConditionalStmt => decorateScope(con, scope.child(con.getName, con))
          case stmt: Stmt => stmt.state = Some(scope)
        }
      case i: IfStmt =>
        i.state = Some(scope)
        decorateScope(i.test, scope)
        decorateScope(i.testBody, scope.child("Test body", i.testBody))
        i.elseBody.foreach {
          case block: StmtBlock => decorateScope(block, scope.child("Subblock", block))
          case con: ConditionalStmt => decorateScope(con, scope.child(con.getName, con))
          case stmt: Stmt => stmt.state = Some(scope)
        }
      case f: ForStmt =>
        f.state = Some(scope)
        f.init.foreach(decorateScope(_, scope))
        f.step.foreach(decorateScope(_, scope))
        decorateScope(f.test, scope)
        decorateScope(f.body, scope.child("Loop body", f.body))
      case w: WhileStmt =>
        w.state = Some(scope)
        decorateScope(w.loopBody, scope.child("Loop body", w.loopBody))
        decorateScope(w.test, scope)
      case e: CompoundExpr =>
        e.state = Some(scope)
        e.left.foreach(decorateScope(_, scope))
        decorateScope(e.right, scope)
      case n: ASTNode => n.state = Some(scope)
    }
  }


  def descent(node: ASTNode): List[ASTNode] = {
    node match {
      case Program(decls, _) => decls
      case ClassDecl(_, _, _, members) => members
      case FnDecl(_,_,formals,Some(body)) => formals ::: body :: Nil
      case StmtBlock(decls, stmts, _) => decls ::: stmts
      case _ => List[ASTNode]()
    }
  }

  def annotateVariable(v: VarDecl): List[Exception] = {
    val ident = v.n
    val typ = v.t

    if(v.state.isEmpty) {
      throw new IllegalArgumentException("Tree didn't contain a scope for\n" + v.toString)
    }

    val state = v.state.get
    if (state.table.contains(ident.name)) {
      new ConflictingDeclException(ident.name, ident.pos) :: Nil
    } else {
      state.table.put(ident.name, new VariableAnnotation(ident.name, typ, ident.pos))
      Nil
    }
  }

  def annotateFunction(fn: FnDecl): List[Exception] = {
    val ident = fn.name
    var errors = List[Exception]()
    val rettype = fn.returnType
    val formals = fn.formals

    if(fn.state.isEmpty) {
      throw new IllegalArgumentException("Tree didn't contain a scope for\n" + fn.toString)
    }

    val state = fn.state.get

    if(state.table.contains(ident.name)) {
      errors = new ConflictingDeclException(ident.name, ident.pos) :: errors
      return errors
    } else {
      state.table.put(ident.name, new MethodAnnotation(ident.name, rettype, formals.map(_.t), fn.pos))
    }
    for (formal <- formals) {
      if(formal.state.isEmpty) {
        throw new IllegalArgumentException(
          "Tree didn't contain a scope for\n"
          + formal.toString +
          "\nin "
          + fn.toString)
      }
      errors = annotateVariable(formal) ::: errors
    }
    fn.body.foreach(body => errors = annotateStmtBlock(body) ::: errors)
    errors
  }

  def annotateStmtBlock(b: StmtBlock): List[Exception] = {
    var errors = List[Exception]()
    if(b.state.isEmpty) {
      throw new IllegalArgumentException("Tree didn't conatin a scope for\n" + b.toString)
    } else {
      b.decls.foreach {
        case decl => errors = annotateVariable(decl) ::: errors
      }

      errors = errors ::: b.stmts.flatMap {
        case s: StmtBlock => annotateStmtBlock(s)
        case _ => Nil
      }
    }
  errors
  }

  def annotateClass(c: ClassDecl): List[Exception] = {
    var errors = List[Exception]()
    if (c.state.isEmpty) {
      throw new IllegalArgumentException("Tree didn't contain a scope for\n" + c.toString)
    }
    val cscope = c.state.get
    if(cscope.table.contains("this")) {
      throw new IllegalArgumentException(
        "keyword \'this\' already (accidentally?) bound for class scope in "
        + c.toString)
    } else {
      cscope.table.put("this", new VariableAnnotation("this",NamedType(c.name),c.pos))
    }

    errors = errors ::: c.members.map {
      case v: VarDecl => annotateVariable(v)
      case f: FnDecl => annotateFunction(f)
    }.flatten

    val pscope = c.state.get.parent
    if(pscope.isEmpty) {
      throw new IllegalArgumentException(
        "Tree doesn't have a parent scope to enclose class type declaration in class "
          + c.toString)
    } else {
      val ptable = pscope.get.table
      if(ptable.contains(c.name.name)) {
        errors = new ConflictingDeclException(c.name.name, c.name.pos) :: errors
      } else {
        ptable.put(c.name.name,
                   new ClassAnnotation(new NamedType(c.name),
                   c.extnds,
                   c.implements,
                   cscope,
                   c.pos))

      }
    }
    //TODO: Finish me
    errors
  }

  def annotateInterface(i: InterfaceDecl): List[Exception] = {
    var errors = List[Exception]()
    if(i.state.isEmpty) {
      throw new IllegalArgumentException("Tree doesn't contain a scope for " + i.toString)
    }

    val iscope = i.state.get

    errors = errors ::: i.members.map({case m: FnDecl =>
      annotateFunction(m)}
    ).flatten

    val pscope = i.state.get.parent

    if(pscope.isEmpty) {
      throw new IllegalArgumentException(
        "Tree doesn't have a parent scope to enclose interface declaration for interface "
          + i.toString)
    } else {
      val ptable = pscope.get.table
      if(ptable.contains(i.name.name)) {
        errors = new ConflictingDeclException(i.name.name, i.name.pos) :: errors
      } else {
        ptable.put(i.name.name,
                   new InterfaceAnnotation(new NamedType(i.name),
                   iscope,
                   i.pos))
      }
    }
    errors
  }

  /**
   * Annotates all declarations in the specified scope in that scope's symbol table.
   * This essentially performs the first walk
   * @param tree the node to walk
   * @return a [[List]] of [[Exception]]s for each error generated during the check
   */
  def pullDeclsToScope (tree: ASTNode): List[Exception] = {
    if (tree.state.isEmpty) {
      throw new IllegalArgumentException("Tree didn't contain a scope at " + tree.toString)
    }
    var state = tree.state.get
    tree match {
      case Program(decls, _) => decls.map {
        case v: VarDecl => annotateVariable(v)
        case f: FnDecl => annotateFunction(f)
        case c: ClassDecl => annotateClass(c)
        case i: InterfaceDecl => annotateInterface(i)
      }.flatten
    }
  }

  /**
   * Checks that a given type is declared
   * @param node the [[ScopeNode]] against which to check
   * @param pos the position (for error reporting)
   * @param value the [[Type]] to check
   * @return a [[List]] of [[Exception]]s for each error generated during the check
   */
  def checkTypeExists(node: ScopeNode,pos: Position, value: Type, kind: String ="type"): List[Exception] = {
    value match {
      case n: NamedType =>
        node.table.get(n.name.name) match {
          case Some(ClassAnnotation(_,_,_,_,_)) | Some(InterfaceAnnotation(_,_,_)) => Nil
          case _ => new UndeclaredTypeException(n.name.name, pos, kind) :: Nil
        }
      case ArrayType(_, t) => checkTypeExists(node, pos, t)
      case VoidType(_) | IntType(_) | DoubleType(_) | BoolType(_) | StringType(_) | NullType(_) => Nil
      case UndeclaredType(_,_) | _ => new SemanticException(s"Unexpected type '${value.typeName}'!", pos) :: Nil
    }
  }

  /**
   * Check that all inheritance declarations are not cyclic
   * @param scope The scope at which to conduct checks
   * @param seen a list of all the names we've seen so far
   * @param c the class to check
   * @param p the position
   * @return a [[List]] of [[Exception]]s for each error generated during the check
   */
  def verifyClassChain(scope: ScopeNode, seen: List[String], c: String, p: Position): List[Exception] = {
    if(seen.contains(c)) {
      new IllegalClassInheritanceCycle(seen.head, p) :: Nil
    } else if(!scope.table.chainContains(c)) {
      Nil
    } else {
      val t = scope.table.get(c).get
      t match {
        case myc: ClassAnnotation =>
          if(myc.ext.isDefined) {
            verifyClassChain(scope, (seen :+ c), myc.ext.get.name.name, p)
          } else {
            //we've found a class which goes to ground, do we want to do anything here?
            //I think no.
            Nil
          }
        case _ => Nil //what should we even cause here? I.e. the type we got was NOT a class.
      }
    }
  }

  /**
   * Second walk over the AST. Most typechecking happens here.
   * //TODO: explain this better
   * 
   * "Because...types are supposed to make it safe...the program, and when it 
   * makes Object then it isn't safe."
   *    ~ Gary Bernhardt, "Useing You're Type's Good"
   * 
   * @param ast the [[decaf.AST.ASTNode]] over which to walk
   * @return
   */
  def checkTypes(ast: ASTNode): List[Exception] = {
    if(!ast.isInstanceOf[Program] && ast.state.isEmpty)
      throw new IllegalArgumentException("Tree does not contain scope for " + ast)
    val scope = ast.state.get
    ast match {
      case Program(d, _) => d.flatMap(checkTypes(_))

      case VarDecl(_, typ) =>
        checkTypeExists(ast.state.get, typ.pos, typ)

      case ClassDecl(_, ext, impl, mems) =>
        ext.map({x => verifyClassChain(scope, List[String](), ast.asInstanceOf[ClassDecl].name.name, ast.pos)}).getOrElse(Nil) :::
          impl.flatMap(checkTypeExists(scope, ast.pos, _, "interface")) ::: mems.flatMap(checkTypes(_)) /*:::
          checkClassIntegrity(ast.asInstanceOf[ClassDecl]) ::: */
      // 11/7/14: Moved to thirdPass() ~ Hawk

      case InterfaceDecl(_, members) => members.flatMap(checkTypes(_))

      case FnDecl(_, rt, formals, body) =>
        checkTypeExists(scope, ast.pos, rt) :::
          formals.flatMap(checkTypes(_)) ::: body.map(checkTypes(_)).getOrElse(Nil)

      case StmtBlock(decls, stmts, _) => decls.flatMap(checkTypes(_)) ::: stmts.flatMap(checkTypes(_))

      case IfStmt(test, ifbody, elsebody) =>
        val t: List[Exception] = test.typeof(scope) match {
          case BoolType(_) => Nil
          case e: ErrorType => e ::: Nil
          case _ => new InvalidTestException(ast.pos) :: Nil
        }
        t ::: checkTypes(ifbody) ::: elsebody.map(checkTypes(_)).getOrElse(Nil)

      case ForStmt(init, test, step, body) =>
        val t: List[Exception] = test.typeof(scope) match {
          case BoolType(_) => Nil
          case e: ErrorType => e ::: new InvalidTestException(ast.pos) :: Nil
          case _ => new InvalidTestException(ast.pos) :: Nil
        }
        t ::: init.map(checkTypes(_)).getOrElse(Nil) ::: step.map(checkTypes(_)).getOrElse(Nil) ::: checkTypes(body)

      case WhileStmt(test, body) =>
        val t: List[Exception] = test.typeof(scope) match {
          case BoolType(_) => Nil
          case e: ErrorType => e ::: new InvalidTestException(ast.pos) :: Nil
          case _ => new InvalidTestException(ast.pos) :: Nil
        }
        t ::: checkTypes(body) ::: checkTypes(test)

      case SwitchStmt(what, cases, default, _) =>
        what.map(checkTypes(_)).getOrElse(Nil) ::: cases.flatMap(checkTypes(_)) ::: default.map(checkTypes(_)).getOrElse(Nil)

      case CaseStmt(value, body, _) => body.flatMap(checkTypes(_))
      case BreakStmt(_) => scope.insideLoop match {
        case true => Nil //todo: also allow case statements?
        case false => new SemanticException("*** break is only allowed inside a loop", ast.pos) :: Nil
      }
      case ReturnStmt(_, Some(exp)) =>
        val state: ScopeNode = ast.state.orNull
        if (state == null) throw new IllegalArgumentException("Tree does not contain scope for " + ast)
        state.table.get(findReturnType(ast)) match {
          case Some(m: MethodAnnotation) =>
            if (m.matches(MethodAnnotation("", exp.typeof(state), m.formals, m.pos))) {
              Nil
            } else {
              if(exp.typeof(state).isInstanceOf[NullType]) Nil
              else new IncompatibleReturnException(exp.typeof(state).typeName, m.returnType.typeName, ast.pos) :: Nil
            }
          case _ => throw new IllegalArgumentException(
            s"\n*** EXTREMELY BAD PROBLEM occurs on line ${ast.pos.line}" + // this should not happen
              s"\n*** this should not happen ever,  please contact the decaf implementors and I am sorry" +
              s"\n*** code:\n${ast.pos.longString}") // the parser should never allow this
        }
      case PrintStmt(args, _) => (for { i <- (0 until args.length).toList }
        yield args(i).typeof(scope) match {
          case e: ErrorType => e.unpack
          case IntType(_) | BoolType(_) | StringType(_) => Nil
          case t: Type => new SemanticException(s" *** Incompatible argument ${i+1}:" +
        s" ${t.typeName} given, int/bool/string expected  ", ast.pos) :: Nil
        }).flatten
      case ex: Expr => ex.typeof(scope) match {
        case e: ErrorType => e
        case _ => Nil
      }
    }
  }

  /**
   * Walks backwards up the AST from a given node until it finds
   * a function declaration and returns the return type.
   * @param node
   * @return
   */
  def findReturnType(node: ASTNode): String = node match {
    case FnDecl(ident, _, _, _) => ident.name
    case n: ASTNode => findReturnType(n.parent)
    case _ => throw new IllegalArgumentException(
      s"\n*** EXTREMELY BAD PROBLEM occurs on line ${node.pos.line}" + // this should not happen
        s"\n*** this should not happen ever,  please contact the decaf implementors and I am sorry" +
        s"\n*** code:\n${node.pos.longString}") // the parser should never allow this
  }
  /**
   * Checks a ClassDecl for correct inheritance.
   * @param c the class declaration to check
   * @return a list of [[Exception]]s generated during the check
   */
  def checkInheritance(c: ClassDecl): List[Exception] = {
    val classState = c.state.getOrElse(throw new IllegalArgumentException("Tree does not contain scope for " + c))
    val extErr: List[Exception] = (for {
      t <- c.extnds
    } yield {
      checkTypeExists(classState, t.pos, t, "class") // can't extend something that doesn't exist
      // TODO: we should do additional checking here
    }).getOrElse(Nil)

    //scopes mangling moved OUT of this method

    (for {
      i: NamedType <- c.implements
      } yield {
      classState.table.get(i.name.name) match {
        case Some(s: InterfaceAnnotation) => (for {
          (name: String, annotation: TypeAnnotation) <- s.interfaceScope.table
          } yield {
          classState.table.get(name) match {
            case Some(that) => if (annotation matches that) {
              Nil
            } else {
              List[Exception](
                new SemanticException(s"*** Method '$name' must match inherited type signature",
                  classState.table.get(name).get.where),
                new UnimplementedInterfaceException(c.name.name, i.name.name, c.pos)
              )
            }
            case _ => Nil
          }
        }).flatten
        case _ => Nil //is this the right thing to throw if i has no state? Or is it UndeclaredType?
          //new UnimplementedInterfaceException(c.name.name, i.name.name, c.pos) :: Nil
      }
    }).flatten ::: (for {
      thing <- classState.table.keys.toList if thing != "this"
      inherited <- classState.parent.get.table.get(thing) if !classState.table.get(thing).get.matches(inherited)
    } yield {
      new SemanticException(s"*** Method '$thing' must match inherited type signature",classState.table.get(thing).get.where)
    } ) ::: extErr
  }

  /**
   * Performs the third walk across the AST, doing all semantic checks that require a complete symbol table.
   * Currently, this is limited to class integrity checking ([[checkInheritance()]]).
   * @param ast the ASTNode over which to walk
   * @return a list of errors that were generated during the walk
   */
  def checkClasses(ast: ASTNode): List[Exception] = if (!ast.isInstanceOf[Program] && ast.state.isEmpty)
    throw new IllegalArgumentException("Tree does not contain scope for " + ast)
  else {
    val scope = ast.state.get
    ast match {
      case Program(declarations, _) => declarations.flatMap(checkClasses(_))
      case c: ClassDecl => checkInheritance(c) ::: c.members.flatMap(checkClasses(_)) /*
        c.extnds.map(thirdPass(_)).getOrElse(Nil) :::
        c.members.flatMap(thirdPass(_))           :::
        c.implements.flatMap(thirdPass(_))        ::: Nil */ //currently we don't need to do this but we might later

        //TODO: Finish checks
      case _ => Nil
    }
  }

  def locateAnywhere(node: ScopeNode, className: String): Option[ScopeNode] = {
    //scala doesn't have anonymous recursive functions, so these get shitty names
    //x zooms to the top node of a ScopeTree
    def x(p:ScopeNode):ScopeNode = p.parent match {
      case None => p
      case Some(z) => x(z)
    }
    //c finds the first declaration of a class anywhere in the scope tree
    def c(p:ScopeNode):Option[ScopeNode] = {
      p.table.get(className) match {
        case Some(x: ClassAnnotation) => Some(x.classScope)
        case None =>
          var r: Option[ScopeNode] = None
          Breaks.breakable {
            for (l <- p.children) {
              l.table.get(className) match {
                case None =>
                case Some(x: ClassAnnotation) =>
                  r = Some(x.classScope)
                  Breaks.break()
              }
            }
          }
          r
      }
    }
    //get the top of the tree for the later descent match
    //then descend and find declaration
    c(x(node))
  }

  def scopedInheritance(ast: ASTNode): List[Exception] = {
    var ret = List[Exception]()
    if(ast.state.isEmpty) {
      throw new IllegalArgumentException("Tree does not contain scope for " + ast)
    } else {
      val scope = ast.state.get
      ast match {
        case Program(declarations, _) => ret = declarations.flatMap(scopedInheritance(_)) ::: ret
        case c: ClassDecl =>
          if (c.extnds.isDefined) {
            //we are in the middle of rewriting ScopeTree, declarations are in flux
            //and thus we need to locate a potential class everywhere in the tree.
            //
            //This is only important if we ever support inner classes, however,
            //writing it this way NOW enables us to correctly scope even if we ever do
            //instead of having to rewrite things.
            locateAnywhere(scope, c.extnds.get.name.name) match {
              case None => //throw new Exception("EVERYTHING IS ON FIRE")
              //funny enough, classes don't seem to contain their own scopeNodes correctly?
              //therefore we locate it again.
              case Some(x) => locateAnywhere(scope, c.name.name) match {
                case None => //throw new Exception("EVERYTHING IS ON FIRE")
                case Some(y) =>
                  //mount extendING class under the extendED class, so that its decls happen in the scope chain
                  ret = y.reparent(x) ::: ret
              }
            }
          }
          //potentially support inner classes
          ret = c.members.flatMap(scopedInheritance(_)) ::: ret
        case _ =>
      }
    }
    ret
  }

  /**
   *
   * Performs the complete semantic analysis
   *
   * This works by [[decorateScope() decorating]] the tree with [[ScopeNode]]s
   * and then calling the three semantic analysis methods in order:
   *  1. [[pullDeclsToScope()]]
   *  2. [[checkTypes()]]
   *  3. [[checkClasses()]]
   *
   * All errors generated during these three analysis passes are consed together
   * into a List[Exception] that is then returned by this method at the end of
   * analysis, along with the top-level [[ScopeNode]] of the tree. If the list
   * is empty, then the program should be semantically valid.
   *
   * @param top The program to analyze
   * @return The ScopeTree generated by the analysis
   */
  def analyze(top: Program): (ScopeNode, List[Exception]) = {
    val tree: ScopeNode = new ScopeNode(new ScopeTable, "Global", None, top)
    decorateScope(top, tree)
    val problems = pullDeclsToScope(top) ::: scopedInheritance(top) ::: checkClasses(top) ::: checkTypes(top)
    (top.state.get, problems)
  }

  /**
   * Helper method for testing
   * @param progn The program to compile
   * @return The result of compiling the program, printing any errors that happened
   */
  def compileToSemantic(progn: String): ScopeNode = {
    val r = new DecafSyntactical().parse(progn)
    //System.out.println(r)
    val result = analyze(r)
    result._2.map(System.err.println(_))
    result._1
  }

  def main(args: Array[String]): Unit = {
    println(compileToSemantic("class A { } class B extends A { } class C extends B {} class D extends A {} class Q extends Q {} class R extends Q {}").toString)
  }
}
