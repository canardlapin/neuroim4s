import org.scalajs.linker.interface.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*
import sbtcrossproject.CrossPlugin.autoImport.*
import sbtcrossproject.CrossType
import scalajscrossproject.ScalaJSCrossPlugin.autoImport.*

ThisBuild / organization := "io.github.canardlapin"
ThisBuild / scalaVersion := "3.7.4"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / homepage := Some(url("https://github.com/canardlapin/neuroim4s"))
ThisBuild / licenses := List(
  "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt")
)

// Providers are immutable source pins. Each `neuroim4s.<provider>.build`
// property is an explicit sibling-checkout override for coordinated
// development; a consuming build forwards its own overrides here so one
// provider revision resolves throughout the graph.
def pinned(provider: String, revision: String): URI =
  sys.props
    .get(s"neuroim4s.$provider.build")
    .map(path => file(path).getCanonicalFile.toURI)
    .getOrElse(uri(s"https://github.com/canardlapin/$provider.git#$revision"))

lazy val spatial4sRevision = "77037a7e6ced044b92d0a70b713c0a32a94f680d"
lazy val spatial4sBuild = pinned("spatial4s", spatial4sRevision)
lazy val spatial4sCoreJVM = ProjectRef(spatial4sBuild, "spatial4s-coreJVM")
lazy val spatial4sCoreJS = ProjectRef(spatial4sBuild, "spatial4s-coreJS")

lazy val image4sRevision = "aef5e223169f0831c942694c4b109ab20d152217"
lazy val image4sBuild = {
  Seq("spatial4s", "locus4s", "gale", "ravel").foreach { provider =>
    sys.props.get(s"neuroim4s.$provider.build")
      .foreach(System.setProperty(s"image4s.$provider.build", _))
  }
  pinned("image4s", image4sRevision)
}
lazy val image4sGeometryJVM = ProjectRef(image4sBuild, "image4s-geometryJVM")
lazy val image4sGeometryJS = ProjectRef(image4sBuild, "image4s-geometryJS")

lazy val image4sCoreJVM = ProjectRef(image4sBuild, "image4s-coreJVM")
lazy val image4sCoreJS = ProjectRef(image4sBuild, "image4s-coreJS")
lazy val image4sLocusJVM = ProjectRef(image4sBuild, "image4s-locusJVM")
lazy val image4sLocusJS = ProjectRef(image4sBuild, "image4s-locusJS")
lazy val image4sFilterJVM = ProjectRef(image4sBuild, "image4s-filterJVM")
lazy val image4sFilterJS = ProjectRef(image4sBuild, "image4s-filterJS")
lazy val image4sNiftiJS = ProjectRef(image4sBuild, "image4s-niftiJS")
lazy val image4sNiftiJVM = ProjectRef(image4sBuild, "image4s-niftiJVM")
lazy val galeRevision = "779ed3d8169cdd9f4eb9ab10b1ca9defadc27715"
lazy val galeBuild = pinned("gale", galeRevision)
lazy val galeCoreJVM = ProjectRef(galeBuild, "coreJVM")
lazy val galeCoreJS = ProjectRef(galeBuild, "coreJS")
lazy val reframe4sRevision = "7ec7feff96d4057a86196b5e86d592688b7c7f84"
lazy val reframe4sBuild = {
  Seq("image4s", "spatial4s", "gale", "ravel", "locus4s").foreach { provider =>
    sys.props.get(s"neuroim4s.$provider.build")
      .foreach(System.setProperty(s"reframe4s.$provider.build", _))
  }
  pinned("reframe4s", reframe4sRevision)
}
lazy val reframe4sLieJVM = ProjectRef(reframe4sBuild, "reframe4s-lieJVM")
lazy val reframe4sLieJS = ProjectRef(reframe4sBuild, "reframe4s-lieJS")
lazy val reframe4sFieldJVM = ProjectRef(reframe4sBuild, "reframe4s-fieldJVM")
lazy val reframe4sFieldJS = ProjectRef(reframe4sBuild, "reframe4s-fieldJS")
lazy val reframe4sResampleJVM = ProjectRef(reframe4sBuild, "reframe4s-resampleJVM")
lazy val reframe4sResampleJS = ProjectRef(reframe4sBuild, "reframe4s-resampleJS")
lazy val graph4sRevision = "613371f3bd0b12ce99352db0c637645eb62c6283"
lazy val graph4sBuild = {
  sys.props.get("neuroim4s.gale.build").foreach(System.setProperty("graph4s.gale.build", _))
  pinned("graph4s", graph4sRevision)
}
lazy val graph4sAlgorithmsJVM = ProjectRef(graph4sBuild, "algorithmsJVM")
lazy val graph4sAlgorithmsJS = ProjectRef(graph4sBuild, "algorithmsJS")

// Rendering is optional: none of the image, transform or routing modules
// depends on these projects. Intaglio owns color and drawing primitives.
lazy val intaglioRevision = "d553c327824f9916b1c0a055226c6c5e8f77bc59"
lazy val intaglioBuild = pinned("intaglio", intaglioRevision)
lazy val intaglioCoreJVM = ProjectRef(intaglioBuild, "coreJVM")
lazy val intaglioCoreJS = ProjectRef(intaglioBuild, "coreJS")
lazy val intaglioCanvasJS = ProjectRef(intaglioBuild, "canvasJS")
lazy val intaglioJava2dJVM = ProjectRef(intaglioBuild, "java2dJVM")
lazy val intaglioJavafxJVM = ProjectRef(intaglioBuild, "javafxJVM")
lazy val javafxVersion = sys.props.getOrElse("intaglio.javafx.version", "21.0.5")
lazy val javafxPlatformClassifier: String = {
  val os = sys.props.getOrElse("os.name", "").toLowerCase
  val arch = sys.props.getOrElse("os.arch", "").toLowerCase
  val base =
    if (os.contains("mac")) "mac"
    else if (os.contains("win")) "win"
    else "linux"
  if (arch.contains("aarch64") && base != "win") base + "-aarch64" else base
}
lazy val routingBoundaryCheck = taskKey[Unit]("Preserve volume-only routing and portable execution boundaries")
lazy val transformBoundaryCheck = taskKey[Unit]("Preserve portable toolkit interpretation and frame ownership")
lazy val coreBoundaryCheck = taskKey[Unit]("Preserve frame ownership and prevent public provider-state facades")

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-release:17"
  ),
  Test / fork := false,
  libraryDependencies += "org.scalameta" %%% "munit" % "1.2.1" % Test
)

lazy val jsSettings = Seq(
  scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
  Test / jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv()
)

// Anatomical world declarations shared by volume, surface and atlas cores.
// Its only dependency is the spatial4s coordinate kernel.
lazy val spaces =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/neuroim4s-spaces"))
    .settings(commonSettings)
    .settings(
      name := "neuroim4s-spaces",
      description := "Subject, template and declared world identities and their frames."
    )
    .jvmConfigure(_.dependsOn(spatial4sCoreJVM))
    .jsConfigure(_.dependsOn(spatial4sCoreJS))
    .jsSettings(jsSettings)

// Neuroimaging policy; image4s remains the sole geometry representation.
// This module does not depend on ScalaFIM or its surface/atlas/MVPA integrations.
lazy val core =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/neuroim4s-core"))
    .dependsOn(spaces)
    .settings(commonSettings)
    .settings(
      name := "neuroim4s-core",
      description := "Neuroimaging values and policy over provider-owned image geometry.",
      libraryDependencies ++= Seq(
        "org.typelevel" %%% "cats-core" % "2.12.0",
        "org.typelevel" %%% "spire" % "0.18.0",
        "org.scalameta" %%% "munit-scalacheck" % "1.1.0" % Test,
        "org.typelevel" %%% "cats-effect" % "3.5.4" % Test
      ),
      Test / scalacOptions += "-Wconf:src=.*NeuroImageErrorContractSuite\\.scala&id=E29:e",
      coreBoundaryCheck := {
        val repository = (LocalRootProject / baseDirectory).value
        FrameErasureGate.selfTest()
        val violations = FrameErasureGate.check(repository)
        if (violations.nonEmpty) sys.error(violations.mkString("Frame erasure:\n", "\n", ""))
        val source = repository / "modules/neuroim4s-core/shared/src/main/scala/neuroim4s/image/SampleSpaces.scala"
        val publicAliases = "(?m)^\\s+(?:inline\\s+)?def\\s+(?:dims|ndim|spatialDims|spatialShape|spacing|origin|orientation|affineD3|inverseAffineD3|gridToIndex|indexToGrid|gridToIndex3D|indexToGrid3D|indexToVoxel3D|spatialSpace|addDim|dropDim|indexToCoord|indexToPoint|voxelToWorld|coordToIndex|coordToIndexPoint|worldToVoxel)\\b".r
        if (publicAliases.findFirstIn(IO.read(source)).nonEmpty) sys.error("Public provider-state facade returned")
        val packageSource = source.getParentFile / "package.scala"
        if ("(?m)^\\s*export SampleSpaces\\.\\*\\s*$".r.findFirstIn(IO.read(packageSource)).nonEmpty)
          sys.error("Package-wide SampleSpaces export returned")
        if (libraryDependencies.value.exists(module => module.organization == "ai.dragonfly" && module.name.contains("slash")))
          sys.error("Obsolete image algebra dependency returned")
      },
      Compile / compile := (Compile / compile).dependsOn(coreBoundaryCheck).value
    )
    .jvmConfigure(_.dependsOn(image4sGeometryJVM, image4sCoreJVM, image4sLocusJVM, image4sFilterJVM, galeCoreJVM, reframe4sLieJVM, reframe4sFieldJVM, reframe4sResampleJVM))
    .jsConfigure(_.dependsOn(image4sGeometryJS, image4sCoreJS, image4sLocusJS, image4sFilterJS, galeCoreJS, reframe4sLieJS, reframe4sFieldJS, reframe4sResampleJS))
    .jsSettings(jsSettings)

// Optional platform IO; shared core has no dependency on file formats.
lazy val ioJVM = project
  .in(file("modules/neuroim4s-io/jvm"))
  .dependsOn(core.jvm % "compile->compile;test->test", image4sNiftiJVM)
  .settings(commonSettings)
  .settings(name := "neuroim4s-io")

// Toolkit codecs and anatomical interpretation remain portable; no file/HDF5 or ScalaFIM dependency.
lazy val transform =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/neuroim4s-transform"))
    .dependsOn(core)
    .settings(commonSettings)
    .settings(
      name := "neuroim4s-transform",
      scalacOptions ++= Seq("-Werror", "-Wunused:all", "-Wvalue-discard"),
      libraryDependencies += "org.scalameta" %%% "munit-scalacheck" % "1.1.0" % Test,
      transformBoundaryCheck := {
        val repository = (LocalRootProject / baseDirectory).value
        FrameErasureGate.selfTest()
        val violations = FrameErasureGate.check(repository)
        if (violations.nonEmpty) sys.error(violations.mkString("Frame erasure:\n", "\n", ""))
        val sharedMain = repository / "modules/neuroim4s-transform/shared/src/main/scala"
        val platformLeaks = (sharedMain ** "*.scala").get.filter { source =>
          "\\bjava\\.(?:io|nio)\\b|\\bio\\.jhdf\\b|\\bscala\\.io\\.Source\\b".r.findFirstIn(IO.read(source)).nonEmpty
        }
        if (platformLeaks.nonEmpty) sys.error(s"Shared transform sources use platform IO: ${platformLeaks.mkString(", ")}")
      },
      Compile / compile := (Compile / compile).dependsOn(transformBoundaryCheck).value
    )
    .jvmConfigure(_.dependsOn(image4sNiftiJVM))
    .jsConfigure(_.dependsOn(image4sNiftiJS))
    .jsSettings(jsSettings)
    .jsSettings(
      Test / sourceGenerators += Def.task {
        val out = (Test / sourceManaged).value / "OracleResourceLocation.scala"
        val resources = (LocalRootProject / baseDirectory).value / "modules/neuroim4s-transform/shared/src/test/resources"
        val escaped = resources.getAbsolutePath.replace("\\", "\\\\").replace("\"", "\\\"")
        IO.write(out, "package neuroim4s.transform.oracle\nprivate[oracle] object OracleResourceLocation:\n  val root: String = \"" + escaped + "\"\n")
        Seq(out)
      }.taskValue
    )

// Optional JVM containers: gzip/files, ITK HDF5 and X5. Portable users do not resolve jHDF.
lazy val transformIoJVM = project
  .in(file("modules/neuroim4s-transform-io/jvm"))
  .dependsOn(transform.jvm % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "neuroim4s-transform-io",
    scalacOptions ++= Seq("-Werror", "-Wunused:all", "-Wvalue-discard"),
    libraryDependencies += "io.jhdf" % "jhdf" % "0.12.0"
  )

// Optional anatomical volume routing and execution. Surface/atlas/functional integrations remain downstream.
lazy val routing =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/neuroim4s-routing"))
    .dependsOn(core, transform)
    .settings(commonSettings)
    .settings(
      name := "neuroim4s-routing",
      scalacOptions ++= Seq("-Werror", "-Wunused:all", "-Wvalue-discard"),
      routingBoundaryCheck := {
        val repository = (LocalRootProject / baseDirectory).value
        val shared = repository / "modules/neuroim4s-routing/shared/src"
        val sources = (shared ** "*.scala").get
        val forbidden = "\\b(?:scalafim|neuroatlas4s|neurosurf4s)\\.|\\bjava\\.(?:io|nio)\\b|\\bscala\\.io\\.Source\\b".r
        if (forbidden.findFirstIn("import scalafim.spatial.Domain").isEmpty ||
            forbidden.findFirstIn("import java.nio.file.Path").isEmpty)
          sys.error("Routing boundary detector did not recognize its refusal witnesses")
        val violations = sources.filter(source => forbidden.findFirstIn(IO.read(source)).nonEmpty)
        if (violations.nonEmpty) sys.error(s"Volume routing boundary violations: ${violations.mkString(", ")}")
        val frameViolations = FrameErasureGate.check(repository)
        if (frameViolations.nonEmpty) sys.error(frameViolations.mkString("Frame erasure:\n", "\n", ""))
      },
      Compile / compile := (Compile / compile).dependsOn(routingBoundaryCheck).value
    )
    .jvmConfigure(_.dependsOn(graph4sAlgorithmsJVM))
    .jsConfigure(_.dependsOn(graph4sAlgorithmsJS))
    .jsSettings(jsSettings)

// Scoped NIfTI reads are optional; portable routing never resolves platform IO.
lazy val routingIoJVM = project
  .in(file("modules/neuroim4s-routing-io/jvm"))
  .dependsOn(routing.jvm, ioJVM)
  .settings(commonSettings)
  .settings(
    name := "neuroim4s-routing-io",
    scalacOptions ++= Seq("-Werror", "-Wunused:all", "-Wvalue-discard")
  )

// Portable volume slice scenes and interaction; platform drawing stays optional.
lazy val view =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/neuroim4s-view"))
    .dependsOn(core)
    .settings(commonSettings)
    .settings(name := "neuroim4s-view")
    .jvmConfigure(_.dependsOn(intaglioCoreJVM))
    .jsConfigure(_.dependsOn(intaglioCoreJS))
    .jsSettings(jsSettings)

lazy val viewCanvas =
  crossProject(JSPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/neuroim4s-view-canvas"))
    .dependsOn(view)
    .settings(commonSettings)
    .settings(name := "neuroim4s-view-canvas")
    .jsConfigure(_.dependsOn(intaglioCanvasJS))
    .jsSettings(jsSettings)

lazy val viewJava2d =
  crossProject(JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/neuroim4s-view-java2d"))
    .dependsOn(view)
    .settings(commonSettings)
    .settings(name := "neuroim4s-view-java2d")
    .jvmConfigure(_.dependsOn(intaglioJava2dJVM))

lazy val viewJavafx =
  crossProject(JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/neuroim4s-view-javafx"))
    .dependsOn(view)
    .settings(commonSettings)
    .settings(
      name := "neuroim4s-view-javafx",
      libraryDependencies ++= Seq(
        "org.openjfx" % "javafx-base" % javafxVersion % Provided classifier javafxPlatformClassifier,
        "org.openjfx" % "javafx-graphics" % javafxVersion % Provided classifier javafxPlatformClassifier
      )
    )
    .jvmConfigure(_.dependsOn(intaglioJavafxJVM))

lazy val root =
  project
    .in(file("."))
    .aggregate(spaces.jvm, spaces.js, core.jvm, core.js, ioJVM, transform.jvm, transform.js, transformIoJVM, routing.jvm, routing.js, routingIoJVM,
      view.jvm, view.js, viewCanvas.js, viewJava2d.jvm, viewJavafx.jvm)
    .settings(name := "neuroim4s-root", publish / skip := true)

addCommandAlias("neuroim4sCompileAll", ";root/compile;root/Test/compile")
addCommandAlias("neuroim4sTestAll", ";spacesJVM/test;spacesJS/test;coreJVM/test;coreJS/test;ioJVM/test;transformJVM/test;transformJS/test;transformIoJVM/test;routingJVM/test;routingJS/test;routingIoJVM/test;viewJVM/test;viewJS/test;viewCanvasJS/test;viewJava2dJVM/test;viewJavafxJVM/test")
