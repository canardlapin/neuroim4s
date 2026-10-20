package consumer.volume

import cats.Hash
import image4s.geometry.{Affine, D3, Frame}
import neuroim4s.image.{SampleSpaces, SpatialPoint}
import neuroim4s.spaces.{FrameCatalog, TemplateName, WorldSpace}
import neuroim4s.spatial.*
import scala.compiletime.testing.typeCheckErrors

class VolumeRoutingSuite extends munit.FunSuite:
  private given Hash[String] = Hash.fromUniversalHashCode
  private def name(value: String): TemplateName = TemplateName(value).toOption.get
  private def frame(value: String): Frame[D3] = FrameCatalog.frame(WorldSpace.Template(name(value)))
  private val af = frame("volume-a")
  private val bf = frame("volume-b")
  private val cf = frame("volume-c")
  private val a = VolumeDomain.unsampled("a", VolumeReference.Template(name("volume-a")), af)
  private val b = VolumeDomain.unsampled("b", VolumeReference.Template(name("volume-b")), bf)
  private val c = VolumeDomain.unsampled("c", VolumeReference.Template(name("volume-c")), cf)

  private def edge(id: String, from: String, to: String, cost: Double, quality: Option[Double] = Some(1.0),
      map: Option[ProviderMapBinding] = None): VolumeRegistration[String, String] =
    VolumeRegistration.build(id, from, to, VolumeMapKind.Affine3D, cost, quality, map).toOption.get

  private def translate(x: Double): Affine[D3] = Affine.fromRowMajor[D3](Vector(
    Vector(1.0, 0.0, 0.0, x), Vector(0.0, 1.0, 0.0, 0.0),
    Vector(0.0, 0.0, 1.0, 0.0), Vector(0.0, 0.0, 0.0, 1.0)).flatten).toOption.get

  test("forward-first routing, inverse penalties, quality and parallel paths preserve policy"):
    val edges = Vector(edge("ab", "a", "b", 5.0, Some(0.8)), edge("ba", "b", "a", 0.0), edge("bc", "b", "c", 1.0, Some(0.6)), edge("bc2", "b", "c", 2.0))
    val g = VolumeGraph.build(Vector(a, b, c), edges).toOption.get
    val forward = g.path("a", "c", allowInverses = true).toOption.get
    assertEquals(forward.steps.map(_.registration.id), Vector("ab", "bc"))
    assertEqualsDouble(forward.quality, 0.6, 0.0)
    val reverse = g.path("c", "a", allowInverses = true, inversePenalty = 10.0).toOption.get
    assertEquals(reverse.steps.map(_.registration.id), Vector("bc2", "ba"))
    assertEqualsDouble(reverse.cost, 2.0, 0.0)
    assert(reverse.usedInverses)
    val paths = g.allPaths("a", "c", 4, allowInverses = true)(identity).toOption.get
    assertEquals(paths.head.steps.map(_.registration.id), Vector("ba", "bc"))
    assertEquals(paths.length, 4)
    assertEquals(g.path("missing", "a"), Left(VolumeRoutingError.MissingDomain("missing")))
    assert(g.path("a", "c", inversePenalty = Double.NaN).left.exists {
      case VolumeRoutingError.InvalidPenalty(value) => value.isNaN
      case _ => false
    })

  test("missing routes, planned maps and unsampled execution refuse with typed causes"):
    val isolated = VolumeGraph.build(Vector(a, b), Vector.empty[VolumeRegistration[String, String]]).toOption.get
    assertEquals(isolated.path("a", "b"), Left(VolumeRoutingError.NoPath("a", "b")))
    val planned = VolumeGraph.build(Vector(a, b), Vector(edge("planned", "a", "b", 1.0))).toOption.get
    assertEquals(planned.path("a", "b").toOption.get.pullback(SpatialPoint.Origin), Left(VolumeRoutingError.MissingMap("planned")))
    assertEquals(VolumeExecution.compile(planned, "a", "b", Vector(0)).left.toOption, Some(VolumeExecutionError.UnsampledDomain("a")))

  test("provider frame ownership is checked even when a second owner has the same declared world"):
    val otherA = frame("volume-a")
    val wrong = ProviderMapBinding.affineBetween(otherA, bf, Affine.identity[D3]).toOption.get
    val bad = VolumeGraph.build(Vector(a, b), Vector(edge("wrong", "a", "b", 1.0, map = Some(wrong))))
    assert(bad.left.exists {
      case VolumeRoutingError.FrameMismatch(_) => true
      case _ => false
    })
    val ab = ProviderMapBinding.affineBetween(af, bf, translate(-1.0)).toOption.get
    val bc = ProviderMapBinding.affineBetween(bf, cf, translate(-2.0)).toOption.get
    val g = VolumeGraph.build(Vector(a, b, c), Vector(edge("ab", "a", "b", 1.0, map = Some(ab)), edge("bc", "b", "c", 1.0, map = Some(bc)))).toOption.get
    assertEquals(g.path("a", "c").toOption.get.pullback(SpatialPoint(3.0, 0.0, 0.0)), Right(SpatialPoint.Origin))
    assertEquals(g.path("c", "a", allowInverses = true).toOption.get.pullback(SpatialPoint.Origin), Right(SpatialPoint(3.0, 0.0, 0.0)))

  test("volume contract has no mixed-domain alternatives and checked constructors cannot be bypassed"):
    assertEquals(typeCheckErrors("import neuroim4s.spatial.{VolumeGrid, VolumeDomain, VolumeReference, VolumeMapKind}"), Nil)
    assert(typeCheckErrors("val surface = neuroim4s.spatial.VolumeMapKind.SurfaceToSurface").nonEmpty)
    assert(typeCheckErrors("val latent = neuroim4s.spatial.VolumeReference.Latent(3)").nonEmpty)
    assert(typeCheckErrors("import neuroim4s.spatial.VolumeGrid; import neuroim4s.image.SampleSpaces; new VolumeGrid(SampleSpaces(Vector(2, 2, 1)), None)").nonEmpty)

  test("sampled identity preserves caller target order with a standalone provider grid"):
    val grid = VolumeGrid.from(SampleSpaces(Vector(3, 1, 1))).toOption.get
    val sampled = VolumeDomain.sampled("a", a.reference, grid)
    val g = VolumeGraph.build(Vector(sampled), Vector.empty[VolumeRegistration[String, String]]).toOption.get
    val result = VolumeExecution.compile(g, "a", "a", Vector(2, 0), SamplingPolicy.Nearest).toOption.get
    assertEquals(result.targetRows, Vector(2, 0))
    assertEquals(result.sourceRows, Vector(0, 2))
    assertEqualsDouble(result.operator.map(0, 2), 1.0, 0.0)
    assertEqualsDouble(result.operator.map(1, 0), 1.0, 0.0)

  test("an empty target demand cannot turn a planned map into an executable registration"):
    val grid = VolumeGrid.from(SampleSpaces(Vector(2, 1, 1))).toOption.get
    val from = VolumeDomain.sampled("a", a.reference, grid)
    val onto = VolumeDomain.sampled("b", b.reference, grid)
    val graph = VolumeGraph.build(Vector(from, onto), Vector(edge("planned", "a", "b", 1.0))).toOption.get
    assertEquals(VolumeExecution.compile(graph, "a", "b", Vector.empty).left.toOption,
      Some(VolumeExecutionError.Routing(VolumeRoutingError.MissingMap("planned"))))
