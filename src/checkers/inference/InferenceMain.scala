package checkers.inference

import checkers.basetype.BaseTypeChecker
import com.sun.source.tree.CompilationUnitTree
import javax.lang.model.element.AnnotationMirror
import java.io.FileOutputStream
import java.io.File
import checkers.util.AnnotationUtils
import java.io.StringWriter
import java.io.PrintWriter

/*
TODO: improve statistics:
  output annotation counts/ CNF counts
  output annotations only for fields/parameters/etc.
  option to output as LaTeX
*/

object InferenceMain {
  def DEBUG(cls: AnyRef): Boolean = {
    options.optDebug || (options.optDebugClass match {
      case Some(s) => { s.contains(cls.toString) }
      case _ => false
    })
  }

  val TIMING = true
  var t_start: Long = 0
  var t_javac: Long = 0
  var t_solver: Long = 0
  var t_end: Long = 0

  var inferenceChecker: InferenceChecker = null
  var realChecker: InferenceTypeChecker = null
  var constraintMgr: ConstraintManager = null
  var slotMgr: SlotManager = null

  var options: TTIRun = null

  def run(params: TTIRun) {
    if (TIMING) {
      t_start = System.currentTimeMillis()
    }
    this.options = params

    val solver = createSolver()

    if (options.optVersion) {
      println("Checker Inference Framework version 0.2")
      println(solver.version)
      println
    }

    val cpath = System.getenv("CLASSPATH")

    // TODO: Note that also the class path used by the scala interpreter is
    // important. Maybe we don't need the path here at all?
    val infArgs = Array[String]("-Xbootclasspath/p:" + cpath,
      "-processor", "checkers.inference.InferenceChecker", // TODO: parameterize to allow specialization of ATF
      "-proc:only", // don't compile classes to save time
      "-encoding", "ISO8859-1", // TODO: needed for JabRef only, make optional
      "-Xmaxwarns", "1000",
      "-AprintErrorStack",
      // "-Ashowchecks",
      "-Awarns")

    // This should work, if we are using the JSR308 javac.
    // However, in Eclipse it does not :-(
    // TODO: how do I get the scala Eclipse plug-in to use a different JDK?
    // val l = java.lang.annotation.ElementType.TYPE_USE

    val newArgs: Array[String] = new Array[String](infArgs.length + params.residualArgs.length)

    System.arraycopy(infArgs, 0, newArgs, 0, infArgs.length);
    System.arraycopy(params.residualArgs.toArray, 0, newArgs, infArgs.length, params.residualArgs.length);

    var javacoutput = new StringWriter()
    var compiler = new com.sun.tools.javac.main.Main("javac", new PrintWriter(javacoutput, true));
    val compres = compiler.compile(newArgs);

    if (DEBUG(this)) {
      println("javac output: " + javacoutput)
    }

    if (TIMING) {
      t_javac = System.currentTimeMillis()
    }

    if (compres != com.sun.tools.javac.main.Main.Result.OK) {
      println("Error return code from javac! Quitting.")
      if (!DEBUG(this)) {
        println("javac output: " + javacoutput)
      }
      return
    }

    // maybe this helps garbage collection
    javacoutput = null
    compiler = null

    println

    if (slotMgr == null) {
      // The slotMgr is still null if the init method is not called.
      // Something strange happened then.
      println("The system is not configured correctly. Try again.")
      return
    }

    if (slotMgr.variables.isEmpty) {
      println("No variables found! Stopping!")
      return
    }

    println("All " + slotMgr.variables.size + " variables:")
    for (varval <- slotMgr.variables.values) {
      println(varval)
    }

    println
    if (constraintMgr.constraints.isEmpty) {
      println("No constraints!")
    } else {
      println("All " + constraintMgr.constraints.size + " constraints:")
      for (const <- constraintMgr.constraints) {
        println(const)
      }
    }

    val allVars = slotMgr.variables.values.toList
    val allCstr = constraintMgr.constraints.toList
    val allCombVars = slotMgr.combvariables.values.toList
    val theAFUAnnotHeader = getAFUAnnotationsHeader

    // free whatever we can from the compilation phase
    this.cleanUp()

    val weighter = createWeightManager
    // TODO do this nicer
    val allWeights = if (weighter != null) weighter.weight(allVars, allCstr) else null

    println

    val solution = solver.solve(allVars, allCombVars, allCstr, allWeights, params)

    if (TIMING) {
      t_solver = System.currentTimeMillis()
    }

    solution match {
      case Some(ssolution) => {
        val solAFU = theAFUAnnotHeader +
          ssolution.keySet.map((v) => v.toAFUString(ssolution(v))).mkString("\n")
        println("Solution:\n" + solAFU)

        val jaifFile = new File(params.optJaifFileName)
        // check for existing file?
        val output = new PrintWriter(new FileOutputStream(jaifFile))
        output.write(solAFU)
        output.close()

        // this doesn't work b/c the compiler is loaded by a different classloader
        // and an instanceof fails :-(
        // annotator.Main.main(Array("--abbreviate=false", "-d", "inference-output", jaifFileName) ++ args)
      }
      case None => {
        println("No solution found. Sorry!")
      }
    }

    if (TIMING) {
      t_end = System.currentTimeMillis()

      println("Number of variables: " + allVars.size)
      println("Number of constraints: " + allCstr.size)
      println
      println("Total running time: " + (t_end - t_start))
      println("Generation: " + (t_javac - t_start))
      println("Solving: " + (t_solver - t_javac))
      println("Output: " + (t_end - t_solver))

      val sol = solver.timing;
      if (sol!=null) {
        println
        println("Solver: ")
        println(sol)
      }
    }

  }

  // The checker gets created by javac at some point. It calls us back, don't worry.
  def init(checker: InferenceChecker) {
    inferenceChecker = checker
    constraintMgr = new ConstraintManager()
    slotMgr = new SlotManager()
  }

  def cleanUp() {
    inferenceChecker.cleanUp()
    inferenceChecker = null
    constraintMgr.cleanUp()
    constraintMgr = null
    slotMgr.cleanUp()
    slotMgr = null
  }

  def createSolver(): ConstraintSolver = {
    try {
      Class.forName(options.optSolver)
        .getConstructor()
        .newInstance().asInstanceOf[ConstraintSolver]
    } catch {
      case th: Throwable =>
        println("Error instantiating solver class \"" + options.optSolver + "\".")
        System.exit(5)
        null
    }
  }

  def createWeightManager: WeightManager = {
    // new gut.GUTWeightManager()

    if (options.optWeightManager != "") {
      try {
        Class.forName(options.optWeightManager).newInstance().asInstanceOf[WeightManager]
      } catch {
        case th: Throwable =>
          println("Error instantiating weight manager class \"" + options.optWeightManager + "\".")
          System.exit(5)
          null
      }
    } else {
      null
    }
  }

  def getRealChecker: InferenceTypeChecker = {
    if (realChecker == null) {
      try {
        realChecker = Class.forName(options.optChecker).newInstance().asInstanceOf[InferenceTypeChecker];
        realChecker.init(inferenceChecker.getProcessingEnvironment)
      } catch {
        case th: Throwable =>
          println("Error instantiating checker class \"" + options.optChecker + "\".")
          System.exit(5)
      }

      // TODO: set the boolean flags of the checker here.
      // But this would create a dependency on GUT.
      // Instead, change the options to a single String and pass it?
    }
    realChecker
  }

  def createRealVisitor(root: CompilationUnitTree): InferenceVisitor = {
    // We pass the inferenceChecker, not the getRealChecker, as checker argument.
    // This ensures that the InferenceAnnotatedTypeFactory will be used by the visitor.
    var checkerClass = Class.forName(options.optChecker)
    var visitorName = options.optVisitor
    var result: InferenceVisitor = null
    while (checkerClass != classOf[BaseTypeChecker]) {
      try {
        result = BaseTypeChecker.invokeConstructorFor(visitorName,
                Array(classOf[BaseTypeChecker], classOf[CompilationUnitTree], checkerClass, classOf[Boolean]),
                Array(inferenceChecker, root, getRealChecker, true.asInstanceOf[AnyRef])).asInstanceOf[InferenceVisitor]
      } catch {
        case th: Throwable => result = null
      }
      if (result != null) {
        return result
      }
      checkerClass = checkerClass.getSuperclass()
    }
    println("Error instantiating visitor class \"" + options.optVisitor + "\".")
    System.exit(5)
    null
  }

  def getAFUAnnotationsHeader: String = {
    def findPackage(am: AnnotationMirror): String = {

      val elems = inferenceChecker.getProcessingEnv.getElementUtils
      elems.getPackageOf(am.getAnnotationType.asElement).toString
    }
    def findAnnot(am: AnnotationMirror): String = {
      // println("Annotation: " + am.getAnnotationType.asElement.getAnnotationMirrors)
      am.getAnnotationType.asElement.getSimpleName.toString
    }
    // "package GUT.quals:\n" +
    // "annotation @Any: @Retention(value=RUNTIME) @java.lang.annotation.Target(value={TYPE_USE})\n" +
    // "annotation @Peer: @Retention(value=RUNTIME) @java.lang.annotation.Target(value={TYPE_USE})\n" +
    // "annotation @Rep: @Retention(value=RUNTIME) @java.lang.annotation.Target(value={TYPE_USE})\n\n"
    // currently we do not ouptut the annotations on the annotation; still seems to work
    import scala.collection.JavaConversions._
    ((for (am <- inferenceChecker.REAL_QUALIFIERS.values) yield {
      ("package " + findPackage(am) + ":\n" +
        "annotation @" + findAnnot(am) + ":")
    }) mkString ("\n")) + "\n\n"

  }
}